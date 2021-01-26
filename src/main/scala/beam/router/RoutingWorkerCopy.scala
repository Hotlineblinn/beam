package beam.router

import java.io.File
import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.vehicles._
import beam.cch.CchNative
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.model._
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.osmlib.OSM
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.io.Directory

class RoutingWorkerCopy(workerParams: R5Parameters) extends Actor with ActorLogging with MetricsSupport {

  System.load("/home/crixal/work/projects/RoutingKit/src/cchnative.so")

  def this(config: Config) {
    this(workerParams = {
      R5Parameters.fromConfig(config)
    })
  }

  private val carRouter = workerParams.beamConfig.beam.routing.carRouter

  private val noOfTimeBins = Math
    .floor(
      Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
      workerParams.beamConfig.beam.agentsim.timeBinSize
    )
    .toInt

  private val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1
    else Runtime.getRuntime.availableProcessors() - 2
  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("r5-routing-worker-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)
  private var msgs = 0
  private var firstMsgTime: Option[ZonedDateTime] = None
  log.info("R5RoutingWorker_v2[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  private def getNameAndHashCode: String = s"R5RoutingWorker_v2[${hashCode()}], Path: `${self.path}`"

  private var workAssigner: ActorRef = context.parent

  private var r5: R5Wrapper = new R5Wrapper(
    workerParams,
    new FreeFlowTravelTime,
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
  private var binToCarGraphHopper: Map[Int, GraphHopperWrapper] = _
  private var walkGraphHopper: GraphHopperWrapper = _
  private var nativeCCH: CchNative = _

  private val ghTimeCounter = new AtomicInteger(0)
  private val cchTimeCounter = new AtomicInteger(0)

  private val linksBelowMinCarSpeed =
    workerParams.networkHelper.allLinks
      .count(l => l.getFreespeed < workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    log.warning(
      "{} links are below quick_fix_minCarSpeedInMetersPerSecond, already in free-flow",
      linksBelowMinCarSpeed
    )
  }

  override def preStart(): Unit = {
    createNativeCCH()
    if (carRouter == "staticGH" || carRouter == "quasiDynamicGH") {
      new Directory(new File(graphHopperDir)).deleteRecursively()
      createWalkGraphHopper()
      createCarGraphHoppers()
      askForMoreWork()
    }
  }

  override def postStop(): Unit = {
    tickTask.cancel()
    execSvc.shutdown()
  }

  // Let the dispatcher on which the Future in receive will be running
  // be the dispatcher on which this actor is running.
  val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
    .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
    .toMap

  val osm2R5 = new mutable.HashMap[Long, ArrayBuffer[Int]]()

  var nodes2Link: mutable.Map[(Long, Long), Long] = mutable.Map[(Long, Long), Long]()
  val osm = new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile)

  def convertNodes2Links(cchNodes: mutable.Buffer[Long]): Seq[Int] = {
    cchNodes.sliding(2)
      .foreach { list =>
        val linkId = nodes2Link.getOrElse(list.head -> list.last, -1)
        if (linkId == -1) {
          println("Missed link!!")
        }
      }

    cchNodes.sliding(2).map { list =>
      nodes2Link(list.head -> list.last).toInt
    }.toSeq
  }

  override final def receive: Receive = {
    case "tick" =>
      firstMsgTime match {
        case Some(firstMsgTimeValue) =>
          val seconds =
            ChronoUnit.SECONDS.between(firstMsgTimeValue, ZonedDateTime.now(ZoneOffset.UTC))
          if (seconds > 0) {
            val rate = msgs.toDouble / seconds
            if (seconds > 60) {
              firstMsgTime = None
              msgs = 0
            }
            if (workerParams.beamConfig.beam.outputs.displayPerformanceTimings) {
              log.info(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            } else {
              log.debug(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            }
          }
        case None => //
      }
    case WorkAvailable =>
      workAssigner = sender
      askForMoreWork()

    case request: RoutingRequest =>
      msgs = msgs + 1
      if (firstMsgTime.isEmpty) firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val eventualResponse = Future {
        latency("request-router-time", Metrics.RegularLevel) {
          if (!request.withTransit && (carRouter == "staticGH" || carRouter == "quasiDynamicGH")) {
            // run graphHopper for only cars
            val ghCarResponse = calcCarGhRoute(request)
            // run graphHopper for only walk
            val ghWalkResponse = calcWalkGhRoute(request)

            val modesToExclude = calcExcludeModes(
              ghCarResponse.exists(_.itineraries.nonEmpty),
              ghWalkResponse.exists(_.itineraries.nonEmpty)
            )

            val response = if (modesToExclude.isEmpty) {
              r5.calcRoute(request)
            } else {
              val filteredStreetVehicles = request.streetVehicles.filter(it => !modesToExclude.contains(it.mode))
              val r5Response = if (filteredStreetVehicles.isEmpty) {
                None
              } else {
                Some(r5.calcRoute(request.copy(streetVehicles = filteredStreetVehicles)))
              }
              ghCarResponse
                .getOrElse(ghWalkResponse.get)
                .copy(
                  ghCarResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                  ghWalkResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                  r5Response.map(_.itineraries).getOrElse(Seq.empty)
                )
            }
            response
          } else {
            val resp = r5.calcRoute(request)

            val r5Res = resp.itineraries.flatMap(_.beamLegs).map(_.travelPath).flatMap(_.linkIds)
            if (request.streetVehicles.exists(_.mode == Modes.BeamMode.CAR) && r5Res.nonEmpty) {
              this.synchronized {
                val origin = workerParams.geo.utm2Wgs(request.originUTM)
                val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

                val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)
                cchResponse.getDepTime

                val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
                def getFullLine(list: Seq[Int]) = {
                  val geomStr = list.map { id =>
                    cur.seek(id)
                    cur.getGeometry.toText.replaceAll("LINESTRING", "")
                  }.mkString(",")
                  s"multilinestring(${geomStr})"
                }

                def getLine(list: Seq[Int]) = {
                  val coordsIter = list
                    .map(linkId => linkId -> id2Link(linkId))
                    .map { it =>
                      if (it._1 % 2 == 0) {
                        workerParams.geo.utm2Wgs(it._2._1) -> workerParams.geo.utm2Wgs(it._2._2)
                      } else {
                        workerParams.geo.utm2Wgs(it._2._2) -> workerParams.geo.utm2Wgs(it._2._1)
                      }
                    }
                    .iterator

                  var coord = coordsIter.next()
                  var res = s"linestring(${coord._1.getX} ${coord._1.getY}, ${coord._2.getX} ${coord._2.getY}"
                  while (coordsIter.hasNext) {
                    coord = coordsIter.next()
                    res += s", ${coord._2.getX} ${coord._2.getY}"
                  }
                  res += ")"
                  res
                }

                val r5Line = getFullLine(r5Res)

                val cchNodes = cchResponse.getNodes.asScala.map(_.toLong)
                val links = new ArrayBuffer[Long]()
                val nodesIter = cchNodes.iterator
                var prevNode = nodesIter.next()
                while (nodesIter.hasNext) {
                  val node = nodesIter.next()
                  links += nodes2Link(prevNode, node)
                  prevNode = node
                }

                val cchLine = getFullLine(links.map(_.toInt))
                r5Line == cchLine

//                val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
//                val vertexCur = workerParams.transportNetwork.streetLayer.vertexStore.getCursor
//                var previousVertex = 0
//
//                val cchWayByR5 = new ArrayBuffer[Int]()
//                val cchWaysIterator = cchWays.iterator
//                val firstWay = osm2R5(cchWaysIterator.next())
//                val secondWay = osm2R5(cchWaysIterator.next())
//
//                cur.seek(firstWay.head)
//                val firstFromVertex = cur.getFromVertex
//                cur.seek(firstWay.last)
//                val firstToVertex = cur.getToVertex
//                cur.seek(secondWay.head)
//                val secondFromVertex = cur.getFromVertex
//                cur.seek(secondWay.last)
//                val secondToVertex = cur.getToVertex
//                if (firstToVertex == secondFromVertex) {
//                  firstWay.foreach {id => cchWayByR5 += id}
//                  secondWay.foreach {id => cchWayByR5 += id}
//                  previousVertex = secondToVertex
//                } else if (firstToVertex == secondToVertex) {
//                  firstWay.foreach {id => cchWayByR5 += id}
//                  secondWay.reverse.foreach {id => cchWayByR5 += (id+1) }
//                  previousVertex = secondFromVertex
//                } else if (firstFromVertex == secondFromVertex) {
//                  firstWay.reverse.foreach {id => cchWayByR5 += (id+1)}
//                  secondWay.foreach {id => cchWayByR5 += id}
//                  previousVertex = secondToVertex
//                } else if (firstFromVertex == secondToVertex) {
//                  firstWay.reverse.foreach {id => cchWayByR5 += (id+1)}
//                  secondWay.reverse.foreach {id => cchWayByR5 += (id+1) }
//                  previousVertex = secondFromVertex
//                } else {
//                  assert(false)
//                }
//
//                while (cchWaysIterator.hasNext) {
//                  val nextWay = osm2R5(cchWaysIterator.next())
//                  cur.seek(nextWay.head)
//                  val nextFromVertex = cur.getFromVertex
//                  cur.seek(nextWay.last)
//                  val nextToVertex = cur.getToVertex
//                  if (previousVertex == nextFromVertex) {
//                    nextWay.foreach {id => cchWayByR5 += id}
//                    previousVertex = nextToVertex
//                  } else if (previousVertex == nextToVertex) {
//                    nextWay.reverse.foreach {id => cchWayByR5 += (id+1)}
//                    previousVertex = nextFromVertex
//                  } else {
//                    assert(false)
//                  }
//                }



//                val cchLine = getLine(cchWayByR5)
//                val cchLine = getLine(cchWays.map(_.toInt))
//                r5Line == cchLine

                //                val cchNodes1 = cchNodes
//                  .map(osm.ways.get(_))
//                  .flatMap(way => way.nodes)
//                  .toList
//
//                val cchLineByWays = cchNodes1.map(osm.nodes.get(_))
//                  .map(node => s"${node.getLon.toString} ${node.getLat.toString}")
//                  .mkString(",")
//                val cchLineByNodes = cchNodes
//                  .map(osm.nodes.get(_))
//                  .map(node => s"${node.getLon.toString} ${node.getLat.toString}")
//                  .mkString(",")

//                val cchLinks = convertNodes2Links(cchNodes)

//                val cchLine = getLine(cchLinks)
//                r5Line == cchLineByNodes
//                r5Line == cchLineByWays
              }
            }
            resp
          }
        }
      }
      eventualResponse.recover {
        case e =>
          log.error(e, "calcRoute failed")
          RoutingFailure(e, request.requestId)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      log.info("!!!!!! Total cch time: {}, gh time: {}", cchTimeCounter, ghTimeCounter)
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(Some(newTravelTime))
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info("{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      log.info("!!!!!! Total cch time: {}, gh time: {}", cchTimeCounter, ghTimeCounter)
      val newTravelTime =
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(workerParams.beamConfig.beam.agentsim.timeBinSize, map)
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(Some(newTravelTime))
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info(
        "{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
        getNameAndHashCode,
        map.keySet().size()
      )
      askForMoreWork()

    case EmbodyWithCurrentTravelTime(
        leg: BeamLeg,
        vehicleId: Id[Vehicle],
        vehicleTypeId: Id[BeamVehicleType],
        embodyRequestId: Int
        ) =>
      val response: RoutingResponse = r5.embodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, embodyRequestId)
      sender ! response
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  private def createWalkGraphHopper(): Unit = {
    GraphHopperWrapper.createWalkGraphDirectoryFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir
    )

    walkGraphHopper = new WalkGraphHopperWrapper(graphHopperDir, workerParams.geo, id2Link)
  }

  private def createCarGraphHoppers(travelTime: Option[TravelTime] = None): Unit = {
    // Clean up GHs variable and than calculate new ones
    binToCarGraphHopper = Map()
    new Directory(new File(carGraphHopperDir)).deleteRecursively()

    val graphHopperInstances = if (carRouter == "quasiDynamicGH") noOfTimeBins else 1

    val futures = (0 until graphHopperInstances).map { i =>
      Future {
        val ghDir = Paths.get(carGraphHopperDir, i.toString).toString

        val wayId2TravelTime = travelTime
          .map { times =>
            workerParams.networkHelper.allLinks.toSeq
              .map(
                l =>
                  l.getId.toString.toLong ->
                  times.getLinkTravelTime(l, i * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null)
              )
              .toMap
          }
          .getOrElse(Map.empty)

        GraphHopperWrapper.createCarGraphDirectoryFromR5(
          carRouter,
          workerParams.transportNetwork,
          new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
          ghDir,
          wayId2TravelTime
        )

        i -> new CarGraphHopperWrapper(
          carRouter,
          ghDir,
          workerParams.geo,
          workerParams.vehicleTypes,
          workerParams.fuelTypePrices,
          wayId2TravelTime,
          id2Link
        )
      }
    }

    val s = System.currentTimeMillis()
    binToCarGraphHopper = Await.result(Future.sequence(futures), 20.minutes).toMap
    val e = System.currentTimeMillis()
    log.info(s"GH built in ${e - s} ms")
  }

  private def createNativeCCH() = {
    /*
        val timeStampStr = "2021-01-21T15:41:30Z"

        FileUtils.using(IOUtils.getBufferedWriter("/home/crixal/work/sf-light.osm")) { bw =>
          bw.write("<?xml version='1.0' encoding='UTF-8'?>")
          bw.newLine()
          bw.write("<osm version=\"0.6\" generator=\"BEAM\">")
          bw.newLine()

          val nodes = new ArrayBuffer[Long]()
          val vertexCur = workerParams.transportNetwork.streetLayer.vertexStore.getCursor
          for (idx <- 0 until workerParams.transportNetwork.streetLayer.vertexStore.getVertexCount) {
            vertexCur.seek(idx)
            val nodeStr = "  <node id=\"" + idx + "\" timestamp=\"" + timeStampStr + "\" lat=\"" + vertexCur.getLat.toString + "\" lon=\"" + vertexCur.getLon.toString + "\" uid=\"1\" version=\"1\" changeset=\"0\"/>"
            bw.write(nodeStr)
            bw.newLine()
            nodes += idx
          }

          val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
          for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 2) {
            cur.seek(idx)

            if (!(nodes.contains(cur.getFromVertex) && nodes.contains(cur.getToVertex)))  {
              throw new IllegalStateException("Missed node")
            }

            bw.write("  <way id=\"" + idx + "\" timestamp=\"" + timeStampStr + "\" uid=\"1\" user=\"beam\" version=\"1\" changeset=\"0\">")
            bw.newLine()
            bw.write("    <nd ref=\"" + cur.getFromVertex.toString + "\"/>")
            bw.newLine()
            bw.write("    <nd ref=\"" + cur.getToVertex.toString + "\"/>")
            bw.newLine()

            val osmWay = osm.ways.get(cur.getOSMID)
            if (osmWay != null) {
              osmWay.tags.forEach((tag: OSMEntity.Tag) => {
                bw.write("    <tag k=\"" + tag.key + "\" v=\"" + escapeHTML(tag.value) + "\"/>")
                bw.newLine()
              })
            } else {
              bw.write("    <tag k=\"highway\" v=\"trunk\"/>")
              bw.newLine()
            }

            bw.write("  </way>")
            bw.newLine()
          }

          bw.write("</osm>")
          bw.flush()
        }
    */
    val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor

    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges) {
      cur.seek(idx)
      nodes2Link += (cur.getFromVertex.toLong, cur.getToVertex.toLong) -> idx.toLong
    }

      //    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 2) {
//      cur.seek(idx)
//      nodes2Link += (cur.getFromVertex.toLong, cur.getToVertex.toLong) -> idx.toLong
//      if (osm.ways.get(cur.getOSMID) != null) {
//        osm2R5.get(cur.getOSMID) match {
//          case Some(value) => osm2R5.update(cur.getOSMID, value += idx)
//          case None => osm2R5(cur.getOSMID) = ArrayBuffer(idx)
//        }
//      }
//    }

    nativeCCH = new CchNative()
    nativeCCH.init("sdfsdfsd")

//    val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
//    val vertexCur = workerParams.transportNetwork.streetLayer.vertexStore.getCursor
//    FileUtils.using(IOUtils.getBufferedWriter("/home/crixal/work/missed_edges.csv")) { bw =>
//      bw.write("geometry")
//      bw.newLine()
//      for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 2) {
//        cur.seek(idx)
//        if (osm.ways.get(cur.getOSMID) == null) {
//          vertexCur.seek(cur.getFromVertex)
//          val fromCoord = s"${vertexCur.getLon} ${vertexCur.getLat}"
//          vertexCur.seek(cur.getToVertex)
//          val toCoord = s"${vertexCur.getLon} ${vertexCur.getLat}"
//          bw.write(s"linestring($fromCoord, $toCoord)")
//          bw.newLine()
//        }
//      }
//      bw.flush()
//    }
//    cur == null


//    id2Link.foreach { case (linkId , v) =>
//      cur.seek(linkId)
//      val osmWay = osm.ways.get(cur.getOSMID)
//      if (osmWay != null && checkTags(osmWay)) {
//        val origin = workerParams.geo.utm2Wgs(v._1)
//        val destination = workerParams.geo.utm2Wgs(v._2)
//        nativeCCH.addNodeToMapping(linkId, origin.getX, origin.getY, destination.getX, destination.getY)
//      }
//    }

//    def checkTags(way: Way): Boolean = {
//      if (way.hasTag("junction")) {
//        true
//      } else if (way.hasTag("route", "ferry")) {
//        true
//      } else if (way.hasTag("ferry", "yes")) {
//        true
//      } else if (!way.hasTag("highway")) {
//        false
//      } else if (way.hasTag("motorcar", "no")) {
//        false
//      } else if (way.hasTag("motor_vehicle", "no")) {
//        false
//      } else if (way.hasTag("access") && !(way.hasTag("access", "yes") || way.hasTag("access", "permissive") || way.hasTag("access", "delivery") || way.hasTag("access", "designated") || way.hasTag("access", "destination"))) {
//        false
//      } else if (way.hasTag("highway", "motorway") || way.hasTag("highway", "trunk") || way.hasTag("highway", "primary") || way.hasTag("highway", "secondary") || way.hasTag("highway", "tertiary") ||
//        way.hasTag("highway", "unclassified") || way.hasTag("highway", "residential") || way.hasTag("highway", "service") || way.hasTag("highway", "motorway_link") || way.hasTag("highway", "trunk_link") ||
//        way.hasTag("highway", "primary_link") || way.hasTag("highway", "secondary_link") || way.hasTag("highway", "tertiary_link") || way.hasTag("highway", "motorway_junction") || way.hasTag("highway", "living_street") ||
//        way.hasTag("highway", "residential") || way.hasTag("highway", "track") || way.hasTag("highway", "ferry")) {
//        true
//      } else if (way.hasTag("highway", "bicycle_road") && way.hasTag("motorcar", "yes")) {
//        true
//      } else if (way.hasTag("highway", "bicycle_road") && !way.hasTag("motorcar", "yes")) {
//        false
//      } else if (way.hasTag("highway", "construction") || way.hasTag("highway", "path") || way.hasTag("highway", "footway") || way.hasTag("highway", "cycleway") || way.hasTag("highway", "bridleway") ||
//        way.hasTag("highway", "pedestrian") || way.hasTag("highway", "bus_guideway") || way.hasTag("highway", "raceway") || way.hasTag("highway", "escape") || way.hasTag("highway", "steps") ||
//        way.hasTag("highway", "proposed") || way.hasTag("highway", "conveying") ) {
//        false
//      } else if (way.hasTag("oneway", "reversible") || way.hasTag("oneway", "alternating")) {
//        false
//      } else if (way.hasTag("maxspeed")) {
//        true
//      } else {
//        false
//      }
//    }

//    val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
//    val notOsmEdges = (0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges())
//      .map { idx =>
//        cur.seek(idx)
//        if (osm.ways.get(cur.getOSMID) == null) {
//          Some(new EdgeInfo(cur.getEdgeIndex, cur.getFromVertex, cur.getToVertex))
//        } else {
//          None
//        }
//      }.filter(_.isDefined)

//    val first = notOsmEdges.iterator.next().get





//    var osmWay: Way = null
//    var nodesCoord: List[Tuple2[Double, Double]] = null
//    var nodeIdx = 0

//    val vertexCur = workerParams.transportNetwork.streetLayer.vertexStore.getCursor
//
//    val fromNode = osm.nodes.get(65361720.toLong)
//    val toNode = osm.nodes.get(65310843.toLong)
//
//    var r5FromNode = 0
//    var r5ToNode = 0
//    var skippedLinksCounter = 0
//    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 2) {
//      cur.seek(idx)
//      val way = osm.ways.get(cur.getOSMID)
//      if (way == null) {
//        skippedLinksCounter += 1
//      }
//
//      vertexCur.seek(cur.getFromVertex)
//      val fromCoord = vertexCur.getLat -> vertexCur.getLon
//      vertexCur.seek(cur.getToVertex)
//      val toCoord = vertexCur.getLat -> vertexCur.getLon
//
//      if (fromCoord._1 == fromNode.getLat && fromCoord._2 == fromNode.getLon) {
//        r5FromNode = cur.getFromVertex
//      }
//      if (toCoord._1 == fromNode.getLat && toCoord._2 == fromNode.getLon) {
//        r5FromNode = cur.getToVertex
//      }
//
//      if (toCoord._1 == toNode.getLat && toCoord._2 == toNode.getLon) {
//        r5ToNode = cur.getToVertex
//      }
//      if (fromCoord._1 == toNode.getLat && fromCoord._2 == toNode.getLon) {
//        r5ToNode = cur.getFromVertex
//      }


//      if (way != null) {
//        val fromOsmNode = way.nodes(0)
//        val toOsmNode = way.nodes(way.nodes.length-1)
//        nodes2Link += (fromOsmNode, toOsmNode) ->idx.toLong
//        nodes2Link += (toOsmNode, fromOsmNode) -> (idx+1).toLong

        // 65361720,65310843,65289886,297254899,65301331,65354097,1350909875,3998115407,65325408,4018752380,3998115408,65287287,65287285,65287280,65287277,65287273,65287265,65287262,65287261,65352353,65308334,65368988,1148372886,65349161,65376331,263991315,65376377,342573716,3998017582,65337991,258467480,258467576,65336821,258913493,258913486,65339338,65314202,65303581,3931471411,65319992,259139108,4019294259,4019294261,65336921,65339516,65337445,65339518,65333918,65299440,65339521,65312716,65322720,65336125,262263366,262078989,262078988,262078987,621547505,262078986,262078985,262078984,262078983,262078982,262078981,65350777,267454187,65317168,65303162,65296839,65285879,542900786,65298435,65298461,1304953093
        // 65361720 -> 65310843
//      } else {
//        skippedLinksCounter += 1
//      }


//      cur.forEachPoint(new EdgeStore.PointConsumer() {
//        override def consumePoint(index: Int, fixedLat: Int, fixedLon: Int): Unit = {
//          println(s"$index, $fixedLat, $fixedLon")
//        }
//      })

//      val newOsmWay = osm.ways.get(cur.getOSMID)
//      if (newOsmWay != null) {
//        osmWay = newOsmWay
//        nodeIdx = 0
//        nodesCoord = osmWay.nodes
//          .map(osm.nodes.get(_))
//          .map { node =>
//            node.getLat -> node.getLon
//          }.toList
//      }
//
//      vertexCur.seek(cur.getFromVertex)
//      val fromCoord = vertexCur.getLat -> vertexCur.getLon
//      vertexCur.seek(cur.getToVertex)
//      val toCoord = vertexCur.getLat -> vertexCur.getLon

//      fromCoord == nodesCoord(nodeIdx) && toCoord == nodesCoord(nodeIdx+1)
//      assert(fromCoord == nodesCoord(nodeIdx) && toCoord == nodesCoord(nodeIdx+1))
//      nodeIdx += 1


//      if (cur.getFromVertex == first.toNode || cur.getToVertex == first.fromNode) {
//        val osmWay = osm.ways.get(cur.getOSMID)
//        val nodesCoord = osmWay.nodes
//          .map(osm.nodes.get(_))
//          .map { node =>
//            node.getLat -> node.fixedLon
//          }
//        nodesCoord.isEmpty
//        log.info("Found!!!!")
//      }
//    }

//    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 1) {
//      cur.seek(idx)
//
//      if (r5FromNode == cur.getFromVertex && r5ToNode == cur.getToVertex) {
//        println("Got it!!!!")
//      }
//
//      if (r5FromNode == cur.getToVertex && r5ToNode == cur.getFromVertex) {
//        println("Got it!!!!")
//      }
//    }
//
//    println(s"Skipped links: $skippedLinksCounter")

      //Edge from 28322 to 21095. Length 7990.135000 meters, speed 1.800000 kph. LINK ALLOWS_PEDESTRIAN ALLOWS_BIKE ALLOWS_CAR ALLOWS_WHEELCHAIR
//      nativeCCH.addNodeToMapping2(forwardEdge.getEdgeIndex.toLong, osmWay.)
  }

  def escapeHTML(s: String): String = {
    val out = new StringBuilder(Math.max(16, s.length))
    for (i <- 0 until s.length) {
      val c = s.charAt(i)
      if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
        out.append("&#")
        out.append(c.toInt)
        out.append(';')
      }
      else out.append(c)
    }
    out.toString
  }

  private def calcCarGhRoute(request: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (request.streetVehicles.exists(_.mode == mode)) {
      val idx =
        if (carRouter == "quasiDynamicGH")
          Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        else 0

      val origin = workerParams.geo.utm2Wgs(request.originUTM)
      val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

      val startCch = System.currentTimeMillis()
      val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)
      val cchTime = System.currentTimeMillis() - startCch
      cchTimeCounter.addAndGet(cchTime.toInt)
      cchResponse.getNodes.isEmpty

      val startGh = System.currentTimeMillis()
      val ghRes = binToCarGraphHopper(idx).calcRoute(
        request.copy(streetVehicles = request.streetVehicles.filter(_.mode == mode))
      )
      val ghTime = System.currentTimeMillis() - startGh
      ghTimeCounter.addAndGet(ghTime.toInt)
      Some(ghRes)
    } else None
  }

  private def calcWalkGhRoute(request: RoutingRequest): Option[RoutingResponse] = {
    val mode = Modes.BeamMode.WALK
    if (request.streetVehicles.exists(_.mode == mode)) {
      Some(
        walkGraphHopper.calcRoute(request.copy(streetVehicles = request.streetVehicles.filter(_.mode == mode)))
      )
    } else None
  }

  private def calcExcludeModes(successfulCarResponse: Boolean, successfulWalkResponse: Boolean) = {
    if (successfulCarResponse && successfulWalkResponse) {
      List(CAR, WALK)
    } else if (successfulCarResponse) {
      List(CAR)
    } else if (successfulWalkResponse) {
      List(WALK)
    } else {
      List()
    }
  }
}



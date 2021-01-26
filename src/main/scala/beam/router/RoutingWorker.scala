package beam.router

import java.io.File
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.cch.CchNative
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.RoutingWorker.EdgeInfo
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.gtfs.FareCalculator
import beam.router.model.{EmbodiedBeamTrip, _}
import beam.router.osm.TollCalculator
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.osmlib.{OSM, OSMEntity, Way}
import com.conveyal.r5.api.util._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.util.concurrent.{AtomicDouble, ThreadFactoryBuilder}
import com.typesafe.config.Config
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.io.IOUtils
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.io.Directory
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class RoutingWorker(workerParams: R5Parameters) extends Actor with ActorLogging with MetricsSupport {

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
    if (carRouter == "staticGH" || carRouter == "quasiDynamicGH") {
      new Directory(new File(graphHopperDir)).deleteRecursively()
      createWalkGraphHopper()
      createCarGraphHoppers()
    }

//    if (carRouter == "nativeCCH") {
      createNativeCCH()
//    }

    askForMoreWork()
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

  var nodes2Link: mutable.Map[(Long, Long), Long] = mutable.Map[(Long, Long), Long]()

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
          } else  if (carRouter == "nativeCCH") {
            calcCarNativeCCHRoute(request)
          } else {
            val resp = r5.calcRoute(request)

            val r5Res = resp.itineraries.flatMap(_.beamLegs).map(_.travelPath).flatMap(_.linkIds)
            if (request.streetVehicles.exists(_.mode == Modes.BeamMode.CAR) && r5Res.nonEmpty) {
              this.synchronized {
                val origin = workerParams.geo.utm2Wgs(request.originUTM)
                val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

                val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)

                val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
                def getFullLine(list: Seq[Int]) = {
                  val geomStr = list.map { id =>
                    cur.seek(id)
                    cur.getGeometry.toText.replaceAll("LINESTRING", "")
                  }.mkString(",")
                  s"multilinestring(${geomStr})"
                }

                val r5Line = getFullLine(r5Res)

                val cchNodes = cchResponse.getNodes.asScala.map(_.toLong)
                val links = new ArrayBuffer[Int]()
                val nodesIter = cchNodes.iterator
                var prevNode = nodesIter.next()
                while (nodesIter.hasNext) {
                  val node = nodesIter.next()
                  links += nodes2Link(prevNode, node).toInt
                  prevNode = node
                }

                val cchLine = getFullLine(links)
                r5Line == cchLine
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
        val tagsKey = new ArrayBuffer[String]()
        val osm = new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile)
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
          for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 1) {
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
              val flags = cur.getFlagsAsString
              val isF = cur.isForward
              val isB = cur.isBackward
              if (flags.isEmpty && isF && isB) {
                System.out.println("")
              }
              tagsKey ++= osmWay.tags.asScala.map(_.key).toList
              osmWay.tags.forEach((tag: OSMEntity.Tag) => {
                if (tag.key == "oneway" && (tag.value == "no" || tag.value == "false" || tag.value == "0")) {
                  bw.write("    <tag k=\"" + tag.key + "\" v=\"yes\"/>")
                } else {
                  bw.write("    <tag k=\"" + tag.key + "\" v=\"" + escapeHTML(tag.value) + "\"/>")
                }
                bw.newLine()
              })
            } else {
              bw.write("    <tag k=\"highway\" v=\"trunk\"/>")
              bw.write("    <tag k=\"oneway\" v=\"yes\"/>")
              bw.newLine()
            }

            bw.write("  </way>")
            bw.newLine()
          }

          bw.write("</osm>")
          bw.flush()
        }
    System.out.println(tagsKey.distinct.mkString(","))
    */

    val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges) {
      cur.seek(idx)
      nodes2Link += (cur.getFromVertex.toLong, cur.getToVertex.toLong) -> idx.toLong
    }

    nativeCCH = new CchNative()
    nativeCCH.init("sdfsdfsd")
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

  private def calcCarNativeCCHRoute(request: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (request.streetVehicles.exists(_.mode == mode)) {
      val origin = workerParams.geo.utm2Wgs(request.originUTM)
      val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

      val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)
      val cchNodes = cchResponse.getNodes.asScala.map(_.toInt)
      val linkIds = new ArrayBuffer[Int]()
      val nodesIter = cchNodes.iterator
      var prevNode = nodesIter.next()
      while (nodesIter.hasNext) {
        val node = nodesIter.next()
        linkIds += nodes2Link(prevNode, node).toInt
        prevNode = node
      }

      val linkTravelTimes = new ArrayBuffer[Double]()
      val streetVehicle = request.streetVehicles.head
      val beamLeg = BeamLeg(
        request.departureTime,
        mode,
        cchResponse.getDepTime.toInt,
        BeamPath(
          linkIds.toIndexedSeq,
          linkTravelTimes,
          None,
          SpaceTime(origin, request.departureTime),
          SpaceTime(destination, request.departureTime + cchResponse.getDepTime.toInt),
          cchResponse.getDistance
        )
      )
      val alternative = EmbodiedBeamTrip(
        IndexedSeq(
          EmbodiedBeamLeg(
            beamLeg,
            streetVehicle.id,
            streetVehicle.vehicleTypeId,
            asDriver = true,
            cost = DrivingCost.estimateDrivingCost(beamLeg, workerParams.vehicleTypes(streetVehicle.vehicleTypeId), workerParams.fuelTypePrices),
            unbecomeDriverOnCompletion = true
          )
        )
      )

      RoutingResponse(Seq(alternative), request.requestId, Some(request), isEmbodyWithCurrentTravelTime = false)
    } else None
  }

  private def calcCarGhRoute(request: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (request.streetVehicles.exists(_.mode == mode)) {
      val idx =
        if (carRouter == "quasiDynamicGH")
          Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        else 0
//
//      val origin = workerParams.geo.utm2Wgs(request.originUTM)
//      val destination = workerParams.geo.utm2Wgs(request.destinationUTM)
//
//      val startCch = System.currentTimeMillis()
//      val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)
//      val cchTime = System.currentTimeMillis() - startCch
//      cchTimeCounter.addAndGet(cchTime.toInt)
//      cchResponse.getNodes.isEmpty
//
//      val startGh = System.currentTimeMillis()
      val ghRes = binToCarGraphHopper(idx).calcRoute(
        request.copy(streetVehicles = request.streetVehicles.filter(_.mode == mode))
      )
//      val ghTime = System.currentTimeMillis() - startGh
//      ghTimeCounter.addAndGet(ghTime.toInt)
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

object RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 1.38

  // 3.1 mph -> 1.38 meter per second, changed from 1 mph
  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    networkHelper: NetworkHelper,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator
  ): Props = Props(
    new RoutingWorker(
      R5Parameters(
        beamScenario.beamConfig,
        transportNetwork,
        beamScenario.vehicleTypes,
        beamScenario.fuelTypePrices,
        beamScenario.ptFares,
        new GeoUtilsImpl(beamScenario.beamConfig),
        beamScenario.dates,
        networkHelper,
        fareCalculator,
        tollCalculator
      )
    )
  )

  case class R5Request(
    from: Coord,
    to: Coord,
    time: Int,
    directMode: LegMode,
    accessMode: LegMode,
    withTransit: Boolean,
    egressMode: LegMode,
    timeValueOfMoney: Double,
    beamVehicleTypeId: Id[BeamVehicleType]
  )

  def createBushwackingBeamLeg(
    atTime: Int,
    startUTM: Location,
    endUTM: Location,
    geo: GeoUtils
  ): BeamLeg = {
    val distanceInMeters = GeoUtils.minkowskiDistFormula(startUTM, endUTM) //changed from geo.distUTMInMeters(startUTM, endUTM)
    val bushwhackingTime = Math.round(distanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
    val path = BeamPath(
      Vector(),
      Vector(),
      None,
      SpaceTime(geo.utm2Wgs(startUTM), atTime),
      SpaceTime(geo.utm2Wgs(endUTM), atTime + bushwhackingTime.toInt),
      distanceInMeters
    )
    BeamLeg(atTime, WALK, bushwhackingTime.toInt, path)
  }

  def createBushwackingTrip(
    originUTM: Location,
    destUTM: Location,
    atTime: Int,
    body: StreetVehicle,
    geo: GeoUtils
  ): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      Vector(
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(atTime, originUTM, destUTM, geo),
          body.id,
          body.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = true
        )
      )
    )
  }

  class StopVisitor(
    val streetLayer: StreetLayer,
    val dominanceVariable: StreetRouter.State.RoutingVariable,
    val maxStops: Int,
    val minTravelTimeSeconds: Int,
    val destinationSplit: Split
  ) extends RoutingVisitor {
    private val NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.getNoEntryKey
    val stops: TIntIntMap = new TIntIntHashMap
    private var s0: StreetRouter.State = _
    private val destinationSplitVertex0 = if (destinationSplit != null) destinationSplit.vertex0 else -1
    private val destinationSplitVertex1 = if (destinationSplit != null) destinationSplit.vertex1 else -1

    override def visitVertex(state: StreetRouter.State): Unit = {
      s0 = state
      val stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex)
      if (stop != NO_STOP_FOUND) {
        if (state.getDurationSeconds < minTravelTimeSeconds) return
        if (!stops.containsKey(stop) || stops.get(stop) > state.getRoutingVariable(dominanceVariable))
          stops.put(stop, state.getRoutingVariable(dominanceVariable))
      }
    }

    override def shouldBreakSearch: Boolean =
      stops.size >= this.maxStops || s0.vertex == destinationSplitVertex0 || s0.vertex == destinationSplitVertex1
  }

  class EdgeInfo(
                  val edgeId: Int,
                  val fromNode: Int,
                  val toNode: Int
                )
}

package beam.agentsim.infrastructure

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Failure, Random, Success, Try}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Failure, Random, Success, Try}

class ZonalParkingManager(
  tazTreeMap: TAZTreeMap,
  geo: GeoUtils,
  parkingZones: Array[ParkingZone],
  zoneSearchTree: ParkingZoneSearch.ZoneSearch[TAZ],
  rand: Random,
  maxSearchRadius: Double,
  boundingBox: Envelope
) extends Actor
    with ActorLogging {

  if (maxSearchRadius < ZonalParkingManager.MinSearchRadius) {
    log.warning(
      s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of ${ZonalParkingManager.MinSearchRadius}; no searches will occur with these settings."
    )
  }

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  override def receive: Receive = {

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      val preferredParkingTypes: Seq[ParkingType] = inquiry.activityType match {
        case act if act.equalsIgnoreCase("home") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("init") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("work") => Seq(ParkingType.Workplace, ParkingType.Public)
        case act if act.equalsIgnoreCase("charge") =>
          Seq(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
        case _ => Seq(ParkingType.Public)
      }

      val returnSpotsWithChargers: Boolean = inquiry.activityType.toLowerCase match {
        case "charge" => true
        case "init"   => false
        case _ =>
          inquiry.vehicleType match {
            case Some(vehicleType) =>
              vehicleType.beamVehicleType.primaryFuelType match {
                case Electricity => true
                case _           => false
              }
            case _ => false
          }
      }

      val returnSpotsWithoutChargers: Boolean = inquiry.activityType.toLowerCase match {
        case "charge" => false
        case _        => true
      }

      // performs a concentric ring search from the destination to find a parking stall, and creates it
      val (parkingZone, parkingStall) = ZonalParkingManager.incrementalParkingZoneSearch(
        ZonalParkingManager.MinSearchRadius,
        maxSearchRadius,
        inquiry.destinationUtm,
        inquiry.valueOfTime,
        inquiry.parkingDuration,
        preferredParkingTypes,
        inquiry.utilityFunction,
        zoneSearchTree,
        parkingZones,
        tazTreeMap.tazQuadTree,
        geo.distUTMInMeters,
        rand,
        returnSpotsWithChargers,
        returnSpotsWithoutChargers,
        boundingBox
      )

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {

        // update the parking stall data
        val claimed: Boolean = ParkingZone.claimStall(parkingZone).value
        if (claimed) {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
        }

        log.debug(s"Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)

        if (totalStallsInUse % 1000 == 0) log.debug(s"Parking stalls in use: {}", totalStallsInUse)
      }

      sender() ! ParkingInquiryResponse(parkingStall, inquiry.requestId)

    case ReleaseParkingStall(parkingZoneId) =>
      if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
        if (log.isDebugEnabled) {
          // this is an infinitely available resource; no update required
          log.debug("Releasing a stall in the default/emergency zone")
        }
      } else if (parkingZoneId < ParkingZone.DefaultParkingZoneId || parkingZones.length <= parkingZoneId) {
        if (log.isDebugEnabled) {
          log.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
        }
      } else {

        val released: Boolean = ParkingZone.releaseStall(parkingZones(parkingZoneId)).value
        if (released) {
          totalStallsInUse -= 1
          totalStallsAvailable += 1
        }
      }
      if (log.isDebugEnabled) {
        log.debug("ReleaseParkingStall with {} available stalls ", totalStallsAvailable)
      }
  }
}

object ZonalParkingManager extends LazyLogging {

  val ParkingDurationForRideHailAgents: Int = 30 * 60 // 30 minutes?
  val SearchFactor: Double = 2.0 // increases search radius by this factor at each iteration
  val DefaultParkingPrice: Double = 0.0
  val ParkingAvailabilityThreshold: Double = 0.25
  val DepotParkingValueOfTime: Double = 0.0 // ride hail drivers do not have a value of time

  // this number should be less than the MaxSearchRadius config value, tuned to being
  // slightly less than the average distance between TAZ centroids.
  val MinSearchRadius: Double = 1000.0

  /**
    * constructs a ZonalParkingManager from file
    * @param random random number generator used to sample parking stall locations
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): ZonalParkingManager = {

    // generate or load parking
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath

    val (stalls, searchTree) = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingFromTazfile(beamConfig.beam.agentsim.taz.filePath)
    } else {
      Try {
        ParkingZoneFileUtils.fromFile(parkingFilePath)
      } match {
        case Success((s, t)) => (s, t)
        case Failure(e) =>
          logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
          ParkingZoneFileUtils.generateDefaultParkingFromTazfile(beamConfig.beam.agentsim.taz.filePath)
      }
    }
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius

    new ZonalParkingManager(tazTreeMap, geo, stalls, searchTree, random, maxSearchRadius, boundingBox)
  }

  /**
    * constructs a ZonalParkingManager from a string iterator
    * @param parkingDescription line-by-line string representation of parking including header
    * @param random random generator used for sampling parking locations
    * @param includesHeader true if the parkingDescription includes a csv-style header
    * @return
    */
  def apply(
    parkingDescription: Iterator[String],
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    random: Random,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    includesHeader: Boolean = true
  ): ZonalParkingManager = {
    val parking = ParkingZoneFileUtils.fromIterator(parkingDescription, includesHeader)
    new ZonalParkingManager(tazTreeMap, geo, parking.zones, parking.tree, random, maxSearchRadius, boundingBox)
  }

  /**
    * builds a ZonalParkingManager Actor
    * @param beamRouter Actor responsible for routing decisions (deprecated/previously unused)
    * @return
    */
  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    beamRouter: ActorRef,
    boundingBox: Envelope
  ): Props = {
    val random = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius
    Props(ZonalParkingManager(beamConfig, tazTreeMap, geo, random, boundingBox))
  }

  /**
    * looks for the nearest ParkingZone that meets the agent's needs
    * @param searchStartRadius small radius describing a ring shape
    * @param searchMaxRadius larger radius describing a ring shape
    * @param destinationUTM coordinates of this request
    * @param parkingDuration duration requested for this parking, used to calculate cost in ranking
    * @param parkingTypes types of parking this request is interested in
    * @param utilityFunction a utility function for parking alternatives
    * @param searchTree nested map structure assisting search for parking within a TAZ and by parking type
    * @param stalls collection of all parking alternatives
    * @param tazQuadTree lookup of all TAZs in this simulation
    * @param random random generator used to sample a location from the TAZ for this stall
    * @return a stall from the found ParkingZone, or a ParkingStall.DefaultStall
    */
  def incrementalParkingZoneSearch(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    destinationUTM: Location,
    valueOfTime: Double,
    parkingDuration: Double,
    parkingTypes: Seq[ParkingType],
    utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
    searchTree: ParkingZoneSearch.ZoneSearch[TAZ],
    stalls: Array[ParkingZone],
    tazQuadTree: QuadTree[TAZ],
    distanceFunction: (Coord, Coord) => Double,
    random: Random,
    returnSpotsWithChargers: Boolean,
    returnSpotsWithoutChargers: Boolean,
    boundingBox: Envelope
  ): (ParkingZone, ParkingStall) = {

    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > searchMaxRadius) None
      else {
        val tazDistance: Map[TAZ, Double] =
          tazQuadTree
            .getRing(destinationUTM.getX, destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .map { taz =>
              (taz, GeoUtils.distFormula(taz.coord, destinationUTM))
            }
            .toMap
        val tazList: List[TAZ] = tazDistance.keys.toList

        ParkingZoneSearch.find(
          destinationUTM,
          valueOfTime,
          parkingDuration,
          utilityFunction,
          tazList,
          parkingTypes,
          searchTree,
          stalls,
          distanceFunction,
          random,
          returnSpotsWithChargers,
          returnSpotsWithoutChargers
        ) match {
          case Some(
              ParkingZoneSearch.ParkingSearchResult(
                bestTAZ,
                bestParkingType,
                bestParkingZone,
                bestCoord,
                bestRankingValue
              )
              ) =>
            val stallPrice: Double =
              bestParkingZone.pricingModel
                .map { PricingModel.evaluateParkingTicket(_, parkingDuration.toInt) }
                .getOrElse(DefaultParkingPrice)

            // create a new stall instance. you win!
            val newStall = ParkingStall(
              bestTAZ.tazId,
              bestParkingZone.parkingZoneId,
              bestCoord,
              stallPrice,
              bestParkingZone.chargingPointType,
              bestParkingZone.pricingModel,
              bestParkingType
            )

            Some { (bestParkingZone, newStall) }
          case None =>
            _search(thisOuterRadius, thisOuterRadius * SearchFactor)
        }
      }
    }

    _search(0, searchStartRadius) match {
      case Some(result) =>
        result
      case None =>
        val newStall = ParkingStall.lastResortStall(boundingBox, random)
        (ParkingZone.DefaultParkingZone, newStall)
    }
  }
}

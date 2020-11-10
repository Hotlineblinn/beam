package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.{ChargingZone, VehicleManager}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Created by haitamlaarabi
  */

class ChargingNetwork(vehicleManagerName: VehicleManager, chargingStationsQTree: QuadTree[ChargingZone])
    extends LazyLogging {
  import ChargingNetwork._
  import ChargingStatus._

  private val chargingVehicleMap: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private val disconnectedVehicleMap: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  private val chargingStationMap: Map[ChargingZone, ChargingStation] =
    chargingStationsQTree.values().asScala.map(z => z -> ChargingStation(z)).toMap

  /**
    * lookup a station from a parking stall
    * @param stall the parking stall
    * @return charging station
    */
  def lookupStation(stall: ParkingStall): Option[ChargingStation] =
    chargingStationMap.get(ChargingZone.to(stall, vehicleManager))

  /**
    * lookup information about charging vehicle
    * @param vehicleId vehicle Id
    * @return charging vehicle
    */
  def lookupVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
    chargingVehicleMap.get(vehicleId)

  /**
    * get all stations
    * @return list of station
    */
  def lookupStations: List[ChargingStation] =
    chargingStationMap.values.toList

  /**
    * get all vehicles
    * @return list of charging vehicle
    */
  def lookupConnectedVehicles: List[ChargingVehicle] =
    chargingStationMap.flatMap(_._2.connectedVehicles.map(v => chargingVehicleMap(v.id))).toList

  /**
    * remove the disconnected vehicle
    * @param vehicleId the disconnected vehicle id
    * @return list of charging vehicle
    */
  def removeDisconnectedVehicle(vehicleId: Id[BeamVehicle]): Option[ChargingVehicle] =
    disconnectedVehicleMap.remove(vehicleId)

  /**
    * get name of the vehicle manager
    * @return VehicleManager
    */
  def vehicleManager: VehicleManager = vehicleManagerName

  /**
    * clear charging vehicle map
    */
  def clear(): Unit = chargingVehicleMap.clear()

  /**
    * combine charging vehicle information
    * @param vehicleId vehicle Id
    * @param latestChargingSession latest session of charging
    * @return charging vehicle information
    */
  def combine(vehicleId: Id[BeamVehicle], latestChargingSession: ChargingSession): ChargingVehicle = {
    val prev = chargingVehicleMap(vehicleId)
    val updated = prev.copy(
      cumulatedChargingSession = prev.cumulatedChargingSession.combine(prev.latestChargingSession),
      latestChargingSession = latestChargingSession
    )
    chargingVehicleMap.update(vehicleId, updated)
    updated
  }

  /**
    * update charging vehicle information
    * @param vehicleId vehicle Id
    * @param latestChargingSession latest session of charging
    * @return charging vehicle information
    */
  def update(vehicleId: Id[BeamVehicle], latestChargingSession: ChargingSession): ChargingVehicle = {
    val prev = chargingVehicleMap(vehicleId)
    val updated = prev.copy(
      cumulatedChargingSession = prev.cumulatedChargingSession.combine(latestChargingSession),
      latestChargingSession = latestChargingSession
    )
    chargingVehicleMap.update(vehicleId, updated)
    updated
  }

  /**
    * Connect to charging point or add to waiting line
    * @param tick current time
    * @param vehicle vehicle to charge
    * @param stall the correspondant parking stall
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def connectVehicle(
    tick: Int,
    vehicle: BeamVehicle,
    stall: ParkingStall,
    theSender: ActorRef
  ): Map[ChargingVehicle, ConnectionStatus] = {
    val station = lookupStation(stall).get
    val chargingVehicle = ChargingVehicle(
      vehicle,
      vehicleManager,
      stall,
      station,
      cumulatedChargingSession = ChargingSession(tick),
      latestChargingSession = ChargingSession(tick),
      theSender
    )
    chargingVehicleMap.put(vehicle.id, chargingVehicle)
    processWaitingLine(station) + (chargingVehicle -> station.connectVehicle(tick, vehicle))
  }

  /**
    * Disconnect the vehicle for the charging point/station
    * @param vehicleId vehicle to disconnect
    * @return a tuple of the status of the charging vehicle and the connection status
    */
  def disconnectVehicle(vehicleId: Id[BeamVehicle]): Option[(ChargingVehicle, ConnectionStatus)] =
    chargingVehicleMap.remove(vehicleId).map { vehicleCharging =>
      val status = lookupStation(vehicleCharging.stall).get.disconnectVehicle(vehicleId)
      disconnectedVehicleMap.put(vehicleId, vehicleCharging)
      vehicleCharging -> status
    }

  /**
    * Process the waiting line by connecting vehicles that are still in the queue
    * @param station ChargingStation
    * @return a map of vehicle and its corresponding connection status
    */
  def processWaitingLine(station: ChargingStation): Map[ChargingVehicle, ConnectionStatus] =
    station.processWaitingLine().map(x => chargingVehicleMap(x._1) -> x._2)
}

object ChargingNetwork {

  final case class ChargingStation(zone: ChargingZone) {
    import ChargingStatus._

    private val connectedVehiclesInternal = mutable.Map.empty[Id[BeamVehicle], BeamVehicle]
    private val waitingLineInternal: mutable.PriorityQueue[(Int, BeamVehicle)] =
      mutable.PriorityQueue.empty[(Int, BeamVehicle)](Ordering.by((_: (Int, BeamVehicle))._1).reverse)

    def numAvailableChargers: Int = zone.numChargers - connectedVehiclesInternal.size
    def connectedVehicles: List[BeamVehicle] = connectedVehiclesInternal.values.toList
    def waitingLine: List[BeamVehicle] = waitingLineInternal.toList.map(_._2)

    /**
      * add vehicle to connected list and connect to charging point
      * @param tick current time
      * @param vehicle vehicle to connect
      * @return status of connection
      */
    private[ChargingNetwork] def connectVehicle(tick: Int, vehicle: BeamVehicle): ConnectionStatus = {
      if (numAvailableChargers > 0) {
        connectedVehiclesInternal.put(vehicle.id, vehicle)
        ChargingStatus.Connected
      } else {
        waitingLineInternal.enqueue((tick, vehicle))
        ChargingStatus.Waiting
      }
    }

    /**
      * remove vehicle from connected list and disconnect from charging point
      * @param vehicleId vehicle to disconnect
      * @return status of connection
      */
    private[ChargingNetwork] def disconnectVehicle(vehicleId: Id[BeamVehicle]): ConnectionStatus = {
      connectedVehiclesInternal
        .remove(vehicleId)
        .map(_ => ChargingStatus.Disconnected)
        .getOrElse[ConnectionStatus](ChargingStatus.NotConnected)
    }

    /**
      * process waiting line by removing vehicle from waiting line and adding it to the connected list
      * @return map of vehicles that got connected
      */
    private[ChargingNetwork] def processWaitingLine(): Map[Id[BeamVehicle], ConnectionStatus] = {
      (1 to Math.min(waitingLineInternal.size, numAvailableChargers)).map { _ =>
        val (_, vehicle) = waitingLineInternal.dequeue()
        connectedVehiclesInternal.put(vehicle.id, vehicle)
        vehicle.id -> ChargingStatus.Connected
      }.toMap
    }
  }

  final case class ChargingSession(startTime: Int, energy: Double = 0.0, duration: Long = 0) {

    def combine(other: ChargingSession): ChargingSession = {
      ChargingSession(
        startTime = Math.min(this.startTime, other.startTime),
        energy = this.energy + other.energy,
        duration = this.duration + other.duration
      )
    }
  }

  object ChargingStatus extends Enumeration {
    type ConnectionStatus = Value
    val Waiting, Connected, Disconnected, NotConnected = Value
  }

  final case class ChargingVehicle(
    vehicle: BeamVehicle,
    vehicleManager: VehicleManager,
    stall: ParkingStall,
    chargingStation: ChargingStation,
    cumulatedChargingSession: ChargingSession,
    latestChargingSession: ChargingSession,
    theSender: ActorRef
  ) {
    def totalChargingSession: ChargingSession = cumulatedChargingSession.combine(latestChargingSession)
  }
}
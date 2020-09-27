package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  private[power] lazy val beamFederateOption: Option[BeamFederate] = Try {
    logger.debug("Init PowerController resources...")
    BeamFederate.loadHelics
    BeamFederate.getInstance(
      beamServices,
      beamConfig.beam.agentsim.chargingNetworkManager.helicsFederateName,
      beamConfig.beam.agentsim.chargingNetworkManager.planningHorizonInSeconds
    )
  }.recoverWith {
    case e =>
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }.toOption

  def initFederateConnection: Boolean = beamFederateOption.isDefined

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(currentTime: Int, requiredLoad: Map[Int, Double]): Map[Int, PhysicalBounds] = {
    logger.debug("Sending power over next planning horizon to the grid at time {}...", currentTime)
    beamFederateOption
      .fold(logger.warn("Not connected to grid, nothing was sent")) { beamFederate =>
        beamFederate.publishPowerOverPlanningHorizon(requiredLoad)
      }
    logger.debug("Obtaining power from the grid at time {}...", currentTime)
    val bounds = beamFederateOption
        .map { beamFederate =>
          beamFederate.syncAndMoveToNextTimeStep(currentTime)
          beamFederate.obtainPowerFlowValue.map {
            case (k, (a, b, c)) => k -> PhysicalBounds(a, b, c)
          }
        }
        .getOrElse(defaultPowerPhysicalBounds(currentTime, requiredLoad))
    logger.debug("Obtained power from the grid {}...", bounds)
    bounds
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.warn("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
      }

    try {
      logger.debug("Destroying BeamFederate")
      BeamFederate.destroyInstance()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
    }
  }

  def defaultPowerPhysicalBounds(currentTime: Int, requiredLoad: Map[Int, Double]): Map[Int, PhysicalBounds] = {
    logger.warn("Not connected to grid, falling to default physical bounds at time {}...", currentTime)
    requiredLoad.map{case (k, v) => k -> PhysicalBounds(v, v, 0.0)}
  }
}

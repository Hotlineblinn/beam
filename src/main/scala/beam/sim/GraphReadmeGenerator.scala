package beam.sim

import java.io.{BufferedWriter, FileWriter, IOException}

import com.typesafe.scalalogging.LazyLogging

/**
  * Generates a readme for .png files in root folder.
  */

object GraphReadmeGenerator extends LazyLogging {

  private val fileName = "graph_readme.txt"

  private val content =
    """averageCarSpeed.png - Average car speeds in meters per second, grouped by car type, per iteration.
      |
      |averageCarTravelTimes.png - Average travel time, using car, in minutes, including or excluding walk.
      |
      |delayAveragePerKilometer.png - Average delay intensity, in seconds per kilometer, per iteration.
      |
      |delayTotalByLinkCapacity.png - Total delay by binned link capacity, in hours, per iteration.
      |
      |modeChoice.png - Number of transportation chooses, distributed by modes, per iteration.
      |
      |realizedModeChoice.png - Actual number of transportation chooses, distributed by modes, per iteration. Choosen mode might be not used due to different reasons (for example, ride hail can be too far). It that case person will go to replanning and anothe mode will be choosen.
      |
      |referenceModeChoice.png - The same as `modeChoice.png`, but with additional reference modes plot if benchmark file is provided (Beam param `beam.calibration.mode.benchmarkFilePath`)
      |
      |referenceRealizedModeChoice.png - The same as `realizedModeChoice.png`, but with additional reference mode plot
      |
      |replanningCountModeChoice.png - Replanning event count, per iteration. Replanning events are generated when a person is unable to use a planned mode, e.g. due to shortage of seats on buses or shortage of ride hail in a ridehail_transit trip, at the end of the trip.
      |
      |rideHailRevenue.png - Ride hail revenue generated by all ride hail vehicles, per iteration.
      |
      |rideHailUtilisation.png - Number of ride hail trips by passenger distribution, per iteration.
      |
      |scorestats.png - Average agent plans' utility scores.
      |
      |stopwatch.png - Computation time distribution per iteration.
      """.stripMargin

  def generateGraphReadme(rootFolder: String): Unit = {

    //Generate graph comparison html element and write it to the html page at desired location
    val bw = new BufferedWriter(new FileWriter(rootFolder + "/" + fileName))
    try {
      bw.write(content)
    } catch {
      case e: IOException => logger.error("exception occurred due to ", e)
    } finally {
      bw.close()
    }
  }

}

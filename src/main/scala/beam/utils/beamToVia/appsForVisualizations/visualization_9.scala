package beam.utils.beamToVia.appsForVisualizations

import beam.utils.beamToVia.IO.{EventsReader, HashSetReader, Writer}
import beam.utils.beamToVia.beamEvent.BeamPathTraversal
import beam.utils.beamToVia.beamEventsFilter.{MutablePopulationFilter, MutableSamplingFilter, PopulationSample}
import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable


object visualization_9 extends App {
  val personsInCircleFilePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF.persons.txt"
  val personsInCircle = HashSetReader.fromFile(personsInCircleFilePath)

  val beamEventsFilePath = "D:/Work/BEAM/visualizations/v1.it20.events.bridge_cap_5000.csv"
  val sampleSize = 1

  val viaOutputBaseFilePath = "D:/Work/BEAM/visualizations/v9.it20.events.bridge_cap_5000.popSize" + sampleSize
  val viaEventsFile = viaOutputBaseFilePath + ".via.xml"
  val viaIdsFile = viaOutputBaseFilePath + ".ids.txt"
  val viaModesFile = viaOutputBaseFilePath + ".mode.txt"

  val idPrefix = ""

  val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(0.3, personsInCircle.contains)))

  // val selectedPersons = scala.collection.mutable.HashSet("5637427", "6034392", "5856103")
  // val filter: MutableSamplingFilter = MutablePopulationFilter(Seq(PopulationSample(1, selectedPersons.contains)))

  def vehicleType(pte: BeamPathTraversal): String =
    pte.mode + "_" + pte.vehicleType + "_P%03d".format(pte.numberOfPassengers)

  def vehicleId(pte: BeamPathTraversal): String =
    idPrefix + vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, personsEvents) = EventsReader.readWithFilter(beamEventsFilePath, filter)
  //val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  val events = mutable.PriorityQueue.empty[ViaEvent]((e1, e2) => e2.time.compare(e1.time))
  val (modeChoiceEvents, modeToCnt) = EventsReader.transformModeChoices(personsEvents)
  modeChoiceEvents.foreach(events.enqueue(_))

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, viaEventsFile)
  //Writer.writeViaIdFile(typeToId, viaIdsFile)
  Writer.writeViaModes(modeToCnt, viaModesFile)
}

package beam.utils.beamToVia.viaEvent

trait ViaEvent {
  var time: Double
  val link: Int
  def toXml: scala.xml.Elem
  def toXmlString: String
  def timeString: String = time.toInt + ".0"
}

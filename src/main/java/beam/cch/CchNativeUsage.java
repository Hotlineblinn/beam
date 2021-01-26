package beam.cch;

public class CchNativeUsage {
    static {
//        System.load("/home/crixal/work/projects/RoutingKit/src/libroutingkit.so");
        System.load("/home/crixal/work/projects/RoutingKit/src/cchnative.so");
    }

    public static void main(String[] args) {
        CchNative cch = new CchNative();
        cch.init("test");
//        cch.addNodeToMapping(1L, -97.74716922248103,30.39190072165441);
//        cch.addNodeToMapping(2L, -97.78798957076901,30.48329189500538);
//
        CchNativeResponse resp = cch.route(-122.45382120000001, 37.7704974, -122.42595030000001, 37.804280299999995);
        System.out.println("--------------------------------------------");
        System.out.println("Response nodes: " + resp.getNodes());
        System.out.println("Response distance: " + resp.getDistance() + ", dep_time: " + resp.getDepTime());
        System.out.println("--------------------------------------------");
    }
}

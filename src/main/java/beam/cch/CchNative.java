package beam.cch;

public class CchNative {
    public native void init(String filePath);
    public native void addNodeToMapping(long nodeId, double fromX, double fromY, double toX, double toY);
    public native void addNodeToMapping2(long nodeId, long fromId, long toId);
    public native CchNativeResponse route(double fromX, double fromY, double toX, double toY);
}

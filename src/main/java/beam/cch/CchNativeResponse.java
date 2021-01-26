package beam.cch;

import java.util.List;

public class CchNativeResponse {
    private List<String> nodes;
    private long distance;
    private double depTime;
    private List<String> times;

    public CchNativeResponse() {
    }

    public List<String> getNodes() {
        return nodes;
    }

    public List<String> getTimes() {
        return times;
    }

    public long getDistance() {
        return distance;
    }

    public double getDepTime() {
        return depTime;
    }
}

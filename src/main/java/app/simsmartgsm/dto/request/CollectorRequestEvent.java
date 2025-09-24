package app.simsmartgsm.dto.request;

public class CollectorRequestEvent {
    private final String ccid;
    private final String imsi;
    private final String portName;

    public CollectorRequestEvent(String ccid, String imsi, String portName) {
        this.ccid = ccid;
        this.imsi = imsi;
        this.portName = portName;
    }

    public String getCcid() {
        return ccid;
    }

    public String getImsi() {
        return imsi;
    }

    public String getPortName() {
        return portName;
    }
}

package app.simsmartgsm.dto.request;


public class CollectorResolvedEvent {
    private final String ccid;
    private final String phone;

    public CollectorResolvedEvent(String ccid, String phone) {
        this.ccid = ccid;
        this.phone = phone;
    }

    public String getCcid() {
        return ccid;
    }

    public String getPhone() {
        return phone;
    }
}

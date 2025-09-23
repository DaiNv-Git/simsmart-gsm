package app.simsmartgsm.dto.request;
import lombok.Data;

@Data
public class SimRequest {
    private String simId;
    private String phoneNumber;
    private String country;
}

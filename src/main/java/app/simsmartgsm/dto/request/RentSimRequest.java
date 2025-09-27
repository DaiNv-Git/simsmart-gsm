package app.simsmartgsm.dto.request;

import lombok.Data;

import java.util.List;

// === RentSimRequest.java ===
@Data
public class RentSimRequest {
    private String sim;
    private Long accountId;
    private List<String> serviceCode;
    private int rentDuration; 
    private String countryCode;
}

package app.simsmartgsm.dto.request;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

// === RentSimRequest.java ===
@Data
public class RentSimRequest {
    private String deviceName;
    private String phoneNumber;
    private String comNumber;
    private Long customerId;
    private String serviceCode; // CSV string: "FB,GOOGLE"
    private int waitingTime;
    private String countryName;

    // helper: convert serviceCode -> List
    public List<String> getServiceCodeList() {
        if (serviceCode == null || serviceCode.isBlank()) return List.of();
        return Arrays.asList(serviceCode.split(","));
    }
}

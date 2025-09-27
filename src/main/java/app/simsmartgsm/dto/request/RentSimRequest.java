package app.simsmartgsm.dto.request;

import lombok.Data;

import java.util.List;

// === RentSimRequest.java ===
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Arrays;
import java.util.List;

@Data
public class RentSimRequest {

    @JsonProperty("comNumber")
    private String sim;

    @JsonProperty("customerId")
    private Long accountId;

    @JsonProperty("serviceCode")
    private String serviceCodeCsv;  // ví dụ: "FB,GOOGLE"

    @JsonProperty("waitingTime")
    private int rentDuration;

    @JsonProperty("countryName")
    private String countryCode;

    private String deviceName;

    // ✅ Helper method để lấy List<String>
    public List<String> getServiceCodeList() {
        if (serviceCodeCsv == null || serviceCodeCsv.isBlank()) {
            return List.of();
        }
        return Arrays.asList(serviceCodeCsv.split(","));
    }
}


package app.simsmartgsm.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

// === RentSimRequest.java ===
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Arrays;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // ✅ Bỏ qua field không map
public class RentSimRequest {

    @JsonProperty("comNumber")
    private String sim;

    @JsonProperty("type")
    private String type;

    @JsonProperty("customerId")
    private String customerId;
    
    @JsonProperty("phoneNumber")
    private String phoneNumber;

    @JsonProperty("serviceCode")
    private String serviceCodeCsv;  // ví dụ: "FB,GOOGLE"

    @JsonProperty("waitingTime")
    private int rentDuration;

    @JsonProperty("countryName")
    private String countryCode;

    private String deviceName;

    @JsonProperty("orderId")
    private String orderId;

    
    // ✅ Helper method để lấy List<String>
    public List<String> getServiceCodeList() {
        if (serviceCodeCsv == null || serviceCodeCsv.isBlank()) {
            return List.of();
        }
        return Arrays.asList(serviceCodeCsv.split(","));
    }
}


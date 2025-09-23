package app.simsmartgsm.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimRequest {
    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("port_data")
    private List<PortInfo> portData;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortInfo {
        @JsonProperty("port_name")
        private String portName;
        private boolean success;
        @JsonProperty("sim_provider")
        private String simProvider;
        @JsonProperty("phone_number")
        private String phoneNumber;
        private String ccid;
        private String message;
    }

}

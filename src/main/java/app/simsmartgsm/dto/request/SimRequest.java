package app.simsmartgsm.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Minimal payload: deviceName + list of PortSimple
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimRequest {
    private String deviceName;
    private List<PortSimple> portData;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortSimple {
        private String portName;
        private String phoneNumber; // null nếu không có
    }
}

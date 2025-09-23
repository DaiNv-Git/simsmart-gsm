package app.simsmartgsm.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimRequest {
    private String deviceName;
    private List<PortInfo> portData;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortInfo {
        private String portName;     // COMx
        private boolean success;     // trạng thái
        private String simProvider;  // Mạng di động (Docomo, Rakuten…)
        private String phoneNumber;  // số điện thoại
        private String ccid;         // ICCID (AT+CCID)
        private String message;      // log hoặc lỗi
    }
}

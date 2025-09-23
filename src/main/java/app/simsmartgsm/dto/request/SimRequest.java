package app.simsmartgsm.dto.request;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
@Data
@AllArgsConstructor
public class SimRequest {
    private String deviceName;
    private List<PortInfo> portData;
    public static class PortInfo {
        private String portName;
        private boolean success;
        private String phoneNumber;
        private String message;

        public PortInfo() {}

        public PortInfo(String portName, boolean success, String phoneNumber, String message) {
            this.portName = portName;
            this.success = success;
            this.phoneNumber = phoneNumber;
            this.message = message;
        }

        public String getPortName() {
            return portName;
        }

        public void setPortName(String portName) {
            this.portName = portName;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}

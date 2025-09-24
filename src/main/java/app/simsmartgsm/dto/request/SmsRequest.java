package app.simsmartgsm.dto.request;

import lombok.Data;

@Data
public class SmsRequest {
    private String portName;     // COM92
    private String phoneNumber;  // số đích gửi
    private String message;      // nội dung SMS
}

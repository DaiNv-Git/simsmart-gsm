package app.simsmartgsm.dto.response;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SmsResponse {
    private String com;           // COM port
    private String phone;         // Số điện thoại SIM
    private String sender;        // Người gửi
    private String receivedTime;  // Thời gian nhận
    private String content;       // Nội dung tin nhắn
}


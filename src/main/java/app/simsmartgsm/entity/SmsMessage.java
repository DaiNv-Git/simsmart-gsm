package app.simsmartgsm.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sms_messages")
public class SmsMessage {
    @Id
    private String id;

    /** Liên kết với Order thuê SIM */
    private String orderId;

    /** Thời gian thuê (minutes) */
    private int durationMinutes;

    /** Tên device chạy service (hostname / deviceName) */
    private String deviceName;

    /** COM port modem (vd: COM76) */
    private String comPort;

    /** Số điện thoại SIM gắn trên modem */
    private String simPhone;

    /** Số điện thoại SIM gắn trên modem */
    private String serviceCode;

    /** Số gửi (Sender / From) */
    private String fromNumber;

    /** Số nhận (Receiver / To) */
    private String toNumber;

    /** Nội dung SMS */
    private String content;

    /** Trả về từ modem (OK/ERROR/...) */
    private String modemResponse;

    /** Loại tin nhắn: INBOX / OUTBOX / OTP... */
    private String type;
    
    private String customerId;

    /** Thời điểm lưu SMS */
    private Instant timestamp;
}

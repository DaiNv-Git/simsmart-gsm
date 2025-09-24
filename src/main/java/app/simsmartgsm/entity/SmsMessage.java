package app.simsmartgsm.entity;

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
    private String deviceName;
    private String fromPort;
    private String toPhone;
    private String message;
    private String modemResponse;
    private String type;
    private Instant timestamp;
}

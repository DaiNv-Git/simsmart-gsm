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
@Document(collection = "call_messages")
public class CallMessage {
    @Id
    private String id;

    private String orderId;
    private Long accountId;

    private String simPhone;
    private String fromNumber;
    private String toNumber;

    private Instant startTime;
    private Instant endTime;

    private String status;        // RECEIVED, MISSED
    private String recordingPath; // file ghi âm nếu có
}

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
@Document(collection = "call_records")
public class CallRecord {

    @Id
    private String id;

    private String orderId;
    private Long customerId;

    private String simPhone;
    private String fromNumber;

    private String deviceName;
    private String comPort;

    private String countryCode;
    private String serviceCode;

    private String status;

    private String recordFile;

    private Instant callStartTime;
    private Instant callEndTime;
    private Instant expireAt;

    private Instant createdAt;
    private Instant updatedAt;
}

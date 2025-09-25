package app.simsmartgsm.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Bảng SIM chứa danh sách SIM đang có trong hệ thống.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "sims")
public class Sim {

    /** MongoDB document ID */
    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString();

    /** Số điện thoại SIM */
    private String phoneNumber;

    /** Tổng doanh thu từ SIM này */
    private Double revenue;

    /** Trạng thái SIM: new, active, inactive */
    private String status;

    /** Mã quốc gia (mặc định JVM) */
    @Builder.Default
    private String countryCode = "JVM";

    /** Thiết bị đang chứa SIM (tên server/vps) */
    private String deviceName;

    /** Tên cổng COM mà SIM được kết nối */
    private String comName;

    /** Nhà mạng cung cấp SIM */
    private String simProvider;

    /** ICCID/CCID của SIM */
    private String ccid;

    private String imsi;

    /** Nội dung (ghi chú / thông tin khác) */
    private String content;

    /** Thời điểm cập nhật cuối cùng */
    private Instant lastUpdated;

    /** Ngày SIM được kích hoạt */
    private Instant activeDate;
}

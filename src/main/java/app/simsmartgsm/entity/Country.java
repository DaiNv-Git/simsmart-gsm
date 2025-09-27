package app.simsmartgsm.entity;


import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Bảng COUNTRY lưu thông tin quốc gia.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "countries")
public class Country {

    @Id
    private String id;              // Mongo ObjectId

    /** Mã quốc gia (ISO Alpha-3, ví dụ: VNM, JPN) */
    private String countryCode;

    /** Tên quốc gia (Vietnam, Japan, ...) */
    private String countryName;

    /** URL ảnh cờ */
    private String flagImage;
}

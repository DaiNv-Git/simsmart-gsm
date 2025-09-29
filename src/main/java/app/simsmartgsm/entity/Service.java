package app.simsmartgsm.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "services")
public class Service {
    @Id
    private String id;
    private String code;
    private String text;
    private List<String> matches;
    private String countryCode;
    private boolean isActive;
}

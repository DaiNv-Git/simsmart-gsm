package app.simsmartgsm.dto.response;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RentSimRequest {
    private String simId;

    @JsonProperty("accountId")
    @JsonAlias({"custId", "customerId"})
    private Long accountId;

    @JsonProperty("serviceCode")
    @JsonAlias({"services", "serviceCodes", "service"})
    private List<String> serviceCode;

    @JsonProperty("rentDuration")
    @JsonAlias({"duration", "durationMinutes"})
    private int rentDuration;

    @JsonProperty("countryCode")
    @JsonAlias({"country", "nationCode"})
    private String countryCode;
}

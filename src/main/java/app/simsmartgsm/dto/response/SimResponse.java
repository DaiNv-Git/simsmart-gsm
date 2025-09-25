package app.simsmartgsm.dto.response;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SimResponse {
    private String com;
    private String status;
    private String simProvider;
    private String phoneNumber;
    private String ccid;
    private String content;
}


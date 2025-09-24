package app.simsmartgsm.dto.request;

import lombok.Data;

import java.util.List;
@Data
public class SmartBulkSmsRequest {
    private String text;
    private List<String> phones;
}

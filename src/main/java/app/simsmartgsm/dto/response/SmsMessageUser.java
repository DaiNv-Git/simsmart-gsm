package app.simsmartgsm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
  public class SmsMessageUser {
    private String from;
    private String content;
}
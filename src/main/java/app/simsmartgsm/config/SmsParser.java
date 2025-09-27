package app.simsmartgsm.config;

import app.simsmartgsm.dto.response.SmsMessageUser;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmsParser {

    public static SmsMessageUser parse(String resp) {
        try {
            if (resp == null || !resp.contains("+CMGL")) {
                return null;
            }

            // Ví dụ raw:
            // +CMGL: 1,"REC UNREAD","+819012345678",,"25/09/27,15:59:10+36"
            // OTP CODE 123456
            String[] lines = resp.split("\r\n|\n");
            String from = null;
            String content = null;

            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("+CMGL:")) {
                    String[] parts = lines[i].split(",");
                    if (parts.length >= 3) {
                        from = parts[2].replace("\"", "");
                    }
                    if (i + 1 < lines.length) {
                        content = lines[i + 1].trim();
                    }
                }
            }

            if (from != null && content != null && !content.isBlank()) {
                return new SmsMessageUser(from, content);
            }
        } catch (Exception e) {
            log.warn("⚠️ Cannot parse SMS from resp: {}", e.getMessage());
        }
        return null;
    }
}

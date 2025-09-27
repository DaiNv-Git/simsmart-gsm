package app.simsmartgsm.config;

import app.simsmartgsm.dto.response.SmsMessageUser;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SmsParser {

    public static SmsMessageUser parse(String resp) {
        try {
            if (resp == null || resp.isBlank()) {
                return null;
            }

            // ===== Case 1: CMGL (list inbox) =====
            if (resp.contains("+CMGL:")) {
                String[] lines = resp.split("\r\n|\n");
                String from = null;
                String content = null;

                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].startsWith("+CMGL:")) {
                        String[] parts = lines[i].split(",");
                        if (parts.length >= 3) {
                            from = parts[2].replace("\"", "").trim();
                        }
                        if (i + 1 < lines.length) {
                            content = lines[i + 1].trim();
                        }
                    }
                }
                if (from != null && content != null && !content.isBlank()) {
                    return new SmsMessageUser(from, content);
                }
            }

            // ===== Case 2: CMT (push new SMS) =====
            if (resp.contains("+CMT:")) {
                String[] lines = resp.split("\r\n|\n");
                String from = null;
                String content = null;

                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].startsWith("+CMT:")) {
                        String[] parts = lines[i].split(",");
                        if (parts.length >= 2) {
                            from = parts[1].replace("\"", "").trim();
                        }
                        if (i + 1 < lines.length) {
                            content = lines[i + 1].trim();
                        }
                    }
                }
                if (from != null && content != null && !content.isBlank()) {
                    return new SmsMessageUser(from, content);
                }
            }

            // ===== Case 3: CMTI (new SMS index) =====
            if (resp.contains("+CMTI:")) {
                // Ví dụ: +CMTI: "SM",3
                String[] parts = resp.split(",");
                if (parts.length >= 2) {
                    String index = parts[1].trim();
                    log.info("📩 New SMS arrived at index {}", index);
                    // Caller sẽ cần gọi AT+CMGR=index để đọc chi tiết
                    return new SmsMessageUser("UNKNOWN", "[INDEX:" + index + "]");
                }
            }

        } catch (Exception e) {
            log.warn("⚠️ Cannot parse SMS from resp: {}", e.getMessage());
        }
        return null;
    }
}

package app.simsmartgsm.config;

import app.simsmartgsm.dto.response.SmsMessageUser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SmsParser {

    public static SmsMessageUser parse(String resp) {
        try {
            if (resp == null || resp.isBlank()) {
                return null;
            }

            String[] lines = resp.split("\r\n|\n");
            String from = null;
            String content = null;

            // ===== Case 1: CMGL (list inbox) =====
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("+CMGL:")) {
                    // +CMGL: 1,"REC UNREAD","+819012345678",,"25/09/28,09:16:55+36"
                    Matcher m = Pattern.compile("\"(\\+?\\d+)\"").matcher(line);
                    if (m.find()) {
                        from = m.group(1);
                    }
                    if (i + 1 < lines.length) {
                        content = lines[i + 1].trim();
                    }
                    break; // chỉ lấy tin đầu tiên
                }
            }
            if (from != null && content != null && !content.isBlank()) {
                return new SmsMessageUser(from, content);
            }

            // ===== Case 2: CMT (push new SMS ngay khi đến) =====
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("+CMT:")) {
                    // +CMT: "+819012345678","","25/09/28,09:16:55+36"
                    Matcher m = Pattern.compile("\"(\\+?\\d+)\"").matcher(line);
                    if (m.find()) {
                        from = m.group(1);
                    }
                    if (i + 1 < lines.length) {
                        content = lines[i + 1].trim();
                    }
                    break;
                }
            }
            if (from != null && content != null && !content.isBlank()) {
                return new SmsMessageUser(from, content);
            }

            // ===== Case 3: CMTI (chỉ báo index) =====
            if (resp.contains("+CMTI:")) {
                // +CMTI: "SM",3
                Matcher m = Pattern.compile("\\+CMTI:\\s*\"\\w+\",(\\d+)").matcher(resp);
                if (m.find()) {
                    String index = m.group(1);
                    log.info("📩 New SMS arrived at index {}", index);
                    return new SmsMessageUser("UNKNOWN", "[INDEX:" + index + "]");
                }
            }

        } catch (Exception e) {
            log.warn("⚠️ Cannot parse SMS. Raw={} Error={}", resp.replace("\r"," ").replace("\n"," "), e.getMessage());
        }

        return null;
    }
    public static List<SmsMessageUser> parseMulti(String resp) {
        List<SmsMessageUser> messages = new ArrayList<>();
        if (resp == null || resp.isBlank()) return messages;

        String[] lines = resp.split("\r\n|\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.startsWith("+CMGL:") || line.startsWith("+CMGR:")) {
                String from = "UNKNOWN";
                Matcher m = Pattern.compile("\"(\\+?\\d+)\"").matcher(line);
                if (m.find()) {
                    from = m.group(1);
                }
                if (i + 1 < lines.length) {
                    String content = lines[i + 1].trim();
                    if (!content.isBlank()) {
                        messages.add(new SmsMessageUser(from, content));
                    }
                }
            }
        }
        return messages;
    }
}

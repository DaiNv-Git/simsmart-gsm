package app.simsmartgsm.config;
import app.simsmartgsm.dto.response.SmsMessageUser;
import lombok.extern.slf4j.Slf4j;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SmsParser {

    // Regex match dòng header SMS: +CMGL: index,"REC UNREAD","+84901234567","","25/09/26,15:32:10+32"
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "\\+CMGL:\\s*(\\d+),\"[^\"]*\",\"([^\"]+)\",\"\",\"([^\"]+)\""
    );

    /**
     * Parse raw response từ modem thành SmsMessage
     * @param resp Chuỗi trả về từ AT+CMGL
     * @return SmsMessage hoặc null nếu không parse được
     */
    public static SmsMessageUser parse(String resp) {
        try {
            // Tách theo dòng
            String[] lines = resp.split("\r\n");
            String from = null;
            String content = null;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();

                // Nếu match header: lấy số gửi
                Matcher m = HEADER_PATTERN.matcher(line);
                if (m.find()) {
                    from = m.group(2); // số điện thoại gửi tin
                    if (i + 1 < lines.length) {
                        content = lines[i + 1].trim(); // nội dung tin ngay sau header
                    }
                }
            }

            if (from != null && content != null) {
                return new SmsMessageUser(from, content);
            } else {
                log.warn("Cannot parse SMS from resp: {}", resp);
                return null;
            }

        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage(), e);
            return null;
        }
    }
}


package app.simsmartgsm.service;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) {
            throw new RuntimeException("Không thể mở cổng " + portName);
        }
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.info("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    /** Gửi SMS qua cổng chỉ định */
    public String sendSms(String portName, String phoneNumber, String text) {
        SerialPort port = openPort(portName);
        StringBuilder modemResp = new StringBuilder();
        String status = "UNKNOWN";

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1"); // text mode
            sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

            Thread.sleep(500); // đợi modem trả '>'
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A); // Ctrl+Z
            out.flush();

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    modemResp.append(line).append("\n");
                    if (line.contains("OK")) {
                        status = "OK";
                        break;
                    }
                    if (line.contains("ERROR")) {
                        status = "ERROR";
                        break;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi gửi SMS qua " + portName, e);
        } finally {
            port.closePort();
        }

        return "{\n" +
                "  \"status\": \"" + status + "\",\n" +
                "  \"port\": \"" + portName + "\",\n" +
                "  \"phoneNumber\": \"" + phoneNumber + "\",\n" +
                "  \"message\": \"" + text + "\",\n" +
                "  \"modemResponse\": \"" + modemResp.toString().replace("\"", "\\\"").trim() + "\"\n" +
                "}";
    }

    /** Đọc tất cả SMS từ SIM */
    public String readSms(String portName) {
        SerialPort port = openPort(portName);
        List<Map<String, String>> messages = new ArrayList<>();

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            // chuẩn bị môi trường đọc SMS
            sendCmd(out, "AT+CMGF=1");          // text mode
            sendCmd(out, "AT+CSCS=\"GSM\"");   // charset
            sendCmd(out, "AT+CPMS=\"SM\"");    // chọn bộ nhớ SIM
            sendCmd(out, "AT+CMGL=\"ALL\"");   // đọc tất cả SMS

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 8000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("+CMGL:")) {
                    header = line; // header của SMS
                } else if (header != null) {
                    // line này là nội dung
                    Map<String, String> sms = parseSms(header, line);
                    messages.add(sms);
                    header = null;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi đọc SMS qua " + portName, e);
        } finally {
            port.closePort();
        }

        // nếu không có SMS thì trả về []
        if (messages.isEmpty()) {
            return "[]";
        }

        // Convert list sang JSON (có thể thay bằng Jackson/Gson nếu muốn)
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> sms = messages.get(i);
            json.append("  {\n");
            for (Map.Entry<String, String> entry : sms.entrySet()) {
                json.append("    \"").append(entry.getKey()).append("\": \"")
                        .append(entry.getValue().replace("\"", "\\\"")).append("\",\n");
            }
            if (json.charAt(json.length() - 2) == ',') {
                json.delete(json.length() - 2, json.length() - 1);
            }
            json.append("  }");
            if (i < messages.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("]");
        return json.toString();
    }

    private Map<String, String> parseSms(String header, String content) {
        Map<String, String> sms = new LinkedHashMap<>();
        sms.put("header", header);
        sms.put("content", content);

        try {
            // ví dụ header: +CMGL: 1,"REC READ","+84901234567","","25/09/24,10:11:00+08"
            String[] parts = header.split(",");
            if (parts.length >= 2) {
                sms.put("status", parts[1].replace("\"", "").trim());
            }
            if (parts.length >= 3) {
                sms.put("sender", parts[2].replace("\"", "").trim());
            }
            if (parts.length >= 5) {
                sms.put("timestamp", parts[4].replace("\"", "").trim() + " " +
                        parts[5].replace("\"", "").trim());
            }
        } catch (Exception ignore) {}

        return sms;
    }
}

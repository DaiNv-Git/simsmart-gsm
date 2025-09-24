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
        if (!port.openPort()) throw new RuntimeException("Không thể mở cổng " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.info("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    // === đọc tin nhắn theo status cho 1 port ===
    private List<Map<String, String>> readByStatus(String portName, String status) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream(); Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {
            sendCmd(out, "AT+CMGF=1");        // text mode
            sendCmd(out, "AT+CSCS=\"GSM\""); // charset
            sendCmd(out, "AT+CPMS=\"MT\"");  // đọc cả SIM + modem
            sendCmd(out, "AT+CMGL=\"" + status + "\"");

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 8000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("+CMGL:")) {
                    header = line;
                } else if (header != null) {
                    Map<String, String> sms = new LinkedHashMap<>();
                    sms.put("port", portName);
                    sms.put("header", header);
                    sms.put("content", line);
                    messages.add(sms);
                    header = null;
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc SMS {} từ {}: {}", status, portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return messages;
    }

    // === API 1: đọc inbox (tin nhắn đến) của tất cả port ===
    public String readInboxAll() {
        return readAllPorts("REC UNREAD");
    }

    // === API 2: đọc sent (tin nhắn gửi đi) của tất cả port ===
    public String readSentAll() {
        return readAllPorts("STO SENT");
    }

    // === API 3: đọc failed (tin nhắn gửi đi lỗi) của tất cả port ===
    public String readFailedAll() {
        return readAllPorts("STO UNSENT");
    }

    // helper: đọc tất cả port với 1 status
    private String readAllPorts(String status) {
        SerialPort[] ports = SerialPort.getCommPorts();
        List<Map<String, String>> all = new ArrayList<>();
        for (SerialPort sp : ports) {
            all.addAll(readByStatus(sp.getSystemPortName(), status));
        }
        return toJson(all);
    }

    private String toJson(List<Map<String, String>> list) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("  {");
            int j = 0;
            for (Map.Entry<String, String> e : list.get(i).entrySet()) {
                if (j++ > 0) sb.append(", ");
                sb.append("\"").append(e.getKey()).append("\": \"")
                        .append(e.getValue().replace("\"", "\\\"")).append("\"");
            }
            sb.append("}");
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }
}

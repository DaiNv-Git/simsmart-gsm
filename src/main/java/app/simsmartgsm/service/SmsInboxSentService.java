package app.simsmartgsm.service;
import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SmsInboxSentService {
    private static final Logger log = LoggerFactory.getLogger(SmsInboxSentService.class);

    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) throw new RuntimeException("Không thể mở cổng " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    /** Đọc SMS theo status từ 1 port */
    private List<Map<String, String>> readByStatus(String portName, String status) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CMGF=1");
            sendCmd(out, "AT+CSCS=\"GSM\"");
            sendCmd(out, "AT+CPMS=\"SM\"");
            sendCmd(out, "AT+CMGL=\"" + status + "\"");

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("+CMGL:")) {
                    header = line;
                } else if (header != null) {
                    messages.add(parseSms(portName, header, line));
                    header = null;
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc {} từ {}: {}", status, portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return messages;
    }

    /** Đọc tất cả port với status */
    private String readAllPorts(String status) {
        SerialPort[] ports = SerialPort.getCommPorts();
        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2)
        );
        List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
        for (SerialPort sp : ports) {
            futures.add(exec.submit(() -> readByStatus(sp.getSystemPortName(), status)));
        }
        List<Map<String, String>> all = new ArrayList<>();
        for (Future<List<Map<String, String>>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception e) {
                log.error("❌ Lỗi thread: {}", e.getMessage());
            }
        }
        exec.shutdown();
        return toJson(all);
    }

    // Public APIs
    public String readInboxAll() {
        return readAllPorts("REC UNREAD"); // tin nhắn đến chưa đọc
    }

    public String readSentAll() {
        return readAllPorts("STO SENT"); // tin nhắn đã gửi
    }

    private Map<String, String> parseSms(String port, String header, String content) {
        Map<String, String> sms = new LinkedHashMap<>();
        sms.put("port", port);
        sms.put("header", header);
        sms.put("content", content);
        try {
            String[] parts = header.split(",");
            if (parts.length >= 2) sms.put("status", parts[1].replace("\"", "").trim());
            if (parts.length >= 3) sms.put("sender", parts[2].replace("\"", "").trim());
            if (parts.length >= 5) {
                sms.put("timestamp", parts[4].replace("\"", "").trim() + " " +
                        parts[5].replace("\"", "").trim());
            }
        } catch (Exception ignore) {}
        return sms;
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

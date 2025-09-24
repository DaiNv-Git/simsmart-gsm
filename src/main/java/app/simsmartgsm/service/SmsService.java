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
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    // ====== Common helpers ======
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
        log.debug("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    // ====== 1. Gửi SMS ======
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

    // ====== 2. Đọc tất cả SMS cho 1 port ======
    public List<Map<String, String>> readAllSmsForPort(String portName) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1");        // text mode
            sendCmd(out, "AT+CSCS=\"GSM\""); // charset
            sendCmd(out, "AT+CPMS=\"SM\"");  // SIM storage
            sendCmd(out, "AT+CMGL=\"ALL\""); // all SMS

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 8000 && scanner.hasNextLine()) {
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
            log.error("❌ Lỗi đọc SMS từ {}: {}", portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return messages;
    }

    // ====== 3. Đọc SMS mới nhất cho 1 port ======
    public Map<String, String> readLatestSmsForPort(String portName) {
        Map<String, String> latest = null;
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1");
            sendCmd(out, "AT+CSCS=\"GSM\"");
            sendCmd(out, "AT+CPMS=\"SM\"");
            sendCmd(out, "AT+CMGL=\"ALL\"");

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 8000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("+CMGL:")) {
                    header = line;
                } else if (header != null) {
                    latest = parseSms(portName, header, line);
                    header = null;
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc SMS mới nhất từ {}: {}", portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return latest;
    }

    // ====== 4. Đọc tất cả SMS từ tất cả port (đa luồng) ======
    public String readAllSmsAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2)
        );

        List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
        for (SerialPort sp : ports) {
            futures.add(executor.submit(() -> readAllSmsForPort(sp.getSystemPortName())));
        }

        List<Map<String, String>> all = new ArrayList<>();
        for (Future<List<Map<String, String>>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception e) {
                log.error("❌ Lỗi khi đọc SMS đa luồng: {}", e.getMessage());
            }
        }

        executor.shutdown();
        return toJson(all);
    }

    // ====== 5. Đọc SMS mới nhất từ tất cả port (đa luồng) ======
    public String readLatestSmsAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2)
        );

        List<Future<Map<String, String>>> futures = new ArrayList<>();
        for (SerialPort sp : ports) {
            futures.add(executor.submit(() -> readLatestSmsForPort(sp.getSystemPortName())));
        }

        List<Map<String, String>> all = new ArrayList<>();
        for (Future<Map<String, String>> f : futures) {
            try {
                Map<String, String> sms = f.get();
                if (sms != null) all.add(sms);
            } catch (Exception e) {
                log.error("❌ Lỗi khi đọc SMS mới nhất đa luồng: {}", e.getMessage());
            }
        }

        executor.shutdown();
        return toJson(all);
    }

    // ====== Helper parse & JSON ======
    private Map<String, String> parseSms(String port, String header, String content) {
        Map<String, String> sms = new LinkedHashMap<>();
        sms.put("port", port);
        sms.put("header", header);
        sms.put("content", content);

        try {
            // ví dụ: +CMGL: 1,"REC READ","+84901234567","","25/09/24,10:11:00+08"
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

    public String toJson(List<Map<String, String>> messages) {
        if (messages.isEmpty()) return "[]";
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
}

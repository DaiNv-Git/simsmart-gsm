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

    // ====== Common Helpers ======
    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) {
            log.warn("⚠️ Không thể mở cổng {}", portName);
            return null;
        }
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.debug("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    // ====== Đọc SMS theo status cho 1 port ======
    private List<Map<String, String>> readByStatus(String portName, String status) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);
        if (port == null) return messages;

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CMGF=1");          // text mode
            sendCmd(out, "AT+CSCS=\"GSM\"");   // charset
            sendCmd(out, "AT+CNMI=2,1,0,0,0"); // enable new SMS indication

            String[] memories = {"SM", "ME", "MT"};
            for (String mem : memories) {
                sendCmd(out, "AT+CPMS=\"" + mem + "\"");
                sendCmd(out, "AT+CMGL=\"" + status + "\"");

                String header = null;
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 4000 && scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("+CMGL:")) {
                        header = line;
                    } else if (header != null) {
                        Map<String, String> sms = new LinkedHashMap<>();
                        sms.put("port", portName);
                        sms.put("memory", mem);
                        sms.put("header", header);
                        sms.put("content", line);
                        messages.add(sms);
                        header = null;
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc SMS {} từ {}: {}", status, portName, e.getMessage());
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
        return messages;
    }

    // ====== Đọc RAW AT response cho debug ======
    public String readRawAll(String portName) {
        SerialPort port = openPort(portName);
        if (port == null) return "[]";

        StringBuilder raw = new StringBuilder();
        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CMGF=1");
            sendCmd(out, "AT+CSCS=\"GSM\"");
            sendCmd(out, "AT+CPMS=\"MT\"");
            sendCmd(out, "AT+CMGL=\"ALL\"");

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 6000 && scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (!line.trim().isEmpty()) {
                    raw.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc RAW từ {}: {}", portName, e.getMessage());
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
        return raw.toString();
    }

    // ====== Đọc SMS của tất cả port theo status (đa luồng) ======
    private String readAllPorts(String status) {
        SerialPort[] ports = SerialPort.getCommPorts();
        int poolSize = Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<List<Map<String, String>>>> futures = new ArrayList<>();

        for (SerialPort sp : ports) {
            futures.add(executor.submit(() -> readByStatus(sp.getSystemPortName(), status)));
        }

        List<Map<String, String>> all = new ArrayList<>();
        for (Future<List<Map<String, String>>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception e) {
                log.error("❌ Lỗi khi lấy dữ liệu SMS song song: {}", e.getMessage());
            }
        }

        executor.shutdown();
        return toJson(all);
    }

    // ====== Public API ======
    /** Inbox - tin nhắn đến */
    public String readInboxAll() {
        return readAllPorts("REC UNREAD");
    }

    /** Sent - tin nhắn đã gửi */
    public String readSentAll() {
        return readAllPorts("STO SENT");
    }

    /** Failed - tin nhắn gửi đi lỗi */
    public String readFailedAll() {
        return readAllPorts("STO UNSENT");
    }

    // ====== JSON Helper ======
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

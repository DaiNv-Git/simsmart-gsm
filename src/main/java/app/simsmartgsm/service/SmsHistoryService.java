package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class SmsHistoryService {
    private static final Logger log = LoggerFactory.getLogger(SmsHistoryService.class);

    private final SmsMessageRepository repository;
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss");

    // ================== Lấy từ MongoDB ==================
    public Page<SmsMessage> getSentMessages(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return repository.findByType("OK", pageable);
    }

    public Page<SmsMessage> getFailedMessages(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return repository.findByType("FAIL", pageable);
    }

    // ================== Đọc inbox từ GSM ==================
    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) throw new RuntimeException("Không thể mở cổng " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.debug("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }
    private List<Map<String, String>> readInboxFromPort(String portName) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner sc = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CMGF=1");          // text mode
            sendCmd(out, "AT+CSCS=\"GSM\"");   // charset
            sendCmd(out, "AT+CNMI=2,1,0,0,0"); // lưu SMS vào bộ nhớ
            sendCmd(out, "AT+CPMS=\"SM\"");    // thử đọc SIM
            sendCmd(out, "AT+CMGL=\"ALL\"");   // đọc tất cả SMS

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10000 && sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;

                log.info("[{}] <- {}", portName, line);

                if (line.startsWith("+CMGL:")) {
                    header = line;
                } else if (header != null) {
                    messages.add(parseInbox(portName, header, line));
                    header = null;
                }
            }

            // Nếu không thấy tin nào thì thử đọc bộ nhớ máy
            if (messages.isEmpty()) {
                sendCmd(out, "AT+CPMS=\"ME\"");
                sendCmd(out, "AT+CMGL=\"ALL\"");
                start = System.currentTimeMillis();
                header = null;
                while (System.currentTimeMillis() - start < 5000 && sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty()) continue;

                    log.info("[{}][ME] <- {}", portName, line);

                    if (line.startsWith("+CMGL:")) {
                        header = line;
                    } else if (header != null) {
                        messages.add(parseInbox(portName, header, line));
                        header = null;
                    }
                }
            }

        } catch (Exception e) {
            log.error("❌ Lỗi đọc inbox từ {}: {}", portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return messages;
    }

    public Page<Map<String, String>> getInboxMessages(int page, int size) {
        SerialPort[] ports = SerialPort.getCommPorts();
        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2)
        );

        List<Future<List<Map<String, String>>>> futures = new ArrayList<>();
        for (SerialPort sp : ports) {
            futures.add(exec.submit(() -> readInboxFromPort(sp.getSystemPortName())));
        }

        List<Map<String, String>> all = new ArrayList<>();
        for (Future<List<Map<String, String>>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception ex) {
                log.error("❌ Lỗi thread khi đọc inbox: {}", ex.getMessage());
            }
        }
        exec.shutdown();

        // sắp xếp theo ReceivedTime DESC
        all.sort((m1, m2) -> {
            try {
                String ts1 = m1.getOrDefault("ReceivedTime", "");
                String ts2 = m2.getOrDefault("ReceivedTime", "");
                LocalDateTime d1 = LocalDateTime.parse(ts1, TS_FORMAT);
                LocalDateTime d2 = LocalDateTime.parse(ts2, TS_FORMAT);
                return d2.compareTo(d1);
            } catch (Exception e) {
                return 0;
            }
        });

        int start = page * size;
        int end = Math.min(start + size, all.size());
        if (start > end) start = end;

        return new PageImpl<>(all.subList(start, end), PageRequest.of(page, size), all.size());
    }

    private Map<String, String> parseInbox(String port, String header, String content) {
        Map<String, String> sms = new LinkedHashMap<>();
        sms.put("COM", port);
        sms.put("MessageContent", content);

        try {
            // ví dụ: +CMGL: 0,"REC UNREAD","+84901234567",,"25/09/24,14:07:17+36"
            String[] parts = header.split(",");
            if (parts.length >= 3) {
                String phone = parts[2].replace("\"", "").trim();
                sms.put("Phone", phone);
                sms.put("Sender", phone);
            }
            if (parts.length >= 5) {
                String ts = parts[4].replace("\"", "").trim();
                if (parts.length > 5) ts += " " + parts[5].replace("\"", "").trim();
                sms.put("ReceivedTime", ts);
            }
        } catch (Exception ignore) {}

        return sms;
    }
}

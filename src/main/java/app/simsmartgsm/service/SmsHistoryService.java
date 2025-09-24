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
import java.net.InetAddress;
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
        return repository.findByTypeAndDeviceName("OK", getDeviceName(), pageable);
    }

    public Page<SmsMessage> getFailedMessages(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        return repository.findByTypeAndDeviceName("FAIL", getDeviceName(), pageable);
    }

    private String getDeviceName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-device";
        }
    }

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
        Thread.sleep(150);
    }

    // đọc SMS từ 1 bộ nhớ (SM hoặc ME)
    private List<Map<String, String>> readFromMemory(String portName, String memory) {
        List<Map<String, String>> messages = new ArrayList<>();
        SerialPort port = openPort(portName);

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner sc = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CMGF=1");
            sendCmd(out, "AT+CSCS=\"GSM\"");
            sendCmd(out, "AT+CPMS=\"" + memory + "\"");
            sendCmd(out, "AT+CMGL=\"ALL\"");

            String header = null;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 7000 && sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("+CMGL:")) {
                    header = line;
                } else if (header != null) {
                    messages.add(parseInbox(portName, header, line));
                    header = null;
                }
            }
        } catch (Exception e) {
            log.error("❌ Lỗi đọc SMS từ {}[{}]: {}", portName, memory, e.getMessage());
        } finally {
            try { port.closePort(); } catch (Exception ignore) {}
        }
        return messages;
    }

    // đọc inbox từ 1 port (ưu tiên SM, fallback ME)
    private List<Map<String, String>> readInboxFromPort(String portName) {
        List<Map<String, String>> msgs = readFromMemory(portName, "SM");
        if (msgs.isEmpty()) {
            msgs = readFromMemory(portName, "ME");
        }
        return msgs;
    }

    // đọc inbox từ tất cả port + phân trang
    public Page<Map<String, String>> getInboxMessages(int page, int size) {
        SerialPort[] ports = SerialPort.getCommPorts();
        ExecutorService exec = Executors.newFixedThreadPool(
                Math.min(ports.length, Runtime.getRuntime().availableProcessors() * 2)
        );

        List<Callable<List<Map<String, String>>>> tasks = new ArrayList<>();
        for (SerialPort sp : ports) {
            tasks.add(() -> readInboxFromPort(sp.getSystemPortName()));
        }

        List<Map<String, String>> all = new ArrayList<>();
        try {
            List<Future<List<Map<String, String>>>> futures = exec.invokeAll(tasks);
            for (Future<List<Map<String, String>>> f : futures) {
                try {
                    all.addAll(f.get());
                } catch (Exception ex) {
                    log.error("❌ Lỗi thread khi đọc inbox: {}", ex.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exec.shutdown();
        }


        all.sort((m1, m2) -> {
            String ts1 = m1.getOrDefault("ReceivedTime", "");
            String ts2 = m2.getOrDefault("ReceivedTime", "");
            return ts2.compareTo(ts1); // đảo ngược để newest lên đầu
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
            String[] parts = header.split(",");
            if (parts.length >= 2) {
                sms.put("Status", parts[1].replace("\"", "").trim());
            }
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

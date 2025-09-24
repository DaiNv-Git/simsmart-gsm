package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class SmsSenderService {

    private static final Logger log = LoggerFactory.getLogger(SmsSenderService.class);
    private final SmsMessageRepository smsRepo;
    private static final int MAX_RETRY = 3;

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
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000
        );
        if (!port.openPort()) throw new RuntimeException("❌ Không thể mở cổng " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.debug("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    private String getSimPhoneNumber(SerialPort port) {
        String phone = "unknown";
        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream();
             Scanner sc = new Scanner(in, StandardCharsets.US_ASCII)) {

            sendCmd(out, "AT+CNUM");
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 2000 && sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.contains("+CNUM")) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2) {
                        phone = parts[1].replace("\"", "").trim();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Không lấy được số điện thoại SIM ở {}: {}", port.getSystemPortName(), e.getMessage());
        }
        return phone;
    }

    private SmsMessage sendOne(String portName, String phoneNumber, String text) {
        String status = "FAIL";
        StringBuilder resp = new StringBuilder();
        String fromPhone = "unknown";

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            SerialPort port = null;
            try {
                port = openPort(portName);

                // lấy số điện thoại của SIM gửi
                fromPhone = getSimPhoneNumber(port);

                try (OutputStream out = port.getOutputStream();
                     InputStream in = port.getInputStream();
                     Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

                    sendCmd(out, "AT+CMGF=1");
                    sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

                    Thread.sleep(500); // chờ dấu '>'

                    out.write(text.getBytes(StandardCharsets.US_ASCII));
                    out.write(0x1A);
                    out.flush();

                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 7000 && scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();
                        if (!line.isEmpty()) {
                            resp.append(line).append("\n");
                            if (line.contains("OK")) {
                                status = "OK";
                                break;
                            }
                            if (line.contains("ERROR")) {
                                status = "FAIL";
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Lỗi gửi SMS lần {}/{} qua {} -> {}: {}", attempt, MAX_RETRY, portName, phoneNumber, e.getMessage());
            } finally {
                if (port != null) port.closePort();
            }

            if ("OK".equals(status)) break; // thành công thì thoát
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
        }

        SmsMessage saved = SmsMessage.builder()
                .deviceName(getDeviceName())
                .fromPort(portName)
                .fromPhone(fromPhone)   // số điện thoại SIM gửi
                .toPhone(phoneNumber)
                .message(text)
                .type(status) // OK hoặc FAIL
                .modemResponse(resp.toString().trim())
                .timestamp(Instant.now())
                .build();

        return smsRepo.save(saved);
    }

    // Gửi nhiều số điện thoại 1 nội dung
    public List<SmsMessage> sendBulk(List<String> phones, String text) {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) throw new RuntimeException("❌ Không tìm thấy port nào khả dụng");

        ExecutorService exec = Executors.newFixedThreadPool(Math.min(phones.size(), ports.length));
        List<Future<SmsMessage>> futures = new ArrayList<>();
        List<SmsMessage> results = new ArrayList<>();

        int idx = 0;
        for (String phone : phones) {
            String portName = ports[idx % ports.length].getSystemPortName(); // round-robin
            idx++;
            futures.add(exec.submit(() -> sendOne(portName, phone, text)));
        }

        for (Future<SmsMessage> f : futures) {
            try {
                results.add(f.get());
            } catch (Exception e) {
                log.error("❌ Lỗi bulk send: {}", e.getMessage());
            }
        }
        exec.shutdown();
        return results;
    }
}

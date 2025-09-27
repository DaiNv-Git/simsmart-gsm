package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import app.simsmartgsm.repository.SmsMessageRepository;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class SmsSenderService {

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
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) throw new RuntimeException("❌ Không thể mở cổng " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.debug("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    private String getSimPhoneNumber(OutputStream out, Scanner sc) throws Exception {
        sendCmd(out, "AT+CNUM");
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2000 && sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].replace("\"", "").trim();
                }
            }
        }
        return "unknown";
    }


    public SmsMessage sendOne(String portName, String phoneNumber, String text) {
        String status = "FAIL";
        StringBuilder resp = new StringBuilder();
        String fromPhone = "unknown";

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            SerialPort port = null;
            try {
                port = openPort(portName);

                try (OutputStream out = port.getOutputStream();
                     InputStream in = port.getInputStream();
                     Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

                    // Lấy số SIM gửi
                    fromPhone = getSimPhoneNumber(out, scanner);

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
                            if (line.contains("OK") || line.contains("+CMGS")) {
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
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status)) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
        }

        SmsMessage saved = SmsMessage.builder()
                .deviceName(getDeviceName())
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .type(status) // OK hoặc FAIL
                .modemResponse(resp.toString().trim())
                .timestamp(Instant.now())
                .build();

        return smsRepo.save(saved);
    }
    public SmsMessage sendOne(String portName, String phoneNumber, String text, boolean saveToDb) {
        String status = "FAIL";
        StringBuilder resp = new StringBuilder();
        String fromPhone = "unknown";

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            SerialPort port = null;
            try {
                port = openPort(portName);

                try (OutputStream out = port.getOutputStream();
                     InputStream in = port.getInputStream();
                     Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

                    // Lấy số SIM gửi
                    fromPhone = getSimPhoneNumber(out, scanner);

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
                            if (line.contains("OK") || line.contains("+CMGS")) {
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
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status)) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
        }

        SmsMessage saved = SmsMessage.builder()
                .deviceName(getDeviceName())
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .type(status.equals("OK") ? "OUTBOUND" : "OUTBOUND_FAIL") // đánh dấu rõ outbound
                .modemResponse(resp.toString().trim())
                .timestamp(Instant.now())
                .build();

        if (saveToDb) {
            return smsRepo.save(saved);
        } else {
            return saved;
        }
    }

    // === Gửi nhiều số cùng 1 nội dung ===
    public List<SmsMessage> sendBulk(List<String> phones, String text, String portName) {
        ExecutorService exec = Executors.newFixedThreadPool(phones.size());
        List<Future<SmsMessage>> futures = new ArrayList<>();
        List<SmsMessage> results = new ArrayList<>();

        for (String phone : phones) {
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

    // === Shortcut: gửi 1 SMS không lưu DB (cho test nhanh) ===
    public boolean sendSms(String comName, String phoneNumber, String message) {
        return "OK".equals(sendOne(comName, phoneNumber, message,false).getType());
    }
}

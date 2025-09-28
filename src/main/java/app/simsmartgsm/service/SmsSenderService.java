package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Scanner;

@Service
@Slf4j
public class SmsSenderService {

    private static final int MAX_RETRY = 3;

    public boolean sendSms(String comPort, String toNumber, String message) {
        return "OK".equals(sendOne(comPort, toNumber, message).getType());
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
                     InputStream in = port.getInputStream()) {

                    Scanner sc = new Scanner(in, StandardCharsets.US_ASCII);

                    // === clear buffer trước khi bắt đầu ===
                    while (in.available() > 0) in.read();

                    // === lấy số SIM gửi ===
                    fromPhone = getSimPhoneNumber(out, sc);

                    // === config cơ bản ===
                    sendCmd(out, "AT");
                    sendCmd(out, "AT+CMGF=1");          // text mode
                    sendCmd(out, "AT+CSCS=\"GSM\"");    // charset
                    sendCmd(out, "AT+CNMI=2,1,0,1,0");  // enable push DLR
                    sendCmd(out, "AT+CMGD=1,4");        // xoá toàn bộ inbox, tránh đầy bộ nhớ

                    // === chuẩn bị gửi SMS ===
                    sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

                    // === chờ dấu '>' ===
                    String prompt = waitForPrompt(in, 5000);
                    if (prompt == null) {
                        log.warn("❌ {}: không nhận được dấu '>'", portName);
                        status = "FAIL";
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                        continue;
                    }

                    // === gửi nội dung SMS ===
                    out.write((text + "\r").getBytes(StandardCharsets.UTF_8));
                    out.write(0x1A); // Ctrl+Z
                    out.flush();

                    // === đọc phản hồi ===
                    String modemResp = readResponse(in, 30000, resp);
                    if (modemResp.contains("OK") || modemResp.contains("+CDS:")) {
                        status = "OK";
                    } else if (modemResp.contains("+CMGS")) {
                        status = "SENT";
                    } else {
                        status = "FAIL";
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Lỗi gửi SMS lần {}/{} qua {} -> {}: {}", attempt, MAX_RETRY, portName, phoneNumber, e.getMessage());
            } finally {
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status) || "SENT".equals(status)) break;
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 1000);
            } catch (InterruptedException ignored) {}
        }

        return SmsMessage.builder()
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .modemResponse(resp.toString())
                .type(status)  // OK / SENT / FAIL
                .timestamp(Instant.now())
                .build();
    }
    private String waitForPrompt(InputStream in, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == '>') return ">";
            }
            Thread.sleep(50);
        }
        return null;
    }

    private String readResponse(InputStream in, long timeoutMs, StringBuilder resp) throws Exception {
        long start = System.currentTimeMillis();
        StringBuilder lineBuffer = new StringBuilder();

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == -1) break;

                char c = (char) b;
                if (c == '\r' || c == '\n') {
                    String line = lineBuffer.toString().trim();
                    if (!line.isEmpty()) {
                        resp.append(line).append("\n");
                        log.debug("📥 modem resp: {}", line);
                    }
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append(c);
                }
            }
        }
        return resp.toString();
    }


    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) throw new RuntimeException("Không mở được port " + portName);
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        String fullCmd = cmd + "\r";
        out.write(fullCmd.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        log.debug("➡️ CMD: {}", cmd);
        Thread.sleep(200); // small delay tránh modem bị nghẽn
    }

    private String getSimPhoneNumber(OutputStream out, Scanner sc) throws Exception {
        sendCmd(out, "AT+CNUM");
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 2000) {
            if (sc.hasNextLine()) {
                String line = sc.nextLine();
                if (line.contains("+CNUM")) {
                    String[] parts = line.split(",");
                    if (parts.length > 1) {
                        return parts[1].replaceAll("\"", "").trim();
                    }
                }
            }
        }
        return "unknown";
    }
}

package app.simsmartgsm.service;

import app.simsmartgsm.entity.SmsMessage;
import com.fazecast.jSerialComm.SerialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsSenderService {

    private final PortManager portManager;
    private static final int MAX_RETRY = 3;

    /**
     * Gửi SMS, trả về true nếu modem xác nhận gửi thành công.
     */
    public boolean sendSms(String portName, String toNumber, String message) {
        SmsMessage result = sendOne(portName, toNumber, message);
        log.info("📤 [{}] -> {} | status={} | resp={}",
                portName, toNumber, result.getType(), result.getModemResponse());
        return "OK".equals(result.getType()) || "SENT".equals(result.getType());
    }


    /**
     * Gửi 1 SMS, trả về SmsMessage chứa thông tin modem response.
     */
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

                    // clear buffer
                    while (in.available() > 0) in.read();

                    // config cơ bản
                    sendCmd(out, "AT");
                    sendCmd(out, "AT+CMGF=1");          // text mode
                    sendCmd(out, "AT+CSCS=\"GSM\"");    // charset
                    sendCmd(out, "AT+CNMI=2,1,0,1,0");  // push DLR

                    // chuẩn bị gửi
                    sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

                    // chờ dấu '>'
                    String prompt = waitForPrompt(in, 2000);
                    if (prompt == null) {
                        log.warn("❌ {}: không nhận được dấu '>'", portName);
                        status = "FAIL";
                        continue;
                    }

                    // gửi nội dung
                    out.write((text + "\r").getBytes(StandardCharsets.UTF_8));
                    out.write(0x1A); // Ctrl+Z
                    out.flush();

                    // chờ phản hồi ngắn
                    String modemResp = readResponse(in, 5000, resp);
                    if (modemResp.contains("OK")) {
                        status = "OK";   // modem xác nhận đã gửi
                    } else if (modemResp.contains("+CMGS")) {
                        status = "SENT"; // modem nhận, SMS đang gửi đi
                    } else {
                        status = "FAIL";
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Lỗi gửi SMS lần {}/{} qua {} -> {}: {}", attempt, MAX_RETRY,
                        portName, phoneNumber, e.getMessage());
            } finally {
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status) || "SENT".equals(status)) break;
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 500); // backoff: 0.5s, 1s, 2s
            } catch (InterruptedException ignored) {}
        }

        return SmsMessage.builder()
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .modemResponse(resp.toString())
                .type(status)
                .timestamp(Instant.now())
                .build();
    }

    // --- Helper methods ---
    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 3000, 3000);
        if (!port.openPort()) {
            throw new RuntimeException("❌ Cannot open port " + portName);
        }
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        String full = cmd + "\r";
        out.write(full.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(200);
    }

    private String waitForPrompt(InputStream in, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (in.available() > 0) {
                char c = (char) in.read();
                sb.append(c);
                if (sb.toString().contains(">")) {
                    return sb.toString();
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private String readResponse(InputStream in, long timeoutMs, StringBuilder collector) throws Exception {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (in.available() > 0) {
                char c = (char) in.read();
                sb.append(c);
                collector.append(c);
                String resp = sb.toString();
                if (resp.contains("OK") || resp.contains("ERROR")
                        || resp.contains("+CMS ERROR") || resp.contains("+CME ERROR")
                        || resp.contains("+CMGS")) {
                    return resp;
                }
            }
            Thread.sleep(50);
        }
        return sb.toString();
    }
}

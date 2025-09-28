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

                    // ==== Lấy số SIM gửi ====
                    fromPhone = getSimPhoneNumber(out, sc);

                    // ==== Cấu hình cơ bản ====
                    sendCmd(out, "AT+CMGF=1");          // text mode
                    sendCmd(out, "AT+CSCS=\"GSM\"");    // charset
                    sendCmd(out, "AT+CSCA?");           // SMSC
                    sendCmd(out, "AT+CSMP=49,167,0,0"); // enable delivery report
                    sendCmd(out, "AT+CNMI=2,1,0,1,0");  // push delivery report to TE

                    // ==== Bắt đầu gửi SMS ====
                    sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

                    // ==== Chờ dấu '>' ====
                    boolean gotPrompt = false;
                    long waitStart = System.currentTimeMillis();
                    while (System.currentTimeMillis() - waitStart < 5000) {
                        if (in.available() > 0) {
                            int b = in.read();
                            if (b == '>') {
                                gotPrompt = true;
                                log.debug("📥 Nhận dấu '>' từ modem {}", portName);
                                break;
                            }
                        }
                    }
                    if (!gotPrompt) {
                        log.warn("❌ Không nhận được dấu '>' từ modem {}", portName);
                        status = "FAIL";
                        Thread.sleep((long) Math.pow(2, attempt) * 1000); // backoff
                        continue;
                    }

                    // ==== Gửi nội dung SMS ====
                    out.write((text + "\r").getBytes(StandardCharsets.UTF_8));
                    out.write(0x1A); // Ctrl+Z
                    out.flush();

                    // ==== Đọc phản hồi modem ====
                    long start = System.currentTimeMillis();
                    StringBuilder lineBuffer = new StringBuilder();

                    while (System.currentTimeMillis() - start < 30000) {
                        if (in.available() > 0) {
                            int b = in.read();
                            if (b == -1) break;

                            char c = (char) b;
                            if (c == '\r' || c == '\n') {
                                String line = lineBuffer.toString().trim();
                                if (!line.isEmpty()) {
                                    resp.append(line).append("\n");
                                    log.debug("[{}] modem resp: {}", portName, line);

                                    if (line.contains("+CMGS")) {
                                        status = "SENT"; // modem chấp nhận
                                    }
                                    if (line.contains("+CDS:")) {
                                        log.info("📩 Delivery report nhận từ modem {}: {}", portName, line);
                                        status = "OK"; // xác nhận SMSC đã deliver
                                        break;
                                    }
                                    if (line.equals("OK") && "SENT".equals(status)) {
                                        // chưa có CDS nhưng modem đã gửi đi
                                        log.info("✅ Modem {} báo đã gửi SMS (chưa có Delivery Report)", portName);
                                    }
                                    if (line.contains("ERROR") || line.contains("+CMS ERROR") || line.contains("+CME ERROR")) {
                                        status = "FAIL";
                                        break;
                                    }
                                }
                                lineBuffer.setLength(0);
                            } else {
                                lineBuffer.append(c);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Lỗi gửi SMS lần {}/{} qua {} -> {}: {}", attempt, MAX_RETRY, portName, phoneNumber, e.getMessage());
            } finally {
                if (port != null && port.isOpen()) port.closePort();
            }

            if ("OK".equals(status)) break; // success
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 1000); // exponential backoff
            } catch (InterruptedException ignored) {}
        }

        SmsMessage msg = SmsMessage.builder()
                .fromPort(portName)
                .fromPhone(fromPhone)
                .toPhone(phoneNumber)
                .message(text)
                .modemResponse(resp.toString())
                .type(status)              // OK / SENT / FAIL
                .timestamp(Instant.now())
                .build();

        return msg;
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

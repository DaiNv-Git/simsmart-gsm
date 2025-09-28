package app.simsmartgsm.service;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
public class SimpleGsmClient implements AutoCloseable {
    private final SerialPort port;

    public SimpleGsmClient(String portName) {
        port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);

        if (!port.openPort()) {
            throw new RuntimeException("❌ Cannot open port " + portName);
        }
        log.info("✅ Opened GSM port {}", portName);
    }

    public void sendSms(String number, String text) throws IOException {
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            clearBuffer(in);

            // Bật text mode
            sendCmd(out, "AT");
            waitForOk(in);

            sendCmd(out, "AT+CMGF=1"); // text mode
            waitForOk(in);

            // Bắt đầu gửi SMS
            sendCmd(out, "AT+CMGS=\"" + number + "\"");
            waitForPrompt(in);

            // Gửi nội dung + Ctrl+Z
            out.write((text + "\r").getBytes(StandardCharsets.UTF_8));
            out.write(0x1A); // Ctrl+Z
            out.flush();

            String resp = readUntilOkOrError(in, 30000);
            if (resp.contains("OK")) {
                log.info("📤 SMS sent successfully to {}", number);
            } else {
                throw new IOException("❌ SMS failed, modem response: " + resp);
            }
        }
    }

    private void sendCmd(OutputStream out, String cmd) throws IOException {
        String full = cmd + "\r";
        out.write(full.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        log.debug("➡️ CMD: {}", cmd);
        sleep(200);
    }

    private void waitForOk(InputStream in) throws IOException {
        String resp = readUntil(in, "OK", 5000);
        if (!resp.contains("OK")) {
            throw new IOException("❌ Expected OK but got: " + resp);
        }
    }

    private void waitForPrompt(InputStream in) throws IOException {
        String resp = readUntil(in, ">", 5000);
        if (!resp.contains(">")) {
            throw new IOException("❌ Expected '>' but got: " + resp);
        }
    }

    private String readUntil(InputStream in, String token, long timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (in.available() > 0) {
                char c = (char) in.read();
                sb.append(c);
                if (sb.toString().contains(token)) {
                    return sb.toString();
                }
            }
            sleep(50);
        }
        return sb.toString();
    }

    private String readUntilOkOrError(InputStream in, long timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (in.available() > 0) {
                char c = (char) in.read();
                sb.append(c);
                String resp = sb.toString();
                if (resp.contains("OK") || resp.contains("ERROR") || resp.contains("+CMS ERROR") || resp.contains("+CME ERROR")) {
                    return resp;
                }
            }
            sleep(50);
        }
        return sb.toString();
    }

    private void clearBuffer(InputStream in) throws IOException {
        while (in.available() > 0) {
            in.read();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
    public String readAllSms() throws IOException {
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            clearBuffer(in);

            sendCmd(out, "AT");
            waitForOk(in);

            sendCmd(out, "AT+CMGF=1"); // text mode
            waitForOk(in);

            sendCmd(out, "AT+CMGL=\"ALL\"");
            String resp = readUntilOkOrError(in, 10000);

            log.info("📥 ReadAllSms resp:\n{}", resp);
            return resp;
        }
    }

    /**
     * Đọc tin nhắn chưa đọc.
     */
    public String readUnreadSms() throws IOException {
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            clearBuffer(in);

            sendCmd(out, "AT");
            waitForOk(in);

            sendCmd(out, "AT+CMGF=1");
            waitForOk(in);

            sendCmd(out, "AT+CMGL=\"REC UNREAD\"");
            String resp = readUntilOkOrError(in, 10000);

            log.info("📥 ReadUnreadSms resp:\n{}", resp);
            return resp;
        }
    }

    /**
     * Xoá toàn bộ SMS.
     */
    public void deleteAllSms() throws IOException {
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            clearBuffer(in);

            sendCmd(out, "AT");
            waitForOk(in);

            sendCmd(out, "AT+CMGD=1,4");
            waitForOk(in);

            log.info("🗑️ Deleted all SMS in modem {}", port.getSystemPortName());
        }
    }
    
    @Override
    public void close() {
        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("🔌 Closed GSM port {}", port.getSystemPortName());
        }
    }
}

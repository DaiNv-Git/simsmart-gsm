package app.simsmartgsm.service;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

@Service
public class GsmSimpleService {
    private static final Logger log = LoggerFactory.getLogger(GsmSimpleService.class);

    private static final String COM_PORT = "COM1"; // đổi sang /dev/ttyUSB0 nếu Linux
    private static final int BAUD_RATE = 115200;

    private SerialPort openPort() {
        SerialPort port = SerialPort.getCommPort(COM_PORT);
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);

        if (!port.openPort()) {
            throw new RuntimeException("Không thể mở cổng " + COM_PORT);
        }
        return port;
    }

    private void send(OutputStream out, String cmd) throws Exception {
        log.info("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(300);
    }

    /** Gửi SMS và trả kết quả modem trả về */
    public String sendSms(String phoneNumber, String text) {
        SerialPort port = openPort();
        StringBuilder result = new StringBuilder();

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            // Text mode
            send(out, "AT+CMGF=1");

            // Chuẩn bị gửi
            send(out, "AT+CMGS=\"" + phoneNumber + "\"");

            Thread.sleep(500); // chờ modem trả '>'

            // Gửi nội dung + Ctrl+Z
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A);
            out.flush();

            // Đọc phản hồi
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    log.info("📩 {}", line);
                    result.append(line).append("\n");
                    if (line.contains("OK") || line.contains("ERROR")) break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi gửi SMS", e);
        } finally {
            port.closePort();
        }

        return result.toString();
    }

    /** Đọc toàn bộ tin nhắn trong SIM và trả về text */
    public String readAllSms() {
        SerialPort port = openPort();
        StringBuilder result = new StringBuilder();

        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            send(out, "AT+CMGF=1");        // text mode
            send(out, "AT+CSCS=\"GSM\""); // charset chuẩn
            send(out, "AT+CMGL=\"ALL\""); // đọc tất cả SMS

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    log.info("📩 {}", line);
                    result.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi đọc SMS", e);
        } finally {
            port.closePort();
        }

        return result.toString();
    }
}

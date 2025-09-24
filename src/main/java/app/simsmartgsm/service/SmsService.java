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
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) {
            throw new RuntimeException("Không thể mở cổng " + portName);
        }
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.info("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(300);
    }

    /** Gửi SMS qua cổng chỉ định */
    public String sendSms(String portName, String phoneNumber, String text) {
        SerialPort port = openPort(portName);
        StringBuilder result = new StringBuilder();
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1"); // text mode
            sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

            Thread.sleep(500); // chờ modem trả '>'
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A); // Ctrl+Z
            out.flush();

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    result.append(line).append("\n");
                    if (line.contains("OK") || line.contains("ERROR")) break;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi gửi SMS qua " + portName, e);
        } finally {
            port.closePort();
        }
        return result.toString();
    }

    /** Đọc toàn bộ SMS từ SIM */
    public String readSms(String portName) {
        SerialPort port = openPort(portName);
        StringBuilder result = new StringBuilder();
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1");        // text mode
            sendCmd(out, "AT+CSCS=\"GSM\""); // charset
            sendCmd(out, "AT+CMGL=\"ALL\""); // đọc inbox

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    result.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Lỗi đọc SMS qua " + portName, e);
        } finally {
            port.closePort();
        }
        return result.toString();
    }
}

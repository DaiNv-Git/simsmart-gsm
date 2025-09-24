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
public class SmsRoutingService {
    private static final Logger log = LoggerFactory.getLogger(SmsRoutingService.class);

    private SerialPort openPort(String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);
        if (!port.openPort()) {
            throw new RuntimeException("❌ Không thể mở cổng " + portName);
        }
        return port;
    }

    private void sendCmd(OutputStream out, String cmd) throws Exception {
        log.info("➡️ {}", cmd);
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(300);
    }

    /** Lấy số điện thoại từ 1 SIM */
    public String getPhoneNumber(String portName) {
        SerialPort port = openPort(portName);
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            sendCmd(out, "AT+CNUM");
            Scanner sc = new Scanner(in, StandardCharsets.US_ASCII);
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
        } catch (Exception e) {
            log.error("❌ Lỗi đọc số điện thoại từ {}: {}", portName, e.getMessage());
        } finally {
            port.closePort();
        }
        return null;
    }

    /** Gửi SMS từ 1 cổng bất kỳ tới SIM ở cổng đích */
    public String sendViaRandomPort(String targetPort, String message) {
        // 1. Lấy số đích từ SIM targetPort
        String targetPhone = getPhoneNumber(targetPort);
        if (targetPhone == null) {
            throw new RuntimeException("Không lấy được số điện thoại từ " + targetPort);
        }

        // 2. Chọn 1 port khác để gửi
        SerialPort[] ports = SerialPort.getCommPorts();
        String senderPort = null;
        for (SerialPort sp : ports) {
            if (!sp.getSystemPortName().equals(targetPort)) {
                senderPort = sp.getSystemPortName();
                break;
            }
        }
        if (senderPort == null) {
            throw new RuntimeException("Không tìm thấy cổng gửi khác ngoài " + targetPort);
        }

        // 3. Gửi SMS
        SerialPort port = openPort(senderPort);
        StringBuilder resp = new StringBuilder();
        try (OutputStream out = port.getOutputStream(); InputStream in = port.getInputStream()) {
            Scanner sc = new Scanner(in, StandardCharsets.US_ASCII);

            sendCmd(out, "AT+CMGF=1"); // text mode
            sendCmd(out, "AT+CMGS=\"" + targetPhone + "\"");

            Thread.sleep(500);
            out.write(message.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A); // Ctrl+Z
            out.flush();

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (!line.isEmpty()) resp.append(line).append("\n");
                if (line.contains("OK") || line.contains("ERROR")) break;
            }
        } catch (Exception e) {
            throw new RuntimeException("❌ Lỗi gửi SMS qua " + senderPort, e);
        } finally {
            port.closePort();
        }

        return "{\n" +
                "  \"fromPort\": \"" + senderPort + "\",\n" +
                "  \"toPort\": \"" + targetPort + "\",\n" +
                "  \"toPhone\": \"" + targetPhone + "\",\n" +
                "  \"message\": \"" + message + "\",\n" +
                "  \"modemResp\": \"" + resp.toString().replace("\"", "\\\"").trim() + "\"\n" +
                "}";
    }
}


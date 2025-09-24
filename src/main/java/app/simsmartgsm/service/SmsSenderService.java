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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Scanner;

@Service
@RequiredArgsConstructor
public class SmsSenderService {

    private static final Logger log = LoggerFactory.getLogger(SmsSenderService.class);

    private final SmsMessageRepository smsRepo;

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
        Thread.sleep(200);
    }

    /**
     * Gửi 1 SMS qua port và lưu DB
     */
    public SmsMessage sendAndSave(String deviceName, String portName, String phoneNumber, String text) {
        SerialPort port = openPort(portName);
        StringBuilder resp = new StringBuilder();
        String status = "FAIL";

        try (OutputStream out = port.getOutputStream();
             InputStream in = port.getInputStream()) {

            Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII);

            // set text mode
            sendCmd(out, "AT+CMGF=1");

            // chuẩn bị gửi
            sendCmd(out, "AT+CMGS=\"" + phoneNumber + "\"");

            Thread.sleep(500); // đợi dấu '>'

            // nội dung + Ctrl+Z
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A);
            out.flush();

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000 && scanner.hasNextLine()) {
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

        } catch (Exception e) {
            log.error("❌ Lỗi gửi SMS qua {}: {}", portName, e.getMessage());
            resp.append("Exception: ").append(e.getMessage());
        } finally {
            port.closePort();
        }

        SmsMessage saved = SmsMessage.builder()
                .deviceName(deviceName)
                .fromPort(portName)
                .toPhone(phoneNumber)
                .message(text)
                .type(status)
                .modemResponse(resp.toString().trim())
                .timestamp(Instant.now())
                .build();


        return smsRepo.save(saved);
    }
}

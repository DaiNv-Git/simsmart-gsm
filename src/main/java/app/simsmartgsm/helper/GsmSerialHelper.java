package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Scanner;

public class GsmSerialHelper {

    public static String sendAtCommand(String portName, int baudRate, String atCommand, int timeoutSeconds) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutSeconds * 1000, 0);

        if (!port.openPort()) {
            return "❌ Không mở được cổng " + portName;
        }

        try {
            OutputStream out = port.getOutputStream();
            InputStream in = port.getInputStream();

            // Xóa buffer cũ
            port.flushIOBuffers();

            // Gửi lệnh AT
            out.write(atCommand.getBytes());
            out.flush();

            // Đọc phản hồi
            StringBuilder response = new StringBuilder();
            Scanner scanner = new Scanner(in);
            long endTime = System.currentTimeMillis() + timeoutSeconds * 1000L;

            while (System.currentTimeMillis() < endTime && scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    response.append(line).append("\n");
                }
            }

            return response.length() > 0 ? response.toString().trim() : "⏳ Không có phản hồi.";

        } catch (IOException e) {
            return "🚨 Lỗi khi gửi AT: " + e.getMessage();
        } finally {
            port.closePort();
        }
    }

    /**
     * Gửi SMS (chế độ text mode AT+CMGF=1)
     */
    public static String sendSms(String portName, int baudRate, String phoneNumber, String message) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 5000, 0);

        if (!port.openPort()) {
            return "❌ Không mở được cổng " + portName;
        }

        try {
            OutputStream out = port.getOutputStream();
            InputStream in = port.getInputStream();

            // AT+CMGF=1 (chế độ text)
            out.write("AT+CMGF=1\r".getBytes());
            out.flush();
            Thread.sleep(2000);
            System.out.println("Response CMGF: " + new String(in.readAllBytes()));

            // AT+CMGS="phone"
            String cmdSend = "AT+CMGS=\"" + phoneNumber + "\"\r";
            out.write(cmdSend.getBytes());
            out.flush();
            Thread.sleep(2000);
            System.out.println("Response CMGS: " + new String(in.readAllBytes()));

            // Gửi nội dung + Ctrl+Z
            out.write((message + (char) 26).getBytes());
            out.flush();
            Thread.sleep(5000);
            String resp = new String(in.readAllBytes());

            return resp.contains("OK") ? "✅ SMS sent" : "❌ Lỗi gửi SMS: " + resp;

        } catch (Exception e) {
            return "🚨 Lỗi khi gửi SMS: " + e.getMessage();
        } finally {
            port.closePort();
        }
    }

    public static void listenCom(String portName, int baudRate) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!port.openPort()) {
            System.out.println("❌ Không thể mở cổng " + portName);
            return;
        }

        try (InputStream in = port.getInputStream()) {
            System.out.println("✅ Đang lắng nghe cổng " + portName);

            byte[] buffer = new byte[1024];
            while (true) {
                int len = in.read(buffer);
                if (len > 0) {
                    String received = new String(buffer, 0, len).trim();
                    System.out.println("📩 Received: " + received);
                }
                Thread.sleep(100);
            }
        } catch (Exception e) {
            System.out.println("🚨 Lỗi đọc COM: " + e.getMessage());
        } finally {
            port.closePort();
        }
    }

    // Test nhanh
    public static void main(String[] args) {
        String port = "COM39";
        int baud = 9600;

        // Test AT
//        System.out.println(sendAtCommand(port, baud, "AT\r\n", 2));

        // Test SMS
        // System.out.println(sendSms(port, baud, "+84901234567", "Hello Java GSM!"));

        // Nghe COM
         listenCom(port, baud);
    }
}


package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class GetSimNumber {

    private static final String PORT_NAME = "COM91";
    private static final int BAUDRATE = 115200;

    public static void main(String[] args) {
        SerialPort port = SerialPort.getCommPort(PORT_NAME);
        port.setBaudRate(BAUDRATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!port.openPort()) {
            System.err.println("❌ Không thể mở cổng " + PORT_NAME);
            return;
        }

        try {
            // Gửi lệnh AT+CNUM để lấy số
            String phoneNumber = getPhoneNumber(port);
            System.out.println("📱 Số điện thoại SIM: " + phoneNumber);
        } catch (Exception e) {
            System.err.println("❌ Lỗi khi lấy số điện thoại: " + e.getMessage());
        } finally {
            port.closePort();
        }
    }

    public static String getPhoneNumber(SerialPort port) throws IOException, InterruptedException {
        sendAtCommand(port, "AT");      // kiểm tra kết nối
        sendAtCommand(port, "AT+CSCS=\"GSM\""); // set charset GSM (để không bị UCS2)
        String response = sendAtCommand(port, "AT+CNUM");

        // Phân tích chuỗi trả về
        // Ví dụ trả về: +CNUM: "My Number","+84901234567",145,7,4
        for (String line : response.split("\n")) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    return parts[1].replace("\"", "").trim();
                }
            }
        }
        return "Unknown";
    }

    private static String sendAtCommand(SerialPort port, String command) throws IOException, InterruptedException {
        String atCmd = command + "\r";
        port.getOutputStream().write(atCmd.getBytes());
        port.getOutputStream().flush();
        Thread.sleep(300);

        byte[] buffer = new byte[1024];
        int numRead = port.getInputStream().read(buffer);
        if (numRead > 0) {
            String response = new String(buffer, 0, numRead, StandardCharsets.UTF_8);
            System.out.println("Response " + command + ": " + response.trim());
            return response;
        }
        return "";
    }
}


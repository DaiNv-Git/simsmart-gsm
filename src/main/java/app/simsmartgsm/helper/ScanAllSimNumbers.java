package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ScanAllSimNumbers {

    public static void main(String[] args) {
        List<String> simNumbers = getAllSimNumbers();
        System.out.println("📋 Danh sách số điện thoại tìm được:");
        simNumbers.forEach(System.out::println);
    }

    public static List<String> getAllSimNumbers() {
        List<String> numbers = new ArrayList<>();

        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("🔍 Đang quét " + ports.length + " cổng COM...");

        for (SerialPort port : ports) {
            System.out.println("➡ Kiểm tra " + port.getSystemPortName());

            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

            if (port.openPort()) {
                try {
                    // Gửi lệnh AT+CNUM
                    String response = sendAtCommand(port, "AT+CNUM");
                    String phoneNumber = parsePhoneNumber(response);
                    if (!phoneNumber.equals("Unknown")) {
                        numbers.add(port.getSystemPortName() + ": " + phoneNumber);
                    }
                } catch (Exception e) {
                    System.err.println("❌ Lỗi tại " + port.getSystemPortName() + ": " + e.getMessage());
                } finally {
                    port.closePort();
                }
            } else {
                System.err.println("❌ Không mở được " + port.getSystemPortName());
            }
        }

        return numbers;
    }

    private static String sendAtCommand(SerialPort port, String command) throws IOException, InterruptedException {
        String atCmd = command + "\r";
        port.getOutputStream().write(atCmd.getBytes());
        port.getOutputStream().flush();
        Thread.sleep(500);

        byte[] buffer = new byte[1024];
        int numRead = port.getInputStream().read(buffer);
        if (numRead > 0) {
            return new String(buffer, 0, numRead, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String parsePhoneNumber(String response) {
        // Ví dụ response: +CNUM: "My Number","+84901234567",145,7,4
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
}

package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ScanAllSimNumbers {

    public static void main(String[] args) {
        ScanResult result = getAllSimNumbers();

        System.out.println("\n📋 Danh sách số điện thoại tìm được:");
        result.successNumbers.forEach(System.out::println);

        System.out.println("\n✅ Thành công: " + result.successCount);
        System.out.println("❌ Thất bại: " + result.errorCount);
    }

    public static ScanResult getAllSimNumbers() {
        List<String> numbers = new ArrayList<>();
        int success = 0;
        int fail = 0;

        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("🔍 Đang quét " + ports.length + " cổng COM...");

        for (SerialPort port : ports) {
            System.out.println("➡ Kiểm tra " + port.getSystemPortName());

            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

            if (port.openPort()) {
                try {
                    String response = sendAtCommand(port, "AT+CNUM");
                    String phoneNumber = parsePhoneNumber(response);

                    if (!phoneNumber.equals("Unknown")) {
                        numbers.add(port.getSystemPortName() + ": " + phoneNumber);
                        success++;
                    } else {
                        System.err.println("⚠ Không lấy được số từ " + port.getSystemPortName());
                        fail++;
                    }
                } catch (Exception e) {
                    System.err.println("❌ Lỗi tại " + port.getSystemPortName() + ": " + e.getMessage());
                    fail++;
                } finally {
                    port.closePort();
                }
            } else {
                System.err.println("❌ Không mở được " + port.getSystemPortName());
                fail++;
            }
        }

        return new ScanResult(numbers, success, fail);
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

    // ---- DTO kết quả ----
    static class ScanResult {
        List<String> successNumbers;
        int successCount;
        int errorCount;

        public ScanResult(List<String> successNumbers, int successCount, int errorCount) {
            this.successNumbers = successNumbers;
            this.successCount = successCount;
            this.errorCount = errorCount;
        }
    }
}

package app.simsmartgsm.uitils;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Helper để gửi AT command và đọc response ổn định.
 * - flush input trước khi gửi
 * - đọc liên tục trong vòng timeout tổng
 * - hỗ trợ retry
 */
public class AtCommandHelper {

    private final SerialPort port;
    private final InputStream in;
    private final OutputStream out;

    public AtCommandHelper(SerialPort port) {
        this.port = port;
        this.in = port.getInputStream();
        this.out = port.getOutputStream();
    }

    /**
     * Gửi 1 lệnh AT và đọc response.
     *
     * @param command       ví dụ "AT" hoặc "AT+CNUM"
     * @param totalTimeoutMs tổng thời gian chờ để thu thập response (ms)
     * @param retry         số lần retry nếu response rỗng
     */
    public String sendCommand(String command, int totalTimeoutMs, int retry) throws IOException, InterruptedException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }

        // bỏ dữ liệu cũ trong buffer
        flushInput();

        for (int attempt = 0; attempt < Math.max(1, retry); attempt++) {
            // Gửi lệnh
            String at = command + "\r";
            out.write(at.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // nhỏ delay để modem bắt đầu trả
            Thread.sleep(200);

            StringBuilder sb = new StringBuilder();
            long start = System.currentTimeMillis();
            byte[] buffer = new byte[1024];

            // đọc liên tục cho đến khi hết hoặc timeout
            while (System.currentTimeMillis() - start < totalTimeoutMs) {
                int available = in.available();
                if (available > 0) {
                    int len = in.read(buffer, 0, Math.min(buffer.length, available));
                    if (len > 0) {
                        sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    }
                    // nếu thấy OK hoặc ERROR, có thể break sớm (nhiều modem trả "OK")
                    String s = sb.toString();
                    if (s.contains("\r\nOK\r\n") || s.contains("\nOK\n") || s.contains("\r\nERROR\r\n") || s.contains("\nERROR\n")) {
                        break;
                    }
                } else {
                    // không có dữ liệu, chờ 100ms
                    Thread.sleep(100);
                }
            }

            String response = sb.toString().trim();
            if (!response.isEmpty()) {
                return response;
            } else {
                // nếu rỗng và còn retry, đợi chút và retry
                if (attempt < retry - 1) {
                    Thread.sleep(200);
                }
            }
        }

        return ""; // rỗng nếu không có response
    }

    private void flushInput() {
        try {
            while (in.available() > 0) {
                in.read(); // discard
            }
        } catch (IOException ignored) {}
    }
}

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
public class AtCommandHelper implements AutoCloseable {

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
     * @param retry         số lần retry thêm (0 = chỉ 1 lần)
     */
    public String sendCommand(String command, int totalTimeoutMs, int retry) throws IOException, InterruptedException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }

        // bỏ dữ liệu cũ trong buffer
        flushInput();

        int maxAttempts = Math.max(1, retry + 1); // luôn gửi ít nhất 1 lần
        IOException lastIo = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                // Gửi lệnh
                String at = command + "\r";
                out.write(at.getBytes(StandardCharsets.ISO_8859_1)); // an toàn hơn UTF-8
                out.flush();

                // nhỏ delay để modem bắt đầu trả
                Thread.sleep(150);

                StringBuilder sb = new StringBuilder();
                long start = System.currentTimeMillis();
                byte[] buffer = new byte[1024];

                // đọc liên tục cho đến khi hết hoặc timeout
                while (System.currentTimeMillis() - start < totalTimeoutMs) {
                    int available = in.available();
                    if (available > 0) {
                        int len = in.read(buffer, 0, Math.min(buffer.length, available));
                        if (len > 0) {
                            sb.append(new String(buffer, 0, len, StandardCharsets.ISO_8859_1));
                        }
                        String s = sb.toString();
                        if (s.contains("OK") || s.contains("ERROR")) {
                            break; // kết thúc sớm khi có phản hồi chuẩn
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }

                String response = sb.toString().trim();
                if (!response.isEmpty()) {
                    return response;
                }

            } catch (IOException ioe) {
                lastIo = ioe;
            }

            // nếu rỗng và còn retry, đợi chút và thử lại
            if (attempt < maxAttempts - 1) {
                Thread.sleep(200);
            }
        }

        if (lastIo != null) throw lastIo;
        return ""; // rỗng nếu không có response
    }

    /**
     * Gửi dữ liệu thô (dùng cho SMS + Ctrl+Z)
     */
    public void sendRaw(String raw, int totalTimeoutMs) throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void flushInput() {
        try {
            while (in.available() > 0) {
                in.read(new byte[in.available()]);
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }

}

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
 * - hỗ trợ gửi SMS (AT+CMGS với Ctrl+Z)
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

    public String sendCommand(String command, int totalTimeoutMs, int retry) throws IOException, InterruptedException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }

        flushInput();

        int maxAttempts = Math.max(1, retry + 1);
        IOException lastIo = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                String at = command + "\r";
                out.write(at.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                Thread.sleep(150);

                StringBuilder sb = new StringBuilder();
                long start = System.currentTimeMillis();
                byte[] buffer = new byte[1024];

                while (System.currentTimeMillis() - start < totalTimeoutMs) {
                    int available = in.available();
                    if (available > 0) {
                        int len = in.read(buffer, 0, Math.min(buffer.length, available));
                        if (len > 0) {
                            sb.append(new String(buffer, 0, len, StandardCharsets.ISO_8859_1));
                        }
                        String s = sb.toString();
                        if (s.contains("OK") || s.contains("ERROR") || s.contains(">")) {
                            break;
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

            if (attempt < maxAttempts - 1) {
                Thread.sleep(200);
            }
        }

        if (lastIo != null) throw lastIo;
        return "";
    }

    /**
     * Gửi dữ liệu raw (vd: SMS + Ctrl+Z).
     */
    public void sendRaw(String raw) throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * Gửi dữ liệu raw kèm Ctrl+Z (0x1A) để kết thúc SMS.
     */
    public void sendSmsContent(String text) throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0x1A); // Ctrl+Z
        out.flush();
    }

    /**
     * Đọc response trong khoảng timeoutMs.
     */
    public String readResponse(int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        byte[] buffer = new byte[1024];

        while (System.currentTimeMillis() - start < timeoutMs) {
            int available = in.available();
            if (available > 0) {
                int len = in.read(buffer, 0, Math.min(buffer.length, available));
                if (len > 0) {
                    sb.append(new String(buffer, 0, len, StandardCharsets.ISO_8859_1));
                }
                String s = sb.toString();
                if (s.contains("OK") || s.contains("ERROR") || s.contains("+CMGS")) {
                    break;
                }
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }
        }

        return sb.toString().trim();
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
    // Ghi raw bytes rồi flush (dùng sau khi modem trả dấu '>')
    public void writeRaw(byte[] data) throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(data);
        out.flush();
    }

    // Overload kèm offset/length (nếu cần)
    public void writeRaw(byte[] data, int off, int len) throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(data, off, len);
        out.flush();
    }

    // Gửi Ctrl+Z để kết thúc SMS
    public void writeCtrlZ() throws IOException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        out.write(0x1A); // Ctrl+Z
        out.flush();
    }

}

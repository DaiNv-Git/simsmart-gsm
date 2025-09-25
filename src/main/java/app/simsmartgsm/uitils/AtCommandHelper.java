package app.simsmartgsm.uitils;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Helper gửi AT và đọc response ổn định cho modem.
 * - Flush input trước khi gửi
 * - Vòng đọc mềm trong total timeout
 * - Hỗ trợ kiểm tra prompt '>' (CMGS), gửi Ctrl+Z
 * - Có hàm readResponse tách biệt, writeRaw bytes
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

    /** Gửi 1 lệnh AT, chờ đến khi gặp OK/ERROR/'>', hoặc hết timeout. */
    public String sendCommand(String command, int totalTimeoutMs, int retry)
            throws IOException, InterruptedException {
        if (!port.isOpen()) {
            throw new IOException("Port is not open: " + port.getSystemPortName());
        }
        flushInput();
        IOException lastIo = null;
        int maxAttempts = Math.max(1, retry + 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                out.write((command + "\r").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                Thread.sleep(120); // grace cho modem

                StringBuilder sb = new StringBuilder();
                long start = System.currentTimeMillis();
                byte[] buf = new byte[1024];

                while (System.currentTimeMillis() - start < totalTimeoutMs) {
                    int available = in.available();
                    if (available > 0) {
                        int len = in.read(buf, 0, Math.min(buf.length, available));
                        if (len > 0) sb.append(new String(buf, 0, len, StandardCharsets.ISO_8859_1));
                        String s = sb.toString();
                        if (s.contains("OK") || s.contains("ERROR") || s.contains(">")) break;
                    } else {
                        Thread.sleep(40);
                    }
                }
                String resp = sb.toString().trim();
                if (!resp.isEmpty()) return resp;
            } catch (IOException ioe) {
                lastIo = ioe;
            }
            if (attempt < maxAttempts - 1) Thread.sleep(160);
        }
        if (lastIo != null) throw lastIo;
        return "";
    }

    /** Đọc response độc lập (sau khi gửi nội dung SMS + Ctrl-Z). */
    public String readResponse(int timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        byte[] buf = new byte[1024];

        while (System.currentTimeMillis() - start < timeoutMs) {
            int available = in.available();
            if (available > 0) {
                int len = in.read(buf, 0, Math.min(buf.length, available));
                if (len > 0) sb.append(new String(buf, 0, len, StandardCharsets.ISO_8859_1));
                String s = sb.toString();
                if (s.contains("OK") || s.contains("ERROR") || s.contains("+CMGS")) break;
            } else {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        return sb.toString().trim();
    }

    /** Ghi chuỗi raw (không tự thêm CR). */
    public void writeRaw(byte[] data) throws IOException {
        ensureOpen();
        out.write(data);
        out.flush();
    }

    /** Overload ghi raw với offset/length. */
    public void writeRaw(byte[] data, int off, int len) throws IOException {
        ensureOpen();
        out.write(data, off, len);
        out.flush();
    }

    /** Gửi Ctrl+Z (0x1A) để kết thúc nội dung SMS. */
    public void writeCtrlZ() throws IOException {
        ensureOpen();
        out.write(0x1A);
        out.flush();
    }

    /** Gửi nội dung SMS + Ctrl-Z. */
    public void sendSmsContent(String text) throws IOException {
        ensureOpen();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.write(0x1A);
        out.flush();
    }

    private void flushInput() {
        try {
            while (in.available() > 0) in.read(new byte[in.available()]);
        } catch (IOException ignored) {}
    }

    private void ensureOpen() throws IOException {
        if (!port.isOpen()) throw new IOException("Port is not open: " + port.getSystemPortName());
    }

    @Override public void close() {
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }
}

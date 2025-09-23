package app.simsmartgsm.util;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight helper: send AT command and read multi-read until timeout or "OK"/"ERROR".
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
     * Send command and wait for response. Returns full response (may include OK/ERROR).
     * totalTimeoutMs: total waiting time in ms.
     */
    public String sendCommand(String command, int totalTimeoutMs) throws IOException, InterruptedException {
        if (!port.isOpen()) throw new IOException("Port not open: " + port.getSystemPortName());

        flushInput();

        String at = command + "\r";
        out.write(at.getBytes(StandardCharsets.UTF_8));
        out.flush();

        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        byte[] buffer = new byte[1024];

        while (System.currentTimeMillis() - start < totalTimeoutMs) {
            int available = in.available();
            if (available > 0) {
                int len = in.read(buffer, 0, Math.min(buffer.length, available));
                if (len > 0) {
                    sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    String s = sb.toString();
                    // break early if OK or ERROR appears
                    if (s.contains("\r\nOK\r\n") || s.contains("\nOK\n") || s.contains("\r\nERROR\r\n") || s.contains("\nERROR\n")) {
                        break;
                    }
                }
            } else {
                Thread.sleep(100);
            }
        }
        return sb.toString().trim();
    }

    private void flushInput() {
        try {
            while (in.available() > 0) {
                in.read(); // discard
            }
        } catch (IOException ignored) {}
    }
}

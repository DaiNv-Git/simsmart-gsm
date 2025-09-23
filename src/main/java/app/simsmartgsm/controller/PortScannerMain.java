package app.simsmartgsm.controller;



import com.fazecast.jSerialComm.SerialPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-class tool to scan all COM ports and print MSISDN (AT+CNUM), with robust reads and logging.
 *
 * Requires dependency:
 *   <dependency>
 *     <groupId>com.fazecast</groupId>
 *     <artifactId>jSerialComm</artifactId>
 *     <version>2.10.4</version>
 *   </dependency>
 */
public class PortScannerMain {

    // configurable params
    private static final int[] BAUDRATES = new int[] {115200, 9600};
    private static final int AT_RESPONSE_TIMEOUT_MS = 1500;
    private static final int CNUM_TIMEOUT_MS = 3000;
    private static final int CNUM_RETRIES = 3;

    public static void main(String[] args) {
        System.out.println("=== GSM COM Scanner (single class) ===");
        Instant start = Instant.now();

        PortScannerMain scanner = new PortScannerMain();
        List<PortResult> results = scanner.scanAllPorts();

        Instant end = Instant.now();
        long tookMs = Duration.between(start, end).toMillis();

        System.out.println("\n📋 Scan finished in " + tookMs + " ms");
        int success = 0, fail = 0;
        for (PortResult r : results) {
            String out = String.format(" - %-8s => %s", r.portName, (r.phoneNumber != null ? r.phoneNumber : "<no number>"));
            System.out.println(out);
            if (r.phoneNumber != null) success++; else fail++;
        }
        System.out.println("\n✅ Success: " + success);
        System.out.println("❌ Fail: " + fail);
        System.out.println("=== End ===");
    }

    public List<PortResult> scanAllPorts() {
        List<PortResult> results = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("Detected " + ports.length + " serial ports.");

        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            String foundPhone = null;

            System.out.println("\n-----");
            System.out.println("Checking port: " + portName);

            // Try multiple baudrates
            for (int baud : BAUDRATES) {
                port.setBaudRate(baud);
                // Non-blocking mode; we'll read manually
                port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);

                if (!port.openPort()) {
                    System.err.println("Cannot open " + portName + " at baud " + baud);
                    continue;
                }

                try (InputStream in = port.getInputStream(); OutputStream out = port.getOutputStream()) {
                    System.out.println("Opened " + portName + " at baud " + baud);

                    // flush leftover input
                    flushInput(in);

                    // quick AT check
                    String atResp = sendCommandAndCollect(in, out, "AT", AT_RESPONSE_TIMEOUT_MS);
                    System.out.println("AT response (preview): " + safePreview(atResp));
                    if (atResp == null || atResp.isEmpty()) {
                        System.err.println("No AT response from " + portName + " (baud " + baud + ")");
                        continue;
                    }

                    // set charset
                    String cscs = sendCommandAndCollect(in, out, "AT+CSCS=\"GSM\"", 800);
                    System.out.println("CSCS response (preview): " + safePreview(cscs));

                    // try AT+CNUM multiple times
                    for (int attempt = 0; attempt < CNUM_RETRIES && foundPhone == null; attempt++) {
                        System.out.println("Attempt " + (attempt + 1) + " to read AT+CNUM");
                        String cnumResp = sendCommandAndCollect(in, out, "AT+CNUM", CNUM_TIMEOUT_MS);
                        System.out.println("CNUM raw (preview): " + safePreview(cnumResp));
                        foundPhone = parsePhoneNumberFromCnum(cnumResp);
                        if (foundPhone != null) {
                            System.out.println("Found phone: " + foundPhone);
                            break;
                        }
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }

                    if (foundPhone != null) {
                        // success -> break baud loop
                        break;
                    } else {
                        System.out.println("No number on " + portName + " at baud " + baud);
                    }
                } catch (Exception e) {
                    System.err.println("Error using port " + portName + " at baud " + baud + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                } finally {
                    port.closePort();
                    System.out.println("Closed " + portName + " (baud " + baud + ")");
                }
            } // end baudrates

            results.add(new PortResult(portName, foundPhone));
        }

        return results;
    }

    // --------- helpers ----------

    private String sendCommandAndCollect(InputStream in, OutputStream out, String command, int totalTimeoutMs) {
        try {
            flushInput(in);

            String at = command + "\r";
            out.write(at.getBytes(StandardCharsets.UTF_8));
            out.flush();

            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024];
            long start = System.currentTimeMillis();

            while (System.currentTimeMillis() - start < totalTimeoutMs) {
                int avail = in.available();
                if (avail > 0) {
                    int toRead = Math.min(buf.length, avail);
                    int r = in.read(buf, 0, toRead);
                    if (r > 0) {
                        sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
                        String s = sb.toString();
                        // early exit if OK or ERROR seen
                        if (s.contains("\r\nOK\r\n") || s.contains("\nOK\n") || s.contains("\r\nERROR\r\n") || s.contains("\nERROR\n")) {
                            break;
                        }
                    }
                } else {
                    try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void flushInput(InputStream in) {
        try {
            while (in.available() > 0) {
                in.read();
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private String parsePhoneNumberFromCnum(String resp) {
        if (resp == null || resp.isEmpty()) return null;
        String[] lines = resp.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                for (String p : parts) {
                    String token = p.replace("\"", "").trim();
                    if (token.matches("\\+?\\d{7,15}")) return token;
                }
            }
            // fallback: entire line is number
            if (line.matches("\\+?\\d{7,15}")) return line;
        }
        return null;
    }

    private String safePreview(String s) {
        return safePreview(s, 200);
    }
    private String safePreview(String s, int max) {
        if (s == null || s.isEmpty()) return "<empty>";
        String t = s.replace("\r","\\r").replace("\n","\\n");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    // small result holder
    public static class PortResult {
        public final String portName;
        public final String phoneNumber;
        public PortResult(String portName, String phoneNumber) {
            this.portName = portName;
            this.phoneNumber = phoneNumber;
        }
    }
}

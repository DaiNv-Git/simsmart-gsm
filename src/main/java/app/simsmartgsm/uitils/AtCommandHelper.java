package app.simsmartgsm.uitils;

import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class AtCommandHelper implements Closeable {

    private final SerialPort port;
    private final boolean ownsPort;
    private final InputStream in;
    private final OutputStream out;

    // ---------- Factory ----------
    public static AtCommandHelper open(String portName,
                                       int baudRate,
                                       int readTimeoutMs,
                                       int writeTimeoutMs) throws IOException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, writeTimeoutMs);
        if (!port.openPort()) {
            throw new IOException("❌ Cannot open port " + portName);
        }
        return new AtCommandHelper(port, true);
    }

    // ---------- Ctor ----------
    public AtCommandHelper(SerialPort port) {
        this(port, false);
    }

    private AtCommandHelper(SerialPort port, boolean ownsPort) {
        this.port = port;
        this.ownsPort = ownsPort;
        this.in = port.getInputStream();
        this.out = port.getOutputStream();
    }

    // ---------- Core IO ----------
    public String sendCommand(String command, int totalTimeoutMs, int retry)
            throws IOException, InterruptedException {
        ensureOpen();
        flushInput();

        IOException lastIo = null;
        int attempts = Math.max(1, retry + 1);

        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                writeRaw((command + "\r").getBytes(StandardCharsets.ISO_8859_1));
                Thread.sleep(100);
                String resp = readUntilMarkers(totalTimeoutMs, "OK", "ERROR", ">");
                if (resp != null && !resp.isBlank()) {
                    return resp.trim();
                }
            } catch (IOException ioe) {
                lastIo = ioe;
            }
            if (attempt < attempts - 1) Thread.sleep(150);
        }
        if (lastIo != null) throw lastIo;
        return "";
    }

    public String sendAndRead(String command, int timeoutMs) throws IOException, InterruptedException {
        return sendCommand(command, timeoutMs, 0);
    }

    public boolean sendAtOk(String command, int timeoutMs) throws IOException, InterruptedException {
        String r = sendCommand(command, timeoutMs, 0);
        return r.contains("OK") && !r.contains("ERROR");
    }

    public void writeRaw(byte[] data) throws IOException {
        ensureOpen();
        out.write(data);
        out.flush();
    }

    public void writeCtrlZ() throws IOException {
        ensureOpen();
        out.write(0x1A);
        out.flush();
    }

    public void flushInput() {
        try {
            while (in.available() > 0) {
                int n = Math.min(in.available(), 4096);
                if (n <= 0) break;
                in.read(new byte[n]);
            }
        } catch (IOException ignored) {}
    }

    private void ensureOpen() throws IOException {
        if (!port.isOpen()) throw new IOException("Port not open: " + port.getSystemPortName());
    }

    private String readUntilMarkers(int timeoutMs, String... markers) throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[2048];

        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int len = in.read(buf, 0, Math.min(buf.length, available));
                if (len > 0) {
                    sb.append(new String(buf, 0, len, StandardCharsets.ISO_8859_1));
                    String s = sb.toString();
                    for (String mk : markers) {
                        if (s.contains(mk)) return s;
                    }
                }
            } else {
                try { Thread.sleep(40); } catch (InterruptedException ignored) {}
            }
        }
        return sb.toString();
    }

    // ---------- High-level modem helpers ----------
    public boolean echoOff() throws IOException, InterruptedException {
        return sendAtOk("ATE0", 800);
    }

    public boolean ping() throws IOException, InterruptedException {
        return sendAndRead("AT", 800).contains("OK");
    }

    public boolean setTextMode(boolean textMode) throws IOException, InterruptedException {
        return sendAtOk("AT+CMGF=" + (textMode ? "1" : "0"), 1200);
    }

    public boolean setCharset(String cs) throws IOException, InterruptedException {
        return sendAtOk("AT+CSCS=\"" + cs + "\"", 1200);
    }

    public boolean setNewMessageIndicationDefault() throws IOException, InterruptedException {
        return sendAtOk("AT+CNMI=2,1,0,0,0", 1500);
    }

    // ---------- SMS ----------
    public boolean sendTextSms(String toNumber, String content, Duration totalTimeout)
            throws IOException, InterruptedException {
        ensureOpen();
        setTextMode(true);
        setCharset("GSM");

        // Step 1: AT+CMGS
        String cmgsResp = sendCommand("AT+CMGS=\"" + toNumber + "\"",
                (int) Math.max(1500, totalTimeout.toMillis()), 0);

        if (!cmgsResp.contains(">")) {
            String extra = readUntilMarkers(1200, ">");
            if (extra == null || !extra.contains(">")) {
                return false;
            }
        }

        // Step 2: send content + Ctrl+Z
        writeRaw(content.getBytes(StandardCharsets.ISO_8859_1));
        writeCtrlZ();

        // Step 3: final response
        String finalResp = readUntilMarkers((int) Math.max(4000, totalTimeout.toMillis()),
                "OK", "ERROR", "+CMGS");

        boolean ok = finalResp.contains("OK") || finalResp.contains("+CMGS");
        log.warn("⚠Send success message : {}");
        try {
            setNewMessageIndicationDefault();
        } catch (Exception e) {
            log.warn("⚠️ Failed to re-enable CNMI after send: {}", e.getMessage());
        }

        return ok;
    }

    /** Xoá 1 SMS theo index trong bộ nhớ hiện tại (SM/ME). */
    public boolean deleteSms(int index) throws IOException, InterruptedException {
        // Một số modem chấp nhận "AT+CMGD=<idx>", số khác cần "AT+CMGD=<idx>,0".
        // Thử lần 1 (không delflag):
        if (sendAtOk("AT+CMGD=" + index, 2000)) return true;
        // Thử lần 2 (delflag=0: delete message at location <index>):
        return sendAtOk("AT+CMGD=" + index + ",0", 2000);
    }

    public List<SmsRecord> listUnreadSmsText(int timeoutMs) throws IOException, InterruptedException {
        setTextMode(true);
        String out = sendAndRead("AT+CMGL=\"REC UNREAD\"", timeoutMs);
        return parseCmglText(out);
    }

    public List<SmsRecord> listAllSmsText(int timeoutMs) throws IOException, InterruptedException {
        setTextMode(true);
        String out = sendAndRead("AT+CMGL=\"ALL\"", timeoutMs);
        return parseCmglText(out);
    }

    public boolean deleteAllSms() throws IOException, InterruptedException {
        return sendAtOk("AT+CMGD=1,4", 3000);
    }

    // ---------- Parsers ----------
    public static List<SmsRecord> parseCmglText(String out) {
        List<SmsRecord> list = new ArrayList<>();
        if (out == null || out.isBlank()) return list;

        String[] lines = out.split("\\r?\\n");
        SmsRecord cur = null;
        for (String line : lines) {
            if (line.startsWith("+CMGL:")) {
                if (cur != null) list.add(cur);
                cur = new SmsRecord();
                Matcher m = Pattern.compile("\\+CMGL:\\s*(\\d+)\\s*,\"([^\"]*)\"\\s*,\"([^\"]*)\".*?\"([^\"]*)\"")
                        .matcher(line);
                if (m.find()) {
                    cur.index = Integer.parseInt(m.group(1));
                    cur.status = m.group(2);
                    cur.sender = m.group(3);
                    cur.timestamp = m.group(4);
                }
            } else if (!line.isBlank() && cur != null) {
                cur.body = (cur.body == null) ? line : cur.body + "\n" + line;
            }
        }
        if (cur != null) list.add(cur);
        return list;
    }

    // ---------- Lifecycle ----------
    @Override
    public void close() {
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
        if (ownsPort) {
            try { port.closePort(); } catch (Exception ignored) {}
        }
    }
    /** Lấy CCID (ICCID). */
    public String getCcid() throws IOException, InterruptedException {
        String r = sendAndRead("AT+CCID", 1500);
        // ví dụ: +CCID: 8981100025977896009F
        Matcher m = Pattern.compile("\\+?CCID\\s*:\\s*([0-9A-Fa-f]+)").matcher(r);
        return m.find() ? m.group(1) : sanitizeSingleLine(r);
    }
    /**
     * Làm sạch response 1 dòng (loại bỏ OK/ERROR và \r\n).
     */
    private static String sanitizeSingleLine(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\r|\\n|OK|ERROR", "").trim();
        return t.isBlank() ? null : t;
    }

    /** Lấy IMSI (CIMI). */
    public String getImsi() throws IOException, InterruptedException {
        String r = sendAndRead("AT+CIMI", 1500);
        Matcher m = Pattern.compile("(?m)^(\\d{5,20})$").matcher(r);
        return m.find() ? m.group(1) : r.replaceAll("[^0-9]", "");
    }

    /** Lấy số điện thoại (nếu SIM lưu): AT+CNUM. */
    public String getCnum() throws IOException, InterruptedException {
        String r = sendAndRead("AT+CNUM", 1500);
        // ví dụ: +CNUM: "","84901234567",145,7,0,4
        Matcher m = Pattern.compile("\\+?CNUM:.*?\"(\\+?\\d{6,20})\"").matcher(r);
        if (m.find()) return m.group(1);

        // fallback: bắt chuỗi số dài trong dòng CNUM
        m = Pattern.compile("\\+?CNUM:.*?(\\+?\\d{6,20})").matcher(r);
        return m.find() ? m.group(1) : null;
    }

    public String queryOperator() throws IOException, InterruptedException {
        String resp = sendAndRead("AT+COPS?", 2000);
        // Ví dụ: +COPS: 0,0,"NTT DOCOMO NTT DOCOMO",7
        Matcher m = Pattern.compile("\\+COPS:\\s*\\d+,\\d+,\"([^\"]+)\"").matcher(resp);
        if (m.find()) {
            return m.group(1); // Trả về NTT DOCOMO NTT DOCOMO
        }
        return "UNKNOWN";
    }
    // ---------- DTO ----------
    public static class SmsRecord {
        public Integer index;
        public String status;
        public String sender;
        public String timestamp;
        public String body;
        @Override public String toString() {
            return "SmsRecord{" +
                    "index=" + index +
                    ", status='" + status + '\'' +
                    ", sender='" + sender + '\'' +
                    ", timestamp='" + timestamp + '\'' +
                    ", body='" + body + '\'' +
                    '}';
        }
    }
}

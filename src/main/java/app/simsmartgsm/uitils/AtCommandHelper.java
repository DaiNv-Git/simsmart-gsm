package app.simsmartgsm.uitils;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper gửi AT và đọc response ổn định cho modem.
 * - Flush input trước khi gửi
 * - Vòng đọc mềm trong total timeout
 * - Hỗ trợ kiểm tra prompt '>' (CMGS), gửi Ctrl+Z
 * - Có hàm readResponse tách biệt, writeRaw bytes
 * - Thêm tiện ích: AT+CMGF/CSCS/CNMI, CCID/CIMI/CNUM, gửi SMS text, đọc CMGL, xóa CMGD, v.v.
 *
 * Lưu ý: class này KHÔNG thread-safe.
 */
public class AtCommandHelper implements AutoCloseable {

    // ---------- Factory ----------

    /**
     * Mở cổng theo tên và trả về helper đã khởi tạo.
     */
    public static AtCommandHelper open(String portName,
                                       int baudRate,
                                       int readTimeoutMs,
                                       int writeTimeoutMs) throws IOException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        // TIMEOUT_READ_SEMI_BLOCKING: read block tối đa readTimeoutMs cho mỗi lần read
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, writeTimeoutMs);
        if (!port.openPort()) {
            throw new IOException("Không thể mở cổng " + portName);
        }
        return new AtCommandHelper(port, /*ownsPort*/ true);
    }

    // ---------- Fields ----------

    private final SerialPort port;
    private final InputStream in;
    private final OutputStream out;
    private final boolean ownsPort;

    // ---------- Ctor ----------

    /**
     * Dùng khi bạn đã tự mở port từ bên ngoài (helper không đóng port).
     */
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

    /** Gửi 1 lệnh AT, chờ đến khi gặp OK/ERROR/'>', hoặc hết timeout. Có retry. */
    public String sendCommand(String command, int totalTimeoutMs, int retry)
            throws IOException, InterruptedException {
        ensureOpen();
        flushInput();

        IOException lastIo = null;
        int maxAttempts = Math.max(1, retry + 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                writeRaw((command + "\r").getBytes(StandardCharsets.ISO_8859_1));
                // grace cho modem
                Thread.sleep(100);

                String resp = readUntilMarkers(totalTimeoutMs, "OK", "ERROR", ">");
                if (resp != null && !resp.isBlank()) {
                    return resp.trim();
                }
            } catch (IOException ioe) {
                lastIo = ioe;
            }
            if (attempt < maxAttempts - 1) Thread.sleep(150);
        }
        if (lastIo != null) throw lastIo;
        return "";
    }

    /** Alias ngắn gọn. */
    public String sendAndRead(String command, int timeoutMs) throws IOException, InterruptedException {
        return sendCommand(command, timeoutMs, 0);
    }

    /** Gửi và kỳ vọng trả về OK (không chứa ERROR). */
    public boolean sendAtOk(String command, int timeoutMs) throws IOException, InterruptedException {
        String r = sendCommand(command, timeoutMs, 0);
        return r.contains("OK") && !r.contains("ERROR");
    }

    /** Đọc response độc lập (sau khi gửi nội dung SMS + Ctrl-Z). */
    public String readResponse(int timeoutMs) throws IOException {
        String resp = readUntilMarkers(timeoutMs, "OK", "ERROR", "+CMGS");
        return resp == null ? "" : resp.trim();
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

    public void flushInput() {
        try {
            while (in.available() > 0) {
                int n = Math.min(in.available(), 4096);
                if (n <= 0) break;
                byte[] tmp = new byte[n];
                in.read(tmp);
            }
        } catch (IOException ignored) {}
    }

    private void ensureOpen() throws IOException {
        if (!port.isOpen()) throw new IOException("Port is not open: " + port.getSystemPortName());
    }

    /**
     * Đọc đến khi gặp 1 trong các marker hoặc timeout.
     */
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

    /** Tắt echo để log gọn. */
    public boolean echoOff() throws IOException, InterruptedException {
        return sendAtOk("ATE0", 800);
    }

    /** AT ping. */
    public boolean ping() throws IOException, InterruptedException {
        String r = sendAndRead("AT", 800);
        return r.contains("OK");
    }

    /** Đặt text mode cho SMS. */
    public boolean setTextMode(boolean textMode) throws IOException, InterruptedException {
        return sendAtOk("AT+CMGF=" + (textMode ? "1" : "0"), 1200);
    }

    /** Đặt charset (ví dụ "GSM" hoặc "UCS2"). */
    public boolean setCharset(String cs) throws IOException, InterruptedException {
        return sendAtOk("AT+CSCS=\"" + cs + "\"", 1200);
    }

    /**
     * New message indication: AT+CNMI=mode,mt,bm,ds,bfr
     * Gợi ý: CNMI=2,1,0,0,0 => báo tin nhắn đến và lưu bộ nhớ.
     */
    public boolean setNewMessageIndication(int mode, int mt, int bm, int ds, int bfr)
            throws IOException, InterruptedException {
        return sendAtOk("AT+CNMI=" + mode + "," + mt + "," + bm + "," + ds + "," + bfr, 1500);
    }

    public boolean setNewMessageIndicationDefault() throws IOException, InterruptedException {
        return setNewMessageIndication(2, 1, 0, 0, 0);
    }

    /** Đặt bộ nhớ SMS (ví dụ CPMS="ME","ME","ME" hoặc "SM"). */
    public boolean setSmsStorage(String mem1, String mem2, String mem3) throws IOException, InterruptedException {
        String cmd = "AT+CPMS=\"" + mem1 + "\",\"" + mem2 + "\",\"" + mem3 + "\"";
        return sendAtOk(cmd, 2000);
    }

    /** Đặt SMSC (trung tâm tin nhắn) nếu cần. Số ở định dạng +84… */
    public boolean setSmsCenter(String smsc) throws IOException, InterruptedException {
        return sendAtOk("AT+CSCA=\"" + smsc + "\"", 1500);
    }

    /** Lấy CCID (ICCID). */
    public String getCcid() throws IOException, InterruptedException {
        String r = sendAndRead("AT+CCID", 1500);
        // ví dụ: +CCID: 8981100025977896009F
        Matcher m = Pattern.compile("\\+?CCID\\s*:\\s*([0-9A-Fa-f]+)").matcher(r);
        return m.find() ? m.group(1) : sanitizeSingleLine(r);
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

    /** Lấy manufacturer/model để làm deviceName nếu cần. */
    public String getDeviceInfo() throws IOException, InterruptedException {
        String man = sendAndRead("AT+CGMI", 1200).replaceAll("OK|\\r|\\n", "").trim();
        String model = sendAndRead("AT+CGMM", 1200).replaceAll("OK|\\r|\\n", "").trim();
        if (!man.isBlank() && !model.isBlank()) return man + " " + model;
        return (man + " " + model).trim();
    }

    /** Chất lượng sóng. */
    public String getSignalCsq() throws IOException, InterruptedException {
        return sendAndRead("AT+CSQ", 800);
    }

    // ---------- SMS (TEXT mode) ----------

    /**
     * Gửi SMS text đơn giản: tự set CMGF=1, CSCS="GSM".
     * Lưu ý: với nội dung Unicode, cách an toàn là PDU (chưa hỗ trợ ở đây).
     */
    public boolean sendTextSms(String toNumber, String content, Duration totalTimeout) throws IOException, InterruptedException {
        ensureOpen();
        setTextMode(true);
        setCharset("GSM");

        // Bước 1: ra lệnh CMGS
        String cmgsResp = sendCommand("AT+CMGS=\"" + toNumber + "\"", (int) Math.max(1500, totalTimeout.toMillis()), 0);
        if (!cmgsResp.contains(">")) {
            // đôi khi modem trả prompt '>' muộn, thử đọc thêm chút
            String extra = readUntilMarkers(1200, ">");
            if (extra == null || !extra.contains(">")) {
                return false;
            }
        }

        // Bước 2: gửi nội dung + Ctrl+Z
        sendSmsContent(content);

        // Bước 3: đọc kết quả
        String finalResp = readResponse((int) Math.max(4000, totalTimeout.toMillis()));
        return finalResp.contains("OK") || finalResp.contains("+CMGS");
    }

    /**
     * Đọc danh sách SMS UNREAD ở TEXT mode.
     * Trả về list bản ghi đã parse (index, status, sender, timestamp, body).
     */
    public List<SmsRecord> listUnreadSmsText(int timeoutMs) throws IOException, InterruptedException {
        setTextMode(true);
        String out = sendAndRead("AT+CMGL=\"REC UNREAD\"", timeoutMs);
        return parseCmglText(out);
    }

    /** Đọc tất cả SMS (ALL) ở TEXT mode. */
    public List<SmsRecord> listAllSmsText(int timeoutMs) throws IOException, InterruptedException {
        setTextMode(true);
        String out = sendAndRead("AT+CMGL=\"ALL\"", timeoutMs);
        return parseCmglText(out);
    }

    /** Đọc 1 SMS theo index. */
    public SmsRecord readSmsByIndexText(int index, int timeoutMs) throws IOException, InterruptedException {
        setTextMode(true);
        String out = sendAndRead("AT+CMGR=" + index, timeoutMs);
        // CMGR format tương tự: header + line body
        return parseCmgrText(out);
    }

    /** Xóa SMS theo index. */
    public boolean deleteSms(int index) throws IOException, InterruptedException {
        return sendAtOk("AT+CMGD=" + index, 1500);
    }

    /** Xóa toàn bộ SMS (cẩn trọng, tùy modem: 1,4 thường là delete all). */
    public boolean deleteAllSmsDanger() throws IOException, InterruptedException {
        return sendAtOk("AT+CMGD=1,4", 3000);
    }

    // ---------- Parsers ----------

    /**
     * Parse output của AT+CMGL="...".
     * Mẫu:
     * +CMGL: 1,"REC UNREAD","+84901234567",,"23/09/25,10:15:00+28"
     * Hello world
     */
    public static List<SmsRecord> parseCmglText(String out) {
        List<SmsRecord> list = new ArrayList<>();
        if (out == null || out.isBlank()) return list;

        String[] lines = out.split("\\r?\\n");
        SmsRecord cur = null;
        for (String line : lines) {
            if (line.startsWith("+CMGL:")) {
                // đóng bản ghi cũ nếu có
                if (cur != null) {
                    list.add(cur);
                }
                cur = new SmsRecord();
                // tách index, status, sender, timestamp
                // +CMGL: <idx>,"<stat>","<oa>",,"<scts>"
                Matcher m = Pattern.compile("\\+CMGL:\\s*(\\d+)\\s*,\"([^\"]*)\"\\s*,\"([^\"]*)\".*?\"([^\"]*)\"")
                        .matcher(line);
                if (m.find()) {
                    cur.index = Integer.parseInt(m.group(1));
                    cur.status = m.group(2);
                    cur.sender = m.group(3);
                    cur.timestamp = m.group(4);
                } else {
                    // fallback: chỉ lấy index
                    Matcher m2 = Pattern.compile("\\+CMGL:\\s*(\\d+)").matcher(line);
                    if (m2.find()) {
                        cur.index = Integer.parseInt(m2.group(1));
                    }
                }
            } else {
                // body (1 hoặc nhiều dòng)
                if (cur != null) {
                    if (cur.body == null) cur.body = line;
                    else cur.body += "\n" + line;
                }
            }
        }
        if (cur != null) list.add(cur);
        return list;
    }

    /**
     * Parse output của AT+CMGR=<idx>
     * Mẫu:
     * +CMGR: "REC UNREAD","+8490...",,"24/09/25,12:00:00+28"
     * content...
     */
    public static SmsRecord parseCmgrText(String out) {
        if (out == null || out.isBlank()) return null;
        String[] lines = out.split("\\r?\\n");
        SmsRecord rec = null;
        for (String line : lines) {
            if (line.startsWith("+CMGR:")) {
                rec = new SmsRecord();
                Matcher m = Pattern.compile("\\+CMGR:\\s*\"([^\"]*)\"\\s*,\"([^\"]*)\".*?\"([^\"]*)\"").matcher(line);
                if (m.find()) {
                    rec.status = m.group(1);
                    rec.sender = m.group(2);
                    rec.timestamp = m.group(3);
                }
            } else if (line.contains("OK") || line.contains("ERROR")) {
                // stop
            } else {
                if (rec != null) {
                    if (rec.body == null) rec.body = line; else rec.body += "\n" + line;
                }
            }
        }
        return rec;
    }

    private static String sanitizeSingleLine(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\r|\\n|OK|ERROR", "").trim();
        return t.isBlank() ? null : t;
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

    // ---------- DTO ----------
    /**
     * Lấy tên thiết bị (deviceName) từ modem bằng AT+CGMI (manufacturer) + AT+CGMM (model).
     * Ví dụ: "Quectel EC25"
     */
    public String getDeviceName() throws IOException, InterruptedException {
        String man = sendAndRead("AT+CGMI", 1200)
                .replaceAll("OK|\\r|\\n", "").trim();
        String model = sendAndRead("AT+CGMM", 1200)
                .replaceAll("OK|\\r|\\n", "").trim();

        String name = (man + " " + model).trim();
        return name.isBlank() ? port.getSystemPortName() : name;
    }

    public String readAvailable(int timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (in.available() > 0) {
                int len = in.read(buffer);
                if (len > 0) {
                    sb.append(new String(buffer, 0, len, StandardCharsets.US_ASCII));
                }
            } else {
                Thread.sleep(100); // tránh busy loop
            }
        }

        return sb.toString().trim();
    }
    public static class SmsRecord {
        public Integer index;     // CMGL index
        public String status;     // REC READ / REC UNREAD / ...
        public String sender;     // Số người gửi
        public String timestamp;  // Thời gian modem parse
        public String body;       // Nội dung (TEXT mode)
        @Override public String toString() {
            return "SmsRecord{index=" + index + ", status='" + status + "', sender='" + sender + "', timestamp='" + timestamp + "', body='" + body + "'}";
        }

    }
}

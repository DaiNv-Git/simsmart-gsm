package app.simsmartgsm.service;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class GsmService {
    private static final Logger log = LoggerFactory.getLogger(GsmService.class);

    private String comPortName = "COM1";
    private int baudRate = 115200;

    private SerialPort port;
    private Thread listenThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GsmService() {
    }

    public void setComPortName(String comPortName) {
        this.comPortName = comPortName;
    }

    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    @PostConstruct
    public void init() {
        // không auto start nếu bạn muốn start bằng controller -> nhưng ở đây auto start để đơn giản
        start();
    }

    public synchronized void start() {
        if (running.get()) {
            log.info("GSM listener đã chạy rồi");
            return;
        }

        port = SerialPort.getCommPort(comPortName);
        port.setBaudRate(baudRate);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!port.openPort()) {
            log.error("Không thể mở port {}", comPortName);
            return;
        }
        log.info("Mở port {}", comPortName);

        // cấu hình modem cơ bản
        sendAtCommand("AT"); // test
        sendAtCommand("AT+CMGF=1"); // text mode
        // bạn có thể thay CSCS theo nhu cầu charset
        sendAtCommand("AT+CNMI=2,2,0,0,0"); // push khi có SMS

        running.set(true);
        listenThread = new Thread(this::listenLoop, "GSM-Listener-" + comPortName);
        listenThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (listenThread != null) listenThread.interrupt();
        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("Đóng port {}", comPortName);
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private void listenLoop() {
        try (InputStream in = port.getInputStream();
             Scanner scanner = new Scanner(in, StandardCharsets.US_ASCII)) {

            log.info("Bắt đầu lắng nghe SMS trên {}", comPortName);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (!scanner.hasNextLine()) continue;
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                log.debug("RAW LINE <- {}", line);

                // Thông thường modem khi có tin nhắn sẽ gửi +CMT: header rồi dòng content
                if (line.startsWith("+CMT:")) {
                    String header = line;
                    String content = scanner.hasNextLine() ? scanner.nextLine() : "";
                    log.info("SMS header: {}", header);
                    log.info("SMS content: {}", content);

                    // Parse phone từ header (cơ bản)
                    String phone = parsePhoneFromCmtHeader(header);
                    String sender = phone; // tạm

                    // TODO: gọi service xử lý nghiệp vụ ở đây (lưu DB, filter, push WS...)
                    // ví dụ: smsListenerService.onSmsReceived(comPortName, phone, sender, content);

                } else {
                    // Một số modem trả +CMTI: "SM",index -> cần đọc bằng AT+CMGR=index
                    if (line.startsWith("+CMTI:")) {
                        log.info("Notification mới (CMTI): {}", line);
                        // Bạn có thể parse index sau đó gọi readMessageByIndex(index)
                        // Simple handling omitted for brevity
                    } else {
                        // other unsolicited responses
                        log.debug("Unsolicited: {}", line);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Lỗi khi lắng nghe cổng {}", comPortName, e);
        }
    }

    private String parsePhoneFromCmtHeader(String header) {
        try {
            // ví dụ header: +CMT: "+84901234567","","24/09/25,10:00:00+08"
            String[] parts = header.split(",");
            String first = parts[0]; // +CMT: "+84901234567"
            int idx = first.indexOf(":");
            String num = first.substring(idx + 1).replaceAll("\"", "").trim();
            return num;
        } catch (Exception e) {
            log.warn("Không parse được phone từ header: {}", header);
            return "unknown";
        }
    }

    // Gửi 1 AT command (gửi và không chờ full response, dùng cho config)
    private void sendAtCommand(String cmd) {
        try {
            OutputStream out = port.getOutputStream();
            String line = cmd + "\r";
            out.write(line.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            log.info("Gửi AT -> {}", cmd);
            // short sleep để modem kẹt lệnh
            Thread.sleep(200);
        } catch (Exception e) {
            log.error("Gửi AT lỗi: {}", cmd, e);
        }
    }

    /**
     * Gửi SMS ở chế độ text (AT+CMGF=1). Phải gửi CTRL+Z (0x1A) để kết thúc.
     * Trả về true nếu tiến trình gửi được bắt đầu (không đảm bảo deliver).
     */
    public synchronized boolean sendSms(String phoneNumber, String text) {
        if (port == null || !port.isOpen()) {
            log.error("Port chưa mở, không thể gửi SMS");
            return false;
        }

        try {
            OutputStream out = port.getOutputStream();
            // 1) đảm bảo text mode
            out.write(("AT+CMGF=1\r").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(200);

            // 2) chuẩn bị gửi: AT+CMGS="phone"
            String cmd = "AT+CMGS=\"" + phoneNumber + "\"\r";
            out.write(cmd.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(500); // đợi modem phản hồi prompt '>'

            // 3) gửi nội dung text, kết thúc bằng Ctrl+Z
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.write(0x1A); // Ctrl+Z
            out.flush();
            log.info("Bắt đầu gửi SMS tới {}: {}", phoneNumber, text);

            // không block lâu; modem sẽ trả +CMGS: idx ... sau đó
            return true;
        } catch (Exception e) {
            log.error("Gửi SMS lỗi", e);
            return false;
        }
    }
}


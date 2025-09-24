package app.simsmartgsm.helper;

import com.fazecast.jSerialComm.SerialPort;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
public class AtCommandWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AtCommandWorker.class);

    private final SerialPort port;
    private final AtCommandHelper helper;
    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;

    public AtCommandWorker(SerialPort port) throws IOException {
        this.port = port;
        this.port.setBaudRate(115200);
        this.port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!this.port.openPort()) {
            throw new IOException("Cannot open port " + port.getSystemPortName());
        }

        this.helper = new AtCommandHelper(port);
    }

    public void enqueue(Runnable task) {
        taskQueue.add(task);
    }

    @Override
    public void run() {
        while (running) {
            try {
                Runnable task = taskQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception ex) {
                log.error("Worker error on {}: {}", port.getSystemPortName(), ex.getMessage());
            }
        }
        try {
            port.closePort();
        } catch (Exception ignore) {}
    }

    public PortInfo doScan() {
        try {
            // Gửi lệnh AT cần thiết
            String ccid = helper.sendCommand("AT+CCID", 1000, 0);
            ccid = parseCcid(ccid);

            String imsi = helper.sendCommand("AT+CIMI", 1000, 0);
            imsi = parseImsi(imsi);

            String phone = helper.sendCommand("AT+CNUM", 1000, 0);
            phone = parsePhoneNumberFromCnum(phone);

            String provider = detectProvider(imsi);

            boolean ok = (ccid != null) || (imsi != null) || (phone != null);
            String msg = ok ? "OK" : "No data";

            return new PortInfo(port.getSystemPortName(), ok, provider, phone, ccid, msg);
        } catch (Exception e) {
            return new PortInfo(port.getSystemPortName(), false, null, null, null, "Error: " + e.getMessage());
        }
    }

    // --- parsers ---
    private String parsePhoneNumberFromCnum(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) return parts[1].replace("\"", "").trim();
            }
            String t = line.trim();
            if (t.matches("\\+?\\d{7,15}")) return t;
        }
        return null;
    }

    private String parseCcid(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("+CCID")) return l.replace("+CCID:", "").replace(" ", "").trim();
            if (l.matches("\\d{18,22}")) return l;
        }
        return null;
    }

    private String parseImsi(String response) {
        if (response == null) return null;
        for (String line : response.split("\\r?\\n")) {
            String l = line.trim();
            if (l.matches("\\d{14,16}")) return l;
        }
        return null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return null;
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }
}

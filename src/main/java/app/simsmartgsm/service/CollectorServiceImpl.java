package app.simsmartgsm.service;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Service
public class CollectorServiceImpl implements PortScannerService.CollectorService {

    private static final Logger log = LoggerFactory.getLogger(CollectorServiceImpl.class);

    private SerialPort collectorPort;
    private AtCommandHelper helper;
    private final ExecutorService readerExec = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    private String collectorNumber; // số của SIM collector

    private final PortScannerService portScannerService;

    public CollectorServiceImpl(PortScannerService portScannerService) {
        this.portScannerService = portScannerService;
    }

    @PostConstruct
    public void init() {
        try {
     
            for (SerialPort port : SerialPort.getCommPorts()) {
                try {
                    if (port.openPort()) {
                        port.setBaudRate(115200);
                        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
                        this.collectorPort = port;
                        this.helper = new AtCommandHelper(port);
                        log.info("Collector selected port {}", port.getSystemPortName());
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Port {} not usable for collector: {}", port.getSystemPortName(), e.getMessage());
                }
            }

            if (collectorPort == null) {
                log.error("No free COM port found for collector!");
                return;
            }

            // cấu hình modem collector
            helper.sendCommand("AT", 1000, 0);
            helper.sendCommand("AT+CMGF=1", 1000, 0); // text mode
            helper.sendCommand("AT+CNMI=2,1,0,0,0", 1000, 0); // enable URC for incoming SMS

            // thử lấy số của SIM collector (nếu có)
            try {
                String cnum = helper.sendCommand("AT+CNUM", 2000, 1);
                collectorNumber = parsePhoneFromCnum(cnum);
                log.info("Collector SIM number = {}", collectorNumber);
            } catch (Exception e) {
                log.warn("Could not read collector SIM number: {}", e.getMessage());
            }

            running = true;
            readerExec.submit(this::readLoop);

        } catch (Exception e) {
            log.error("Collector init failed", e);
        }
    }

    @Override
    public String getCollectorNumber() {
        return collectorNumber;
    }

    private void readLoop() {
        byte[] buf = new byte[1024];
        StringBuilder acc = new StringBuilder();
        while (running) {
            try {
                int avail = collectorPort.bytesAvailable();
                if (avail > 0) {
                    int len = collectorPort.readBytes(buf, Math.min(buf.length, avail));
                    if (len > 0) {
                        acc.append(new String(buf, 0, len, StandardCharsets.ISO_8859_1));
                        processBuffer(acc);
                    }
                } else {
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                log.warn("Collector read error: {}", e.getMessage());
            }
        }
    }

    private void processBuffer(StringBuilder acc) {
        String raw = acc.toString();
        // kiểm tra SMS đến
        if (raw.contains("+CMT:")) {
            String sender = parseSender(raw);
            String ccid = parseCcidFromBody(raw);

            if (ccid != null && sender != null) {
                log.info("Collector received SMS from {} with ccid {}", sender, ccid);
                portScannerService.resolvePendingFromCollector(ccid, sender);
            }
            acc.setLength(0); // reset buffer sau khi xử lý
        }
    }

    private String parseSender(String raw) {
        // ví dụ: +CMT: "+84901234567","","24/09/25,10:15:00+28"
        int first = raw.indexOf("\"");
        if (first >= 0) {
            int second = raw.indexOf("\"", first + 1);
            if (second > first) return raw.substring(first + 1, second);
        }
        return null;
    }

    private String parseCcidFromBody(String raw) {
        // body có dạng SIMTEST:xxxxxxxx
        int idx = raw.indexOf("SIMTEST:");
        if (idx >= 0) {
            String rest = raw.substring(idx + 8).trim();
            String ccid = rest.split("\\s")[0];
            return ccid;
        }
        return null;
    }

    private String parsePhoneFromCnum(String resp) {
        if (resp == null) return null;
        for (String line : resp.split("\\r?\\n")) {
            if (line.contains("+CNUM")) {
                String[] parts = line.split(",");
                if (parts.length >= 2) return parts[1].replace("\"", "").trim();
            }
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        readerExec.shutdownNow();
        try { if (helper != null) helper.close(); } catch (Exception ignore) {}
        try { if (collectorPort != null && collectorPort.isOpen()) collectorPort.closePort(); } catch (Exception ignore) {}
        log.info("CollectorService stopped");
    }
}

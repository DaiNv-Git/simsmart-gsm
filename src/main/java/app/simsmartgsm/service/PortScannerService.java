package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.CollectorResolvedEvent;
import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int PER_PORT_TOTAL_TIMEOUT_MS = 6000;
    private static final int CMD_TIMEOUT_SHORT_MS = 700;
    private static final int CMD_TIMEOUT_MED_MS = 1200;
    private static final int CMD_RETRY = 1;
    private static final int BAUD_RATE = 115200;

    private static final long COLLECTOR_WAIT_MS = 30000L;
    private static final long MIN_RETRY_INTERVAL_MS = 60000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    private final ConcurrentMap<String, String> lastKnownPhone = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<String>> pendingByCcid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastAttemptAt = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher publisher;

    public PortScannerService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public List<PortInfo> scanAllPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        log.info("Start scanning {} ports", ports.length);

        List<Future<PortInfo>> futures = new ArrayList<>();
        for (SerialPort port : ports) {
            futures.add(executor.submit(() -> scanSinglePortSafely(port)));
        }

        List<PortInfo> results = new ArrayList<>();
        for (Future<PortInfo> f : futures) {
            try {
                results.add(f.get(PER_PORT_TOTAL_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                log.warn("Scan failed: {}", e.getMessage());
                results.add(new PortInfo("unknown", false, null, null, null, "Error"));
            }
        }
        return results;
    }

    private PortInfo scanSinglePortSafely(SerialPort port) {
        try {
            return scanSinglePort(port);
        } catch (Exception e) {
            return new PortInfo(port.getSystemPortName(), false, null, null, null, "Error: " + e.getMessage());
        }
    }

    private PortInfo scanSinglePort(SerialPort port) throws IOException, InterruptedException {
        String portName = port.getSystemPortName();
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, CMD_TIMEOUT_MED_MS, CMD_TIMEOUT_MED_MS);

        if (!port.openPort()) {
            return new PortInfo(portName, false, null, null, null, "Cannot open port");
        }

        try {
            AtCommandHelper helper = new AtCommandHelper(port);

            String ccid = parseCcid(tryCmdWithTimeout(helper, "AT+CCID", CMD_TIMEOUT_MED_MS, CMD_RETRY));
            String imsi = parseImsi(tryCmdWithTimeout(helper, "AT+CIMI", CMD_TIMEOUT_MED_MS, CMD_RETRY));
            String phone = parsePhoneNumberFromCnum(
                    tryCmdWithTimeout(helper, "AT+CNUM", CMD_TIMEOUT_MED_MS, CMD_RETRY)
            );

            if (phone == null && ccid != null) {
                phone = getPhoneViaCollector(ccid, imsi, portName);
            }

            String provider = detectProvider(imsi);
            boolean ok = ccid != null || imsi != null || phone != null;
            String msg = ok ? "OK" : "No data";

            return new PortInfo(portName, ok, provider, phone, ccid, msg);

        } finally {
            try {
                port.closePort();
            } catch (Exception ignore) {}
        }
    }

    private String getPhoneViaCollector(String ccid, String imsi, String portName) {
        if (lastKnownPhone.containsKey(ccid)) return lastKnownPhone.get(ccid);

        long now = System.currentTimeMillis();
        if (now - lastAttemptAt.getOrDefault(ccid, 0L) < MIN_RETRY_INTERVAL_MS) return null;

        lastAttemptAt.put(ccid, now);
        CompletableFuture<String> pending = new CompletableFuture<>();
        pendingByCcid.put(ccid, pending);

        publisher.publishEvent(new app.simsmartgsm.service.CollectorRequestEvent(ccid, imsi, portName));

        try {
            String resolved = pending.get(COLLECTOR_WAIT_MS, TimeUnit.MILLISECONDS);
            if (resolved != null) {
                lastKnownPhone.put(ccid, resolved);
                return resolved;
            }
        } catch (Exception e) {
            log.warn("{}: Collector resolve error {}", portName, e.getMessage());
        } finally {
            pendingByCcid.remove(ccid);
        }
        return null;
    }

    @EventListener
    public void handleCollectorResolved(CollectorResolvedEvent event) {
        CompletableFuture<String> f = pendingByCcid.get(event.getCcid());
        if (f != null && !f.isDone()) {
            f.complete(event.getPhone());
        }
        lastKnownPhone.put(event.getCcid(), event.getPhone());
        log.info("PortScanner resolved ccid={} with phone={}", event.getCcid(), event.getPhone());
    }

    private String tryCmdWithTimeout(AtCommandHelper helper, String cmd, int timeoutMs, int retry)
            throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int i = 0; i <= retry; i++) {
            try {
                String resp = helper.sendCommand(cmd, timeoutMs, 0);
                if (resp != null && !resp.isEmpty()) return resp.trim();
            } catch (IOException ioe) {
                lastIo = ioe;
            }
            Thread.sleep(50);
        }
        if (lastIo != null) throw lastIo;
        return null;
    }

    private String parsePhoneNumberFromCnum(String resp) {
        if (resp == null) return null;
        for (String line : resp.split("\n")) {
            if (line.contains("+CNUM")) {
                String[] p = line.split(",");
                if (p.length >= 2) return p[1].replace("\"", "").trim();
            }
        }
        return null;
    }

    private String parseCcid(String resp) {
        if (resp == null) return null;
        for (String l : resp.split("\n")) {
            if (l.matches("\\d{18,22}")) return l.trim();
        }
        return null;
    }

    private String parseImsi(String resp) {
        if (resp == null) return null;
        for (String l : resp.split("\n")) {
            if (l.matches("\\d{14,16}")) return l.trim();
        }
        return null;
    }

    private String detectProvider(String imsi) {
        if (imsi == null) return "Unknown";
        if (imsi.startsWith("44010")) return "NTT Docomo (JP)";
        if (imsi.startsWith("44011")) return "Rakuten Mobile (JP)";
        if (imsi.startsWith("44020")) return "SoftBank (JP)";
        if (imsi.startsWith("44050")) return "KDDI au (JP)";
        if (imsi.startsWith("45201")) return "Mobifone (VN)";
        if (imsi.startsWith("45202")) return "Vinaphone (VN)";
        if (imsi.startsWith("45204")) return "Viettel (VN)";
        return "Unknown";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}

//package app.simsmartgsm.service;
//
//import app.simsmartgsm.dto.request.CollectorResolvedEvent;
//import app.simsmartgsm.uitils.AtCommandHelper;
//import com.fazecast.jSerialComm.SerialPort;
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.context.ApplicationEventPublisher;
//import org.springframework.context.event.EventListener;
//import org.springframework.stereotype.Service;
//
//import java.nio.charset.StandardCharsets;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//
//@Service
//public class CollectorServiceImpl {
//
//    private static final Logger log = LoggerFactory.getLogger(CollectorServiceImpl.class);
//
//    private SerialPort collectorPort;
//    private AtCommandHelper helper;
//    private final ExecutorService readerExec = Executors.newSingleThreadExecutor();
//    private volatile boolean running = false;
//
//    private String collectorNumber;
//    private final ApplicationEventPublisher publisher;
//
//    public CollectorServiceImpl(ApplicationEventPublisher publisher) {
//        this.publisher = publisher;
//    }
//
//    @PostConstruct
//    public void init() {
//        try {
//            for (SerialPort port : SerialPort.getCommPorts()) {
//                try {
//                    if (port.openPort()) {
//                        port.setBaudRate(115200);
//                        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
//                        this.collectorPort = port;
//                        this.helper = new AtCommandHelper(port);
//                        log.info("Collector selected port {}", port.getSystemPortName());
//                        break;
//                    }
//                } catch (Exception e) {
//                    log.debug("Port {} not usable for collector: {}", port.getSystemPortName(), e.getMessage());
//                }
//            }
//
//            if (collectorPort == null) {
//                log.error("❌ No free COM port found for collector!");
//                return;
//            }
//
//            helper.sendCommand("AT", 1000, 0);
//            helper.sendCommand("AT+CMGF=1", 1000, 0);
//            helper.sendCommand("AT+CNMI=2,1,0,0,0", 1000, 0);
//
//            try {
//                String cnum = helper.sendCommand("AT+CNUM", 2000, 1);
//                collectorNumber = parsePhoneFromCnum(cnum);
//                log.info("Collector SIM number = {}", collectorNumber);
//            } catch (Exception e) {
//                log.warn("Could not read collector SIM number: {}", e.getMessage());
//            }
//
//            running = true;
//            readerExec.submit(this::readLoop);
//
//        } catch (Exception e) {
//            log.error("Collector init failed", e);
//        }
//    }
//
//    public String getCollectorNumber() {
//        return collectorNumber;
//    }
//
//    @EventListener
//    public void handleCollectorRequest(app.simsmartgsm.service.CollectorRequestEvent event) {
//        try {
//            log.info("Collector handling request for ccid={} from port={}", event.getCcid(), event.getPortName());
//            helper.sendCommand("AT+CMGF=1", 1000, 0);
//            helper.sendCommand("AT+CMGS=\"" + collectorNumber + "\"", 2000, 0);
//            helper.sendRaw("SIMTEST:" + event.getCcid() + (char) 26, 5000);
//        } catch (Exception e) {
//            log.error("Failed to send test SMS for ccid={} : {}", event.getCcid(), e.getMessage());
//        }
//    }
//
//    private void readLoop() {
//        byte[] buf = new byte[1024];
//        StringBuilder acc = new StringBuilder();
//        while (running) {
//            try {
//                int avail = collectorPort.bytesAvailable();
//                if (avail > 0) {
//                    int len = collectorPort.readBytes(buf, Math.min(buf.length, avail));
//                    if (len > 0) {
//                        acc.append(new String(buf, 0, len, StandardCharsets.ISO_8859_1));
//                        int idx;
//                        while ((idx = acc.indexOf("\r\n")) >= 0) {
//                            String line = acc.substring(0, idx).trim();
//                            acc.delete(0, idx + 2);
//                            processLine(line);
//                        }
//                    }
//                } else {
//                    Thread.sleep(100);
//                }
//            } catch (Exception e) {
//                log.warn("Collector read error: {}", e.getMessage());
//            }
//        }
//    }
//
//    private void processLine(String line) {
//        if (line.startsWith("SIMTEST:")) {
//            String ccid = line.substring(8).trim();
//            String sender = "unknown"; // có thể parse từ +CMT header
//            publisher.publishEvent(new CollectorResolvedEvent(ccid, sender));
//            log.info("Collector resolved ccid={} with phone={}", ccid, sender);
//        }
//    }
//
//    private String parsePhoneFromCnum(String resp) {
//        if (resp == null) return null;
//        for (String line : resp.split("\\r?\\n")) {
//            if (line.contains("+CNUM")) {
//                String[] parts = line.split(",");
//                if (parts.length >= 2) return parts[1].replace("\"", "").trim();
//            }
//        }
//        return null;
//    }
//
//    @PreDestroy
//    public void shutdown() {
//        running = false;
//        readerExec.shutdownNow();
//        try { if (helper != null) helper.close(); } catch (Exception ignore) {}
//        try { if (collectorPort != null && collectorPort.isOpen()) collectorPort.closePort(); } catch (Exception ignore) {}
//        log.info("CollectorService stopped");
//    }
//}

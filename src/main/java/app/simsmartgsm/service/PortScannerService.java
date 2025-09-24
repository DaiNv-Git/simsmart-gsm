package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.helper.AtCommandWorker;
import com.fazecast.jSerialComm.SerialPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, AtCommandWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, PortInfo> lastSnapshot = new ConcurrentHashMap<>();

    private final ExecutorService workerThreads = Executors.newCachedThreadPool();

    public PortScannerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            try {
                AtCommandWorker worker = new AtCommandWorker(port);
                workers.put(port.getSystemPortName(), worker);
                workerThreads.submit(worker);
                log.info("Started worker for {}", port.getSystemPortName());
            } catch (Exception e) {
                log.warn("Cannot start worker for {}: {}", port.getSystemPortName(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedRate = 20_000)
    public void periodicScan() {
        for (Map.Entry<String, AtCommandWorker> entry : workers.entrySet()) {
            String portName = entry.getKey();
            AtCommandWorker worker = entry.getValue();

            worker.enqueue(() -> {
                PortInfo info = worker.doScan();
                PortInfo old = lastSnapshot.get(portName);
                if (old == null || !old.equals(info)) {
                    lastSnapshot.put(portName, info);
                    messagingTemplate.convertAndSend("/topic/simlist", Collections.singletonList(info));
                    log.info("Pushed changed SIM {} to /topic/simlist", portName);
                }
            });
        }
    }

    public List<PortInfo> scanAllPorts() {
        List<PortInfo> list = new ArrayList<>();
        for (Map.Entry<String, AtCommandWorker> entry : workers.entrySet()) {
            list.add(lastSnapshot.getOrDefault(entry.getKey(),
                    new PortInfo(entry.getKey(), false, null, null, null, "No data yet")));
        }
        return list;
    }

    @PreDestroy
    public void shutdown() {
        workerThreads.shutdownNow();
    }
}

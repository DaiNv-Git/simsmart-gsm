package app.simsmartgsm.service;

import app.simsmartgsm.dto.request.SimRequest.PortInfo;
import app.simsmartgsm.helper.AtCommandWorker;
import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
@Service
public class PortScannerService {

    private static final Logger log = LoggerFactory.getLogger(PortScannerService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, PortInfo> lastSnapshot = new ConcurrentHashMap<>();

    public PortScannerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRate = 200_000)
    public void periodicScan() {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            AtCommandWorker worker = new AtCommandWorker(portName);

            PortInfo info = worker.doScan();
            PortInfo old = lastSnapshot.get(portName);

            if (old == null || !old.equals(info)) {
                lastSnapshot.put(portName, info);
                messagingTemplate.convertAndSend("/topic/simlist", Collections.singletonList(info));
                log.info("Pushed changed SIM {} to /topic/simlist", portName);
            }
        }
    }

    public List<PortInfo> scanAllPorts() {
        List<PortInfo> list = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            list.add(lastSnapshot.getOrDefault(port.getSystemPortName(),
                    new PortInfo(port.getSystemPortName(), false, null, null, null, "No data yet")));
        }
        return list;
    }

    public List<PortInfo> scanAndPush(boolean pushSocket) {
        List<PortInfo> result = new ArrayList<>();
        SerialPort[] ports = SerialPort.getCommPorts();

        for (SerialPort port : ports) {
            String portName = port.getSystemPortName();
            AtCommandWorker worker = new AtCommandWorker(portName);
            PortInfo info = worker.doScan();

            PortInfo old = lastSnapshot.get(portName);
            lastSnapshot.put(portName, info);
            result.add(info);

            if (pushSocket && (old == null || !old.equals(info))) {
                messagingTemplate.convertAndSend("/topic/simlist", Collections.singletonList(info));
                log.info("📡 Pushed SIM {} to /topic/simlist", portName);
            }
        }
        return result;
    }

    public List<PortInfo> getLastSnapshot() {
        return new ArrayList<>(lastSnapshot.values());
    }
}

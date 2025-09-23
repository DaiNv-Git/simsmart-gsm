package app.simsmartgsm.Scheduled;

import app.simsmartgsm.dto.request.SimRequest;
import app.simsmartgsm.service.PortScannerService;
import app.simsmartgsm.service.SimService;
import app.simsmartgsm.uitils.HostUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScheduledSender {

    private final PortScannerService scanner;
    private final SimService simService;

    @Value("${services.sim-smart-gsm.environment.vps-id}")
    private String vpsId;

    public ScheduledSender(PortScannerService scanner, SimService simService) {
        this.scanner = scanner;
        this.simService = simService;
    }

    @Scheduled(fixedRateString = "${SCAN_INTERVAL_MS:900000}")
    public void runPeriodicScan() {
        String deviceName = HostUtils.getDeviceName();
        List<SimRequest.PortInfo> portData = scanner.scanAllPorts();
        SimRequest payload = new SimRequest(deviceName, portData);

        String result = simService.sendSimList(vpsId, payload);

        System.out.println("Sim list sent result: " + result);
    }
}


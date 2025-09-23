package app.simsmartgsm.Scheduled;

import app.simsmartgsm.dto.request.SimRequest;
import app.simsmartgsm.service.PortScannerService;
import app.simsmartgsm.service.SimService;
import app.simsmartgsm.uitils.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ScheduledSender {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSender.class);
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PortScannerService scanner;
    private final SimService simService;

    // inject VPS id from properties
    @Value("${services.sim-smart-gsm.environment.vps-id:unknown-vps}")
    private String vpsId;

    // note: use placeholder directly in @Scheduled below; keep this field for visibility or other uses
    @Value("${services.sim-smart-gsm.environment.scan-interval-ms}")
    private String time;

    public ScheduledSender(PortScannerService scanner, SimService simService) {
        this.scanner = scanner;
        this.simService = simService;
    }

    /**
     * Run every N ms as configured by:
     * services.sim-smart-gsm.environment.scan-interval-ms
     *
     * Default: 60000 ms (1 minute)
     */
    @Scheduled(fixedRateString = "${services.sim-smart-gsm.environment.scan-interval-ms:600000}")
    public void runPeriodicScan() {
        String now = TF.format(Instant.now());
        String deviceName = HostUtils.getDeviceName();

        log.info("[{}] Starting periodic SIM scan. device={}, vpsId={}", now, deviceName, vpsId);

        List<SimRequest.PortInfo> portData;
        try {
            portData = scanner.scanAllPorts();
            int count = portData == null ? 0 : portData.size();
            log.info("[{}] Scan finished. {} port(s) returned.", now, count);
        } catch (Exception e) {
            log.error("[{}] Error while scanning ports: {}", now, e.getMessage(), e);
            return;
        }

        SimRequest payload = new SimRequest(deviceName, portData);

        try {
            String result = simService.sendSimList(vpsId, payload);
            System.out.println("payload"+payload);
            log.info("[{}] sendSimList result: {}", now, result);
        } catch (Exception e) {
            log.error("[{}] Error sending sim list to vpsId='{}': {}", now, vpsId, e.getMessage(), e);
        }
    }
}

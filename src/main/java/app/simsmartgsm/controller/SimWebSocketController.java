package app.simsmartgsm.controller;
import app.simsmartgsm.dto.request.SimRequest;
import app.simsmartgsm.service.PortScannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/scan")
public class SimWebSocketController {

    private final PortScannerService scanner;
    private final SimpMessagingTemplate messagingTemplate;

    public SimWebSocketController(PortScannerService scanner, SimpMessagingTemplate messagingTemplate) {
        this.scanner = scanner;
        this.messagingTemplate = messagingTemplate;
    }


    @PostMapping("/start")
    public ResponseEntity<List<SimRequest.PortInfo>> startScan() {
        List<SimRequest.PortInfo> portData = scanner.scanAllPorts();
        System.out.println("startt scan");
        System.out.println("startt scan size " + portData.size());

        messagingTemplate.convertAndSend("/topic/simlist", portData);

        return ResponseEntity.ok(portData);
    }

}


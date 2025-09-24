package app.simsmartgsm.controller;

import app.simsmartgsm.service.GsmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gsm")
@RequiredArgsConstructor
public class GsmController {
    private final GsmService gsmService;

    @PostMapping("/start")
    public ResponseEntity<String> start(@RequestParam(value = "port", required = false) String port) {
        if (port != null && !port.isBlank()) {
            gsmService.setComPortName(port);
        }
        gsmService.start();
        return ResponseEntity.ok("Started on " + (port != null ? port : "configured port"));
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        gsmService.stop();
        return ResponseEntity.ok("Stopped");
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestParam String phone, @RequestParam String text) {
        boolean ok = gsmService.sendSms(phone, text);
        return ok ? ResponseEntity.ok("Sending started") : ResponseEntity.status(500).body("Failed to start sending");
    }
}


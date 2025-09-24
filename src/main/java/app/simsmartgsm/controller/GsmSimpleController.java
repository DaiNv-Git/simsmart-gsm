package app.simsmartgsm.controller;

import app.simsmartgsm.service.GsmSimpleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gsm")
@RequiredArgsConstructor
public class GsmSimpleController {
    private final GsmSimpleService gsmService;

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestParam String phone, @RequestParam String text) {
        String result = gsmService.sendSms(phone, text);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/read")
    public ResponseEntity<String> readAll() {
        String result = gsmService.readAllSms();
        return ResponseEntity.ok(result);
    }
}

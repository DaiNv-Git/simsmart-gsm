package app.simsmartgsm.controller;
import app.simsmartgsm.dto.request.SmsRequest;
import app.simsmartgsm.service.SmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    @PostMapping("/send")
    public ResponseEntity<String> sendSms(@RequestBody SmsRequest req) {
        return ResponseEntity.ok(smsService.sendSms(req.getPortName(), req.getPhoneNumber(), req.getMessage()));
    }

    @GetMapping("/read/{portName}")
    public ResponseEntity<String> readAll(@PathVariable String portName) {
        return ResponseEntity.ok(smsService.readAllSms(portName));
    }

    @GetMapping("/read-latest/{portName}")
    public ResponseEntity<String> readLatest(@PathVariable String portName) {
        return ResponseEntity.ok(smsService.readLatestSms(portName));
    }
}

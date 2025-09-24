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

    /** API gửi SMS */
    @PostMapping("/send")
    public ResponseEntity<String> sendSms(@RequestBody SmsRequest req) {
        String result = smsService.sendSms(req.getPortName(), req.getPhoneNumber(), req.getMessage());
        return ResponseEntity.ok(result);
    }

    /** API đọc SMS từ port */
    @GetMapping("/read/{portName}")
    public ResponseEntity<String> readSms(@PathVariable String portName) {
        String result = smsService.readSms(portName);
        return ResponseEntity.ok(result);
    }
}

package app.simsmartgsm.controller;

import app.simsmartgsm.service.SmsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sms")
public class SmsController {
    private final SmsService smsService;

    public SmsController(SmsService smsService) {
        this.smsService = smsService;
    }

    @GetMapping("/inbox")
    public String inboxAll() {
        return smsService.readInboxAll();
    }

    @GetMapping("/sent")
    public String sentAll() {
        return smsService.readSentAll();
    }

    @GetMapping("/failed")
    public String failedAll() {
        return smsService.readFailedAll();
    }
}

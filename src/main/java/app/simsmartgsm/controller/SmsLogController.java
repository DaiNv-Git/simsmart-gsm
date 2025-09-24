package app.simsmartgsm.controller;

import app.simsmartgsm.service.SmsInboxSentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sms-log")
public class SmsLogController {

    private final SmsInboxSentService service;

    public SmsLogController(SmsInboxSentService service) {
        this.service = service;
    }

    // API đọc tin nhắn đến
    @GetMapping("/inbox")
    public String getInbox() {
        return service.readInboxAll();
    }

    // API đọc tin nhắn gửi đi
    @GetMapping("/sent")
    public String getSent() {
        return service.readSentAll();
    }
}


package app.simsmartgsm.controller;

import app.simsmartgsm.service.SmsInboxSentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sms-inbox-sent")
public class SmsInboxSentController {

    private final SmsInboxSentService service;

    public SmsInboxSentController(SmsInboxSentService service) {
        this.service = service;
    }

    /** API: đọc inbox (cả tin nhắn đến đã đọc + chưa đọc) */
    @GetMapping("/inbox")
    public String getInboxAll() {
        return service.readInboxAll();
    }

    /** API: đọc tin nhắn đã gửi */
    @GetMapping("/sent")
    public String getSentAll() {
        return service.readSentAll();
    }
}

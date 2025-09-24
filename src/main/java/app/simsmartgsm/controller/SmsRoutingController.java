package app.simsmartgsm.controller;
import app.simsmartgsm.service.SmsRoutingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sms-route")
public class SmsRoutingController {

    private final SmsRoutingService service;

    public SmsRoutingController(SmsRoutingService service) {
        this.service = service;
    }

    /**
     * API: Gửi SMS từ 1 cổng COM bất kỳ sang SIM của cổng đích
     * @param targetPort cổng COM đích (SIM nhận tin nhắn)
     * @param message nội dung tin nhắn
     */
    @PostMapping("/send-to-port")
    public String sendToPort(@RequestParam String targetPort,
                             @RequestParam String message) {
        return service.sendViaRandomPort(targetPort, message);
    }
}

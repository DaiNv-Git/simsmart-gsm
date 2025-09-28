package app.simsmartgsm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SmsService {
    public void send(String com, String number, String message) {
        try (SimpleGsmClient client = new SimpleGsmClient(com)) {
            client.sendSms(number, message);
        } catch (Exception e) {
            log.error("❌ Send failed: {}", e.getMessage(), e);
        }
    }

    public void read(String com) {
        try (SimpleGsmClient client = new SimpleGsmClient(com)) {
            String sms = client.readAllSms();
            log.info("📩 SMS list: \n{}", sms);
        } catch (Exception e) {
            log.error("❌ Read failed: {}", e.getMessage(), e);
        }
    }

    public void cleanup(String com) {
        try (SimpleGsmClient client = new SimpleGsmClient(com)) {
            client.deleteAllSms();
        } catch (Exception e) {
            log.error("❌ Delete failed: {}", e.getMessage(), e);
        }
    }
}

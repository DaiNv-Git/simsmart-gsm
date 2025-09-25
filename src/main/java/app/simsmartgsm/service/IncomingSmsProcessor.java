package app.simsmartgsm.service;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.repository.SimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Date;

@Service
public class IncomingSmsProcessor {
    private static final Logger log = LoggerFactory.getLogger(IncomingSmsProcessor.class);

    private final SimRepository simRepository;

    public IncomingSmsProcessor(SimRepository simRepository) {
        this.simRepository = simRepository;
    }

    /**
     * fromNumber: số thật của SIM gửi đi (SIM cần xác thực)
     * body: nội dung tin — kỳ vọng có token "SIM_VERIF:<token>"
     */
    public void processInboundVerification(String fromNumber, String body) {
        if (fromNumber == null || fromNumber.isBlank() || body == null) {
            log.warn("Inbound verification missing fields: from='{}' body='{}'", fromNumber, body);
            return;
        }
        String token = extractToken(body); // ví dụ parse SIM_VERIF:xxxx
        if (token == null) {
            log.warn("No verification token found in inbound body='{}'", body);
            return;
        }

        // Tìm SIM pending theo content == token
        Sim pending = simRepository.findAll().stream()
                .filter(s -> "PENDING_VERIFICATION".equalsIgnoreCase(s.getStatus()))
                .filter(s -> token.equals(s.getContent()))
                .findFirst()
                .orElse(null);

        if (pending == null) {
            log.warn("No pending SIM matches token='{}'", token);
            return;
        }

        // Gán phoneNumber và kích hoạt
        pending.setPhoneNumber(fromNumber);
        pending.setStatus("active");
        pending.setLastUpdated(new Date().toInstant());
        simRepository.save(pending);

        log.info("Verified SIM ccid={} phone={}", pending.getCcid(), fromNumber);

        // (Tùy chọn) giải phóng bất kỳ SIM receiver nào đang 'busy'…
        // Nếu bạn đánh dấu receiver busy bằng token, có thể clear ở đây.
    }

    private String extractToken(String body) {
        // Giản lược: tìm "SIM_VERIF:" + 8 ký tự
        // Bạn có thể dùng regex linh hoạt hơn.
        int idx = body.indexOf("SIM_VERIF:");
        if (idx < 0) return null;
        String tail = body.substring(idx + "SIM_VERIF:".length()).trim();
        if (tail.isEmpty()) return null;
        // lấy đến khoảng trắng tiếp theo nếu có
        int sp = tail.indexOf(' ');
        return sp > 0 ? tail.substring(0, sp) : tail;
    }
}

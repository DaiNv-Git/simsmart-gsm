package app.simsmartgsm.uitils;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry lưu phiên TWO_WAY theo khóa (simPhone, customerPhone).
 * GsmListenerService sẽ tra về để push chat/campaign.
 */
@Component
public class MarketingSessionRegistry {

    @Data
    @AllArgsConstructor
    public static class TwoWaySession {
        private String simPhone;        // Số SIM đang gửi/nhận
        private String customerPhone;   // Số khách hàng
        private String campaignId;
        private String sessionId;
        private Instant expiresAt;
    }

    private record Key(String simPhone, String customerPhone) {
        static Key of(String sim, String cus) {
            return new Key(nullSafe(sim), nullSafe(cus));
        }
        private static String nullSafe(String s) { return s == null ? "" : s; }
        @Override public int hashCode() { return Objects.hash(simPhone, customerPhone); }
    }

    private final Map<Key, TwoWaySession> sessions = new ConcurrentHashMap<>();

    public void register(String simPhone, String customerPhone, String campaignId, String sessionId, Instant expiresAt) {
        sessions.put(Key.of(simPhone, customerPhone),
                new TwoWaySession(simPhone, customerPhone, campaignId, sessionId, expiresAt));
    }

    public TwoWaySession lookup(String simPhone, String fromNumber) {
        TwoWaySession s = sessions.get(Key.of(simPhone, fromNumber));
        if (s != null && s.getExpiresAt() != null && s.getExpiresAt().isBefore(Instant.now())) {
            // hết hạn → bỏ
            sessions.remove(Key.of(simPhone, fromNumber));
            return null;
        }
        return s;
    }

    public void remove(String simPhone, String customerPhone) {
        sessions.remove(Key.of(simPhone, customerPhone));
    }
}

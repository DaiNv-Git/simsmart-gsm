package app.simsmartgsm.service;

import app.simsmartgsm.uitils.AtCommandHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Quản lý lock theo COM + retry. Mọi thao tác AT nên đi qua đây để tránh tranh chấp cổng.
 */
@Component
@Slf4j
public class PortManager {

    private final Map<String, ReentrantLock> comLocks = new ConcurrentHashMap<>();

    /**
     * Mở cổng (AtCommandHelper.open) trong vùng lock theo COM, thực thi task, tự đóng cổng sau khi xong.
     *
     * @param com        ví dụ "COM7" hoặc "/dev/ttyUSB0"
     * @param task       phần việc với AtCommandHelper
     * @param timeoutMs  thời gian chờ lấy lock
     * @return           kết quả task, null nếu thất bại
     */
    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = comLocks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.warn("⏳ Không lấy được lock cho {} trong {}ms", com, timeoutMs);
                    return null;
                }

                try (AtCommandHelper helper = AtCommandHelper.open(com, 115200, 3000, 3000)) {
                    // Ping cơ bản
                    String atResp = helper.sendAndRead("AT", 2000);
                    if (atResp == null || !atResp.contains("OK")) {
                        log.warn("⚠️ {} không phản hồi AT OK (thử {}/{})", com, attempt, 3);
                        safeSleep(800);
                        continue;
                    }

                    // cấu hình SMS text mode
                    helper.sendAndRead("AT+CMGF=1", 2000);
                    helper.sendAndRead("AT+CPMS=\"SM\",\"SM\",\"SM\"", 2000);
                    helper.sendAndRead("AT+CSCS=\"GSM\"", 2000);

                    return task.apply(helper);
                }

            } catch (Exception e) {
                log.error("❌ Lỗi thao tác với {} (thử {}/{}): {}", com, attempt, 3, e.getMessage());
                safeSleep(800);
            } finally {
                if (acquired) {
                    lock.unlock();
                    acquired = false;
                }
            }
        }

        log.error("❌ {} thất bại sau 3 lần thử", com);
        return null;
    }

    private void safeSleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

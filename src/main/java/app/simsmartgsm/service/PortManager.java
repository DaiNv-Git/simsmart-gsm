package app.simsmartgsm.service;

import app.simsmartgsm.uitils.AtCommandHelper;
import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

@Component
@Slf4j
public class PortManager {
    // Khóa cho từng COM để tránh race condition
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Mở port an toàn với lock và thực thi task.
     * Có retry + delay để hạn chế false error khi port chưa sẵn sàng.
     *
     * @param com       Tên cổng COM
     * @param task      Logic với AtCommandHelper
     * @param timeoutMs Timeout chờ lock
     */
    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;

        for (int attempt = 1; attempt <= 3; attempt++) {
            SerialPort port = null;
            try {
                // 1. Cố gắng lấy lock
                acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.debug("⏳ Không lấy được lock cho {} trong {}ms", com, timeoutMs);
                    return null;
                }

                // 2. Cấu hình Port
                port = SerialPort.getCommPort(com);
                port.setBaudRate(115200);
                port.setComPortTimeouts(
                        SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                        3000,
                        3000
                );

                // 3. Mở Port
                if (!port.openPort()) {
                    log.warn("❌ KHÔNG THỂ MỞ CỔNG {} (thử {}/{})", com, attempt, 3);
                    safeSleep(1000); // nghỉ 1s rồi thử lại
                    continue;
                }

                // Delay nhỏ cho modem sẵn sàng
                safeSleep(500);

                try (AtCommandHelper helper = new AtCommandHelper(port)) {
                    // 4. Flush input
                    helper.flushInput();

                    // 5. Kiểm tra AT cơ bản
                    String atResp = helper.sendAndRead("AT", 2000);
                    if (atResp == null || !atResp.contains("OK")) {
                        log.warn("❌ {} không phản hồi AT OK (thử {}/{})", com, attempt, 3);
                        safeSleep(1000);
                        continue; // retry
                    }

                    // 6. Cấu hình cho SMS (Text Mode)
                    helper.sendAndRead("AT+CMGF=1", 2000);
                    helper.sendAndRead("AT+CPMS=\"SM\",\"SM\",\"SM\"", 2000);
                    helper.sendAndRead("AT+CSCS=\"GSM\"", 2000);
                    helper.sendAndRead("AT+CSMP=17,167,0,0", 2000);

                    // 7. Chạy logic chính
                    return task.apply(helper);
                } finally {
                    if (port.isOpen()) {
                        port.closePort();
                    }
                }

            } catch (Exception e) {
                log.error("❌ Lỗi khi thao tác với {} (thử {}/{}): {}", com, attempt, 3, e.getMessage());
                safeSleep(1000);
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
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}

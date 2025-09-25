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
    // Mỗi COM có 1 lock + 1 SerialPort cache
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, SerialPort> portCache = new ConcurrentHashMap<>();

    /**
     * Thao tác an toàn trên 1 cổng COM
     */
    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;

        try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.debug("⏭️ Bỏ qua {} (lock bận)", com);
                return null;
            }

            SerialPort port = portCache.computeIfAbsent(com, this::openPortSafely);
            if (port == null || !port.isOpen()) {
                log.error("❌ Không mở được port {}", com);
                return null;
            }

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                return task.apply(helper);
            }

        } catch (Exception e) {
            log.error("❌ Lỗi thao tác với {}: {}", com, e.getMessage());
            return null;
        } finally {
            if (acquired) lock.unlock();
        }
    }

    /**
     * Mở port 1 lần và cache lại
     */
    private SerialPort openPortSafely(String com) {
        try {
            SerialPort port = SerialPort.getCommPort(com);
            port.setBaudRate(115200);
            port.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    2000, 2000
            );

            if (!port.openPort()) {
                log.error("❌ Không thể mở cổng {}", com);
                return null;
            }
            log.info("✅ Đã mở port {}", com);
            return port;
        } catch (Exception e) {
            log.error("❌ Lỗi mở port {}: {}", com, e.getMessage());
            return null;
        }
    }

    /**
     * Đóng port khi shutdown app
     */
    public void closeAll() {
        portCache.forEach((com, port) -> {
            if (port != null && port.isOpen()) {
                port.closePort();
                log.info("🔒 Đã đóng port {}", com);
            }
        });
        portCache.clear();
    }
}

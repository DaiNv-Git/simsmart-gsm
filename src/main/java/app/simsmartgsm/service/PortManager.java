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
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;
        SerialPort port = null;

        try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("⏳ Không lấy được lock cho {}", com);
                return null;
            }

            port = SerialPort.getCommPort(com);
            port.setBaudRate(115200);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 2000);

            if (!port.openPort()) {
                throw new RuntimeException("❌ Không thể mở cổng " + com);
            }

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                return task.apply(helper);
            } finally {
                port.closePort();
            }

        } catch (Exception e) {
            log.error("❌ Lỗi thao tác với {}: {}", com, e.getMessage());
            return null;
        } finally {
            if (acquired) lock.unlock();
        }
    }
}


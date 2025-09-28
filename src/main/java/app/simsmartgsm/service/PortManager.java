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
    private static final ConcurrentHashMap<String, SerialPort> ports = new ConcurrentHashMap<>();

    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;

        for (int attempt = 1; attempt <= 3; attempt++) {
            SerialPort port = null;
            try {
                acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.warn("⏳ Không lấy được lock cho {} trong {}ms", com, timeoutMs);
                    return null;
                }

                port = SerialPort.getCommPort(com);
                port.setBaudRate(115200);
                port.setComPortTimeouts(
                        SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                        3000,
                        3000
                );

                if (!port.openPort()) {
                    log.error("❌ KHÔNG THỂ MỞ CỔNG {} (thử {}/{})", com, attempt, 3);
                    safeSleep(1000);
                    continue;
                }

                safeSleep(300);

                try (AtCommandHelper helper = new AtCommandHelper(port)) {
                    // kiểm tra AT cơ bản
                    String atResp = helper.sendAndRead("AT", 2000);
                    if (atResp == null || !atResp.contains("OK")) {
                        log.warn("⚠️ {} không phản hồi AT OK (thử {}/{})", com, attempt, 3);
                        safeSleep(1000);
                        continue;
                    }

                    // cấu hình SMS
                    helper.sendAndRead("AT+CMGF=1", 2000);
                    helper.sendAndRead("AT+CPMS=\"SM\",\"SM\",\"SM\"", 2000);
                    helper.sendAndRead("AT+CSCS=\"GSM\"", 2000);

                    return task.apply(helper);
                } finally {
                    if (port.isOpen()) port.closePort();
                }

            } catch (Exception e) {
                log.error("❌ Lỗi thao tác với {} (thử {}/{}): {}", com, attempt, 3, e.getMessage());
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
    
    public static synchronized SerialPort getPort(String portName) {
        return ports.computeIfAbsent(portName, name -> {
            SerialPort port = SerialPort.getCommPort(name);
            port.setBaudRate(115200);
            port.setNumDataBits(8);
            port.setNumStopBits(SerialPort.ONE_STOP_BIT);
            port.setParity(SerialPort.NO_PARITY);
            if (!port.openPort()) {
                throw new RuntimeException("❌ Cannot open port " + name);
            }
            return port;
        });
    }

    public static synchronized void closePort(String portName) {
        SerialPort port = ports.remove(portName);
        if (port != null && port.isOpen()) {
            port.closePort();
        }
    }
    
    private void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}

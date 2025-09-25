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
    // Đây là nơi khóa cổng duy nhất được quản lý.
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Mở port an toàn với lock và thực thi task.
     * Tự động cấu hình modem về text mode khi gửi SMS.
     * @param com Tên cổng COM.
     * @param task Logic cần thực thi với AtCommandHelper.
     * @param timeoutMs Thời gian chờ để lấy khóa (Lock Timeout).
     */
    public <T> T withPort(String com, Function<AtCommandHelper, T> task, long timeoutMs) {
        ReentrantLock lock = locks.computeIfAbsent(com, k -> new ReentrantLock());
        boolean acquired = false;
        SerialPort port = null;

        try {
            // 1. Cố gắng lấy khóa
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
                    2000,
                    2000
            );

            // 3. Mở Port (Nơi lỗi xảy ra)
            if (!port.openPort()) {
                // Đổi thành WARN để dễ nhìn thấy lỗi hệ thống
                log.warn("❌ KHÔNG THỂ MỞ CỔNG {} (Hệ thống hoặc bị khóa ngoài)", com);
                return null;
            }

            try (AtCommandHelper helper = new AtCommandHelper(port)) {
                // 4. Khởi tạo/Cấu hình Modem (Quan trọng cho cả Scan và SMS)
                helper.flushInput();

                // Gửi AT kiểm tra cơ bản
                String atResp = helper.sendAndRead("AT", 2000);
                if (atResp == null || !atResp.contains("OK")) {
                    log.warn("❌ {} không phản hồi AT OK sau khi mở cổng", com);
                    return null;
                }

                // Cấu hình cho SMS (Text Mode) - áp dụng cho mọi lần mở cổng
                helper.sendAndRead("AT+CMGF=1", 2000);
                helper.sendAndRead("AT+CPMS=\"SM\",\"SM\",\"SM\"", 2000);
                helper.sendAndRead("AT+CSCS=\"GSM\"", 2000);
                helper.sendAndRead("AT+CSMP=17,167,0,0", 2000);

                // 5. Chạy logic chính
                return task.apply(helper);
            } finally {
                // 6. Đóng Port
                if (port.isOpen()) {
                    port.closePort();
                }
            }

        } catch (Exception e) {
            log.error("❌ Lỗi thao tác không mong muốn với {}: {}", com, e.getMessage());
            return null;
        } finally {
            // 7. Nhả khóa
            if (acquired) {
                lock.unlock();
            }
        }
    }
}
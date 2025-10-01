package app.simsmartgsm.uitils;

import app.simsmartgsm.entity.Sim;
import app.simsmartgsm.service.GsmListenerService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class PortWorker implements Runnable {

    private final Sim sim;
    private final long scanIntervalMs;
    private final GsmListenerService listenerService;

    private volatile boolean running = true;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();

    public PortWorker(Sim sim, long scanIntervalMs, GsmListenerService listenerService) {
        this.sim = sim;
        this.scanIntervalMs = scanIntervalMs;
        this.listenerService = listenerService;
    }

    public void stop() {
        running = false;
    }

    /** Gửi SMS */
    public void sendSms(String to, String content) {
        queue.offer(new Task(TaskType.SEND, to, content));
    }

    /** Khi nhận SMS từ modem */
    public void receiveSms(AtCommandHelper.SmsRecord rec) {
        queue.offer(new Task(TaskType.RECEIVE, rec));
    }

    /** Khi có cuộc gọi đến */
    public void receiveCall(String fromNumber, GsmListenerService.RentSession session) {
        listenerService.processIncomingCall(sim, fromNumber, session);
    }

    @Override
    public void run() {
        log.info("🔄 Worker loop started for {}", sim.getComName());
        while (running) {
            try {
                Task task = queue.poll();
                if (task == null) {
                    Thread.sleep(scanIntervalMs);
                    continue;
                }

                if (task.getType() == TaskType.SEND) {
                    log.info("📤 Sending SMS from {} -> {} : {}", sim.getComName(), task.getTo(), task.getContent());
                    // TODO: gọi helper.sendTextSms(to, content, Duration.ofSeconds(30))
                } else if (task.getType() == TaskType.RECEIVE) {
                    listenerService.processSms(sim, task.getSmsRecord());
                }

            } catch (Exception e) {
                log.error("❌ Error in worker loop", e);
            }
        }
        log.info("🛑 Worker stopped for {}", sim.getComName());
    }

    enum TaskType { SEND, RECEIVE }

    @Data
    static class Task {
        private final TaskType type;
        private String to;
        private String content;
        private AtCommandHelper.SmsRecord smsRecord;

        Task(TaskType type, String to, String content) {
            this.type = type;
            this.to = to;
            this.content = content;
        }

        Task(TaskType type, AtCommandHelper.SmsRecord rec) {
            this.type = type;
            this.smsRecord = rec;
        }
    }
}

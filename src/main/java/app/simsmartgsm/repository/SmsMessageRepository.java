package app.simsmartgsm.repository;
import app.simsmartgsm.entity.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SmsMessageRepository extends MongoRepository<SmsMessage, Long> {
    Page<SmsMessage> findByTypeAndDeviceName(String type,String deviceName, Pageable pageable);
    Optional<SmsMessage> findByFromPhoneAndToPhoneAndMessage(String fromPhone, String toPhone, String message);
    Optional<SmsMessage> findByFromPhoneAndToPhoneAndMessageAndType(
            String fromPhone, String toPhone, String message, String type);

}

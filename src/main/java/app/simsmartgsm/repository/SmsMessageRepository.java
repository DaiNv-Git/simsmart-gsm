package app.simsmartgsm.repository;
import app.simsmartgsm.entity.SmsMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SmsMessageRepository extends MongoRepository<SmsMessage, Long> {
    Page<SmsMessage> findByType(String type, Pageable pageable);

}

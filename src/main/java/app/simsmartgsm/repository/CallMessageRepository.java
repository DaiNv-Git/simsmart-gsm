package app.simsmartgsm.repository;

import app.simsmartgsm.entity.CallMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CallMessageRepository extends MongoRepository<CallMessage, String> {
    List<CallMessage> findByOrderId(String orderId);

}

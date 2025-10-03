package app.simsmartgsm.repository;
import app.simsmartgsm.entity.CallRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CallRecordRepository extends MongoRepository<CallRecord, String> {
}

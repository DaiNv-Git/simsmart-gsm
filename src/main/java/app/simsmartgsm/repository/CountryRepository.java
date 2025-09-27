package app.simsmartgsm.repository;

import app.simsmartgsm.entity.Country;
import app.simsmartgsm.entity.Sim;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CountryRepository extends MongoRepository<Country, String> {
    Optional<Country> findByCountryCode(String countryCode);
}

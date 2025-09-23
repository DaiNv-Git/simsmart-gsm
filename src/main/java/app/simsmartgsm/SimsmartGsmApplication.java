package app.simsmartgsm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SimsmartGsmApplication {

	public static void main(String[] args) {
		SpringApplication.run(SimsmartGsmApplication.class, args);
	}

}

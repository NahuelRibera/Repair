package dev.repair.api;

import dev.repair.api.config.DotenvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {

	public static void main(String[] args) {
		// Must run before SpringApplication.run() so OPENAI_API_KEY and
		// friends are visible as system properties by the time Spring
		// resolves application.properties placeholders.
		DotenvLoader.loadIntoSystemProperties();
		SpringApplication.run(ApiApplication.class, args);
	}

}

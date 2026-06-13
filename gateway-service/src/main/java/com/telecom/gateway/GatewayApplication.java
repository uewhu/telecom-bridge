package com.telecom.gateway;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Telecom-Bridge Gateway Service.
 * <p>
 * Provides a non-blocking REST-to-Diameter gateway, translating HTTP charge
 * requests into Diameter CCR messages and returning CCA responses to callers.
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
    info = @Info(
        title       = "Telecom-Bridge Gateway API",
        version     = "1.0",
        description = "REST-to-Diameter (Ro/Gy) Gateway microservice for online charging",
        contact     = @Contact(name = "Telecom Platform Team", email = "platform@telecom.com"),
        license     = @License(name = "Apache 2.0", url = "https://www.apache.org/licenses/LICENSE-2.0")
    )
)
public class GatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    public static void main(String[] args) {
        log.info("Starting Telecom-Bridge Gateway Service...");
        SpringApplication.run(GatewayApplication.class, args);
        log.info("Telecom-Bridge Gateway Service started successfully.");
    }
}

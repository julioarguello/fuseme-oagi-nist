package org.oagi.srt.openapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot entry point for the CC-to-OpenAPI generator.
 *
 * Scans the full org.oagi.srt package tree so that JPA entities, repositories,
 * and ImportedDataProvider from srt-import are all discovered.
 */
@SpringBootApplication
@ComponentScan(basePackages = "org.oagi.srt")
@EntityScan(basePackages = "org.oagi.srt.repository.entity")
@EnableJpaRepositories(basePackages = "org.oagi.srt.repository")
public class OpenApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenApiApplication.class, args);
	}
}

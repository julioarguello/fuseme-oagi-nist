package org.oagi.srt.openapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Spring Boot CLI runner that invokes the CC-to-OpenAPI generator.
 *
 * Activated by the Spring profile "generate-openapi".
 * Usage:
 *   java -jar srt-openapi.jar --spring.profiles.active=generate-openapi \
 *       --openapi.asccp="Purchase Order" \
 *       --openapi.output=./output
 */
@Component
@Profile("generate-openapi")
public class GenerateOpenApiCommand implements CommandLineRunner {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private CcOpenApiGenerator generator;

	@Value("${openapi.asccp:Purchase Order}")
	private String asccpPropertyTerm;

	@Value("${openapi.output:./openapi-output}")
	private String outputPath;

	@Override
	public void run(String... args) throws Exception {
		logger.info("=== CC-to-OpenAPI Generator ===");
		logger.info("ASCCP: {}", asccpPropertyTerm);
		logger.info("Output: {}", outputPath);

		File outputDir = new File(outputPath);
		File result = generator.generate(asccpPropertyTerm, outputDir);

		logger.info("=== Generation complete: {} ===", result.getAbsolutePath());
	}
}

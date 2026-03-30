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
 * Two primary modes:
 *   single (default) - One ASCCP noun with CRUD operations (schema + RESTful paths)
 *     java ... --openapi.asccp="Purchase Order" --openapi.output=./output
 *   super - All root ASCCP nouns without operations (schema catalog for semantic mapping)
 *     java ... --openapi.mode=super --openapi.output=./output
 *
 * Deprecated:
 *   api - Super-schema + operations for ALL 1600+ nouns (use single mode instead)
 *     java ... --openapi.mode=api --openapi.output=./output
 */
@Component
@Profile("generate-openapi")
public class GenerateOpenApiCommand implements CommandLineRunner {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private CcOpenApiGenerator generator;

	@Value("${openapi.asccp:Purchase Order}")
	private String asccpPropertyTerm;

	@Value("${openapi.output:./srt-openapi/target/generated-schemas}")
	private String outputPath;

	@Value("${openapi.mode:single}")
	private String mode;

	@Override
	public void run(String... args) throws Exception {
		logger.info("=== CC-to-OpenAPI Generator ===");
		logger.info("Mode: {}", mode);
		logger.info("Output: {}", outputPath);

		File outputDir = new File(outputPath);
		File result;

		if ("super".equalsIgnoreCase(mode)) {
			logger.info("Generating super-schema (all root nouns)...");
			result = generator.generateSuper(outputDir);
		} else if ("api".equalsIgnoreCase(mode)) {
			logger.info("Generating full API (schema + CRUD operations)...");
			result = generator.generateSuperWithOperations(outputDir);
		} else {
			logger.info("ASCCP: {}", asccpPropertyTerm);
			result = generator.generate(asccpPropertyTerm, outputDir);
		}

		logger.info("=== Generation complete: {} ===", result.getAbsolutePath());
	}
}

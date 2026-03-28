package org.oagi.srt.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.oagi.srt.openapi.CcTreeWalker.SuperTreeResult;
import org.oagi.srt.openapi.CcTreeWalker.TreeResult;
import org.oagi.srt.repository.ReleaseRepository;
import org.oagi.srt.repository.entity.Release;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Orchestrates the CC-to-OpenAPI generation pipeline.
 *
 * Pipeline: ASCCP name -> CcTreeWalker (tree) -> OpenApiSchemaBuilder (OpenAPI doc) -> YAML file.
 */
@Component
@Lazy
public class CcOpenApiGenerator {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private CcTreeWalker ccTreeWalker;

	@Autowired
	private OpenApiSchemaBuilder openApiSchemaBuilder;

	@Autowired
	private ReleaseRepository releaseRepository;

	@Autowired
	private OperationOverlayBuilder operationOverlayBuilder;

	/**
	 * Generate an OpenAPI YAML file for the given ASCCP property term.
	 *
	 * @param asccpPropertyTerm  e.g., "Purchase Order"
	 * @param outputDir          directory to write the YAML file to
	 * @return the generated File
	 */
	public File generate(String asccpPropertyTerm, File outputDir) throws IOException {
		logger.info("Generating OpenAPI for ASCCP: {}", asccpPropertyTerm);

		String releaseNum = resolveReleaseNum();

		// Walk the CC tree
		TreeResult treeResult = ccTreeWalker.walk(asccpPropertyTerm);
		logger.info("Tree walk complete: {} schemas, {} aliases found",
				treeResult.getSchemas().size(), treeResult.getAliasMap().size());
		if (!treeResult.getAliasMap().isEmpty()) {
			for (Map.Entry<String, String> alias : treeResult.getAliasMap().entrySet()) {
				logger.debug("  Alias: {} -> {}", alias.getKey(), alias.getValue());
			}
		}

		// Build the OpenAPI document
		Map<String, Object> openApiDoc = openApiSchemaBuilder.build(
				treeResult, asccpPropertyTerm + " API",
				releaseNum);

		return writeYaml(openApiDoc, outputDir, treeResult.getRootSchemaName() + ".openapi.yaml");
	}

	/**
	 * Generate a super-schema covering ALL root OAGIS nouns in one YAML file.
	 *
	 * @param outputDir  directory to write the YAML file to
	 * @return the generated File
	 */
	public File generateSuper(File outputDir) throws IOException {
		logger.info("=== Generating OAGIS Super Schema ===");

		String releaseNum = resolveReleaseNum();

		SuperTreeResult superResult = ccTreeWalker.walkAll();
		logger.info("Super walk complete: {} roots, {} schemas, {} aliases",
				superResult.getRootSchemaNames().size(),
				superResult.getSchemas().size(),
				superResult.getAliasMap().size());

		Map<String, Object> openApiDoc = openApiSchemaBuilder.buildSuper(
				superResult, "OAGIS Core Components \u2014 Super Schema", releaseNum);

		String fileName = "oagis-" + releaseNum + "-super-schema.openapi.yaml";
		return writeYaml(openApiDoc, outputDir, fileName);
	}

	/**
	 * Generate a super-schema with CRUD operations for all root ASCCP nouns.
	 * Produces a full API spec (schema catalog + RESTful paths + security).
	 *
	 * @param outputDir  directory to write the YAML file to
	 * @return the generated File
	 */
	public File generateSuperWithOperations(File outputDir) throws IOException {
		logger.info("=== Generating OAGIS Super Schema with Operations ===");

		String releaseNum = resolveReleaseNum();

		SuperTreeResult superResult = ccTreeWalker.walkAll();
		logger.info("Super walk complete: {} roots, {} schemas, {} aliases",
				superResult.getRootSchemaNames().size(),
				superResult.getSchemas().size(),
				superResult.getAliasMap().size());

		Map<String, Object> openApiDoc = openApiSchemaBuilder.buildSuper(
				superResult, "OAGIS Core Components \u2014 API", releaseNum);

		// Add CRUD operations for every root ASCCP noun
		operationOverlayBuilder.addOperations(openApiDoc, superResult.getRootSchemaNames());
		logger.info("Added CRUD operations for {} root nouns", superResult.getRootSchemaNames().size());

		String fileName = "oagis-" + releaseNum + "-api.openapi.yaml";
		return writeYaml(openApiDoc, outputDir, fileName);
	}

	private File writeYaml(Map<String, Object> doc, File outputDir, String fileName) throws IOException {
		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}

		File outputFile = new File(outputDir, fileName);

		// MINIMIZE_QUOTES is intentionally disabled: it strips quotes from
		// numeric-looking strings (e.g. "1" → 1), which breaks OpenAPI
		// type:string enums and causes Redocly no-enum-type-mismatch errors.
		// Jackson 2.8 (Spring Boot 1.5) lacks ALWAYS_QUOTE_NUMBERS_AS_STRINGS.
		YAMLFactory yamlFactory = new YAMLFactory()
				.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);

		ObjectMapper yamlMapper = new ObjectMapper(yamlFactory);
		yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
		yamlMapper.writeValue(outputFile, doc);

		logger.info("OpenAPI spec written to: {}", outputFile.getAbsolutePath());
		return outputFile;
	}

	/**
	 * Reads the OAGIS release number from the DB (latest imported release).
	 * Falls back to "unknown" if the release table is empty.
	 */
	private String resolveReleaseNum() {
		Release latest = releaseRepository.findFirstByOrderByReleaseIdDesc();
		if (latest == null) {
			logger.warn("No release found in DB; using 'unknown' as version");
			return "unknown";
		}
		logger.info("OAGIS release: {} (releaseId={})", latest.getReleaseNum(), latest.getReleaseId());
		return latest.getReleaseNum();
	}
}

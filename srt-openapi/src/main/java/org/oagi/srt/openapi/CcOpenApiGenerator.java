package org.oagi.srt.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.oagi.srt.openapi.CcTreeWalker.TreeResult;
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

	/**
	 * Generate an OpenAPI YAML file for the given ASCCP property term.
	 *
	 * @param asccpPropertyTerm  e.g., "Purchase Order"
	 * @param outputDir          directory to write the YAML file to
	 * @return the generated File
	 */
	public File generate(String asccpPropertyTerm, File outputDir) throws IOException {
		logger.info("Generating OpenAPI for ASCCP: {}", asccpPropertyTerm);

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
				treeResult, asccpPropertyTerm + " API", treeResult.getRootSchemaName());

		// Write to YAML
		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}

		String fileName = treeResult.getRootSchemaName() + ".openapi.yaml";
		File outputFile = new File(outputDir, fileName);

		YAMLFactory yamlFactory = new YAMLFactory()
				.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
				.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);

		ObjectMapper yamlMapper = new ObjectMapper(yamlFactory);
		yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
		yamlMapper.writeValue(outputFile, openApiDoc);

		logger.info("OpenAPI spec written to: {}", outputFile.getAbsolutePath());
		return outputFile;
	}
}

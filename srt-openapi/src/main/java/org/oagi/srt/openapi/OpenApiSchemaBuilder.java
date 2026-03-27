package org.oagi.srt.openapi;

import org.oagi.srt.openapi.CcTreeWalker.CcNode;
import org.oagi.srt.openapi.CcTreeWalker.SuperTreeResult;
import org.oagi.srt.openapi.CcTreeWalker.TreeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts the CcTreeWalker output into an OpenAPI 3.0.3 schema structure
 * represented as nested Maps (ready for YAML/JSON serialization).
 *
 * Uses allOf composition for ACC inheritance (based_acc_id) and correctly
 * wraps $ref with description using allOf (OAS 3.0.3 compliance).
 */
@Component
@Lazy
public class OpenApiSchemaBuilder {

	@Autowired
	private TypeMapper typeMapper;

	/**
	 * Build a complete OpenAPI 3.0.3 document from a tree walk result.
	 */
	public Map<String, Object> build(TreeResult treeResult, String title, String rootSchemaName) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.0.3");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", "1.0.0");
		info.put("description", "OpenAPI schemas generated from OAGIS Core Components");
		doc.put("info", info);

		// Servers placeholder
		List<Map<String, Object>> servers = new ArrayList<>();
		Map<String, Object> server = new LinkedHashMap<>();
		server.put("url", "https://api.example.com/v1");
		server.put("description", "Placeholder server");
		servers.add(server);
		doc.put("servers", servers);

		// Generate paths referencing the root schema so renderers (Redocly) display it
		doc.put("paths", buildPaths(Collections.singletonList(rootSchemaName)));

		doc.put("components", buildComponents(treeResult.getSchemas(),
				treeResult.getBaseSchemaMap(), treeResult.getAliasMap()));

		return doc;
	}

	/**
	 * Build a super-schema OpenAPI 3.0.3 document from all root nouns.
	 */
	public Map<String, Object> buildSuper(SuperTreeResult superResult, String title) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.0.3");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", "1.0.0");
		info.put("description",
				"Canonical super-schema containing all OAGIS Core Components. " +
				"Use OpenAPI Overlays to create domain-specific API subsets.");
		doc.put("info", info);

		List<Map<String, Object>> servers = new ArrayList<>();
		Map<String, Object> server = new LinkedHashMap<>();
		server.put("url", "https://api.example.com/v1");
		server.put("description", "Placeholder server");
		servers.add(server);
		doc.put("servers", servers);

		// Tags: one per root noun for overlay discoverability
		List<Map<String, Object>> tags = new ArrayList<>();
		for (String rootName : superResult.getRootSchemaNames()) {
			Map<String, Object> tag = new LinkedHashMap<>();
			tag.put("name", rootName);
			tag.put("description", rootName + " business document operations");
			tags.add(tag);
		}
		doc.put("tags", tags);

		// Build paths for all roots
		doc.put("paths", buildPaths(superResult.getRootSchemaNames()));

		doc.put("components", buildComponents(superResult.getSchemas(),
				superResult.getBaseSchemaMap(), superResult.getAliasMap()));

		return doc;
	}

	/**
	 * Build the components section from schemas, base map, and aliases.
	 */
	private Map<String, Object> buildComponents(
			Map<String, List<CcNode>> schemaNodes,
			Map<String, String> baseMap,
			Map<String, String> aliasMapData) {
		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();

		for (Map.Entry<String, List<CcNode>> entry : schemaNodes.entrySet()) {
			String schemaName = entry.getKey();
			List<CcNode> nodes = entry.getValue();
			String baseName = baseMap.get(schemaName);
			schemas.put(schemaName, buildSchema(nodes, baseName));
		}

		// Generate alias schemas for ACCs referenced under multiple names
		for (Map.Entry<String, String> alias : aliasMapData.entrySet()) {
			Map<String, Object> aliasSchema = new LinkedHashMap<>();
			List<Object> allOfList = new ArrayList<>();
			Map<String, Object> ref = new LinkedHashMap<>();
			ref.put("$ref", "#/components/schemas/" + alias.getValue());
			allOfList.add(ref);
			aliasSchema.put("allOf", allOfList);
			schemas.put(alias.getKey(), aliasSchema);
		}

		components.put("schemas", schemas);
		return components;
	}

	/**
	 * Build CRUD-style paths for one or more root schemas.
	 */
	private Map<String, Object> buildPaths(List<String> rootSchemaNames) {
		Map<String, Object> paths = new LinkedHashMap<>();

		for (String rootSchemaName : rootSchemaNames) {
			String ref = "#/components/schemas/" + rootSchemaName;

			// Convert PascalCase to kebab-case for the path
			String pathSegment = rootSchemaName
					.replaceAll("([a-z])([A-Z])", "$1-$2")
					.toLowerCase();

			// Collection endpoint
			Map<String, Object> collectionOps = new LinkedHashMap<>();
			collectionOps.put("get", buildOperation(
					"List " + rootSchemaName + " resources",
					"200", "array", ref, rootSchemaName));
			collectionOps.put("post", buildOperation(
					"Create a " + rootSchemaName,
					"201", null, ref, rootSchemaName));
			paths.put("/" + pathSegment, collectionOps);

			// Item endpoint
			Map<String, Object> itemOps = new LinkedHashMap<>();
			itemOps.put("get", buildItemOperation(
					"Get a " + rootSchemaName + " by ID", ref, rootSchemaName));
			paths.put("/" + pathSegment + "/{id}", itemOps);
		}

		return paths;
	}

	private Map<String, Object> buildOperation(
			String summary, String statusCode, String wrapType, String ref, String tag) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("summary", summary);
		op.put("tags", Collections.singletonList(tag));

		// Request body for POST
		if ("201".equals(statusCode)) {
			Map<String, Object> reqBody = new LinkedHashMap<>();
			reqBody.put("required", true);
			Map<String, Object> content = new LinkedHashMap<>();
			Map<String, Object> json = new LinkedHashMap<>();
			Map<String, Object> schemaRef = new LinkedHashMap<>();
			schemaRef.put("$ref", ref);
			json.put("schema", schemaRef);
			content.put("application/json", json);
			reqBody.put("content", content);
			op.put("requestBody", reqBody);
		}

		Map<String, Object> responses = new LinkedHashMap<>();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("description", "Successful response");
		Map<String, Object> content = new LinkedHashMap<>();
		Map<String, Object> json = new LinkedHashMap<>();
		Map<String, Object> schema = new LinkedHashMap<>();

		if ("array".equals(wrapType)) {
			schema.put("type", "array");
			Map<String, Object> items = new LinkedHashMap<>();
			items.put("$ref", ref);
			schema.put("items", items);
		} else {
			schema.put("$ref", ref);
		}

		json.put("schema", schema);
		content.put("application/json", json);
		resp.put("content", content);
		responses.put(statusCode, resp);
		op.put("responses", responses);
		return op;
	}

	private Map<String, Object> buildItemOperation(String summary, String ref, String tag) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("summary", summary);
		op.put("tags", Collections.singletonList(tag));

		List<Map<String, Object>> params = new ArrayList<>();
		Map<String, Object> idParam = new LinkedHashMap<>();
		idParam.put("name", "id");
		idParam.put("in", "path");
		idParam.put("required", true);
		Map<String, Object> idSchema = new LinkedHashMap<>();
		idSchema.put("type", "string");
		idParam.put("schema", idSchema);
		params.add(idParam);
		op.put("parameters", params);

		Map<String, Object> responses = new LinkedHashMap<>();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("description", "Successful response");
		Map<String, Object> content = new LinkedHashMap<>();
		Map<String, Object> json = new LinkedHashMap<>();
		Map<String, Object> schemaRef = new LinkedHashMap<>();
		schemaRef.put("$ref", ref);
		json.put("schema", schemaRef);
		content.put("application/json", json);
		resp.put("content", content);
		responses.put("200", resp);
		op.put("responses", responses);
		return op;
	}

	/**
	 * Build a single schema. If baseName is non-null, uses allOf composition
	 * to extend the base schema.
	 */
	private Map<String, Object> buildSchema(List<CcNode> nodes, String baseName) {
		Map<String, Object> ownProps = buildOwnProperties(nodes);

		if (baseName == null) {
			// No inheritance: standard object schema
			return ownProps;
		}

		// allOf composition: [$ref to base, own properties]
		Map<String, Object> schema = new LinkedHashMap<>();
		List<Object> allOfList = new ArrayList<>();

		Map<String, Object> baseRef = new LinkedHashMap<>();
		baseRef.put("$ref", "#/components/schemas/" + baseName);
		allOfList.add(baseRef);

		// Only add own properties block if it has properties
		if (ownProps.containsKey("properties")) {
			allOfList.add(ownProps);
		}

		schema.put("allOf", allOfList);
		return schema;
	}

	private Map<String, Object> buildOwnProperties(List<CcNode> nodes) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");

		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();

		for (CcNode node : nodes) {
			Map<String, Object> prop = buildProperty(node);
			properties.put(node.getPropertyName(), prop);

			if (node.getCardinalityMin() >= 1) {
				required.add(node.getPropertyName());
			}
		}

		if (!properties.isEmpty()) {
			schema.put("properties", properties);
		}
		if (!required.isEmpty()) {
			schema.put("required", required);
		}

		return schema;
	}

	private Map<String, Object> buildProperty(CcNode node) {
		switch (node.getKind()) {
			case BCC_ELEMENT:
			case BCC_ATTRIBUTE:
				return buildPrimitiveProperty(node);

			case ASCC:
				return buildAssociationProperty(node);

			default:
				Map<String, Object> fallback = new LinkedHashMap<>();
				fallback.put("type", "object");
				return fallback;
		}
	}

	private Map<String, Object> buildPrimitiveProperty(CcNode node) {
		Map<String, Object> prop = new LinkedHashMap<>();
		boolean isArray = node.getCardinalityMax() == -1 || node.getCardinalityMax() > 1;

		if (node.getDescription() != null && !node.getDescription().isEmpty()) {
			prop.put("description", node.getDescription());
		}

		if (isArray) {
			prop.put("type", "array");
			Map<String, Object> items = resolveType(node.getBdtId());
			prop.put("items", items);
			if (node.getCardinalityMin() > 0) {
				prop.put("minItems", node.getCardinalityMin());
			}
		} else {
			String[] typeFormat = typeMapper.resolve(node.getBdtId());
			prop.put("type", typeFormat[0]);
			if (typeFormat[1] != null) {
				prop.put("format", typeFormat[1]);
			}
		}

		if (node.isNillable()) {
			prop.put("nullable", true);
		}
		if (node.getDefaultValue() != null && !node.getDefaultValue().isEmpty()) {
			prop.put("default", node.getDefaultValue());
		}

		return prop;
	}

	/**
	 * Build an association (ASCC) property. In OAS 3.0.3, $ref cannot coexist
	 * with sibling keywords like description. When a description exists for a
	 * non-array $ref, we wrap it using allOf.
	 */
	private Map<String, Object> buildAssociationProperty(CcNode node) {
		Map<String, Object> prop = new LinkedHashMap<>();
		boolean isArray = node.getCardinalityMax() == -1 || node.getCardinalityMax() > 1;
		String ref = "#/components/schemas/" + node.getRefSchemaName();
		boolean hasDesc = node.getDescription() != null && !node.getDescription().isEmpty();

		if (isArray) {
			if (hasDesc) {
				prop.put("description", node.getDescription());
			}
			prop.put("type", "array");
			Map<String, Object> refItem = new LinkedHashMap<>();
			refItem.put("$ref", ref);
			prop.put("items", refItem);
			if (node.getCardinalityMin() > 0) {
				prop.put("minItems", node.getCardinalityMin());
			}
		} else if (hasDesc) {
			// OAS 3.0.3: $ref replaces the entire object; use allOf wrapper
			prop.put("description", node.getDescription());
			List<Object> allOfList = new ArrayList<>();
			Map<String, Object> refObj = new LinkedHashMap<>();
			refObj.put("$ref", ref);
			allOfList.add(refObj);
			prop.put("allOf", allOfList);
		} else {
			prop.put("$ref", ref);
		}

		return prop;
	}

	private Map<String, Object> resolveType(long bdtId) {
		Map<String, Object> type = new LinkedHashMap<>();
		String[] typeFormat = typeMapper.resolve(bdtId);
		type.put("type", typeFormat[0]);
		if (typeFormat[1] != null) {
			type.put("format", typeFormat[1]);
		}
		return type;
	}
}

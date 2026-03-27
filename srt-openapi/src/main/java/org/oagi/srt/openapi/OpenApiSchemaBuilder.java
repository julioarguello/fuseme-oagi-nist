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

		// Global security — reference the scheme declared in components
		doc.put("security", Collections.singletonList(
				Collections.singletonMap("bearerAuth", Collections.emptyList())));

		// Generate paths referencing the root schema so renderers (Redocly) display it
		doc.put("paths", buildPaths(Collections.singletonList(rootSchemaName)));

		Map<String, Object> components = buildComponents(treeResult.getSchemas(),
				treeResult.getBaseSchemaMap(), treeResult.getAliasMap());
		addSecuritySchemes(components);
		doc.put("components", components);

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

		// Global security — reference the scheme declared in components
		doc.put("security", Collections.singletonList(
				Collections.singletonMap("bearerAuth", Collections.emptyList())));

		// Build paths for all roots
		doc.put("paths", buildPaths(superResult.getRootSchemaNames()));

		Map<String, Object> components = buildComponents(superResult.getSchemas(),
				superResult.getBaseSchemaMap(), superResult.getAliasMap());
		addSecuritySchemes(components);
		doc.put("components", components);

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
	 * Add OAuth2/Bearer security scheme to the components section.
	 * Prevents Redocly {@code security-defined} errors by declaring the
	 * scheme referenced by the global {@code security} block.
	 */
	private void addSecuritySchemes(Map<String, Object> components) {
		Map<String, Object> securitySchemes = new LinkedHashMap<>();
		Map<String, Object> bearer = new LinkedHashMap<>();
		bearer.put("type", "http");
		bearer.put("scheme", "bearer");
		bearer.put("bearerFormat", "JWT");
		bearer.put("description", "JWT token for API authentication");
		securitySchemes.put("bearerAuth", bearer);
		components.put("securitySchemes", securitySchemes);
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
			collectionOps.put("get", buildListOperation(rootSchemaName, ref));
			collectionOps.put("post", buildCreateOperation(rootSchemaName, ref));
			paths.put("/" + pathSegment, collectionOps);

			// Item endpoint
			Map<String, Object> itemOps = new LinkedHashMap<>();
			itemOps.put("get", buildGetOperation(rootSchemaName, ref));
			paths.put("/" + pathSegment + "/{id}", itemOps);
		}

		return paths;
	}

	/**
	 * Build a GET (list) operation with {@code operationId} and a 200 array response.
	 */
	private Map<String, Object> buildListOperation(String schemaName, String ref) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("operationId", "list" + schemaName);
		op.put("summary", "List " + schemaName + " resources");
		op.put("tags", Collections.singletonList(schemaName));

		Map<String, Object> responses = new LinkedHashMap<>();

		// 200 — array of resources
		Map<String, Object> okResp = new LinkedHashMap<>();
		okResp.put("description", "Successful response");
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "array");
		Map<String, Object> items = new LinkedHashMap<>();
		items.put("$ref", ref);
		schema.put("items", items);
		okResp.put("content", jsonContent(schema));
		responses.put("200", okResp);

		// 400 — bad request
		responses.put("400", errorResponse("Bad request"));

		op.put("responses", responses);
		return op;
	}

	/**
	 * Build a POST (create) operation with {@code operationId}, request body,
	 * and 201/400 responses.
	 */
	private Map<String, Object> buildCreateOperation(String schemaName, String ref) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("operationId", "create" + schemaName);
		op.put("summary", "Create a " + schemaName);
		op.put("tags", Collections.singletonList(schemaName));

		// Request body
		Map<String, Object> reqBody = new LinkedHashMap<>();
		reqBody.put("required", true);
		Map<String, Object> schemaRef = new LinkedHashMap<>();
		schemaRef.put("$ref", ref);
		reqBody.put("content", jsonContent(schemaRef));
		op.put("requestBody", reqBody);

		Map<String, Object> responses = new LinkedHashMap<>();

		// 201 — created
		Map<String, Object> createdResp = new LinkedHashMap<>();
		createdResp.put("description", "Resource created");
		Map<String, Object> respRef = new LinkedHashMap<>();
		respRef.put("$ref", ref);
		createdResp.put("content", jsonContent(respRef));
		responses.put("201", createdResp);

		// 400 — bad request
		responses.put("400", errorResponse("Bad request"));

		op.put("responses", responses);
		return op;
	}

	/**
	 * Build a GET-by-ID (item) operation with {@code operationId}, path parameter,
	 * and 200/404 responses.
	 */
	private Map<String, Object> buildGetOperation(String schemaName, String ref) {
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("operationId", "get" + schemaName);
		op.put("summary", "Get a " + schemaName + " by ID");
		op.put("tags", Collections.singletonList(schemaName));

		// Path parameter
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

		// 200 — found
		Map<String, Object> okResp = new LinkedHashMap<>();
		okResp.put("description", "Successful response");
		Map<String, Object> respRef = new LinkedHashMap<>();
		respRef.put("$ref", ref);
		okResp.put("content", jsonContent(respRef));
		responses.put("200", okResp);

		// 404 — not found
		responses.put("404", errorResponse("Resource not found"));

		op.put("responses", responses);
		return op;
	}

	/** Wrap a schema map as {@code application/json} content. */
	private Map<String, Object> jsonContent(Map<String, Object> schema) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("schema", schema);
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("application/json", json);
		return content;
	}

	/** Build a minimal error response with only a description. */
	private Map<String, Object> errorResponse(String description) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("description", description);
		return resp;
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

	/**
	 * Build a primitive (BCC) property schema, injecting {@code enum} arrays when the
	 * underlying BDT is constrained by a {@link org.oagi.srt.repository.entity.CodeList}
	 * or {@link org.oagi.srt.repository.entity.AgencyIdList}.
	 *
	 * @see TypeMapper#resolve(long)
	 * @see TypeResolution#hasEnum()
	 */
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
			TypeResolution resolution = typeMapper.resolve(node.getBdtId());
			prop.put("type", resolution.getType());
			if (resolution.getFormat() != null) {
				prop.put("format", resolution.getFormat());
			}
			if (resolution.hasEnum()) {
				prop.put("enum", resolution.getEnumValues());
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

	/**
	 * Resolves a BDT to an OpenAPI type map for use in array {@code items}.
	 * Includes {@code enum} when the BDT is constrained by a code list.
	 *
	 * @see TypeMapper#resolve(long)
	 */
	private Map<String, Object> resolveType(long bdtId) {
		Map<String, Object> type = new LinkedHashMap<>();
		TypeResolution resolution = typeMapper.resolve(bdtId);
		type.put("type", resolution.getType());
		if (resolution.getFormat() != null) {
			type.put("format", resolution.getFormat());
		}
		if (resolution.hasEnum()) {
			type.put("enum", resolution.getEnumValues());
		}
		return type;
	}
}

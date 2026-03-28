package org.oagi.srt.openapi;

import org.oagi.srt.openapi.CcTreeWalker.CcNode;
import org.oagi.srt.openapi.CcTreeWalker.SuperTreeResult;
import org.oagi.srt.openapi.CcTreeWalker.TreeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts the CcTreeWalker output into an OpenAPI 3.1.0 schema structure
 * represented as nested Maps (ready for YAML/JSON serialization).
 *
 * Uses allOf composition for ACC inheritance (based_acc_id). In OAS 3.1.0,
 * $ref can coexist with sibling keywords like description, so no allOf
 * wrapper is needed for association properties.
 */
@Component
@Lazy
public class OpenApiSchemaBuilder {

	@Autowired
	private TypeMapper typeMapper;

	/**
	 * Build a complete OpenAPI 3.1.0 document from a tree walk result.
	 */
	public Map<String, Object> build(TreeResult treeResult, String title, String rootSchemaName) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.1.0");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", "1.0.0");
		info.put("description", "OpenAPI schemas generated from OAGIS Core Components");
		info.put("contact", buildInfoContact());
		info.put("license", buildInfoLicense());
		doc.put("info", info);

		// Servers placeholder
		List<Map<String, Object>> servers = new ArrayList<>();
		Map<String, Object> server = new LinkedHashMap<>();
		server.put("url", "https://api.example.com/v1");
		server.put("description", "Placeholder server");
		servers.add(server);
		doc.put("servers", servers);

		// Global security - reference the scheme declared in components
		doc.put("security", Collections.singletonList(
				Collections.singletonMap("bearerAuth", Collections.emptyList())));

		// Generate paths referencing the root schema so renderers (Redocly) display it
		doc.put("paths", buildPaths(Collections.singletonList(rootSchemaName)));

		Map<String, Object> components = buildComponentsFromTree(treeResult);
		addSecuritySchemes(components);
		doc.put("components", components);

		return doc;
	}

	/**
	 * Build a super-schema OpenAPI 3.1.0 document from all root nouns.
	 */
	public Map<String, Object> buildSuper(SuperTreeResult superResult, String title) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.1.0");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", "1.0.0");
		info.put("description",
				"Canonical super-schema containing all OAGIS Core Components. " +
				"Use OpenAPI Overlays to create domain-specific API subsets.");
		info.put("contact", buildInfoContact());
		info.put("license", buildInfoLicense());
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

		// Global security - reference the scheme declared in components
		doc.put("security", Collections.singletonList(
				Collections.singletonMap("bearerAuth", Collections.emptyList())));

		// Build paths for all roots
		doc.put("paths", buildPaths(superResult.getRootSchemaNames()));

		Map<String, Object> components = buildComponentsFromSuper(superResult);
		addSecuritySchemes(components);
		doc.put("components", components);

		return doc;
	}

	/**
	 * Build components from a single-root TreeResult.
	 */
	private Map<String, Object> buildComponentsFromTree(TreeResult tr) {
		return buildComponents(
				tr.getSchemas(), tr.getBaseSchemaMap(), tr.getAliasMap(),
				tr.getDerivedTypesMap(), tr.getSchemaDescriptionMap(),
				tr.getSchemaDeprecatedSet(), tr.getSchemaAbstractSet(),
				tr.getSchemaComponentTypeMap(), tr.getSchemaNamespaceMap(),
				tr.getSchemaGuidMap(), tr.getSchemaDenMap(),
				tr.getSchemaQualifierMap(), tr.getSchemaModuleMap(),
				tr.getSchemaNamespacePrefixMap());
	}

	/**
	 * Build components from a multi-root SuperTreeResult.
	 */
	private Map<String, Object> buildComponentsFromSuper(SuperTreeResult sr) {
		return buildComponents(
				sr.getSchemas(), sr.getBaseSchemaMap(), sr.getAliasMap(),
				sr.getDerivedTypesMap(), sr.getSchemaDescriptionMap(),
				sr.getSchemaDeprecatedSet(), sr.getSchemaAbstractSet(),
				sr.getSchemaComponentTypeMap(), sr.getSchemaNamespaceMap(),
				sr.getSchemaGuidMap(), sr.getSchemaDenMap(),
				sr.getSchemaQualifierMap(), sr.getSchemaModuleMap(),
				sr.getSchemaNamespacePrefixMap());
	}

	/**
	 * Build the components section from schemas, metadata maps, and aliases.
	 */
	private Map<String, Object> buildComponents(
			Map<String, List<CcNode>> schemaNodes,
			Map<String, String> baseMap,
			Map<String, String> aliasMapData,
			Map<String, List<String>> derivedTypesMap,
			Map<String, String> schemaDescriptionMap,
			Set<String> schemaDeprecatedSet,
			Set<String> schemaAbstractSet,
			Map<String, String> schemaComponentTypeMap,
			Map<String, String> schemaNamespaceMap,
			Map<String, String> schemaGuidMap,
			Map<String, String> schemaDenMap,
			Map<String, String> schemaQualifierMap,
			Map<String, String> schemaModuleMap,
			Map<String, String> schemaNamespacePrefixMap) {
		Map<String, Object> components = new LinkedHashMap<>();
		Map<String, Object> schemas = new LinkedHashMap<>();

		for (Map.Entry<String, List<CcNode>> entry : schemaNodes.entrySet()) {
			String schemaName = entry.getKey();
			List<CcNode> nodes = entry.getValue();
			String baseName = baseMap.get(schemaName);
			List<String> derivedTypes = derivedTypesMap.getOrDefault(schemaName,
					Collections.emptyList());
			String schemaDescription = schemaDescriptionMap.get(schemaName);
			boolean schemaDeprecated = schemaDeprecatedSet.contains(schemaName);
			boolean schemaAbstract = schemaAbstractSet.contains(schemaName);
			String componentType = schemaComponentTypeMap.get(schemaName);
			String namespaceUri = schemaNamespaceMap.get(schemaName);
			String schemaGuid = schemaGuidMap.get(schemaName);
			String schemaDen = schemaDenMap.get(schemaName);
			String qualifier = schemaQualifierMap.get(schemaName);
			String module = schemaModuleMap.get(schemaName);
			String nsPrefix = schemaNamespacePrefixMap.get(schemaName);

			schemas.put(schemaName, buildSchema(nodes, baseName, derivedTypes,
					schemaDescription, schemaDeprecated, schemaAbstract,
					componentType, namespaceUri, schemaGuid, schemaDen,
					qualifier, module, nsPrefix));
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

		// Reusable error response schema
		schemas.put("ErrorResponse", buildErrorResponseSchema());

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

		// 200 - array of resources
		Map<String, Object> okResp = new LinkedHashMap<>();
		okResp.put("description", "Successful response");
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "array");
		Map<String, Object> items = new LinkedHashMap<>();
		items.put("$ref", ref);
		schema.put("items", items);
		okResp.put("content", jsonContent(schema));
		responses.put("200", okResp);

		// 400 - bad request
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

		// 201 - created
		Map<String, Object> createdResp = new LinkedHashMap<>();
		createdResp.put("description", "Resource created");
		Map<String, Object> respRef = new LinkedHashMap<>();
		respRef.put("$ref", ref);
		createdResp.put("content", jsonContent(respRef));
		responses.put("201", createdResp);

		// 400 - bad request
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
		idParam.put("description", "Unique resource identifier");
		Map<String, Object> idSchema = new LinkedHashMap<>();
		idSchema.put("type", "string");
		idParam.put("schema", idSchema);
		params.add(idParam);
		op.put("parameters", params);

		Map<String, Object> responses = new LinkedHashMap<>();

		// 200 - found
		Map<String, Object> okResp = new LinkedHashMap<>();
		okResp.put("description", "Successful response");
		Map<String, Object> respRef = new LinkedHashMap<>();
		respRef.put("$ref", ref);
		okResp.put("content", jsonContent(respRef));
		responses.put("200", okResp);

		// 404 - not found
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

	/** Build a structured error response referencing the reusable ErrorResponse schema. */
	private Map<String, Object> errorResponse(String description) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("description", description);
		Map<String, Object> schemaRef = new LinkedHashMap<>();
		schemaRef.put("$ref", "#/components/schemas/ErrorResponse");
		resp.put("content", jsonContent(schemaRef));
		return resp;
	}

	/** Build the reusable ErrorResponse schema with code, message, and details. */
	private Map<String, Object> buildErrorResponseSchema() {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");

		Map<String, Object> properties = new LinkedHashMap<>();

		Map<String, Object> code = new LinkedHashMap<>();
		code.put("type", "integer");
		code.put("format", "int32");
		code.put("description", "HTTP status code");
		properties.put("code", code);

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "string");
		message.put("description", "Human-readable error message");
		properties.put("message", message);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("type", "string");
		details.put("description", "Additional diagnostic information");
		properties.put("details", details);

		schema.put("properties", properties);
		schema.put("required", Arrays.asList("code", "message"));

		return schema;
	}

	/**
	 * Build a single schema with all metadata extensions. If baseName is non-null,
	 * uses allOf composition to extend the base schema. If derivedTypes exist and the
	 * schema contains a {@code typeCode} BCC, emits an OAS 3.1.0 discriminator block.
	 */
	private Map<String, Object> buildSchema(List<CcNode> nodes, String baseName,
	                                        List<String> derivedTypes,
	                                        String schemaDescription,
	                                        boolean schemaDeprecated,
	                                        boolean schemaAbstract,
	                                        String componentType,
	                                        String namespaceUri,
	                                        String schemaGuid,
	                                        String schemaDen,
	                                        String qualifier,
	                                        String module,
	                                        String namespacePrefix) {
		Map<String, Object> ownProps = buildOwnProperties(nodes);

		if (schemaDescription != null && !schemaDescription.isEmpty()) {
			ownProps.put("description", schemaDescription);
		}

		if (schemaDeprecated) {
			ownProps.put("deprecated", true);
		}

		if (schemaAbstract) {
			ownProps.put("x-abstract", true);
		}

		// D2: CCTS component type (always "ACC" for schema-level)
		if (componentType != null) {
			ownProps.put("x-component-type", componentType);
		}

		// F1: Namespace provenance
		if (namespaceUri != null) {
			ownProps.put("x-namespace", namespaceUri);
		}

		// G2: Schema-level GUID and DEN
		if (schemaGuid != null) {
			ownProps.put("x-guid", schemaGuid);
		}
		if (schemaDen != null) {
			ownProps.put("x-den", schemaDen);
		}

		// Phase 7: ACC-level qualifier
		if (qualifier != null) {
			ownProps.put("x-qualifier", qualifier);
		}

		// Phase 7: Module provenance (source XSD file)
		if (module != null) {
			ownProps.put("x-module", module);
		}

		// Phase 7: Namespace prefix enrichment
		if (namespacePrefix != null) {
			// Supplement existing x-namespace URI with prefix
			Map<String, Object> nsObj = new LinkedHashMap<>();
			if (namespaceUri != null) {
				nsObj.put("uri", namespaceUri);
			}
			nsObj.put("prefix", namespacePrefix);
			ownProps.put("x-namespace", nsObj);
		}

		if (derivedTypes.size() >= 2 && hasTypeCodeProperty(nodes)) {
			ownProps.put("discriminator", buildDiscriminator(derivedTypes));
		}

		if (baseName == null) {
			return ownProps;
		}

		Map<String, Object> schema = new LinkedHashMap<>();
		List<Object> allOfList = new ArrayList<>();

		Map<String, Object> baseRef = new LinkedHashMap<>();
		baseRef.put("$ref", "#/components/schemas/" + baseName);
		allOfList.add(baseRef);

		if (ownProps.containsKey("properties")) {
			allOfList.add(ownProps);
		}

		schema.put("allOf", allOfList);
		return schema;
	}

	/**
	 * Check if any BCC node in the schema has a property named "typeCode".
	 * Only schemas with this natural discriminator field qualify for
	 * discriminator annotation.
	 */
	private boolean hasTypeCodeProperty(List<CcNode> nodes) {
		return nodes.stream()
				.filter(n -> n.getKind() == CcNode.Kind.BCC_ELEMENT
						|| n.getKind() == CcNode.Kind.BCC_ATTRIBUTE)
				.anyMatch(n -> "typeCode".equals(n.getPropertyName()));
	}

	/**
	 * Build an OAS 3.1.0 discriminator object with explicit mapping of
	 * derived type names to their schema references.
	 */
	private Map<String, Object> buildDiscriminator(List<String> derivedTypes) {
		Map<String, Object> discriminator = new LinkedHashMap<>();
		discriminator.put("propertyName", "typeCode");

		Map<String, String> mapping = new LinkedHashMap<>();
		for (String derived : derivedTypes) {
			mapping.put(derived, "#/components/schemas/" + derived);
		}
		discriminator.put("mapping", mapping);

		return discriminator;
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

		if (node.isDeprecated()) {
			prop.put("deprecated", true);
		}

		if (isArray) {
			prop.put("type", "array");
			Map<String, Object> items = resolveType(node.getBdtId());
			prop.put("items", items);
			if (node.getCardinalityMin() > 0) {
				prop.put("minItems", node.getCardinalityMin());
			}
			if (node.getCardinalityMax() > 1) {
				prop.put("maxItems", node.getCardinalityMax());
			}
		} else {
			TypeResolution resolution = typeMapper.resolve(node.getBdtId());
			prop.put("type", resolution.getType());
			if (resolution.getFormat() != null) {
				prop.put("format", resolution.getFormat());
			}
			if (resolution.hasEnum()) {
				prop.put("enum", resolution.getEnumValues());
				if (resolution.getEnumSource() != null) {
					prop.put("x-enum-source", resolution.getEnumSource());
				}
				// Per-value definitions
				if (resolution.getEnumDescriptions() != null) {
					prop.put("x-enum-descriptions", resolution.getEnumDescriptions());
				}
				// Per-value labels
				if (resolution.getEnumLabels() != null) {
					prop.put("x-enum-labels", resolution.getEnumLabels());
				}
				// Append CodeList/AgencyIdList definition to property description
				if (resolution.getEnumSourceDescription() != null) {
					appendDescription(prop, resolution.getEnumSourceDescription());
				}
				// C1: CodeList.remark
				if (resolution.getEnumRemark() != null) {
					prop.put("x-enum-remark", resolution.getEnumRemark());
				}
				// C2: CodeList.definitionSource
				if (resolution.getEnumDefinitionSource() != null) {
					prop.put("x-enum-definition-source", resolution.getEnumDefinitionSource());
				}
				// CodeList.extensibleIndicator
				if (resolution.getEnumExtensible() != null) {
					prop.put("x-enum-extensible", resolution.getEnumExtensible());
				}
				// Per-value CodeListValue.definitionSource
				if (resolution.getEnumValueSources() != null) {
					prop.put("x-enum-value-sources", resolution.getEnumValueSources());
				}
				// External standard identifier (CodeList.listId / AgencyIdList.listId)
				if (resolution.getEnumListId() != null) {
					prop.put("x-enum-list-id", resolution.getEnumListId());
				}
				// Responsible agency (resolved from CodeList.agencyId)
				if (resolution.getEnumAgency() != null) {
					prop.put("x-enum-agency", resolution.getEnumAgency());
				}
				// Per-value extension indicators (CodeListValue.extensionIndicator)
				if (resolution.getEnumExtensions() != null) {
					prop.put("x-enum-extensions", resolution.getEnumExtensions());
				}
			}
			// E1: Append DataType description as complementary paragraph
			if (resolution.getDataTypeDescription() != null) {
				appendDescription(prop, resolution.getDataTypeDescription());
			}
			// E3: DataType version
			if (resolution.getDataTypeVersion() != null) {
				prop.put("x-version", resolution.getDataTypeVersion());
			}
			// E: Supplementary components (DT_SC)
			if (resolution.getSupplementaryComponents() != null) {
				prop.put("x-supplementary-components", resolution.getSupplementaryComponents());
			}
			// Phase 7: DataType qualifier (CCTS qualifier for the BDT)
			if (resolution.getDataTypeQualifier() != null) {
				prop.put("x-qualifier", resolution.getDataTypeQualifier());
			}
			// Phase 7: Content component DEN (DT.contentComponentDen)
			if (resolution.getContentComponentDen() != null) {
				prop.put("x-content-component-den", resolution.getContentComponentDen());
			}
			// Phase 7: Content component definition (DT.contentComponentDefinition)
			if (resolution.getContentComponentDefinition() != null) {
				prop.put("x-content-component-definition", resolution.getContentComponentDefinition());
			}
		}

		// D2: Property-level component type (BCC)
		if (node.getComponentType() != null) {
			prop.put("x-component-type", node.getComponentType());
		}

		// D3: Entity type (Element vs Attribute)
		if (node.getEntityType() != null) {
			prop.put("x-entity-type", node.getEntityType());
		}

		// D4: Representation term from BCCP
		if (node.getRepresentationTerm() != null) {
			prop.put("x-representation-term", node.getRepresentationTerm());
		}

		// G1: Property-level GUID and DEN from BCCP
		if (node.getGuid() != null && !node.getGuid().isEmpty()) {
			prop.put("x-guid", node.getGuid());
		}
		if (node.getDen() != null && !node.getDen().isEmpty()) {
			prop.put("x-den", node.getDen());
		}

		if (node.isNillable()) {
			Object currentType = prop.get("type");
			if (currentType != null) {
				prop.put("type", Arrays.asList(currentType, "null"));
			}
		}
		if (node.getDefaultValue() != null && !node.getDefaultValue().isEmpty()) {
			prop.put("default", node.getDefaultValue());
		}

		return prop;
	}

	/**
	 * Build an association (ASCC) property. In OAS 3.1.0, $ref can coexist
	 * with sibling keywords like description, so no allOf wrapper is needed.
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
			if (node.isDeprecated()) {
				prop.put("deprecated", true);
			}
			prop.put("type", "array");
			Map<String, Object> refItem = new LinkedHashMap<>();
			refItem.put("$ref", ref);
			prop.put("items", refItem);
			if (node.getCardinalityMin() > 0) {
				prop.put("minItems", node.getCardinalityMin());
			}
			// Bounded arrays: emit maxItems when upper bound is finite
			if (node.getCardinalityMax() > 1) {
				prop.put("maxItems", node.getCardinalityMax());
			}
		} else {
			// OAS 3.1.0: $ref allows sibling keywords - no allOf wrapper needed
			if (hasDesc) {
				prop.put("description", node.getDescription());
			}
			if (node.isDeprecated()) {
				prop.put("deprecated", true);
			}
			prop.put("$ref", ref);
		}

		// D2: Property-level component type (ASCC)
		if (node.getComponentType() != null) {
			prop.put("x-component-type", node.getComponentType());
		}

		// G1: Property-level GUID and DEN from ASCCP
		if (node.getGuid() != null && !node.getGuid().isEmpty()) {
			prop.put("x-guid", node.getGuid());
		}
		if (node.getDen() != null && !node.getDen().isEmpty()) {
			prop.put("x-den", node.getDen());
		}

		// Phase 7: ASCCP reusable indicator
		if (node.getReusable() != null) {
			prop.put("x-reusable", node.getReusable());
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
			if (resolution.getEnumSource() != null) {
				type.put("x-enum-source", resolution.getEnumSource());
			}
		}
		return type;
	}

	/**
	 * Appends additional text to an existing "description" property value
	 * as a separate paragraph. Creates the description if none exists.
	 */
	private void appendDescription(Map<String, Object> prop, String text) {
		String existing = (String) prop.get("description");
		if (existing != null && !existing.isEmpty()) {
			prop.put("description", existing + "\n\n" + text);
		} else {
			prop.put("description", text);
		}
	}

	/** OAGi organization contact metadata. */
	private Map<String, Object> buildInfoContact() {
		Map<String, Object> contact = new LinkedHashMap<>();
		contact.put("name", "Open Applications Group (OAGi)");
		contact.put("url", "https://oagi.org");
		return contact;
	}

	/** OAGIS uses Apache License 2.0. */
	private Map<String, Object> buildInfoLicense() {
		Map<String, Object> license = new LinkedHashMap<>();
		license.put("name", "Apache License 2.0");
		license.put("url", "https://www.apache.org/licenses/LICENSE-2.0");
		return license;
	}
}

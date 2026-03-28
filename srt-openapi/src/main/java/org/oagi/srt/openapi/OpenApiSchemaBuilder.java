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
 * <p>Uses allOf composition for ACC inheritance (based_acc_id). In OAS 3.1.0,
 * $ref can coexist with sibling keywords like description, so no allOf
 * wrapper is needed for association properties.</p>
 *
 * <h3>Field exclusion policy</h3>
 * <p>Approximately 60 entity fields across ACC, BCC, BCCP, ASCC, ASCCP, and DataType
 * are intentionally excluded from the OpenAPI output. These fall into categories such as
 * internal audit/lifecycle metadata, revision tracking, surrogate primary keys, FK join
 * columns, redundant GUIDs, CC-level definition sources, DataType internals, and
 * module/namespace at the property level. All exclusions have been audited for semantic
 * relevance and documented with justifications.</p>
 *
 * @see CcTreeWalker.WalkContext WalkContext (excluded fields list)
 * @see <a href="../../../../../../README.md">README.md § Excluded Fields</a>
 */
@Component
@Lazy
public class OpenApiSchemaBuilder {

	@Autowired
	private TypeMapper typeMapper;

	/**
	 * Build a complete OpenAPI 3.1.0 document from a tree walk result.
	 *
	 * @param releaseNum OAGIS release version (e.g., "10.3") used as {@code info.version}
	 */
	public Map<String, Object> build(TreeResult treeResult, String title,
									  String releaseNum) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.1.0");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", releaseNum);
		info.put("description", "OpenAPI schemas generated from OAGIS Core Components. " +
				"Use OpenAPI Overlays to create domain-specific API subsets.");
		info.put("contact", buildInfoContact());
		info.put("license", buildInfoLicense());
		doc.put("info", info);

		doc.put("components", buildComponentsFromTree(treeResult));

		return doc;
	}

	/**
	 * Build a super-schema OpenAPI 3.1.0 document from all root nouns.
	 *
	 * @param releaseNum OAGIS release version (e.g., "10.3") used as {@code info.version}
	 */
	public Map<String, Object> buildSuper(SuperTreeResult superResult, String title,
										  String releaseNum) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.1.0");

		Map<String, Object> info = new LinkedHashMap<>();
		info.put("title", title);
		info.put("version", releaseNum);
		info.put("description",
				"Canonical super-schema containing all OAGIS Core Components. " +
				"Use OpenAPI Overlays to create domain-specific API subsets.");
		info.put("contact", buildInfoContact());
		info.put("license", buildInfoLicense());
		doc.put("info", info);

		doc.put("components", buildComponentsFromSuper(superResult));

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

		components.put("schemas", schemas);
		return components;
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
			ownProps.put("x-oagis-abstract", true);
		}

		// D2: CCTS component type (always "ACC" for schema-level)
		if (componentType != null) {
			ownProps.put("x-oagis-component-type", componentType);
		}

		// F1: Namespace provenance
		if (namespaceUri != null) {
			ownProps.put("x-oagis-namespace", namespaceUri);
		}

		// G2: Schema-level GUID and DEN
		if (schemaGuid != null) {
			ownProps.put("x-oagis-guid", schemaGuid);
		}
		if (schemaDen != null) {
			ownProps.put("x-oagis-den", schemaDen);
		}

		// Phase 7: ACC-level qualifier
		if (qualifier != null) {
			ownProps.put("x-oagis-qualifier", qualifier);
		}

		// Phase 7: Module provenance (source XSD file)
		if (module != null) {
			ownProps.put("x-oagis-module", module);
		}

		// Phase 7: Namespace prefix enrichment
		if (namespacePrefix != null) {
			// Supplement existing x-namespace URI with prefix
			Map<String, Object> nsObj = new LinkedHashMap<>();
			if (namespaceUri != null) {
				nsObj.put("uri", namespaceUri);
			}
			nsObj.put("prefix", namespacePrefix);
			ownProps.put("x-oagis-namespace", nsObj);
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
					prop.put("x-oagis-enum-source", resolution.getEnumSource());
				}
				// Per-value definitions
				if (resolution.getEnumDescriptions() != null) {
					prop.put("x-oagis-enum-descriptions", resolution.getEnumDescriptions());
				}
				// Per-value labels
				if (resolution.getEnumLabels() != null) {
					prop.put("x-oagis-enum-labels", resolution.getEnumLabels());
				}
				// Append CodeList/AgencyIdList definition to property description
				if (resolution.getEnumSourceDescription() != null) {
					appendDescription(prop, resolution.getEnumSourceDescription());
				}
				// C1: CodeList.remark
				if (resolution.getEnumRemark() != null) {
					prop.put("x-oagis-enum-remark", resolution.getEnumRemark());
				}
				// C2: CodeList.definitionSource
				if (resolution.getEnumDefinitionSource() != null) {
					prop.put("x-oagis-enum-definition-source", resolution.getEnumDefinitionSource());
				}
				// CodeList.extensibleIndicator
				if (resolution.getEnumExtensible() != null) {
					prop.put("x-oagis-enum-extensible", resolution.getEnumExtensible());
				}
				// Per-value CodeListValue.definitionSource
				if (resolution.getEnumValueSources() != null) {
					prop.put("x-oagis-enum-value-sources", resolution.getEnumValueSources());
				}
				// External standard identifier (CodeList.listId / AgencyIdList.listId)
				if (resolution.getEnumListId() != null) {
					prop.put("x-oagis-enum-list-id", resolution.getEnumListId());
				}
				// Responsible agency (resolved from CodeList.agencyId)
				if (resolution.getEnumAgency() != null) {
					prop.put("x-oagis-enum-agency", resolution.getEnumAgency());
				}
				// Per-value extension indicators (CodeListValue.extensionIndicator)
				if (resolution.getEnumExtensions() != null) {
					prop.put("x-oagis-enum-extensions", resolution.getEnumExtensions());
				}
			}
			// E1: Append DataType description as complementary paragraph
			if (resolution.getDataTypeDescription() != null) {
				appendDescription(prop, resolution.getDataTypeDescription());
			}
			// E3: DataType version
			if (resolution.getDataTypeVersion() != null) {
				prop.put("x-oagis-version", resolution.getDataTypeVersion());
			}
			// E: Supplementary components (DT_SC)
			if (resolution.getSupplementaryComponents() != null) {
				prop.put("x-oagis-supplementary-components", resolution.getSupplementaryComponents());
			}
			// Phase 7: DataType qualifier (CCTS qualifier for the BDT)
			if (resolution.getDataTypeQualifier() != null) {
				prop.put("x-oagis-qualifier", resolution.getDataTypeQualifier());
			}
			// Phase 7: Content component DEN (DT.contentComponentDen)
			if (resolution.getContentComponentDen() != null) {
				prop.put("x-oagis-content-component-den", resolution.getContentComponentDen());
			}
			// Phase 7: Content component definition (DT.contentComponentDefinition)
			if (resolution.getContentComponentDefinition() != null) {
				prop.put("x-oagis-content-component-definition", resolution.getContentComponentDefinition());
			}
		}

		// D2: Property-level component type (BCC)
		if (node.getComponentType() != null) {
			prop.put("x-oagis-component-type", node.getComponentType());
		}

		// D3: Entity type (Element vs Attribute)
		if (node.getEntityType() != null) {
			prop.put("x-oagis-entity-type", node.getEntityType());
		}

		// D4: Representation term from BCCP
		if (node.getRepresentationTerm() != null) {
			prop.put("x-oagis-representation-term", node.getRepresentationTerm());
		}

		// G1: Property-level GUID and DEN from BCCP
		if (node.getGuid() != null && !node.getGuid().isEmpty()) {
			prop.put("x-oagis-guid", node.getGuid());
		}
		if (node.getDen() != null && !node.getDen().isEmpty()) {
			prop.put("x-oagis-den", node.getDen());
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
			prop.put("x-oagis-component-type", node.getComponentType());
		}

		// G1: Property-level GUID and DEN from ASCCP
		if (node.getGuid() != null && !node.getGuid().isEmpty()) {
			prop.put("x-oagis-guid", node.getGuid());
		}
		if (node.getDen() != null && !node.getDen().isEmpty()) {
			prop.put("x-oagis-den", node.getDen());
		}

		// Phase 7: ASCCP reusable indicator
		if (node.getReusable() != null) {
			prop.put("x-oagis-reusable", node.getReusable());
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
				type.put("x-oagis-enum-source", resolution.getEnumSource());
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

	/**
	 * OAGi permissive license (from {@code OAGi License Agreement.xml} in the OAGIS distribution).
	 *
	 * <p>Not Apache 2.0 — the OAGIS® standard uses its own license that permits reproduction,
	 * distribution, and derivative works without fee, provided attribution is included.</p>
	 */
	private Map<String, Object> buildInfoLicense() {
		Map<String, Object> license = new LinkedHashMap<>();
		license.put("name", "OAGi License Agreement");
		license.put("url", "https://oagi.org/pages/license");
		return license;
	}
}

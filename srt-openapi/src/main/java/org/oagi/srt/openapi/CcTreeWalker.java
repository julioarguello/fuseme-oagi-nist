package org.oagi.srt.openapi;

import org.oagi.srt.provider.ImportedDataProvider;
import org.oagi.srt.repository.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Walks the CC tree starting from an ASCCP root. Produces an ordered list of
 * schema entries that the OpenApiSchemaBuilder consumes.
 *
 * Traversal order: ACC -> (BCC children sorted by seqKey, ASCC children sorted by seqKey),
 * recursing through ASCC -> ASCCP -> roleOfACC.
 *
 * When the same ACC is referenced by multiple ASCCPs under different names
 * (e.g., "BuyerContact" vs "SalesContact"), only the first visit produces the
 * full schema. Subsequent names become aliases that reference the original.
 */
@Component
@Lazy
public class CcTreeWalker {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private ImportedDataProvider importedDataProvider;

	/**
	 * Immutable node representing one property in the CC tree.
	 */
	public static class CcNode {
		public enum Kind { BCC_ELEMENT, BCC_ATTRIBUTE, ASCC }

		private final Kind kind;
		private final String propertyName;
		private final String description;
		private final int cardinalityMin;
		private final int cardinalityMax;
		private final boolean nillable;
		private final boolean deprecated;
		private final String defaultValue;

		// BCC-specific: the BCCP's bdtId for type resolution
		private final long bdtId;

		// ASCC-specific: the target ACC for recursive schema generation
		private final long roleOfAccId;
		private final String refSchemaName;

		// CCTS structural metadata (Group D)
		private final String componentType;
		private final String entityType;
		private final String representationTerm;

		// Traceability metadata (Group G)
		private final String guid;
		private final String den;

		// ASCCP reusability flag
		private final Boolean reusable;

		private CcNode(Kind kind, String propertyName, String description,
		               int cardinalityMin, int cardinalityMax,
		               boolean nillable, boolean deprecated, String defaultValue,
		               long bdtId, long roleOfAccId, String refSchemaName,
		               String componentType, String entityType, String representationTerm,
		               String guid, String den, Boolean reusable) {
			this.kind = kind;
			this.propertyName = propertyName;
			this.description = description;
			this.cardinalityMin = cardinalityMin;
			this.cardinalityMax = cardinalityMax;
			this.nillable = nillable;
			this.deprecated = deprecated;
			this.defaultValue = defaultValue;
			this.bdtId = bdtId;
			this.roleOfAccId = roleOfAccId;
			this.refSchemaName = refSchemaName;
			this.componentType = componentType;
			this.entityType = entityType;
			this.representationTerm = representationTerm;
			this.guid = guid;
			this.den = den;
			this.reusable = reusable;
		}

		public Kind getKind() { return kind; }
		public String getPropertyName() { return propertyName; }
		public String getDescription() { return description; }
		public int getCardinalityMin() { return cardinalityMin; }
		public int getCardinalityMax() { return cardinalityMax; }
		public boolean isNillable() { return nillable; }
		public boolean isDeprecated() { return deprecated; }
		public String getDefaultValue() { return defaultValue; }
		public long getBdtId() { return bdtId; }
		public long getRoleOfAccId() { return roleOfAccId; }
		public String getRefSchemaName() { return refSchemaName; }
		/** CCTS component type: "BCC", "BCCP", "ASCC", "ASCCP". */
		public String getComponentType() { return componentType; }
		/** CCTS entity type (e.g., "Element", "Attribute"). */
		public String getEntityType() { return entityType; }
		/** CCTS representation term from BCCP (e.g., "Text", "Identifier", "Amount"). */
		public String getRepresentationTerm() { return representationTerm; }
		/** OAGIS GUID for traceability. */
		public String getGuid() { return guid; }
		/** OAGIS Dictionary Entry Name for traceability. */
		public String getDen() { return den; }
		/** Whether the ASCCP is reusable across ACCs. Null for non-ASCC nodes. */
		public Boolean getReusable() { return reusable; }
	}

	/**
	 * Mutable context accumulated during a tree walk. Encapsulates all the maps
	 * and sets that were previously separate method parameters.
	 */
	private static class WalkContext {
		final Map<String, List<CcNode>> schemas = new LinkedHashMap<>();
		final Map<String, String> baseSchemaMap = new LinkedHashMap<>();
		final Map<String, String> aliasMap = new LinkedHashMap<>();
		final Map<String, String> schemaDescriptionMap = new LinkedHashMap<>();
		final Set<String> schemaDeprecatedSet = new LinkedHashSet<>();
		final Set<String> schemaAbstractSet = new LinkedHashSet<>();
		// CCTS: schema-level component type (always "ACC" for schemas)
		final Map<String, String> schemaComponentTypeMap = new LinkedHashMap<>();
		// Group F: namespace URI per schema (from ACC.namespaceId -> Namespace.uri)
		final Map<String, String> schemaNamespaceMap = new LinkedHashMap<>();
		// Group G: GUID and DEN per schema (from ACC)
		final Map<String, String> schemaGuidMap = new LinkedHashMap<>();
		final Map<String, String> schemaDenMap = new LinkedHashMap<>();
		// Phase 7: ACC qualifier, module, and namespace prefix
		final Map<String, String> schemaQualifierMap = new LinkedHashMap<>();
		final Map<String, String> schemaModuleMap = new LinkedHashMap<>();
		final Map<String, String> schemaNamespacePrefixMap = new LinkedHashMap<>();
		// Deduplication tracker
		final Map<Long, String> accIdToSchemaName = new HashMap<>();
	}

	/**
	 * Result of a full tree walk: the root ACC schema name plus child nodes for
	 * each ACC encountered (keyed by schema name).
	 */
	public static class TreeResult {
		private final String rootSchemaName;
		private final Map<String, List<CcNode>> schemas;
		private final Map<String, String> baseSchemaMap;
		private final Map<String, String> aliasMap;
		private final Map<String, List<String>> derivedTypesMap;
		private final Map<String, String> schemaDescriptionMap;
		private final Set<String> schemaDeprecatedSet;
		private final Set<String> schemaAbstractSet;
		private final Map<String, String> schemaComponentTypeMap;
		private final Map<String, String> schemaNamespaceMap;
		private final Map<String, String> schemaGuidMap;
		private final Map<String, String> schemaDenMap;
		private final Map<String, String> schemaQualifierMap;
		private final Map<String, String> schemaModuleMap;
		private final Map<String, String> schemaNamespacePrefixMap;

		TreeResult(String rootSchemaName, WalkContext ctx) {
			this.rootSchemaName = rootSchemaName;
			this.schemas = ctx.schemas;
			this.baseSchemaMap = ctx.baseSchemaMap;
			this.aliasMap = ctx.aliasMap;
			this.derivedTypesMap = computeDerivedTypesMap(ctx.baseSchemaMap);
			this.schemaDescriptionMap = ctx.schemaDescriptionMap;
			this.schemaDeprecatedSet = ctx.schemaDeprecatedSet;
			this.schemaAbstractSet = ctx.schemaAbstractSet;
			this.schemaComponentTypeMap = ctx.schemaComponentTypeMap;
			this.schemaNamespaceMap = ctx.schemaNamespaceMap;
			this.schemaGuidMap = ctx.schemaGuidMap;
			this.schemaDenMap = ctx.schemaDenMap;
			this.schemaQualifierMap = ctx.schemaQualifierMap;
			this.schemaModuleMap = ctx.schemaModuleMap;
			this.schemaNamespacePrefixMap = ctx.schemaNamespacePrefixMap;
		}

		public String getRootSchemaName() { return rootSchemaName; }
		public Map<String, List<CcNode>> getSchemas() { return schemas; }
		public Map<String, String> getBaseSchemaMap() { return baseSchemaMap; }
		public Map<String, String> getAliasMap() { return aliasMap; }
		public Map<String, List<String>> getDerivedTypesMap() { return derivedTypesMap; }
		public Map<String, String> getSchemaDescriptionMap() { return schemaDescriptionMap; }
		public Set<String> getSchemaDeprecatedSet() { return schemaDeprecatedSet; }
		public Set<String> getSchemaAbstractSet() { return schemaAbstractSet; }
		public Map<String, String> getSchemaComponentTypeMap() { return schemaComponentTypeMap; }
		public Map<String, String> getSchemaNamespaceMap() { return schemaNamespaceMap; }
		public Map<String, String> getSchemaGuidMap() { return schemaGuidMap; }
		public Map<String, String> getSchemaDenMap() { return schemaDenMap; }
		public Map<String, String> getSchemaQualifierMap() { return schemaQualifierMap; }
		public Map<String, String> getSchemaModuleMap() { return schemaModuleMap; }
		public Map<String, String> getSchemaNamespacePrefixMap() { return schemaNamespacePrefixMap; }
	}

	/**
	 * Multi-root result: all root nouns merged into one schema set.
	 */
	public static class SuperTreeResult {
		private final List<String> rootSchemaNames;
		private final Map<String, List<CcNode>> schemas;
		private final Map<String, String> baseSchemaMap;
		private final Map<String, String> aliasMap;
		private final Map<String, List<String>> derivedTypesMap;
		private final Map<String, String> schemaDescriptionMap;
		private final Set<String> schemaDeprecatedSet;
		private final Set<String> schemaAbstractSet;
		private final Map<String, String> schemaComponentTypeMap;
		private final Map<String, String> schemaNamespaceMap;
		private final Map<String, String> schemaGuidMap;
		private final Map<String, String> schemaDenMap;
		private final Map<String, String> schemaQualifierMap;
		private final Map<String, String> schemaModuleMap;
		private final Map<String, String> schemaNamespacePrefixMap;

		SuperTreeResult(List<String> rootSchemaNames, WalkContext ctx) {
			this.rootSchemaNames = rootSchemaNames;
			this.schemas = ctx.schemas;
			this.baseSchemaMap = ctx.baseSchemaMap;
			this.aliasMap = ctx.aliasMap;
			this.derivedTypesMap = computeDerivedTypesMap(ctx.baseSchemaMap);
			this.schemaDescriptionMap = ctx.schemaDescriptionMap;
			this.schemaDeprecatedSet = ctx.schemaDeprecatedSet;
			this.schemaAbstractSet = ctx.schemaAbstractSet;
			this.schemaComponentTypeMap = ctx.schemaComponentTypeMap;
			this.schemaNamespaceMap = ctx.schemaNamespaceMap;
			this.schemaGuidMap = ctx.schemaGuidMap;
			this.schemaDenMap = ctx.schemaDenMap;
			this.schemaQualifierMap = ctx.schemaQualifierMap;
			this.schemaModuleMap = ctx.schemaModuleMap;
			this.schemaNamespacePrefixMap = ctx.schemaNamespacePrefixMap;
		}

		public List<String> getRootSchemaNames() { return rootSchemaNames; }
		public Map<String, List<CcNode>> getSchemas() { return schemas; }
		public Map<String, String> getBaseSchemaMap() { return baseSchemaMap; }
		public Map<String, String> getAliasMap() { return aliasMap; }
		public Map<String, List<String>> getDerivedTypesMap() { return derivedTypesMap; }
		public Map<String, String> getSchemaDescriptionMap() { return schemaDescriptionMap; }
		public Set<String> getSchemaDeprecatedSet() { return schemaDeprecatedSet; }
		public Set<String> getSchemaAbstractSet() { return schemaAbstractSet; }
		public Map<String, String> getSchemaComponentTypeMap() { return schemaComponentTypeMap; }
		public Map<String, String> getSchemaNamespaceMap() { return schemaNamespaceMap; }
		public Map<String, String> getSchemaGuidMap() { return schemaGuidMap; }
		public Map<String, String> getSchemaDenMap() { return schemaDenMap; }
		public Map<String, String> getSchemaQualifierMap() { return schemaQualifierMap; }
		public Map<String, String> getSchemaModuleMap() { return schemaModuleMap; }
		public Map<String, String> getSchemaNamespacePrefixMap() { return schemaNamespacePrefixMap; }
	}

	/**
	 * Walk the CC tree starting from a named ASCCP.
	 */
	public TreeResult walk(String asccpPropertyTerm) {
		AssociationCoreComponentProperty asccp = importedDataProvider.findASCCP().stream()
				.filter(a -> asccpPropertyTerm.equals(a.getPropertyTerm()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"ASCCP not found: " + asccpPropertyTerm));

		return walkFromAsccp(asccp);
	}

	/**
	 * Walk ALL non-reusable, published ASCCPs and merge their trees into a
	 * single SuperTreeResult. Shared ACCs are deduplicated across all walks.
	 */
	public SuperTreeResult walkAll() {
		List<AssociationCoreComponentProperty> allAsccp = importedDataProvider.findASCCP();

		// Filter to root business documents: non-reusable and published
		List<AssociationCoreComponentProperty> roots = allAsccp.stream()
				.filter(a -> !a.isReusableIndicator())
				.filter(a -> a.getState() == CoreComponentState.Published)
				.sorted(Comparator.comparing(AssociationCoreComponentProperty::getPropertyTerm))
				.collect(Collectors.toList());

		logger.info("walkAll: {} total ASCCPs, {} root nouns after filtering",
				allAsccp.size(), roots.size());

		// Use LinkedHashSet to deduplicate root names while preserving order
		Set<String> rootSchemaNameSet = new LinkedHashSet<>();
		WalkContext ctx = new WalkContext();

		int count = 0;
		for (AssociationCoreComponentProperty asccp : roots) {
			count++;
			AggregateCoreComponent roleOfAcc = importedDataProvider.findACC(asccp.getRoleOfAccId());
			if (roleOfAcc == null) {
				logger.warn("Skipping ASCCP '{}': roleOfAcc not found (id={})",
						asccp.getPropertyTerm(), asccp.getRoleOfAccId());
				continue;
			}

			// Derive root name from ACC objectClassTerm (business document name)
			String rootName = toCamelCase(roleOfAcc.getObjectClassTerm());
			logger.info("  [{}/{}] Walking root: {} (ASCCP ID={}, ACC='{}')",
					count, roots.size(), rootName, asccp.getAsccpId(),
					roleOfAcc.getObjectClassTerm());

			rootSchemaNameSet.add(rootName);
			walkAcc(roleOfAcc, rootName, ctx);
		}

		List<String> rootSchemaNames = new ArrayList<>(rootSchemaNameSet);
		logger.info("walkAll complete: {} roots ({} unique), {} schemas, {} aliases",
				roots.size(), rootSchemaNames.size(),
				ctx.schemas.size(), ctx.aliasMap.size());

		return new SuperTreeResult(rootSchemaNames, ctx);
	}

	/**
	 * Walk the CC tree starting from an ASCCP entity.
	 */
	public TreeResult walkFromAsccp(AssociationCoreComponentProperty asccp) {
		AggregateCoreComponent roleOfAcc = importedDataProvider.findACC(asccp.getRoleOfAccId());
		String rootName = toCamelCase(asccp.getPropertyTerm());

		WalkContext ctx = new WalkContext();
		walkAcc(roleOfAcc, rootName, ctx);

		return new TreeResult(rootName, ctx);
	}

	private void walkAcc(AggregateCoreComponent acc, String schemaName, WalkContext ctx) {
		String existing = ctx.accIdToSchemaName.get(acc.getAccId());
		if (existing != null) {
			if (!existing.equals(schemaName)) {
				ctx.aliasMap.put(schemaName, existing);
			}
			return;
		}
		ctx.accIdToSchemaName.put(acc.getAccId(), schemaName);

		// ACC-level description: definition + objectClassQualifier (D1)
		String accDescription = concatDescriptions(acc.getDefinition(),
				acc.getObjectClassQualifier() != null && !acc.getObjectClassQualifier().isEmpty()
						? "**Qualifier:** " + acc.getObjectClassQualifier() : null);
		if (accDescription != null) {
			ctx.schemaDescriptionMap.put(schemaName, accDescription);
		}
		if (acc.isDeprecated()) {
			ctx.schemaDeprecatedSet.add(schemaName);
		}
		if (acc.isAbstract()) {
			ctx.schemaAbstractSet.add(schemaName);
		}

		// D2: Schema-level component type (always "ACC")
		ctx.schemaComponentTypeMap.put(schemaName, "ACC");

		// F1: Namespace provenance
		if (acc.getNamespaceId() > 0) {
			Namespace ns = importedDataProvider.findNamespace(acc.getNamespaceId());
			if (ns != null && ns.getUri() != null && !ns.getUri().isEmpty()) {
				ctx.schemaNamespaceMap.put(schemaName, ns.getUri());
			}
			if (ns != null && ns.getPrefix() != null && !ns.getPrefix().isEmpty()) {
				ctx.schemaNamespacePrefixMap.put(schemaName, ns.getPrefix());
			}
		}

		// Phase 7: ACC qualifier
		if (acc.getObjectClassQualifier() != null && !acc.getObjectClassQualifier().isEmpty()) {
			ctx.schemaQualifierMap.put(schemaName, acc.getObjectClassQualifier());
		}

		// Phase 7: Module provenance (source XSD file name)
		if (acc.getModuleId() > 0) {
			Module mod = importedDataProvider.findModule(acc.getModuleId());
			if (mod != null && mod.getModule() != null && !mod.getModule().isEmpty()) {
				ctx.schemaModuleMap.put(schemaName, mod.getModule());
			}
		}

		// G2: Schema-level GUID and DEN from ACC
		if (acc.getGuid() != null && !acc.getGuid().isEmpty()) {
			ctx.schemaGuidMap.put(schemaName, acc.getGuid());
		}
		if (acc.getDen() != null && !acc.getDen().isEmpty()) {
			ctx.schemaDenMap.put(schemaName, acc.getDen());
		}

		List<CcNode> nodes = new ArrayList<>();

		// Collect BCC children (basic properties)
		List<BasicCoreComponent> bccList = importedDataProvider.findBCCByFromAccId(acc.getAccId());
		for (BasicCoreComponent bcc : bccList) {
			BasicCoreComponentProperty bccp = importedDataProvider.findBCCP(bcc.getToBccpId());
			if (bccp == null) {
				continue;
			}

			CcNode.Kind kind = (bcc.getEntityType() == BasicCoreComponentEntityType.Attribute)
					? CcNode.Kind.BCC_ATTRIBUTE
					: CcNode.Kind.BCC_ELEMENT;

			String propName = toCamelCase(bccp.getPropertyTerm());
			// Lowercase-start for attributes
			if (kind == CcNode.Kind.BCC_ATTRIBUTE) {
				propName = propName.substring(0, 1).toLowerCase() + propName.substring(1);
			}

			// Concatenate BCCP and BCC definitions as complementary paragraphs
			String description = concatDescriptions(bccp.getDefinition(), bcc.getDefinition());

			boolean deprecated = bccp.isDeprecated() || bcc.isDeprecated();

			// D3: entity type string for BCC
			String entityTypeStr = (bcc.getEntityType() == BasicCoreComponentEntityType.Attribute)
					? "Attribute" : "Element";

			// D4: representation term from BCCP
			String representationTerm = bccp.getRepresentationTerm();
			if (representationTerm != null && representationTerm.isEmpty()) {
				representationTerm = null;
			}

			// G1: GUID and DEN from BCCP (property-level traceability)
			String bccpGuid = bccp.getGuid();
			String bccpDen = bccp.getDen();

			nodes.add(new CcNode(
					kind, propName, description,
					bcc.getCardinalityMin(), bcc.getCardinalityMax(),
					bcc.isNillable(), deprecated, bcc.getDefaultValue(),
					bccp.getBdtId(), 0, null,
					"BCC", entityTypeStr, representationTerm,
					bccpGuid, bccpDen, null));
		}

		// Collect ASCC children (association properties)
		List<AssociationCoreComponent> asccList = importedDataProvider.findASCCByFromAccId(acc.getAccId());
		for (AssociationCoreComponent ascc : asccList) {
			AssociationCoreComponentProperty asccp = importedDataProvider.findASCCP(ascc.getToAsccpId());
			if (asccp == null) {
				continue;
			}

			AggregateCoreComponent roleOfAcc = importedDataProvider.findACC(asccp.getRoleOfAccId());
			if (roleOfAcc == null) {
				continue;
			}

			String childSchemaName = toCamelCase(asccp.getPropertyTerm());
			String propName = childSchemaName.substring(0, 1).toLowerCase() + childSchemaName.substring(1);

			// Concatenate ASCCP and ASCC definitions as complementary paragraphs
			String asccDescription = concatDescriptions(asccp.getDefinition(), ascc.getDefinition());

			// G1: GUID and DEN from ASCCP (property-level traceability)
			String asccpGuid = asccp.getGuid();
			String asccpDen = asccp.getDen();

			// Phase 7: ASCCP reusable indicator
			Boolean asccpReusable = asccp.isReusableIndicator();

			nodes.add(new CcNode(
					CcNode.Kind.ASCC, propName, asccDescription,
					ascc.getCardinalityMin(), ascc.getCardinalityMax(),
					false, ascc.isDeprecated(), null,
					0, roleOfAcc.getAccId(), childSchemaName,
					"ASCC", "Element", null,
					asccpGuid, asccpDen, asccpReusable));

			// Recurse into the referenced ACC
			walkAcc(roleOfAcc, childSchemaName, ctx);
		}

		// Record and recurse into base ACC for allOf composition
		if (acc.getBasedAccId() > 0) {
			AggregateCoreComponent basedAcc = importedDataProvider.findACC(acc.getBasedAccId());
			if (basedAcc != null) {
				String baseSchemaName = toCamelCase(basedAcc.getObjectClassTerm());
				ctx.baseSchemaMap.put(schemaName, baseSchemaName);
				walkAcc(basedAcc, baseSchemaName, ctx);
			}
		}

		// Sort: BCC_ATTRIBUTE first, then remaining nodes in insertion order
		List<CcNode> sorted = Stream.concat(
				nodes.stream().filter(n -> n.getKind() == CcNode.Kind.BCC_ATTRIBUTE),
				nodes.stream().filter(n -> n.getKind() != CcNode.Kind.BCC_ATTRIBUTE)
		).collect(Collectors.toList());

		ctx.schemas.put(schemaName, sorted);
	}

	/**
	 * Reverses baseSchemaMap (child -> parent) into a derived types map
	 * (parent -> sorted list of children). Only entries with 2+ derived
	 * types are meaningful for discriminator generation.
	 */
	private static Map<String, List<String>> computeDerivedTypesMap(
			Map<String, String> baseSchemaMap) {
		Map<String, List<String>> derived = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : baseSchemaMap.entrySet()) {
			derived.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
					.add(entry.getKey());
		}
		// Sort each list for deterministic output
		for (List<String> children : derived.values()) {
			Collections.sort(children);
		}
		return Collections.unmodifiableMap(derived);
	}

	/**
	 * Concatenates two description fragments as separate paragraphs.
	 * Returns null if both are null/empty. Skips null/empty fragments.
	 */
	private static String concatDescriptions(String first, String second) {
		boolean hasFirst = first != null && !first.isEmpty();
		boolean hasSecond = second != null && !second.isEmpty();
		if (hasFirst && hasSecond) {
			return first + "\n\n" + second;
		}
		if (hasFirst) {
			return first;
		}
		if (hasSecond) {
			return second;
		}
		return null;
	}

	private static String toCamelCase(String term) {
		if (term == null || term.isEmpty()) {
			return term;
		}
		StringBuilder sb = new StringBuilder();
		for (String word : term.split("\\s+")) {
			if (!word.isEmpty()) {
				if ("Identifier".equals(word)) {
					sb.append("ID");
				} else {
					sb.append(Character.toUpperCase(word.charAt(0)));
					if (word.length() > 1) {
						sb.append(word.substring(1));
					}
				}
			}
		}
		return sb.toString();
	}
}

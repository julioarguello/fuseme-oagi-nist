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
		private final String defaultValue;

		// BCC-specific: the BCCP's bdtId for type resolution
		private final long bdtId;

		// ASCC-specific: the target ACC for recursive schema generation
		private final long roleOfAccId;
		private final String refSchemaName;

		private CcNode(Kind kind, String propertyName, String description,
		               int cardinalityMin, int cardinalityMax,
		               boolean nillable, String defaultValue,
		               long bdtId, long roleOfAccId, String refSchemaName) {
			this.kind = kind;
			this.propertyName = propertyName;
			this.description = description;
			this.cardinalityMin = cardinalityMin;
			this.cardinalityMax = cardinalityMax;
			this.nillable = nillable;
			this.defaultValue = defaultValue;
			this.bdtId = bdtId;
			this.roleOfAccId = roleOfAccId;
			this.refSchemaName = refSchemaName;
		}

		public Kind getKind() { return kind; }
		public String getPropertyName() { return propertyName; }
		public String getDescription() { return description; }
		public int getCardinalityMin() { return cardinalityMin; }
		public int getCardinalityMax() { return cardinalityMax; }
		public boolean isNillable() { return nillable; }
		public String getDefaultValue() { return defaultValue; }
		public long getBdtId() { return bdtId; }
		public long getRoleOfAccId() { return roleOfAccId; }
		public String getRefSchemaName() { return refSchemaName; }
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

		TreeResult(String rootSchemaName, Map<String, List<CcNode>> schemas,
		           Map<String, String> baseSchemaMap, Map<String, String> aliasMap) {
			this.rootSchemaName = rootSchemaName;
			this.schemas = schemas;
			this.baseSchemaMap = baseSchemaMap;
			this.aliasMap = aliasMap;
		}

		public String getRootSchemaName() { return rootSchemaName; }
		public Map<String, List<CcNode>> getSchemas() { return schemas; }
		/** Maps schema name -> base schema name (from ACC.based_acc_id). */
		public Map<String, String> getBaseSchemaMap() { return baseSchemaMap; }
		/** Maps alias schema name -> canonical schema name (same ACC, different ASCCP). */
		public Map<String, String> getAliasMap() { return aliasMap; }
	}

	/**
	 * Multi-root result: all root nouns merged into one schema set.
	 */
	public static class SuperTreeResult {
		private final List<String> rootSchemaNames;
		private final Map<String, List<CcNode>> schemas;
		private final Map<String, String> baseSchemaMap;
		private final Map<String, String> aliasMap;

		SuperTreeResult(List<String> rootSchemaNames,
		                Map<String, List<CcNode>> schemas,
		                Map<String, String> baseSchemaMap,
		                Map<String, String> aliasMap) {
			this.rootSchemaNames = rootSchemaNames;
			this.schemas = schemas;
			this.baseSchemaMap = baseSchemaMap;
			this.aliasMap = aliasMap;
		}

		public List<String> getRootSchemaNames() { return rootSchemaNames; }
		public Map<String, List<CcNode>> getSchemas() { return schemas; }
		public Map<String, String> getBaseSchemaMap() { return baseSchemaMap; }
		public Map<String, String> getAliasMap() { return aliasMap; }
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
		Map<String, List<CcNode>> mergedSchemas = new LinkedHashMap<>();
		Map<String, String> mergedBaseMap = new LinkedHashMap<>();
		Map<String, String> mergedAliasMap = new LinkedHashMap<>();
		// Shared across all walks for cross-noun deduplication
		Map<Long, String> sharedAccIdToSchemaName = new HashMap<>();

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
			// instead of ASCCP propertyTerm which is too generic (e.g., "Data Area")
			String rootName = toCamelCase(roleOfAcc.getObjectClassTerm());
			logger.info("  [{}/{}] Walking root: {} (ASCCP ID={}, ACC='{}')",
					count, roots.size(), rootName, asccp.getAsccpId(),
					roleOfAcc.getObjectClassTerm());

			rootSchemaNameSet.add(rootName);
			walkAcc(roleOfAcc, rootName, mergedSchemas, mergedBaseMap,
					mergedAliasMap, sharedAccIdToSchemaName);
		}

		List<String> rootSchemaNames = new ArrayList<>(rootSchemaNameSet);
		logger.info("walkAll complete: {} roots ({} unique), {} schemas, {} aliases",
				roots.size(), rootSchemaNames.size(),
				mergedSchemas.size(), mergedAliasMap.size());

		return new SuperTreeResult(rootSchemaNames, mergedSchemas, mergedBaseMap, mergedAliasMap);
	}

	/**
	 * Walk the CC tree starting from an ASCCP entity.
	 */
	public TreeResult walkFromAsccp(AssociationCoreComponentProperty asccp) {
		AggregateCoreComponent roleOfAcc = importedDataProvider.findACC(asccp.getRoleOfAccId());
		String rootName = toCamelCase(asccp.getPropertyTerm());

		Map<String, List<CcNode>> schemas = new LinkedHashMap<>();
		Map<String, String> baseSchemaMap = new LinkedHashMap<>();
		Map<String, String> aliasMap = new LinkedHashMap<>();
		// Track accId -> first schema name to detect duplicate visits
		Map<Long, String> accIdToSchemaName = new HashMap<>();
		walkAcc(roleOfAcc, rootName, schemas, baseSchemaMap, aliasMap, accIdToSchemaName);

		return new TreeResult(rootName, schemas, baseSchemaMap, aliasMap);
	}

	private void walkAcc(AggregateCoreComponent acc, String schemaName,
	                     Map<String, List<CcNode>> schemas,
	                     Map<String, String> baseSchemaMap,
	                     Map<String, String> aliasMap,
	                     Map<Long, String> accIdToSchemaName) {
		String existing = accIdToSchemaName.get(acc.getAccId());
		if (existing != null) {
			// Same ACC already visited under a different schema name — create alias
			if (!existing.equals(schemaName)) {
				aliasMap.put(schemaName, existing);
			}
			return;
		}
		accIdToSchemaName.put(acc.getAccId(), schemaName);

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

			nodes.add(new CcNode(
					kind, propName, bccp.getDefinition(),
					bcc.getCardinalityMin(), bcc.getCardinalityMax(),
					bcc.isNillable(), bcc.getDefaultValue(),
					bccp.getBdtId(), 0, null));
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

			nodes.add(new CcNode(
					CcNode.Kind.ASCC, propName, asccp.getDefinition(),
					ascc.getCardinalityMin(), ascc.getCardinalityMax(),
					false, null,
					0, roleOfAcc.getAccId(), childSchemaName));

			// Recurse into the referenced ACC
			walkAcc(roleOfAcc, childSchemaName, schemas, baseSchemaMap, aliasMap, accIdToSchemaName);
		}

		// Record and recurse into base ACC for allOf composition
		if (acc.getBasedAccId() > 0) {
			AggregateCoreComponent basedAcc = importedDataProvider.findACC(acc.getBasedAccId());
			if (basedAcc != null) {
				String baseSchemaName = toCamelCase(basedAcc.getObjectClassTerm());
				baseSchemaMap.put(schemaName, baseSchemaName);
				walkAcc(basedAcc, baseSchemaName, schemas, baseSchemaMap, aliasMap, accIdToSchemaName);
			}
		}

		// Sort: BCC_ATTRIBUTE first, then remaining nodes in insertion order
		List<CcNode> sorted = Stream.concat(
				nodes.stream().filter(n -> n.getKind() == CcNode.Kind.BCC_ATTRIBUTE),
				nodes.stream().filter(n -> n.getKind() != CcNode.Kind.BCC_ATTRIBUTE)
		).collect(Collectors.toList());

		schemas.put(schemaName, sorted);
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

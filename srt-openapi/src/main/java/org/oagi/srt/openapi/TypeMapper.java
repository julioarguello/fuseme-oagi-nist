package org.oagi.srt.openapi;

import org.oagi.srt.provider.ImportedDataProvider;
import org.oagi.srt.repository.CoreDataTypeAllowedPrimitiveExpressionTypeMapRepository;
import org.oagi.srt.repository.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps OAGIS data types to OpenAPI 3.0 type/format pairs, enriched with
 * {@code enum} values when a BDT's default restriction references a
 * {@link CodeList} or {@link AgencyIdList}.
 *
 * <h3>Resolution chain</h3>
 * <pre>
 * BCCP.bdtId → BDT_PRI_RESTRI (default) →
 *   ├─ codeListId > 0       → string + enum from {@link CodeListValue#getValue()}
 *   ├─ agencyIdListId > 0   → string + enum from {@link AgencyIdListValue#getValue()}
 *   └─ cdtAwdPriXpsTypeMapId → CDT_AWD_PRI_XPS_TYPE_MAP → XBT.builtInType → XSD_TO_OPENAPI
 * </pre>
 *
 * @see TypeResolution
 * @see <a href="https://spec.openapis.org/oas/v3.1.0#schema-object">OAS 3.1.0 Schema Object</a>
 */
@Component
@Lazy
public class TypeMapper {

	private static final Map<String, String[]> XSD_TO_OPENAPI;

	static {
		Map<String, String[]> m = new LinkedHashMap<>();
		// Numeric types
		m.put("xsd:integer",            new String[]{"integer", "int64"});
		m.put("xsd:nonNegativeInteger", new String[]{"integer", "int64"});
		m.put("xsd:positiveInteger",    new String[]{"integer", "int64"});
		m.put("xsd:int",               new String[]{"integer", "int32"});
		m.put("xsd:long",              new String[]{"integer", "int64"});
		m.put("xsd:short",             new String[]{"integer", "int32"});
		m.put("xsd:byte",              new String[]{"integer", "int32"});
		m.put("xsd:decimal",           new String[]{"number", null});
		m.put("xsd:float",             new String[]{"number", "float"});
		m.put("xsd:double",            new String[]{"number", "double"});

		// Boolean
		m.put("xsd:boolean",           new String[]{"boolean", null});

		// Date/time types
		m.put("xsd:date",              new String[]{"string", "date"});
		m.put("xsd:dateTime",          new String[]{"string", "date-time"});
		m.put("xsd:time",              new String[]{"string", "time"});
		m.put("xsd:gYear",             new String[]{"string", null});
		m.put("xsd:gYearMonth",        new String[]{"string", null});
		m.put("xsd:gMonth",            new String[]{"string", null});
		m.put("xsd:gMonthDay",         new String[]{"string", null});
		m.put("xsd:gDay",              new String[]{"string", null});
		m.put("xsd:duration",          new String[]{"string", "duration"});

		// Binary types
		m.put("xsd:base64Binary",      new String[]{"string", "byte"});
		m.put("xsd:hexBinary",         new String[]{"string", null});

		// URI/ID types
		m.put("xsd:anyURI",            new String[]{"string", "uri"});
		m.put("xsd:ID",                new String[]{"string", null});
		m.put("xsd:IDREF",             new String[]{"string", null});

		// String types (all variations)
		m.put("xsd:string",            new String[]{"string", null});
		m.put("xsd:normalizedString",  new String[]{"string", null});
		m.put("xsd:token",             new String[]{"string", null});
		m.put("xsd:language",          new String[]{"string", null});
		m.put("xsd:Name",              new String[]{"string", null});
		m.put("xsd:NCName",            new String[]{"string", null});
		m.put("xsd:NMTOKEN",           new String[]{"string", null});
		m.put("xsd:QName",             new String[]{"string", null});

		XSD_TO_OPENAPI = Collections.unmodifiableMap(m);
	}

	@Autowired
	private ImportedDataProvider importedDataProvider;

	@Autowired
	private CoreDataTypeAllowedPrimitiveExpressionTypeMapRepository cdtAwdPriXpsTypeMapRepository;

	/**
	 * Resolves a BCCP's data type (bdtId) to an OpenAPI {@link TypeResolution}.
	 *
	 * <p>The resolution scans <strong>all</strong> restrictions for the given BDT,
	 * not just the default one. In OAGIS, code list restrictions are stored as
	 * <em>non-default</em> entries ({@code is_default = 0}) in {@code BDT_PRI_RESTRI},
	 * while the default entry points to the CDT primitive type. This method
	 * prioritizes code list / agency ID list hits over the primitive fallback.</p>
	 *
	 * @param bdtId the Business Data Type identifier from {@code BCCP.bdtId}
	 * @return a {@link TypeResolution} carrying type, format, and optional enum values
	 * @see TypeResolution#hasEnum()
	 */
	public TypeResolution resolve(long bdtId) {
		List<BusinessDataTypePrimitiveRestriction> restrictions =
				importedDataProvider.findBdtPriRestriListByDtId(bdtId);

		if (restrictions == null || restrictions.isEmpty()) {
			return enrichWithDataType(new TypeResolution("string", null), bdtId);
		}

		// Priority 1: scan ALL restrictions for a code list (regardless of is_default).
		for (BusinessDataTypePrimitiveRestriction restri : restrictions) {
			if (restri.getCodeListId() > 0) {
				return enrichWithDataType(resolveCodeListEnum(restri.getCodeListId()), bdtId);
			}
		}

		// Priority 2: scan ALL restrictions for an agency ID list.
		for (BusinessDataTypePrimitiveRestriction restri : restrictions) {
			if (restri.getAgencyIdListId() > 0) {
				return enrichWithDataType(resolveAgencyIdListEnum(restri.getAgencyIdListId()), bdtId);
			}
		}

		// Priority 3: fall back to primitive type via the default restriction.
		BusinessDataTypePrimitiveRestriction defaultRestri = restrictions.stream()
				.filter(BusinessDataTypePrimitiveRestriction::isDefault)
				.findFirst()
				.orElse(restrictions.get(0));

		long cdtAwdPriXpsTypeMapId = defaultRestri.getCdtAwdPriXpsTypeMapId();
		if (cdtAwdPriXpsTypeMapId <= 0) {
			return enrichWithDataType(new TypeResolution("string", null), bdtId);
		}

		// Resolve through CDT_AWD_PRI_XPS_TYPE_MAP -> XBT
		List<CoreDataTypeAllowedPrimitiveExpressionTypeMap> maps =
				cdtAwdPriXpsTypeMapRepository.findByCdtAwdPriXpsTypeMapIdIn(
						Collections.singletonList(cdtAwdPriXpsTypeMapId));

		if (maps.isEmpty()) {
			return enrichWithDataType(new TypeResolution("string", null), bdtId);
		}

		long xbtId = maps.get(0).getXbtId();
		XSDBuiltInType xbt = importedDataProvider.findXbt(xbtId);
		if (xbt == null) {
			return enrichWithDataType(new TypeResolution("string", null), bdtId);
		}

		String builtInType = xbt.getBuiltInType();
		if (builtInType == null) {
			return enrichWithDataType(new TypeResolution("string", null), bdtId);
		}

		String[] result = XSD_TO_OPENAPI.get(builtInType);
		if (result != null) {
			return enrichWithDataType(new TypeResolution(result[0], result[1]), bdtId);
		}
		return enrichWithDataType(new TypeResolution("string", null), bdtId);
	}

	/**
	 * Wraps a base {@link TypeResolution} with DataType metadata (definition,
	 * versionNum) and supplementary components looked up from the BDT entity.
	 */
	private TypeResolution enrichWithDataType(TypeResolution base, long bdtId) {
		DataType dt = importedDataProvider.findDT(bdtId);
		if (dt == null) {
			return base;
		}
		String dtDesc = concatDataTypeDescriptions(dt);
		String dtVersion = (dt.getVersionNum() != null && !dt.getVersionNum().isEmpty())
				? dt.getVersionNum() : null;

		// DT qualifier (e.g., "Open" in "Open_ Amount")
		String dtQualifier = (dt.getQualifier() != null && !dt.getQualifier().isEmpty())
				? dt.getQualifier() : null;

		// Content component DEN and definition (separate from the concatenated description)
		String ccDen = (dt.getContentComponentDen() != null && !dt.getContentComponentDen().isEmpty())
				? dt.getContentComponentDen() : null;
		String ccDef = (dt.getContentComponentDefinition() != null
				&& !dt.getContentComponentDefinition().isEmpty())
				? dt.getContentComponentDefinition() : null;

		// Resolve supplementary components (DT_SC) for this BDT
		List<Map<String, Object>> scList = resolveSupplementaryComponents(bdtId);

		if (dtDesc == null && dtVersion == null && scList == null
				&& dtQualifier == null && ccDen == null && ccDef == null) {
			return base;
		}

		return new TypeResolution(
				base.getType(), base.getFormat(),
				base.getEnumValues(), base.getEnumSource(),
				base.getEnumDescriptions(), base.getEnumLabels(),
				base.getEnumSourceDescription(),
				base.getEnumRemark(), base.getEnumDefinitionSource(),
				base.getEnumExtensible(), base.getEnumValueSources(),
				base.getEnumListId(), base.getEnumAgency(), base.getEnumExtensions(),
				dtDesc, dtVersion, scList,
				dtQualifier, ccDen, ccDef);
	}

	/**
	 * Concatenates {@code DataType.definition} and
	 * {@code DataType.contentComponentDefinition} as complementary paragraphs.
	 */
	private String concatDataTypeDescriptions(DataType dt) {
		String def = dt.getDefinition();
		String ccDef = dt.getContentComponentDefinition();
		boolean hasDef = def != null && !def.isEmpty();
		boolean hasCcDef = ccDef != null && !ccDef.isEmpty();

		if (hasDef && hasCcDef) {
			return def + "\n\n" + ccDef;
		}
		if (hasDef) {
			return def;
		}
		if (hasCcDef) {
			return ccDef;
		}
		return null;
	}

	/**
	 * Resolves supplementary components (DT_SC) for a BDT. Each SC becomes
	 * an entry with name, type, description, and required flag.
	 *
	 * @return list of SC maps, or null if no SCs exist
	 */
	private List<Map<String, Object>> resolveSupplementaryComponents(long bdtId) {
		List<DataTypeSupplementaryComponent> scList =
				importedDataProvider.findDtScByOwnerDtId(bdtId);

		if (scList == null || scList.isEmpty()) {
			return null;
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (DataTypeSupplementaryComponent sc : scList) {
			// Skip SCs with max cardinality 0 (not used)
			if (sc.getCardinalityMax() == 0) {
				continue;
			}

			Map<String, Object> scMap = new LinkedHashMap<>();
			scMap.put("name", sc.getPropertyTerm());
			if (sc.getRepresentationTerm() != null && !sc.getRepresentationTerm().isEmpty()) {
				scMap.put("representationTerm", sc.getRepresentationTerm());
			}

			// Resolve SC type via BDT_SC_PRI_RESTRI
			String scType = resolveScType(sc.getDtScId());
			scMap.put("type", scType);

			if (sc.getDefinition() != null && !sc.getDefinition().isEmpty()) {
				scMap.put("description", sc.getDefinition());
			}
			scMap.put("required", sc.getCardinalityMin() >= 1);

			result.add(scMap);
		}

		return result.isEmpty() ? null : result;
	}

	/**
	 * Resolves the OAS type for a supplementary component via BDT_SC_PRI_RESTRI.
	 */
	private String resolveScType(long dtScId) {
		List<BusinessDataTypeSupplementaryComponentPrimitiveRestriction> scRestris =
				importedDataProvider.findBdtScPriRestriListByDtScId(dtScId);

		if (scRestris == null || scRestris.isEmpty()) {
			return "string";
		}

		// Check for code list or agency ID list first
		for (BusinessDataTypeSupplementaryComponentPrimitiveRestriction restri : scRestris) {
			if (restri.getCodeListId() > 0) {
				return "string";
			}
			if (restri.getAgencyIdListId() > 0) {
				return "string";
			}
		}

		// Fall back to CDT_SC primitive type
		BusinessDataTypeSupplementaryComponentPrimitiveRestriction defaultRestri =
				scRestris.stream()
						.filter(BusinessDataTypeSupplementaryComponentPrimitiveRestriction::isDefault)
						.findFirst()
						.orElse(scRestris.get(0));

		long cdtScAwdPriXpsTypeMapId = defaultRestri.getCdtScAwdPriXpsTypeMapId();
		if (cdtScAwdPriXpsTypeMapId <= 0) {
			return "string";
		}

		CoreDataTypeSupplementaryComponentAllowedPrimitiveExpressionTypeMap map =
				importedDataProvider.findCdtScAwdPriXpsTypeMap(cdtScAwdPriXpsTypeMapId);
		if (map == null) {
			return "string";
		}

		long xbtId = map.getXbtId();
		XSDBuiltInType xbt = importedDataProvider.findXbt(xbtId);
		if (xbt == null || xbt.getBuiltInType() == null) {
			return "string";
		}

		String[] oasType = XSD_TO_OPENAPI.get(xbt.getBuiltInType());
		return oasType != null ? oasType[0] : "string";
	}

	/**
	 * Fetches all values from a {@link CodeList} and builds a {@link TypeResolution}
	 * with an {@code enum} array, per-value metadata, and CodeList-level metadata.
	 */
	private TypeResolution resolveCodeListEnum(long codeListId) {
		CodeList codeList = importedDataProvider.findCodeList(codeListId);
		List<CodeListValue> values = importedDataProvider.findCodeListValueByCodeListId(codeListId);

		if (values == null || values.isEmpty()) {
			return new TypeResolution("string", null);
		}

		// Filter to non-empty values that are marked as "used"
		List<CodeListValue> validValues = values.stream()
				.filter(v -> v.getValue() != null && !v.getValue().isEmpty())
				.filter(CodeListValue::isUsedIndicator)
				.collect(Collectors.toList());

		List<String> enumValues = validValues.stream()
				.map(CodeListValue::getValue)
				.collect(Collectors.toList());

		// Per-value definitions from CodeListValue.definition
		Map<String, String> enumDescriptions = new LinkedHashMap<>();
		for (CodeListValue v : validValues) {
			if (v.getDefinition() != null && !v.getDefinition().isEmpty()) {
				enumDescriptions.put(v.getValue(), v.getDefinition());
			}
		}

		// Per-value human-readable labels from CodeListValue.name
		Map<String, String> enumLabels = new LinkedHashMap<>();
		for (CodeListValue v : validValues) {
			if (v.getName() != null && !v.getName().isEmpty()) {
				enumLabels.put(v.getValue(), v.getName());
			}
		}

		// Source with version (B1)
		String source;
		if (codeList != null) {
			source = "CodeList: " + codeList.getName();
			if (codeList.getVersionId() != null && !codeList.getVersionId().isEmpty()) {
				source += " (v" + codeList.getVersionId() + ")";
			}
		} else {
			source = "CodeList ID: " + codeListId;
		}

		String sourceDescription = (codeList != null && codeList.getDefinition() != null
				&& !codeList.getDefinition().isEmpty())
				? codeList.getDefinition() : null;

		// CodeList.remark (C1)
		String remark = (codeList != null && codeList.getRemark() != null
				&& !codeList.getRemark().isEmpty())
				? codeList.getRemark() : null;

		// CodeList.definitionSource (C2)
		String definitionSource = (codeList != null && codeList.getDefinitionSource() != null
				&& !codeList.getDefinitionSource().isEmpty())
				? codeList.getDefinitionSource() : null;

		// CodeList.extensibleIndicator — whether user extensions are allowed
		Boolean extensible = (codeList != null && codeList.isExtensibleIndicator())
				? Boolean.TRUE : null;

		// Per-value definitionSource from CodeListValue.definitionSource
		Map<String, String> enumValueSources = new LinkedHashMap<>();
		for (CodeListValue v : validValues) {
			if (v.getDefinitionSource() != null && !v.getDefinitionSource().isEmpty()) {
				enumValueSources.put(v.getValue(), v.getDefinitionSource());
			}
		}

		// CodeList.listId — external standard identifier (e.g., "ISO 4217")
		String listId = (codeList != null && codeList.getListId() != null
				&& !codeList.getListId().isEmpty())
				? codeList.getListId() : null;

		// CodeList.agencyId — resolve to human-readable agency label
		String agencyLabel = resolveAgencyLabel(codeList);

		// Per-value extensionIndicator from CodeListValue.extensionIndicator
		Map<String, Boolean> enumExtensions = new LinkedHashMap<>();
		for (CodeListValue v : validValues) {
			if (v.isExtensionIndicator()) {
				enumExtensions.put(v.getValue(), Boolean.TRUE);
			}
		}

		return new TypeResolution("string", null, enumValues, source,
				enumDescriptions.isEmpty() ? null : enumDescriptions,
				enumLabels.isEmpty() ? null : enumLabels,
				sourceDescription, remark, definitionSource,
				extensible, enumValueSources.isEmpty() ? null : enumValueSources,
				listId, agencyLabel, enumExtensions.isEmpty() ? null : enumExtensions,
				null, null);
	}

	/**
	 * Resolves the responsible agency label from a CodeList's agencyId.
	 * The agencyId is a foreign key to AgencyIdListValue.
	 */
	private String resolveAgencyLabel(CodeList codeList) {
		if (codeList == null || codeList.getAgencyId() <= 0) {
			return null;
		}
		AgencyIdListValue agencyValue =
				importedDataProvider.findAgencyIdListValue(codeList.getAgencyId());
		if (agencyValue == null) {
			return null;
		}
		// Prefer name (human-readable) over value (code)
		if (agencyValue.getName() != null && !agencyValue.getName().isEmpty()) {
			return agencyValue.getName();
		}
		return agencyValue.getValue();
	}

	/**
	 * Fetches all values from an {@link AgencyIdList} and builds a {@link TypeResolution}
	 * with an {@code enum} array, per-value metadata, and list-level metadata.
	 * Now at parity with {@link #resolveCodeListEnum(long)} (Groups A1-A3, B2).
	 */
	private TypeResolution resolveAgencyIdListEnum(long agencyIdListId) {
		AgencyIdList agencyIdList = importedDataProvider.findAgencyIdList(agencyIdListId);
		List<AgencyIdListValue> values = importedDataProvider.findAgencyIdListValueByOwnerListId(agencyIdListId);

		if (values == null || values.isEmpty()) {
			return new TypeResolution("string", null);
		}

		// Filter to non-empty values
		List<AgencyIdListValue> validValues = values.stream()
				.filter(v -> v.getValue() != null && !v.getValue().isEmpty())
				.collect(Collectors.toList());

		List<String> enumValues = validValues.stream()
				.map(AgencyIdListValue::getValue)
				.collect(Collectors.toList());

		// A2: Per-value definitions from AgencyIdListValue.definition
		Map<String, String> enumDescriptions = new LinkedHashMap<>();
		for (AgencyIdListValue v : validValues) {
			if (v.getDefinition() != null && !v.getDefinition().isEmpty()) {
				enumDescriptions.put(v.getValue(), v.getDefinition());
			}
		}

		// A1: Per-value human-readable labels from AgencyIdListValue.name
		Map<String, String> enumLabels = new LinkedHashMap<>();
		for (AgencyIdListValue v : validValues) {
			if (v.getName() != null && !v.getName().isEmpty()) {
				enumLabels.put(v.getValue(), v.getName());
			}
		}

		// Source with version (B2)
		String source;
		if (agencyIdList != null) {
			source = "AgencyIdList: " + agencyIdList.getName();
			if (agencyIdList.getVersionId() != null && !agencyIdList.getVersionId().isEmpty()) {
				source += " (v" + agencyIdList.getVersionId() + ")";
			}
		} else {
			source = "AgencyIdList ID: " + agencyIdListId;
		}

		// A3: AgencyIdList.definition as source description
		String sourceDescription = (agencyIdList != null && agencyIdList.getDefinition() != null
				&& !agencyIdList.getDefinition().isEmpty())
				? agencyIdList.getDefinition() : null;

		// AgencyIdList.listId — external standard identifier
		String listId = (agencyIdList != null && agencyIdList.getListId() != null
				&& !agencyIdList.getListId().isEmpty())
				? agencyIdList.getListId() : null;

		return new TypeResolution("string", null, enumValues, source,
				enumDescriptions.isEmpty() ? null : enumDescriptions,
				enumLabels.isEmpty() ? null : enumLabels,
				sourceDescription, null, null, null, null,
				listId, null, null,
				null, null);
	}
}

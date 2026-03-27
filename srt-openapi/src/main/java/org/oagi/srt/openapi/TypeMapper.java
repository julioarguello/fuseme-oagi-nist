package org.oagi.srt.openapi;

import org.oagi.srt.provider.ImportedDataProvider;
import org.oagi.srt.repository.CoreDataTypeAllowedPrimitiveExpressionTypeMapRepository;
import org.oagi.srt.repository.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

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
 * <h3>Design rationale for enum injection</h3>
 * <p>OAGIS uses code lists to constrain string fields (e.g., CurrencyCode, CountryCode).
 * By extracting the allowed values at type-resolution time, we produce OpenAPI schemas
 * with {@code enum} arrays that enable client-side validation and documentation tooling
 * (e.g., Redocly) to display allowed values without consulting external references.</p>
 *
 * @see TypeResolution
 * @see <a href="https://spec.openapis.org/oas/v3.0.3#schema-object">OAS 3.0.3 Schema Object</a>
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
	 * <p>The resolution follows the chain documented in this class's Javadoc.
	 * When the default {@link BusinessDataTypePrimitiveRestriction} points to a
	 * {@link CodeList} or {@link AgencyIdList}, the returned resolution includes
	 * the allowed enum values extracted from {@link CodeListValue#getValue()} or
	 * {@link AgencyIdListValue#getValue()} respectively.</p>
	 *
	 * @param bdtId the Business Data Type identifier from {@code BCCP.bdtId}
	 * @return a {@link TypeResolution} carrying type, format, and optional enum values
	 * @see TypeResolution#hasEnum()
	 */
	public TypeResolution resolve(long bdtId) {
		List<BusinessDataTypePrimitiveRestriction> restrictions =
				importedDataProvider.findBdtPriRestriListByDtId(bdtId);

		// Find the default restriction entry
		BusinessDataTypePrimitiveRestriction defaultRestri = restrictions.stream()
				.filter(BusinessDataTypePrimitiveRestriction::isDefault)
				.findFirst()
				.orElse(restrictions.isEmpty() ? null : restrictions.get(0));

		if (defaultRestri == null) {
			return new TypeResolution("string", null);
		}

		// Code list restriction: resolve enum values from CodeListValue entries
		if (defaultRestri.getCodeListId() > 0) {
			return resolveCodeListEnum(defaultRestri.getCodeListId());
		}

		// Agency ID list restriction: resolve enum values from AgencyIdListValue entries
		if (defaultRestri.getAgencyIdListId() > 0) {
			return resolveAgencyIdListEnum(defaultRestri.getAgencyIdListId());
		}

		long cdtAwdPriXpsTypeMapId = defaultRestri.getCdtAwdPriXpsTypeMapId();
		if (cdtAwdPriXpsTypeMapId <= 0) {
			return new TypeResolution("string", null);
		}

		// Resolve through CDT_AWD_PRI_XPS_TYPE_MAP -> XBT
		List<CoreDataTypeAllowedPrimitiveExpressionTypeMap> maps =
				cdtAwdPriXpsTypeMapRepository.findByCdtAwdPriXpsTypeMapIdIn(
						Collections.singletonList(cdtAwdPriXpsTypeMapId));

		if (maps.isEmpty()) {
			return new TypeResolution("string", null);
		}

		long xbtId = maps.get(0).getXbtId();
		XSDBuiltInType xbt = importedDataProvider.findXbt(xbtId);
		if (xbt == null) {
			return new TypeResolution("string", null);
		}

		String builtInType = xbt.getBuiltInType();
		if (builtInType == null) {
			return new TypeResolution("string", null);
		}

		String[] result = XSD_TO_OPENAPI.get(builtInType);
		if (result != null) {
			return new TypeResolution(result[0], result[1]);
		}
		return new TypeResolution("string", null);
	}

	/**
	 * Fetches all values from a {@link CodeList} and builds a {@link TypeResolution}
	 * with an {@code enum} array.
	 *
	 * <p>Values are extracted from {@link CodeListValue#getValue()}, which holds the
	 * code itself (e.g. {@code "USD"}, {@code "EUR"} for a currency code list).</p>
	 *
	 * @param codeListId the code list identifier from {@link BusinessDataTypePrimitiveRestriction#getCodeListId()}
	 * @return a string-typed resolution with enum values, or plain string if no values found
	 * @see ImportedDataProvider#findCodeListValueByCodeListId(long)
	 */
	private TypeResolution resolveCodeListEnum(long codeListId) {
		CodeList codeList = importedDataProvider.findCodeList(codeListId);
		List<CodeListValue> values = importedDataProvider.findCodeListValueByCodeListId(codeListId);

		if (values == null || values.isEmpty()) {
			return new TypeResolution("string", null);
		}

		List<String> enumValues = values.stream()
				.map(CodeListValue::getValue)
				.filter(v -> v != null && !v.isEmpty())
				.collect(Collectors.toList());

		String source = codeList != null
				? "CodeList: " + codeList.getName()
				: "CodeList ID: " + codeListId;

		return new TypeResolution("string", null, enumValues, source);
	}

	/**
	 * Fetches all values from an {@link AgencyIdList} and builds a {@link TypeResolution}
	 * with an {@code enum} array.
	 *
	 * <p>Values are extracted from {@link AgencyIdListValue#getValue()}, which holds the
	 * agency identifier code (e.g. {@code "6"} for UN/CEFACT, {@code "16"} for DUNS).</p>
	 *
	 * @param agencyIdListId the agency ID list identifier from {@link BusinessDataTypePrimitiveRestriction#getAgencyIdListId()}
	 * @return a string-typed resolution with enum values, or plain string if no values found
	 * @see ImportedDataProvider#findAgencyIdListValueByOwnerListId(long)
	 */
	private TypeResolution resolveAgencyIdListEnum(long agencyIdListId) {
		AgencyIdList agencyIdList = importedDataProvider.findAgencyIdList(agencyIdListId);
		List<AgencyIdListValue> values = importedDataProvider.findAgencyIdListValueByOwnerListId(agencyIdListId);

		if (values == null || values.isEmpty()) {
			return new TypeResolution("string", null);
		}

		List<String> enumValues = values.stream()
				.map(AgencyIdListValue::getValue)
				.filter(v -> v != null && !v.isEmpty())
				.collect(Collectors.toList());

		String source = agencyIdList != null
				? "AgencyIdList: " + agencyIdList.getName()
				: "AgencyIdList ID: " + agencyIdListId;

		return new TypeResolution("string", null, enumValues, source);
	}
}

package org.oagi.srt.openapi;

import org.oagi.srt.provider.ImportedDataProvider;
import org.oagi.srt.repository.CoreDataTypeAllowedPrimitiveExpressionTypeMapRepository;
import org.oagi.srt.repository.entity.BusinessDataTypePrimitiveRestriction;
import org.oagi.srt.repository.entity.CoreDataTypeAllowedPrimitiveExpressionTypeMap;
import org.oagi.srt.repository.entity.XSDBuiltInType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps OAGIS data types to OpenAPI 3.0 type/format pairs.
 *
 * Resolution chain: BCCP.bdtId -> BDT_PRI_RESTRI (default) -> CDT_AWD_PRI_XPS_TYPE_MAP -> XBT.builtInType
 * Then XBT builtInType is matched against a hardcoded XSD-to-OpenAPI mapping table.
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
		m.put("xsd:decimal",           new String[]{"number", "double"});
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
	 * Resolves a BCCP's data type (bdtId) to an OpenAPI type/format pair.
	 *
	 * @return String[2] where [0]=type, [1]=format (nullable)
	 */
	public String[] resolve(long bdtId) {
		List<BusinessDataTypePrimitiveRestriction> restrictions =
				importedDataProvider.findBdtPriRestriListByDtId(bdtId);

		// Find the default restriction entry
		BusinessDataTypePrimitiveRestriction defaultRestri = restrictions.stream()
				.filter(BusinessDataTypePrimitiveRestriction::isDefault)
				.findFirst()
				.orElse(restrictions.isEmpty() ? null : restrictions.get(0));

		if (defaultRestri == null) {
			return new String[]{"string", null};
		}

		// Code list or agency ID list restrictions map to string with enum semantics
		if (defaultRestri.getCodeListId() > 0 || defaultRestri.getAgencyIdListId() > 0) {
			return new String[]{"string", null};
		}

		long cdtAwdPriXpsTypeMapId = defaultRestri.getCdtAwdPriXpsTypeMapId();
		if (cdtAwdPriXpsTypeMapId <= 0) {
			return new String[]{"string", null};
		}

		// Resolve through CDT_AWD_PRI_XPS_TYPE_MAP -> XBT
		List<CoreDataTypeAllowedPrimitiveExpressionTypeMap> maps =
				cdtAwdPriXpsTypeMapRepository.findByCdtAwdPriXpsTypeMapIdIn(
						Collections.singletonList(cdtAwdPriXpsTypeMapId));

		if (maps.isEmpty()) {
			return new String[]{"string", null};
		}

		long xbtId = maps.get(0).getXbtId();
		XSDBuiltInType xbt = importedDataProvider.findXbt(xbtId);
		if (xbt == null) {
			return new String[]{"string", null};
		}

		String builtInType = xbt.getBuiltInType();
		if (builtInType == null) {
			return new String[]{"string", null};
		}

		String[] result = XSD_TO_OPENAPI.get(builtInType);
		return (result != null) ? result : new String[]{"string", null};
	}
}

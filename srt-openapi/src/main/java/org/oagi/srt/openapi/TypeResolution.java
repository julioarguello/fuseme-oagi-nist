package org.oagi.srt.openapi;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable value object representing the result of resolving an OAGIS data type (BDT)
 * to an OpenAPI 3.1.0 type/format pair, optionally carrying {@code enum} values and
 * per-value metadata when the BDT's default restriction references a
 * {@link org.oagi.srt.repository.entity.CodeList} or
 * {@link org.oagi.srt.repository.entity.AgencyIdList}.
 *
 * <p>This replaces the previous {@code String[2]} return type from
 * {@link TypeMapper#resolve(long)}, which could not carry enum metadata.</p>
 *
 * <h3>Why enum values belong here</h3>
 * <p>The enum constraint is a property of the <em>data type</em>, not the property itself.
 * A BDT whose default restriction points to a code list always carries the same allowed
 * values regardless of which BCCP references it. Attaching enums at resolution time
 * keeps the {@link OpenApiSchemaBuilder} free of type-system knowledge.</p>
 *
 * @see TypeMapper#resolve(long)
 * @see org.oagi.srt.repository.entity.CodeListValue#getValue()
 * @see org.oagi.srt.repository.entity.AgencyIdListValue#getValue()
 */
public final class TypeResolution {

	private final String type;
	private final String format;
	private final List<String> enumValues;
	private final String enumSource;
	private final Map<String, String> enumDescriptions;
	private final Map<String, String> enumLabels;
	private final String enumSourceDescription;
	private final String enumRemark;
	private final String enumDefinitionSource;
	private final Boolean enumExtensible;
	private final Map<String, String> enumValueSources;
	private final String enumListId;
	private final String enumAgency;
	private final Map<String, Boolean> enumExtensions;
	private final String dataTypeDescription;
	private final String dataTypeVersion;
	private final List<Map<String, Object>> supplementaryComponents;
	private final String dataTypeQualifier;
	private final String contentComponentDen;
	private final String contentComponentDefinition;

	/**
	 * Full constructor with all metadata fields.
	 */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource,
	                      Map<String, String> enumDescriptions, Map<String, String> enumLabels,
	                      String enumSourceDescription, String enumRemark, String enumDefinitionSource,
	                      Boolean enumExtensible, Map<String, String> enumValueSources,
	                      String enumListId, String enumAgency, Map<String, Boolean> enumExtensions,
	                      String dataTypeDescription, String dataTypeVersion,
	                      List<Map<String, Object>> supplementaryComponents,
	                      String dataTypeQualifier, String contentComponentDen,
	                      String contentComponentDefinition) {
		this.type = type;
		this.format = format;
		this.enumValues = enumValues != null ? Collections.unmodifiableList(enumValues) : null;
		this.enumSource = enumSource;
		this.enumDescriptions = enumDescriptions != null ? Collections.unmodifiableMap(enumDescriptions) : null;
		this.enumLabels = enumLabels != null ? Collections.unmodifiableMap(enumLabels) : null;
		this.enumSourceDescription = enumSourceDescription;
		this.enumRemark = enumRemark;
		this.enumDefinitionSource = enumDefinitionSource;
		this.enumExtensible = enumExtensible;
		this.enumValueSources = enumValueSources != null ? Collections.unmodifiableMap(enumValueSources) : null;
		this.enumListId = enumListId;
		this.enumAgency = enumAgency;
		this.enumExtensions = enumExtensions != null ? Collections.unmodifiableMap(enumExtensions) : null;
		this.dataTypeDescription = dataTypeDescription;
		this.dataTypeVersion = dataTypeVersion;
		this.supplementaryComponents = supplementaryComponents != null
				? Collections.unmodifiableList(supplementaryComponents) : null;
		this.dataTypeQualifier = dataTypeQualifier;
		this.contentComponentDen = contentComponentDen;
		this.contentComponentDefinition = contentComponentDefinition;
	}

	/** Constructor with enum and DataType metadata but no SC/qualifier/contentComponent. */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource,
	                      Map<String, String> enumDescriptions, Map<String, String> enumLabels,
	                      String enumSourceDescription, String enumRemark, String enumDefinitionSource,
	                      Boolean enumExtensible, Map<String, String> enumValueSources,
	                      String enumListId, String enumAgency, Map<String, Boolean> enumExtensions,
	                      String dataTypeDescription, String dataTypeVersion) {
		this(type, format, enumValues, enumSource, enumDescriptions, enumLabels,
				enumSourceDescription, enumRemark, enumDefinitionSource,
				enumExtensible, enumValueSources,
				enumListId, enumAgency, enumExtensions,
				dataTypeDescription, dataTypeVersion, null,
				null, null, null);
	}

	/** Constructor with enum and DataType metadata (no remark/definitionSource/SC). */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource,
	                      Map<String, String> enumDescriptions, Map<String, String> enumLabels,
	                      String enumSourceDescription,
	                      String dataTypeDescription, String dataTypeVersion) {
		this(type, format, enumValues, enumSource, enumDescriptions, enumLabels,
				enumSourceDescription, null, null, null, null,
				null, null, null,
				dataTypeDescription, dataTypeVersion, null,
				null, null, null);
	}

	/** Constructor for enum values with per-value metadata but no DataType info. */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource,
	                      Map<String, String> enumDescriptions, Map<String, String> enumLabels,
	                      String enumSourceDescription) {
		this(type, format, enumValues, enumSource, enumDescriptions, enumLabels,
				enumSourceDescription, null, null, null, null,
				null, null, null,
				null, null, null,
				null, null, null);
	}

	/** Backward-compatible constructor for enum values without per-value metadata. */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource) {
		this(type, format, enumValues, enumSource, null, null, null, null, null, null, null,
				null, null, null,
				null, null, null,
				null, null, null);
	}

	/** Convenience constructor for simple type/format pairs without enum constraints. */
	public TypeResolution(String type, String format) {
		this(type, format, null, null, null, null, null, null, null, null, null,
				null, null, null,
				null, null, null,
				null, null, null);
	}

	public String getType() { return type; }

	public String getFormat() { return format; }

	/**
	 * Returns the list of allowed enum values, or {@code null} if no code list restriction applies.
	 */
	public List<String> getEnumValues() { return enumValues; }

	/**
	 * Human-readable description of the enum source for traceability, e.g.
	 * {@code "CodeList: oacl_CurrencyCode (v1)"}.
	 */
	public String getEnumSource() { return enumSource; }

	/**
	 * Per-value definitions from CodeListValue/AgencyIdListValue definition.
	 * Map keys are enum values, map values are their definitions.
	 */
	public Map<String, String> getEnumDescriptions() { return enumDescriptions; }

	/**
	 * Per-value human-readable labels from CodeListValue/AgencyIdListValue name.
	 * Map keys are enum values, map values are display names.
	 */
	public Map<String, String> getEnumLabels() { return enumLabels; }

	/** Definition of the CodeList/AgencyIdList itself, for property-level description enrichment. */
	public String getEnumSourceDescription() { return enumSourceDescription; }

	/** Additional remarks about the code list (CodeList.remark). */
	public String getEnumRemark() { return enumRemark; }

	/** Source of the code list definition (CodeList.definitionSource). */
	public String getEnumDefinitionSource() { return enumDefinitionSource; }

	/** Whether the code list accepts user-defined extension values (CodeList.extensibleIndicator). */
	public Boolean getEnumExtensible() { return enumExtensible; }

	/**
	 * Per-value attribution from CodeListValue.definitionSource.
	 * Map keys are enum values, map values are definition sources.
	 */
	public Map<String, String> getEnumValueSources() { return enumValueSources; }

	/** External standard identifier from CodeList.listId or AgencyIdList.listId. */
	public String getEnumListId() { return enumListId; }

	/** Responsible agency label (resolved from CodeList.agencyId via AgencyIdListValue). */
	public String getEnumAgency() { return enumAgency; }

	/**
	 * Per-value extension indicators from CodeListValue.extensionIndicator.
	 * Map keys are enum values, map values are true for user-defined extensions.
	 */
	public Map<String, Boolean> getEnumExtensions() { return enumExtensions; }

	/** Concatenated DataType.definition + DataType.contentComponentDefinition. */
	public String getDataTypeDescription() { return dataTypeDescription; }

	/** DataType.versionNum for x-version emission. */
	public String getDataTypeVersion() { return dataTypeVersion; }

	/**
	 * Supplementary components (DT_SC) for the data type (e.g., currencyCode on Amount).
	 * Each map contains: name, type, description, required.
	 */
	public List<Map<String, Object>> getSupplementaryComponents() { return supplementaryComponents; }

	/** CCTS qualifier from DataType.qualifier (e.g., "Open" in "Open_ Amount"). */
	public String getDataTypeQualifier() { return dataTypeQualifier; }

	/** DEN of the DataType content component (DataType.contentComponentDen). */
	public String getContentComponentDen() { return contentComponentDen; }

	/** Separate definition for the DataType content component (DataType.contentComponentDefinition). */
	public String getContentComponentDefinition() { return contentComponentDefinition; }

	/** Returns {@code true} when this resolution carries enum constraints. */
	public boolean hasEnum() { return enumValues != null && !enumValues.isEmpty(); }
}

package org.oagi.srt.openapi;

import java.util.Collections;
import java.util.List;

/**
 * Immutable value object representing the result of resolving an OAGIS data type (BDT)
 * to an OpenAPI 3.0 type/format pair, optionally carrying {@code enum} values when the
 * BDT's default restriction references a {@link org.oagi.srt.repository.entity.CodeList}
 * or {@link org.oagi.srt.repository.entity.AgencyIdList}.
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

	/**
	 * @param type       OAS type (e.g. {@code "string"}, {@code "number"})
	 * @param format     nullable OAS format (e.g. {@code "date-time"}, {@code "double"})
	 * @param enumValues nullable list of allowed values from a code list restriction
	 * @param enumSource nullable human-readable origin (e.g. {@code "CodeList: oacl_CurrencyCode"})
	 */
	public TypeResolution(String type, String format, List<String> enumValues, String enumSource) {
		this.type = type;
		this.format = format;
		this.enumValues = enumValues != null ? Collections.unmodifiableList(enumValues) : null;
		this.enumSource = enumSource;
	}

	/** Convenience constructor for simple type/format pairs without enum constraints. */
	public TypeResolution(String type, String format) {
		this(type, format, null, null);
	}

	public String getType() { return type; }

	public String getFormat() { return format; }

	/**
	 * Returns the list of allowed enum values, or {@code null} if no code list restriction applies.
	 * Values come from {@link org.oagi.srt.repository.entity.CodeListValue#getValue()} or
	 * {@link org.oagi.srt.repository.entity.AgencyIdListValue#getValue()}.
	 */
	public List<String> getEnumValues() { return enumValues; }

	/**
	 * Human-readable description of the enum source for traceability, e.g.
	 * {@code "CodeList: oacl_CurrencyCode"} or {@code "AgencyIdList: oagis_AgencyIdentification"}.
	 */
	public String getEnumSource() { return enumSource; }

	/** Returns {@code true} when this resolution carries enum constraints. */
	public boolean hasEnum() { return enumValues != null && !enumValues.isEmpty(); }
}

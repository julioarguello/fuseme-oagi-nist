# srt-openapi — CC-to-OpenAPI 3.1 Generator

Generates [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0) schemas from OAGIS **Core Components (CC)** stored in a MariaDB/MySQL database. The module walks the CC tree (ASCCP → ACC → BCC/ASCC) and produces a fully valid, lint-clean YAML specification.

## Key Features

- **Full CC tree traversal** — recursive walk from a root ASCCP through ACCs, BCCs, and ASCCs
- **`allOf` composition** — ACC inheritance via `based_acc_id` emitted as `allOf` references
- **Alias detection** — identical ACCs referenced under different ASCCP names produce thin `allOf` wrappers instead of duplicate schemas
- **XSD-to-OpenAPI type mapping** — 30+ XSD built-in types resolved through the BDT → CDT → XBT chain
- **Enum generation** — automatic `enum` arrays from `CodeList` and `AgencyIdList` restrictions, with per-value descriptions, labels, and CodeList provenance
- **Exhaustive description enrichment** — concatenated definitions from BCCP+BCC, ASCCP+ASCC, ACC qualifiers, and DataType definitions for maximum semantic density
- **Schema-level metadata** — `deprecated`, `x-abstract`, discriminator patterns, and `x-version` extensions
- **Property-level metadata** — `default` values, nullable types (OAS 3.1.0 `type: [T, "null"]`), and bounded array constraints
- **CRUD-style paths** — auto-generated REST endpoints referencing the root schema for documentation renderers (Redocly, Swagger UI)

---

## Tech Stack

| Layer        | Technology                                  |
| :----------- | :------------------------------------------ |
| **Language** | Java 8 (JDK 1.8.0_211)                     |
| **Framework**| Spring Boot 1.5.22.RELEASE                  |
| **ORM**      | Hibernate 5 / JPA 2.1                       |
| **Database** | MariaDB 10.6 (`--lower_case_table_names=1`) |
| **YAML**     | Jackson `jackson-dataformat-yaml`           |
| **Build**    | Maven 3.x                                   |

---

## Prerequisites

- **JDK 1.8** — specifically `jdk1.8.0_211` (referenced as `$JAVA_HOME`)
- **Maven 3.x**
- **MariaDB 10.6** running on `127.0.0.1:3306` with database `oagi` populated by `srt-import`
- **Node.js / npx** (optional, for Redocly linting and HTML docs)

---

## Getting Started

### 1. Database Setup

The generator reads from a MariaDB database pre-populated by the `srt-import` module. If you haven't done so already:

```bash
# Start MariaDB with case-insensitive table names
docker run -d --name oagi-mariadb \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=oagi \
  -e MYSQL_USER=oagi \
  -e MYSQL_PASSWORD=oagi \
  -p 3306:3306 \
  mariadb:10.6 --lower_case_table_names=1

# Then run the srt-import data population
# (see ../docs/oagi-how-to-populate-cc-tables.md)
```

### 2. Set JAVA_HOME

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_211.jdk/Contents/Home
```

### 3. Compile

```bash
cd /path/to/fuseme-oagi-nist
mvn -pl srt-openapi compile -q
```

> **Note:** Use `-pl srt-openapi` (without `-am`) to avoid dependency resolution issues from sibling modules. The `srt-import` classes must already be compiled in `srt-import/target/classes`.

### 4. Build the Classpath

```bash
CLASSPATH="$(mvn -pl srt-openapi dependency:build-classpath -q \
  -DincludeScope=compile -Dmdep.outputFile=/dev/stdout):\
srt-openapi/target/classes:srt-import/target/classes:$JAVA_HOME/lib/tools.jar"
```

### 5. Run the Generator

```bash
$JAVA_HOME/bin/java -cp "$CLASSPATH" \
  -Dspring.profiles.active=generate-openapi \
  -Dspring.datasource.url="jdbc:mysql://127.0.0.1:3306/oagi?useSSL=false&allowPublicKeyRetrieval=true" \
  -Dspring.datasource.username=oagi \
  -Dspring.datasource.password=oagi \
  -Dspring.datasource.driver-class-name=com.mysql.jdbc.Driver \
  -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL5InnoDBDialect \
  org.oagi.srt.openapi.OpenApiApplication
```

The YAML will be written to `./openapi-output/<RootSchema>.openapi.yaml`.

### 6. Configure a Different Noun

Override the default ASCCP and output directory via system properties:

```bash
$JAVA_HOME/bin/java -cp "$CLASSPATH" \
  -Dspring.profiles.active=generate-openapi \
  -Dopenapi.asccp="Invoice" \
  -Dopenapi.output=./my-output \
  # ... datasource properties ...
  org.oagi.srt.openapi.OpenApiApplication
```

| Property           | Default              | Description                                  |
| :----------------- | :------------------- | :------------------------------------------- |
| `openapi.asccp`    | `Purchase Order`     | ASCCP property term (the root business noun)  |
| `openapi.output`   | `./openapi-output`   | Directory where the YAML file is written      |

---

## Architecture

### Pipeline Overview

```
ASCCP name ──► CcTreeWalker ──► TreeResult ──► OpenApiSchemaBuilder ──► YAML
                  (DB queries)     (schemas,       (OpenAPI doc)
                                    aliases,
                                    base map)
```

### Directory Structure

```
srt-openapi/
├── pom.xml                              # Maven module descriptor
└── src/main/
    ├── java/org/oagi/srt/openapi/
    │   ├── OpenApiApplication.java      # Spring Boot entry point
    │   ├── GenerateOpenApiCommand.java  # CLI runner (profile: generate-openapi)
    │   ├── CcOpenApiGenerator.java      # Pipeline orchestrator
    │   ├── CcTreeWalker.java            # Recursive CC tree traversal
    │   ├── OpenApiSchemaBuilder.java     # TreeResult → OpenAPI 3.1.0 Map
    │   └── TypeMapper.java              # XSD → OpenAPI type/format resolution
    └── resources/
        └── application.yml              # Default datasource config
```

### Component Responsibilities

| Class                      | Purpose                                                                   |
| :------------------------- | :------------------------------------------------------------------------ |
| `OpenApiApplication`       | Spring Boot bootstrap with entity scan for `org.oagi.srt.repository`      |
| `GenerateOpenApiCommand`   | `CommandLineRunner` activated by profile `generate-openapi`               |
| `CcOpenApiGenerator`       | Orchestrates walk → build → YAML serialization pipeline                   |
| `CcTreeWalker`             | Walks ASCCP → ACC → (BCC + ASCC children); detects aliases and cycles     |
| `OpenApiSchemaBuilder`     | Converts `TreeResult` into OpenAPI doc with `allOf` composition and paths |
| `TypeMapper`               | Resolves BDT → CDT → XBT chain to `TypeResolution`; enriches with DataType metadata |
| `TypeResolution`           | Immutable value object: type, format, enums, per-value metadata, DataType description, version |

### CC Tree Traversal (CcTreeWalker)

1. Looks up the root ASCCP by property term
2. Resolves its `roleOfAccId` to the root ACC
3. For each ACC:
   - Collects BCC children (primitive properties)
   - Collects ASCC children (association properties)
   - Sorts by `seqKey` for deterministic output
   - Recurses into ASCC → ASCCP → role-of-ACC
4. **Cycle detection**: tracks `accId` → first schema name. Subsequent references to the same ACC under a different ASCCP name are recorded as **aliases** (`aliasMap`)
5. **Inheritance**: if an ACC has a `basedAccId`, the relationship is recorded in `baseSchemaMap`

### Type Resolution (TypeMapper → TypeResolution)

```
bdtId → BDT_PRI_RESTRI (default)
  ├─ codeListId > 0       → string + enum[] from CodeListValue.getValue()
  ├─ agencyIdListId > 0   → string + enum[] from AgencyIdListValue.getValue()
  └─ cdtAwdPriXpsTypeMapId → CDT_AWD_PRI_XPS_TYPE_MAP → XBT.builtInType
                                                              ↓
                                                   XSD_TO_OPENAPI table
                                                   (xsd:decimal → number)
```

See [Type Mapping Reference](#type-mapping-reference) and [Enum Generation](#enum-generation) for details.

---

## Type Mapping Reference

The generator resolves OAGIS data types to OpenAPI `type`/`format` pairs through a multi-table chain defined by the NIST Core Component specification.

### Resolution Chain

```
BCCP.bdtId
  → BDT_PRI_RESTRI (isDefault=true)
    → CDT_AWD_PRI_XPS_TYPE_MAP
      → XBT.builtInType
        → XSD_TO_OPENAPI (hardcoded in TypeMapper.java)
```

**Source of truth**: `P_1_2_PopulateCDTandCDTSC.java` populates the CDT → CDT Primitive → XBT Expression Type Map relationships during database import.

### CDT Default Primitives (from NIST import)

Each Core Data Type has a single **default** CDT Primitive that determines its canonical XSD expression type:

| CDT          | Default Primitive | XBT Expression    | OAS type   | OAS format  |
|:-------------|:------------------|:-------------------|:-----------|:------------|
| Amount       | Decimal           | `xsd:decimal`      | `number`   | —           |
| Measure      | Decimal           | `xsd:decimal`      | `number`   | —           |
| Quantity     | Decimal           | `xsd:decimal`      | `number`   | —           |
| Number       | Decimal           | `xsd:decimal`      | `number`   | —           |
| Percent      | Decimal           | `xsd:decimal`      | `number`   | —           |
| Rate         | Decimal           | `xsd:decimal`      | `number`   | —           |
| Ratio        | Decimal           | `xsd:decimal`      | `number`   | —           |
| Value        | Decimal           | `xsd:decimal`      | `number`   | —           |
| Ordinal      | Integer           | `xsd:integer`      | `integer`  | `int64`     |
| Indicator    | Boolean           | `xbt_BooleanType`  | `boolean`  | —           |
| Code         | Token             | `xsd:token`        | `string`   | —           |
| Identifier   | Token             | `xsd:token`        | `string`   | —           |
| Name         | Token             | `xsd:token`        | `string`   | —           |
| Text         | String            | `xsd:string`       | `string`   | —           |
| Date         | TimePoint         | `xsd:date`         | `string`   | `date`      |
| Date Time    | TimePoint         | `xsd:dateTime`     | `string`   | `date-time` |
| Time         | TimePoint         | `xsd:time`         | `string`   | `time`      |
| Duration     | TimeDuration      | `xsd:duration`     | `string`   | `duration`  |
| Binary Object| Binary            | `xsd:base64Binary` | `string`   | `byte`      |

### XSD → OpenAPI Mapping Table

| XSD Built-in Type       | OAS `type`  | OAS `format`   | Rationale |
|:------------------------|:------------|:---------------|:----------|
| `xsd:integer`           | `integer`   | `int64`        | Arbitrary-precision integer; `int64` covers all practical OAGIS values |
| `xsd:nonNegativeInteger`| `integer`   | `int64`        | Same as integer with ≥ 0 constraint (enforce via `minimum: 0`) |
| `xsd:positiveInteger`   | `integer`   | `int64`        | Same with > 0 constraint (enforce via `minimum: 1, exclusiveMinimum: true`) |
| `xsd:decimal`           | `number`    | —              | **Arbitrary-precision decimal**; no format = unconstrained per OAS spec |
| `xsd:double`            | `number`    | `double`       | IEEE 754 64-bit floating point |
| `xsd:float`             | `number`    | `float`        | IEEE 754 32-bit floating point |
| `xsd:boolean`           | `boolean`   | —              | Direct mapping |
| `xsd:date`              | `string`    | `date`         | RFC 3339 `full-date` |
| `xsd:dateTime`          | `string`    | `date-time`    | RFC 3339 `date-time` |
| `xsd:time`              | `string`    | `time`         | `HH:MM:SS` (no OAS standard; custom format) |
| `xsd:duration`          | `string`    | `duration`     | ISO 8601 duration (custom format) |
| `xsd:base64Binary`      | `string`    | `byte`         | OAS standard for base64-encoded content |
| `xsd:string`            | `string`    | —              | Direct mapping |
| `xsd:token`             | `string`    | —              | Whitespace-normalized string |
| `xsd:normalizedString`  | `string`    | —              | Single-line string |

### Key Design Decision: `xsd:decimal` → `number` (no format)

`xsd:decimal` represents arbitrary-precision decimal numbers (analogous to Java's `BigDecimal`). The NIST import code (`Utility.checkCorrespondingTypes()`) explicitly treats `Decimal` and `Double` as distinct CDT Primitives.

In OpenAPI 3.1:
- `type: number, format: double` = IEEE 754 64-bit (~15 significant digits)
- `type: number, format: float` = IEEE 754 32-bit (~7 significant digits)
- `type: number` (no format) = **unconstrained numeric precision**

Since Amount, Measure, Quantity, and other financial types default to `Decimal` → `xsd:decimal`, constraining to `double` would lose precision for values like `12345678901234.99`. Using `number` without format preserves the semantic intent of the OAGIS specification.

---

## Enum Generation

When a BDT's default restriction references a **CodeList** or **AgencyIdList** (rather than a CDT Primitive), the generator extracts all allowed values and emits them as OpenAPI `enum` arrays with rich per-value metadata.

### Resolution Chain

```
bdtId -> BDT_PRI_RESTRI (all restrictions scanned)
  |-- codeListId > 0
  |    -> CodeList (name, definition, remark, definitionSource, versionId, extensibleIndicator,
  |    |            listId, agencyId)
  |    |    +-- .getListId()    -> x-enum-list-id: "oacl_CurrencyCode"
  |    |    +-- .getAgencyId()  -> AgencyIdListValue.getName() -> x-enum-agency: "UN/CEFACT"
  |    |
  |    -> CodeListValue[] (filtered: usedIndicator=true only)
  |         |-- .getValue()              -> enum: ["USD", "EUR", ...]
  |         |-- .getDefinition()         -> x-enum-descriptions: {USD: "...", EUR: "..."}
  |         |-- .getName()               -> x-enum-labels: {USD: "US Dollar", EUR: "Euro"}
  |         |-- .getDefinitionSource()   -> x-enum-value-sources: {USD: "ISO 4217", ...}
  |         +-- .getExtensionIndicator() -> x-enum-extensions: {ZZZ: true}
  |
  +-- agencyIdListId > 0  (full parity with CodeList)
       -> AgencyIdList (name, definition, versionId, listId)
       |    +-- .getListId() -> x-enum-list-id: "oagis-id-..."
       -> AgencyIdListValue[]
            |-- .getValue()      -> enum: ["6", "16", ...]
            |-- .getDefinition() -> x-enum-descriptions: {6: "...", 16: "..."}
            +-- .getName()       -> x-enum-labels: {6: "DUNS", 16: "EAN"}
```

### Implementation

| Class            | Responsibility |
|:-----------------|:---------------|
| `TypeMapper`     | Detects code list restrictions in `BDT_PRI_RESTRI`, fetches values and per-value metadata via `ImportedDataProvider`. Resolves DT_SC supplementary components. |
| `TypeResolution`  | Carries `enumValues`, `enumSource`, `enumDescriptions`, `enumLabels`, `enumSourceDescription`, `enumRemark`, `enumDefinitionSource`, `enumListId`, `enumAgency`, `enumExtensions`, `dataTypeDescription`, `dataTypeVersion`, `supplementaryComponents` |
| `OpenApiSchemaBuilder` | Injects all `enum`/`x-enum-*` extensions, DataType metadata, CCTS metadata, namespace, GUID/DEN |

### Example Output

A field constrained by the OAGIS Currency Code List would generate:

```yaml
currencyCode:
  description: >-
    The currency for this amount.

    A character string that constitutes the recognized code designator of the currency.
  type: string
  enum: [AED, AFN, ALL, AMD, ANG, ...]   # only usedIndicator=true values
  x-enum-source: "CodeList: oacl_CurrencyCode (v1)"
  x-enum-list-id: "oacl_CurrencyCode"
  x-enum-agency: "UN/CEFACT"
  x-enum-descriptions:
    AED: "United Arab Emirates Dirham"
    AFN: "Afghani"
  x-enum-labels:
    AED: "UAE Dirham"
    AFN: "Afghani"
  x-enum-remark: "Based on ISO 4217"
  x-enum-definition-source: "UN/ECE Rec 9"
  x-enum-extensible: true
  x-enum-value-sources:
    AED: "ISO 4217"
    AFN: "ISO 4217"
  x-enum-extensions:
    ZZZ: true
  x-component-type: BCC
  x-entity-type: Element
  x-representation-term: Code
  x-guid: "oagis-id-abc123..."
  x-den: "Currency. Code"
```

### Design Rationale

- **Enum at the type level, not the property level**: The allowed values are a property of the data type (BDT), not the individual BCCP. Every field referencing the same BDT gets the same enum constraint.
- **Full AgencyIdList parity**: `AgencyIdListValue.name` → `x-enum-labels`, `.definition` → `x-enum-descriptions`, and `AgencyIdList.definition` → property description, matching CodeList behavior.
- **Per-value metadata**: `x-enum-descriptions` and `x-enum-labels` enable documentation tooling to display human-readable names and definitions alongside code values.
- **CodeList provenance**: The `CodeList.definition` is appended to the property description as a complementary paragraph, providing context about the code list itself.
- **Source traceability**: `x-enum-source` preserves the origin with version (e.g., `"CodeList: oacl_CurrencyCode (v1)"`) for debugging and auditing.
- **Active-value filtering**: `CodeListValue.usedIndicator` controls which values appear in the `enum` array, ensuring unused legacy codes are excluded from consumers.
- **Standard identifier**: `x-enum-list-id` exposes the external code list identifier (e.g., `oacl_CurrencyCode`) for cross-referencing with OAGIS standards.
- **Agency attribution**: `x-enum-agency` resolves the maintaining agency from the FK chain (`CodeList.agencyId` -> `AgencyIdListValue.name`), e.g., `"UN/CEFACT"`.
- **Extension flags**: `x-enum-extensions` marks user-defined code values, enabling consumers to distinguish standard from extended entries.

---

## Schema Enrichment

The generator exhaustively extracts semantic metadata from OAGIS database entities to produce maximally rich OpenAPI schemas. Every field below is sourced from database records, never synthesized.

### Description Concatenation

Instead of simple fallback logic, definitions from multiple OAGIS entities are **concatenated as complementary paragraphs** (separated by `\n\n`), preserving both the generic meaning and the usage-specific context:

| Source Pair | Example |
|:------------|:--------|
| BCCP.definition + BCC.definition | Generic property meaning + context-specific usage in the parent ACC |
| ASCCP.definition + ASCC.definition | Generic association meaning + context-specific role in the parent ACC |
| Property description + DataType.definition + DataType.contentComponentDefinition | Property semantics + data type semantics |
| Property description + CodeList.definition | Property semantics + code list provenance |

### DB Entity -> OAS Output Mapping

| DB Entity | DB Field | OAS Output | Location |
|:----------|:---------|:-----------|:---------|
| BCC | `definition` | Appended to property `description` | Property schema |
| BCCP | `definition` | Prepended to property `description` | Property schema |
| BCCP | `representationTerm` | `x-representation-term` | Property extensions |
| BCCP | `guid` | `x-guid` | Property extensions |
| BCCP | `den` | `x-den` | Property extensions |
| ASCC | `definition` | Appended to association `description` | Association property |
| ASCCP | `definition` | Prepended to association `description` | Association property |
| ASCCP | `guid` | `x-guid` | Association property extensions |
| ASCCP | `den` | `x-den` | Association property extensions |
| ACC | `definition` | Schema-level `description` | `components/schemas` |
| ACC | `objectClassQualifier` | Prepended as `**Qualifier:** ...` + `x-qualifier` | Schema `description` + extensions |
| ACC | `isAbstract` | `x-abstract: true` | Schema extensions |
| ACC | `guid` | `x-guid` | Schema extensions |
| ACC | `den` | `x-den` | Schema extensions |
| ACC | `namespaceId` -> Namespace.uri | `x-namespace` | Schema extensions |
| ACC | `moduleId` -> Module.module | `x-module` | Schema extensions |
| ASCCP | `reusableIndicator` | `x-reusable: true` | Association property extensions |
| BCC | `entityType` | `x-entity-type: "Element"/"Attribute"` | Property extensions |
| BCC | `isNillable` | `type: [T, "null"]` | Property schema |
| BCC | `defaultValue` | `default: "value"` | Property schema |
| DataType | `definition` | Appended to property `description` | Property schema |
| DataType | `qualifier` | `x-qualifier` | Property extensions |
| DataType | `contentComponentDen` | `x-content-component-den` | Property extensions |
| DataType | `contentComponentDefinition` | Appended to `description` + `x-content-component-definition` | Property schema + extensions |
| DataType | `versionNum` | `x-version: "1.0"` | Property extensions |
| DT_SC | supplementary components | `x-supplementary-components` | Property extensions |
| CodeList | `definition` | Appended to property `description` | Property schema |
| CodeList | `remark` | `x-enum-remark` | Property extensions |
| CodeList | `definitionSource` | `x-enum-definition-source` | Property extensions |
| CodeList | `extensibleIndicator` | `x-enum-extensible: true` | Property extensions |
| CodeList | `versionId` | Included in `x-enum-source` | Property extensions |
| CodeListValue | `definition` | `x-enum-descriptions: {code: def}` | Property extensions |
| CodeListValue | `name` | `x-enum-labels: {code: label}` | Property extensions |
| CodeListValue | `definitionSource` | `x-enum-value-sources: {code: src}` | Property extensions |
| CodeListValue | `usedIndicator` | Filters unused values from `enum` array | Behavioral |
| CodeListValue | `extensionIndicator` | `x-enum-extensions: {code: true}` | Property extensions |
| CodeList | `listId` | `x-enum-list-id: "oacl_..."` | Property extensions |
| CodeList | `agencyId` | `x-enum-agency: "UN/CEFACT"` | Property extensions |
| AgencyIdList | `definition` | Appended to property `description` | Property schema |
| AgencyIdList | `listId` | `x-enum-list-id: "oagis-id-..."` | Property extensions |
| AgencyIdList | `versionId` | Included in `x-enum-source` | Property extensions |
| AgencyIdListValue | `definition` | `x-enum-descriptions: {val: def}` | Property extensions |
| AgencyIdListValue | `name` | `x-enum-labels: {val: label}` | Property extensions |

### Custom Extensions

| Extension | Source | Purpose |
|:----------|:-------|:--------|
| `x-abstract` | `ACC.isAbstract` | Marks schemas not intended for direct instantiation |
| `x-component-type` | CcNode.componentType | CCTS component type: "ACC" (schema), "BCC"/"ASCC" (property) |
| `x-entity-type` | `BCC.entityType` | CCTS entity type: "Element" or "Attribute" |
| `x-representation-term` | `BCCP.representationTerm` | CCTS representation term (e.g., "Text", "Code", "Amount") |
| `x-version` | `DataType.versionNum` | Data type version for traceability |
| `x-namespace` | `ACC.namespaceId` -> `Namespace.uri` | OAGIS namespace provenance |
| `x-qualifier` | `ACC.objectClassQualifier` / `DataType.qualifier` | CCTS qualifier distinguishing specialized types |
| `x-module` | `ACC.moduleId` -> `Module.module` | Source XSD module file for provenance traceability |
| `x-reusable` | `ASCCP.reusableIndicator` | Whether the ASCCP can be reused across multiple ACCs |
| `x-content-component-den` | `DataType.contentComponentDen` | DEN of the content component within a BDT |
| `x-content-component-definition` | `DataType.contentComponentDefinition` | Definition of the content component within a BDT |
| `x-guid` | `ACC/BCCP/ASCCP.guid` | OAGIS GUID for schema/property traceability |
| `x-den` | `ACC/BCCP/ASCCP.den` | OAGIS Dictionary Entry Name |
| `x-supplementary-components` | `DT_SC` via `findDtScByOwnerDtId` | Supplementary components (e.g., Amount.currencyCode) |
| `x-enum-source` | `CodeList.name`/`AgencyIdList.name` + versionId | Provenance of enum constraints with version |
| `x-enum-descriptions` | `CodeListValue.definition` / `AgencyIdListValue.definition` | Per-value definitions for documentation |
| `x-enum-labels` | `CodeListValue.name` / `AgencyIdListValue.name` | Per-value human-readable display names |
| `x-enum-remark` | `CodeList.remark` | Additional remarks about the code list |
| `x-enum-definition-source` | `CodeList.definitionSource` | Source of the code list definition |
| `x-enum-extensible` | `CodeList.extensibleIndicator` | Whether the code list accepts user extensions |
| `x-enum-value-sources` | `CodeListValue.definitionSource` | Per-value attribution (where each value was defined) |
| `x-enum-list-id` | `CodeList.listId` / `AgencyIdList.listId` | External standard identifier for the code list |
| `x-enum-agency` | `CodeList.agencyId` → `AgencyIdListValue.name` | Responsible agency that maintains the code list |
| `x-enum-extensions` | `CodeListValue.extensionIndicator` | Per-value flag identifying user-defined extensions |


---

## OAS 3.1.0 Patterns

The generator applies the following OpenAPI 3.1.0 best practices:

### Nullable as Type Array

OAS 3.0.x used `nullable: true` alongside `type`. OAS 3.1.0 aligns with JSON Schema and expresses nullability as a type array:

```yaml
# 3.0.x (old)
type: string
nullable: true

# 3.1.0 (current)
type:
  - string
  - "null"
```

Applied in `OpenApiSchemaBuilder.buildPrimitiveProperty()` for all nillable BCC properties.

### `$ref` with Sibling Keywords

In OAS 3.0.x, a `$ref` replaced the entire object, preventing sibling keywords like `description`. The workaround was an `allOf` wrapper:

```yaml
# 3.0.x workaround
description: The buyer party
allOf:
  - $ref: '#/components/schemas/Party'

# 3.1.0 (current) — direct siblings
description: The buyer party
$ref: '#/components/schemas/Party'
```

Applied in `OpenApiSchemaBuilder.buildAssociationProperty()` for non-array ASCC properties with descriptions.

### Reusable ErrorResponse Schema

Error responses (400, 404) reference a shared `ErrorResponse` schema in `components/schemas` instead of returning bare descriptions:

```yaml
ErrorResponse:
  type: object
  properties:
    code:
      type: integer
      format: int32
      description: HTTP status code
    message:
      type: string
      description: Human-readable error message
    details:
      type: string
      description: Additional diagnostic information
  required:
    - code
    - message
```

### Path Parameter Descriptions

All path parameters include a `description` field for linting completeness and documentation quality.

### Discriminator on Polymorphic Base Schemas

Base schemas with 2+ derived types and a natural `typeCode` BCC property receive an OAS 3.1.0 `discriminator` block with explicit mapping:

```yaml
Identification:
  type: object
  discriminator:
    propertyName: typeCode
    mapping:
      AccountIdentification: '#/components/schemas/AccountIdentification'
      AssetIdentification: '#/components/schemas/AssetIdentification'
      # ... all 252 derived types
  properties:
    typeCode:
      type: string
    # ... other properties
```

Key design decisions:
- **Conservative scope**: Only schemas with a pre-existing `typeCode` BCC qualify. No synthetic discriminator properties are injected.
- **Purely additive**: The `discriminator` block is metadata for tools (Redocly, Swagger Codegen). It does not change validation behavior — `allOf` inheritance is preserved unchanged.
- **Derived types map**: Computed in `CcTreeWalker` by reversing `baseSchemaMap` (child→parent) into parent→[sorted children].

---

## Validation

### Lint with Redocly CLI

```bash
npx -y @redocly/cli@latest lint openapi-output/PurchaseOrder.openapi.yaml \
  --skip-rule info-license --skip-rule no-unused-components
```

Expected: **0 errors**, 1 negligible warning (placeholder server URL).

### Generate HTML Documentation

```bash
npx -y @redocly/cli@latest build-docs \
  openapi-output/PurchaseOrder.openapi.yaml \
  -o /tmp/purchase-order-docs.html

open /tmp/purchase-order-docs.html
```

---

## Troubleshooting

### `ClassNotFoundException: JBossLogFactory`

**Cause:** Using the system Java (not JDK 1.8) to execute.

**Solution:** Always use the explicit JDK 1.8 binary:
```bash
$JAVA_HOME/bin/java -cp "$CLASSPATH" ...
```

### `Could not find artifact com.sun:tools:jar:1.8`

**Cause:** `JAVA_HOME` points to a JRE instead of JDK, or to a modern JDK where `tools.jar` doesn't exist.

**Solution:**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_211.jdk/Contents/Home
```

### `Communications link failure` or `SSL` errors

**Cause:** MariaDB not reachable on `localhost` (IPv6/SSL issues).

**Solution:** Use `127.0.0.1` instead of `localhost` and `useSSL=false`:
```bash
-Dspring.datasource.url="jdbc:mysql://127.0.0.1:3306/oagi?useSSL=false&allowPublicKeyRetrieval=true"
```

### Empty schemas in Redocly HTML

**Cause:** Older version generated `paths: {}`. Redocly only renders schemas referenced from endpoints.

**Solution:** This is now fixed — the generator produces CRUD-style paths referencing the root schema. If you still see empty docs, regenerate with the latest code.

---

## Verification Results (Super-Schema)

| Metric             | Value               |
| :----------------- | :------------------ |
| Total schemas       | ~3,067 (2,272 + 795 aliases) |
| `x-qualifier` count | 1,367               |
| `x-module` count    | 2,230               |
| `x-reusable` count  | 4,818               |
| `x-content-component-den` count | 1,553   |
| `x-content-component-definition` count | 27 |
| Enum extensions     | `x-enum-descriptions`, `x-enum-labels`, `x-enum-source`, `x-enum-agency` |
| Redocly lint errors | 0                   |
| Output file         | `oagis-super-schema.openapi.yaml` |
| File size           | ~10 MB (247,892 lines) |

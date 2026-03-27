# srt-openapi — CC-to-OpenAPI 3.0 Generator

Generates [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3) schemas from OAGIS **Core Components (CC)** stored in a MariaDB/MySQL database. The module walks the CC tree (ASCCP → ACC → BCC/ASCC) and produces a fully valid, lint-clean YAML specification.

## Key Features

- **Full CC tree traversal** — recursive walk from a root ASCCP through ACCs, BCCs, and ASCCs
- **`allOf` composition** — ACC inheritance via `based_acc_id` emitted as `allOf` references
- **Alias detection** — identical ACCs referenced under different ASCCP names produce thin `allOf` wrappers instead of duplicate schemas
- **XSD-to-OpenAPI type mapping** — 30+ XSD built-in types resolved through the BDT → CDT → XBT chain
- **Enum generation** — automatic `enum` arrays from `CodeList` and `AgencyIdList` restrictions for maximum model fidelity
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
    │   ├── OpenApiSchemaBuilder.java     # TreeResult → OpenAPI 3.0.3 Map
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
| `TypeMapper`               | Resolves BDT → CDT → XBT chain to `TypeResolution` (type/format/enum)    |
| `TypeResolution`           | Immutable value object carrying OAS type, format, and optional enum values|

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

In OpenAPI 3.0:
- `type: number, format: double` = IEEE 754 64-bit (~15 significant digits)
- `type: number, format: float` = IEEE 754 32-bit (~7 significant digits)
- `type: number` (no format) = **unconstrained numeric precision**

Since Amount, Measure, Quantity, and other financial types default to `Decimal` → `xsd:decimal`, constraining to `double` would lose precision for values like `12345678901234.99`. Using `number` without format preserves the semantic intent of the OAGIS specification.

---

## Enum Generation

When a BDT's default restriction references a **CodeList** or **AgencyIdList** (rather than a CDT Primitive), the generator extracts all allowed values and emits them as OpenAPI `enum` arrays.

### Resolution Chain

```
bdtId → BDT_PRI_RESTRI (default)
  ├─ codeListId > 0
  │    → CodeList (name for traceability)
  │    → CodeListValue[] → .getValue() → enum: ["USD", "EUR", ...]
  │
  └─ agencyIdListId > 0
       → AgencyIdList (name for traceability)
       → AgencyIdListValue[] → .getValue() → enum: ["6", "16", ...]
```

### Implementation

| Class            | Responsibility |
|:-----------------|:---------------|
| `TypeMapper`     | Detects code list restrictions in `BDT_PRI_RESTRI`, fetches values via `ImportedDataProvider` |
| `TypeResolution`  | Carries `type`, `format`, `enumValues` (nullable), and `enumSource` (traceability string) |
| `OpenApiSchemaBuilder` | Injects `enum` array into property schema when `TypeResolution.hasEnum()` is true |

### Example Output

A field constrained by the OAGIS Currency Code List would generate:

```yaml
currencyCode:
  type: string
  enum:
    - AED
    - AFN
    - ALL
    - AMD
    - ANG
    # ... (all ISO 4217 codes)
    - ZMW
    - ZWL
```

An agency identification field would generate:

```yaml
agencyIdentification:
  type: string
  enum:
    - "1"    # UN/ECE
    - "2"    # CEN/ISSS
    - "6"    # UN/CEFACT
    - "16"   # DUNS
    # ... (all registered agency codes)
```

### Design Rationale

- **Enum at the type level, not the property level**: The allowed values are a property of the data type (BDT), not the individual BCCP. Every field referencing the same BDT gets the same enum constraint.
- **Maximum model fidelity**: Enums enable client-side validation, IDE autocompletion, and documentation tooling (Redocly, Swagger UI) to display allowed values without external reference lookups.
- **Source traceability**: `TypeResolution.enumSource` preserves the origin (e.g., `"CodeList: oacl_CurrencyCode"`) for debugging and auditing.

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

## Verification Results (Purchase Order)

| Metric             | Value               |
| :----------------- | :------------------ |
| Total schemas       | 267 (213 + 54 aliases) |
| Redocly lint errors | 0                   |
| Output file         | `PurchaseOrder.openapi.yaml` |
| HTML doc size       | ~57 MB              |

# srt-openapi — CC-to-OpenAPI 3.1 Generator

Generates [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0) schemas from OAGIS **Core Components (CC)** stored in a MariaDB/MySQL database. The module walks the CC tree (ASCCP → ACC → BCC/ASCC) and produces a fully valid, lint-clean YAML specification.

## Key Features

- **Full CC tree traversal** — recursive walk from a root ASCCP through ACCs, BCCs, and ASCCs
- **`allOf` composition** — ACC inheritance via `based_acc_id` emitted as `allOf` references
- **Alias detection** — identical ACCs referenced under different ASCCP names produce thin `allOf` wrappers instead of duplicate schemas
- **XSD-to-OpenAPI type mapping** — 30+ XSD built-in types resolved through the BDT → CDT → XBT chain
- **Enum generation** — automatic `enum` arrays from `CodeList` and `AgencyIdList` restrictions, with per-value descriptions, labels, and CodeList provenance
- **Exhaustive description enrichment** — concatenated definitions from BCCP+BCC, ASCCP+ASCC, ACC qualifiers, and DataType definitions for maximum semantic density
- **Schema-level metadata** — `deprecated`, `x-oagis-abstract`, discriminator patterns, and `x-oagis-version` extensions
- **Property-level metadata** — `default` values, nullable types (OAS 3.1.0 `type: [T, "null"]`), and bounded array constraints
- **Pure schema catalog** — no `paths`, `servers`, or `security` blocks; domain-specific APIs are defined via separate OpenAPI Overlay files (see [Architecture](#architecture-super-schema--overlays) below)
- **`x-oagis-*` extension namespace** — all custom extensions use the `x-oagis-` prefix for consistency with Score and to avoid collisions with other OpenAPI tooling

---

## Why This Extension? Score vs. srt-openapi

Score (OAGi's official platform, currently at v3.4.2) includes a built-in **BIE-to-OpenAPI** pipeline that generates RESTful operations from Business Information Entities. In theory, Score is the canonical tool for this job. In practice, several blockers make it unusable for our situation — hence the standalone operation generator in srt-openapi.

### The `oagis.sql` Problem (Critical Blocker)

Score's database requires a seed file (`oagis.sql`) containing all OAGIS Core Components, Code Lists, and Agency lists. **This file is not publicly available.** It is baked into a private Docker Hub image (`oaborern/score-mysql:{version}`) and is not distributed as a standalone SQL dump, nor published in any Score repository (`score-external-api`, `score-http`, etc.).

Without `oagis.sql`, Score boots with an empty database — no components, no code lists, no schemas. The BIE-to-OAS pipeline has nothing to work with.

We verified this exhaustively:

| Source | Result |
|:-------|:-------|
| `score-external-api` GitHub repo | No SQL dump in releases, tags, or commit history |
| Docker Hub `oaborern/score-mysql` | Private image; no public `docker pull` access |
| Score documentation | References the Docker image but never links an SQL file |
| Score 3.4.2 source code | `docker-compose.yml` depends on the private MySQL image |

### Schema Incompatibility (No Migration Path)

Even if we could obtain `oagis.sql` for Score 3.4.2, it would not help with our existing data. Our NIST database (the `oagi` database populated by `srt-import`) uses the **legacy NIST schema** — a compact 39-table structure designed for the original SRT (Standard Reference Tool) project.

Score 3.4.2 uses a **completely different schema** with 108 tables, redesigned for multi-user collaborative editing, revision tracking, and release management. Key differences:

| Aspect | NIST (srt-import) | Score 3.4.2 |
|:-------|:-------------------|:------------|
| Tables | 39 | 108 |
| Revision tracking | None | Per-entity revision history with `revisionNum`, `revisionAction`, `releaseId` |
| Multi-user editing | None | `ownerUserId`, `createdBy`, `lastUpdatedBy`, editorial `state` per entity |
| BIE layer | Not present | Full BIE model (`top_level_asbiep`, `abie`, `bbie`, `asbie`, etc.) |
| Release management | Not present | `release` table with bundle lifecycle |
| Data types | `dt` + `bdt_pri_restri` + `cdt_awd_pri_xps_type_map` | Same core chain, but extended with score-specific audit columns |

There is **no automated migration path** from the 39-table NIST schema to Score's 108-table schema. The structural differences are fundamental, not just additive columns.

### Score's BIE Dependency (Architectural Complexity)

Even with a working Score database, generating OpenAPI operations requires first creating **Business Information Entities (BIEs)** — Score's abstraction layer that sits between Core Components and the API output. The BIE workflow involves:

1. Creating a "Top-Level ASBIEP" for each root noun
2. Profiling the CC tree (selecting which properties to include/exclude)
3. Assigning business context to each BIE
4. Only then generating OAS from the profiled BIE

This is powerful for enterprise governance (where teams curate which OAGIS fields their API exposes), but it is **overkill** for our use case. We want the full OAGIS schema catalog with Score-compatible CRUD operations — no per-field profiling, no business context assignment, no editorial workflow.

### Our Approach: Schema-First Simplicity

The srt-openapi extension takes a fundamentally simpler approach:

```
NIST DB (39 tables)
  → CcTreeWalker (existing — walks the CC tree)
  → OpenApiSchemaBuilder (existing — produces components/schemas)
  → OperationOverlayBuilder (NEW — generates CRUD paths)
  → Full API spec (schema catalog + RESTful operations)
```

**What we replicate from Score** (for compatibility):
- Naming conventions: `camelCase()`, `convertIdentifierToId()` (from `Helper.java`)
- operationId patterns: `query{Name}List`, `create{Name}`, `replace{Name}`, etc. (from `SetOperationIdWithVerb.java`)
- OAuth2 security scheme: authorization code flow with `{bieName}Read`/`Write` scopes
- Resource paths: kebab-case with naive plural (`PurchaseOrder` → `/purchase-orders`)
- Parameters: `sinceLastDateTime` for polling on collection GET, `{id}` path param on instance endpoints
- List wrappers: `{Name}List` array schema per root noun

**What we deliberately omit** (for simplicity):
- BIE profiling layer — all CC properties are included
- Business context assignment — not needed for a universal schema catalog
- Multi-user editorial workflow — our schemas are generated, not collaboratively edited
- Release management — we version by OAGIS release (10.x), not by Score release bundles

The result is a single-command generator that produces a Score-compatible OpenAPI specification directly from the NIST database, without requiring Score's infrastructure, its private seed data, or its BIE workflow.

---

## Architecture: Super-Schema & Overlays

The generator produces a **pure schema catalog** — an OpenAPI 3.1.0 document containing only `components/schemas`. It intentionally excludes `paths`, `servers`, `security`, and `tags`. Domain-specific APIs are built as separate **Overlay** files that reference the super-schema.

This separation follows the same architectural pattern used by **Score** (OAGi's official BIE-to-OAS tool).

### Why This Separation?

| Concern | Super-Schema | Domain Overlay |
|:--------|:-------------|:---------------|
| **Scope** | All 1,600+ OAGIS root nouns + 2,200+ schemas | A handful of domain-specific operations |
| **Authorship** | Generated from the CC database | Hand-crafted per domain/use-case |
| **Lifecycle** | Changes when OAGIS releases a new version | Changes when your API evolves |
| **Content** | `components/schemas` only | `paths`, `servers`, `security`, `tags` |

Bundling thousands of CRUD operations into the super-schema would add noise without value — each domain project selects only the schemas it needs.

### Layered Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: Super-Schema  (this generator)                     │
│  ─────────────────────────────────────────                    │
│  oagis-10.3-super-schema.openapi.yaml                        │
│  Pure components/schemas catalog, x-oagis-* extensions       │
│  Generated from CC database (ASCCP → ACC → BCC/ASCC)         │
└──────────────────────────────┬───────────────────────────────┘
                               │ $ref
┌──────────────────────────────▼───────────────────────────────┐
│  Layer 2: Domain API Overlays  (per project)                 │
│  ──────────────────────────────────────────────               │
│  my-domain-api.openapi.yaml                                  │
│  Defines paths, operations, security, servers                │
│  References super-schema types via $ref                      │
│  Follows Score naming conventions (see below)                │
└──────────────────────────────────────────────────────────────┘
```

### Score Naming Conventions for Domain Overlays

When creating domain-specific API overlays, follow Score's naming patterns for consistency:

| Element | Convention | Example |
|:--------|:-----------|:--------|
| **Schema name** | PascalCase (from `objectClassTerm`) | `PurchaseOrder`, `LocationId` |
| **Path** | kebab-case of schema name | `/purchase-order`, `/location-id` |
| **operationId** | `{verb}{SchemaName}[List]` | `getPurchaseOrder`, `listPurchaseOrderList` |
| **Request/Response** | `$ref` to super-schema | `$ref: './super-schema.yaml#/components/schemas/PurchaseOrder'` |
| **Tags** | One per root noun | `PurchaseOrder` |

### Example Domain Overlay

```yaml
# my-domain-api.openapi.yaml
openapi: "3.1.0"
info:
  title: "My Domain API"
  version: "1.0.0"
servers:
  - url: https://api.example.com/v1
security:
  - bearerAuth: []
paths:
  /purchase-order:
    get:
      operationId: listPurchaseOrderList
      summary: List purchase orders
      tags: [PurchaseOrder]
      responses:
        "200":
          description: Successful response
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: './oagis-10.3-super-schema.openapi.yaml#/components/schemas/PurchaseOrder'
    post:
      operationId: createPurchaseOrder
      summary: Create a purchase order
      tags: [PurchaseOrder]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: './oagis-10.3-super-schema.openapi.yaml#/components/schemas/PurchaseOrder'
      responses:
        "201":
          description: Resource created
  /purchase-order/{id}:
    get:
      operationId: getPurchaseOrder
      summary: Get a purchase order by ID
      tags: [PurchaseOrder]
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: Successful response
          content:
            application/json:
              schema:
                $ref: './oagis-10.3-super-schema.openapi.yaml#/components/schemas/PurchaseOrder'
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
```

### Identifier Naming: `Id` (Not `ID`)

The `toCamelCase` function in `CcTreeWalker` maps `Identifier` → `Id` (matching Score's `Helper.camelCase`). This means schema names like `LocationId`, `PartyId`, `ItemId` — not `LocationID`.

> **Note**: This is a deliberate alignment with Score's conventions. The all-caps `ID` suffix was used in earlier versions but has been changed to `Id` for consistency.

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
  |    |    +-- .getListId()    -> x-oagis-enum-list-id: "oacl_CurrencyCode"
  |    |    +-- .getAgencyId()  -> AgencyIdListValue.getName() -> x-oagis-enum-agency: "UN/CEFACT"
  |    |
  |    -> CodeListValue[] (filtered: usedIndicator=true only)
  |         |-- .getValue()              -> enum: ["USD", "EUR", ...]
  |         |-- .getDefinition()         -> x-oagis-enum-descriptions: {USD: "...", EUR: "..."}
  |         |-- .getName()               -> x-oagis-enum-labels: {USD: "US Dollar", EUR: "Euro"}
  |         |-- .getDefinitionSource()   -> x-oagis-enum-value-sources: {USD: "ISO 4217", ...}
  |         +-- .getExtensionIndicator() -> x-oagis-enum-extensions: {ZZZ: true}
  |
  +-- agencyIdListId > 0  (full parity with CodeList)
       -> AgencyIdList (name, definition, versionId, listId)
       |    +-- .getListId() -> x-oagis-enum-list-id: "oagis-id-..."
       -> AgencyIdListValue[]
            |-- .getValue()      -> enum: ["6", "16", ...]
            |-- .getDefinition() -> x-oagis-enum-descriptions: {6: "...", 16: "..."}
            +-- .getName()       -> x-oagis-enum-labels: {6: "DUNS", 16: "EAN"}
```

### Implementation

| Class            | Responsibility |
|:-----------------|:---------------|
| `TypeMapper`     | Detects code list restrictions in `BDT_PRI_RESTRI`, fetches values and per-value metadata via `ImportedDataProvider`. Resolves DT_SC supplementary components. |
| `TypeResolution`  | Carries `enumValues`, `enumSource`, `enumDescriptions`, `enumLabels`, `enumSourceDescription`, `enumRemark`, `enumDefinitionSource`, `enumListId`, `enumAgency`, `enumExtensions`, `dataTypeDescription`, `dataTypeVersion`, `supplementaryComponents` |
| `OpenApiSchemaBuilder` | Injects all `enum`/`x-oagis-enum-*` extensions, DataType metadata, CCTS metadata, namespace, GUID/DEN |

### Example Output

A field constrained by the OAGIS Currency Code List would generate:

```yaml
currencyCode:
  description: >-
    The currency for this amount.

    A character string that constitutes the recognized code designator of the currency.
  type: string
  enum: [AED, AFN, ALL, AMD, ANG, ...]   # only usedIndicator=true values
  x-oagis-enum-source: "CodeList: oacl_CurrencyCode (v1)"
  x-oagis-enum-list-id: "oacl_CurrencyCode"
  x-oagis-enum-agency: "UN/CEFACT"
  x-oagis-enum-descriptions:
    AED: "United Arab Emirates Dirham"
    AFN: "Afghani"
  x-oagis-enum-labels:
    AED: "UAE Dirham"
    AFN: "Afghani"
  x-oagis-enum-remark: "Based on ISO 4217"
  x-oagis-enum-definition-source: "UN/ECE Rec 9"
  x-oagis-enum-extensible: true
  x-oagis-enum-value-sources:
    AED: "ISO 4217"
    AFN: "ISO 4217"
  x-oagis-enum-extensions:
    ZZZ: true
  x-oagis-component-type: BCC
  x-oagis-entity-type: Element
  x-oagis-representation-term: Code
  x-oagis-guid: "oagis-id-abc123..."
  x-oagis-den: "Currency. Code"
```

### Design Rationale

- **Enum at the type level, not the property level**: The allowed values are a property of the data type (BDT), not the individual BCCP. Every field referencing the same BDT gets the same enum constraint.
- **Full AgencyIdList parity**: `AgencyIdListValue.name` → `x-oagis-enum-labels`, `.definition` → `x-oagis-enum-descriptions`, and `AgencyIdList.definition` → property description, matching CodeList behavior.
- **Per-value metadata**: `x-oagis-enum-descriptions` and `x-oagis-enum-labels` enable documentation tooling to display human-readable names and definitions alongside code values.
- **CodeList provenance**: The `CodeList.definition` is appended to the property description as a complementary paragraph, providing context about the code list itself.
- **Source traceability**: `x-oagis-enum-source` preserves the origin with version (e.g., `"CodeList: oacl_CurrencyCode (v1)"`) for debugging and auditing.
- **Active-value filtering**: `CodeListValue.usedIndicator` controls which values appear in the `enum` array, ensuring unused legacy codes are excluded from consumers.
- **Standard identifier**: `x-oagis-enum-list-id` exposes the external code list identifier (e.g., `oacl_CurrencyCode`) for cross-referencing with OAGIS standards.
- **Agency attribution**: `x-oagis-enum-agency` resolves the maintaining agency from the FK chain (`CodeList.agencyId` -> `AgencyIdListValue.name`), e.g., `"UN/CEFACT"`.
- **Extension flags**: `x-oagis-enum-extensions` marks user-defined code values, enabling consumers to distinguish standard from extended entries.

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
| BCCP | `representationTerm` | `x-oagis-representation-term` | Property extensions |
| BCCP | `guid` | `x-oagis-guid` | Property extensions |
| BCCP | `den` | `x-oagis-den` | Property extensions |
| ASCC | `definition` | Appended to association `description` | Association property |
| ASCCP | `definition` | Prepended to association `description` | Association property |
| ASCCP | `guid` | `x-oagis-guid` | Association property extensions |
| ASCCP | `den` | `x-oagis-den` | Association property extensions |
| ACC | `definition` | Schema-level `description` | `components/schemas` |
| ACC | `objectClassQualifier` | Prepended as `**Qualifier:** ...` + `x-oagis-qualifier` | Schema `description` + extensions |
| ACC | `isAbstract` | `x-oagis-abstract: true` | Schema extensions |
| ACC | `guid` | `x-oagis-guid` | Schema extensions |
| ACC | `den` | `x-oagis-den` | Schema extensions |
| ACC | `namespaceId` -> Namespace.uri | `x-oagis-namespace` | Schema extensions |
| ACC | `moduleId` -> Module.module | `x-oagis-module` | Schema extensions |
| ASCCP | `reusableIndicator` | `x-oagis-reusable: true` | Association property extensions |
| BCC | `entityType` | `x-oagis-entity-type: "Element"/"Attribute"` | Property extensions |
| BCC | `isNillable` | `type: [T, "null"]` | Property schema |
| BCC | `defaultValue` | `default: "value"` | Property schema |
| DataType | `definition` | Appended to property `description` | Property schema |
| DataType | `qualifier` | `x-oagis-qualifier` | Property extensions |
| DataType | `contentComponentDen` | `x-oagis-content-component-den` | Property extensions |
| DataType | `contentComponentDefinition` | Appended to `description` + `x-oagis-content-component-definition` | Property schema + extensions |
| DataType | `versionNum` | `x-oagis-version: "1.0"` | Property extensions |
| DT_SC | supplementary components | `x-oagis-supplementary-components` | Property extensions |
| CodeList | `definition` | Appended to property `description` | Property schema |
| CodeList | `remark` | `x-oagis-enum-remark` | Property extensions |
| CodeList | `definitionSource` | `x-oagis-enum-definition-source` | Property extensions |
| CodeList | `extensibleIndicator` | `x-oagis-enum-extensible: true` | Property extensions |
| CodeList | `versionId` | Included in `x-oagis-enum-source` | Property extensions |
| CodeListValue | `definition` | `x-oagis-enum-descriptions: {code: def}` | Property extensions |
| CodeListValue | `name` | `x-oagis-enum-labels: {code: label}` | Property extensions |
| CodeListValue | `definitionSource` | `x-oagis-enum-value-sources: {code: src}` | Property extensions |
| CodeListValue | `usedIndicator` | Filters unused values from `enum` array | Behavioral |
| CodeListValue | `extensionIndicator` | `x-oagis-enum-extensions: {code: true}` | Property extensions |
| CodeList | `listId` | `x-oagis-enum-list-id: "oacl_..."` | Property extensions |
| CodeList | `agencyId` | `x-oagis-enum-agency: "UN/CEFACT"` | Property extensions |
| AgencyIdList | `definition` | Appended to property `description` | Property schema |
| AgencyIdList | `listId` | `x-oagis-enum-list-id: "oagis-id-..."` | Property extensions |
| AgencyIdList | `versionId` | Included in `x-oagis-enum-source` | Property extensions |
| AgencyIdListValue | `definition` | `x-oagis-enum-descriptions: {val: def}` | Property extensions |
| AgencyIdListValue | `name` | `x-oagis-enum-labels: {val: label}` | Property extensions |

### Custom Extensions

| Extension | Source | Purpose |
|:----------|:-------|:--------|
| `x-oagis-abstract` | `ACC.isAbstract` | Marks schemas not intended for direct instantiation |
| `x-oagis-component-type` | CcNode.componentType | CCTS component type: "ACC" (schema), "BCC"/"ASCC" (property) |
| `x-oagis-entity-type` | `BCC.entityType` | CCTS entity type: "Element" or "Attribute" |
| `x-oagis-representation-term` | `BCCP.representationTerm` | CCTS representation term (e.g., "Text", "Code", "Amount") |
| `x-oagis-version` | `DataType.versionNum` | Data type version for traceability |
| `x-oagis-namespace` | `ACC.namespaceId` -> `Namespace.uri` | OAGIS namespace provenance |
| `x-oagis-qualifier` | `ACC.objectClassQualifier` / `DataType.qualifier` | CCTS qualifier distinguishing specialized types |
| `x-oagis-module` | `ACC.moduleId` -> `Module.module` | Source XSD module file for provenance traceability |
| `x-oagis-reusable` | `ASCCP.reusableIndicator` | Whether the ASCCP can be reused across multiple ACCs |
| `x-oagis-content-component-den` | `DataType.contentComponentDen` | DEN of the content component within a BDT |
| `x-oagis-content-component-definition` | `DataType.contentComponentDefinition` | Definition of the content component within a BDT |
| `x-oagis-guid` | `ACC/BCCP/ASCCP.guid` | OAGIS GUID for schema/property traceability |
| `x-oagis-den` | `ACC/BCCP/ASCCP.den` | OAGIS Dictionary Entry Name |
| `x-oagis-supplementary-components` | `DT_SC` via `findDtScByOwnerDtId` | Supplementary components (e.g., Amount.currencyCode) |
| `x-oagis-enum-source` | `CodeList.name`/`AgencyIdList.name` + versionId | Provenance of enum constraints with version |
| `x-oagis-enum-descriptions` | `CodeListValue.definition` / `AgencyIdListValue.definition` | Per-value definitions for documentation |
| `x-oagis-enum-labels` | `CodeListValue.name` / `AgencyIdListValue.name` | Per-value human-readable display names |
| `x-oagis-enum-remark` | `CodeList.remark` | Additional remarks about the code list |
| `x-oagis-enum-definition-source` | `CodeList.definitionSource` | Source of the code list definition |
| `x-oagis-enum-extensible` | `CodeList.extensibleIndicator` | Whether the code list accepts user extensions |
| `x-oagis-enum-value-sources` | `CodeListValue.definitionSource` | Per-value attribution (where each value was defined) |
| `x-oagis-enum-list-id` | `CodeList.listId` / `AgencyIdList.listId` | External standard identifier for the code list |
| `x-oagis-enum-agency` | `CodeList.agencyId` → `AgencyIdListValue.name` | Responsible agency that maintains the code list |
| `x-oagis-enum-extensions` | `CodeListValue.extensionIndicator` | Per-value flag identifying user-defined extensions |

### Excluded Fields

The following database fields are **intentionally not mapped** to the OpenAPI output. Each exclusion has been audited and falls into one of the categories below.

**Scope**: Categories 1-4 (PKs, FKs, audit, revision) apply to the 6 core CC entities **and** to auxiliary entities (CodeList, CodeListValue, AgencyIdList, AgencyIdListValue, DT_SC, Namespace, Module, Xbt). Auxiliary entities also have UI-state fields (`CodeListValue.lockedIndicator`, `CodeListValue.color`, `CodeListValue.disabled`, `CodeList.editDisabled`, `CodeList.deleteDisabled`, `CodeList.discardDisabled`) that are internal to the Score CMS editing interface and are excluded for the same reason as Category 1.

#### Category 1: Internal Audit & Lifecycle

**Fields** (present in all 6 entities): `createdBy`, `ownerUserId`, `lastUpdatedBy`, `creationTimestamp`, `lastUpdateTimestamp`, `state`

Score CMS authoring metadata that records _who_ edited a CC and _when_, along with the component's editorial lifecycle state (Draft, Candidate, Published). These are internal to the collaborative editing workflow and have no semantic relevance for API schema consumers. The generated schema represents the _published_ specification, not its editorial history.

#### Category 2: Revision Tracking

**Fields** (present in all 6 entities): `revisionNum`, `revisionTrackingNum`, `revisionAction`, `releaseId`, `currentXxxId`

The Score CMS's multi-user revision control system (optimistic concurrency, amendment tracking, release bundle management). These track internal change history within the OAGIS database, not the semantic versioning of the data types. `DataType.versionNum` already captures the externally meaningful version and is emitted as `x-oagis-version`.

#### Category 3: Surrogate Primary Keys

**Fields**: `accId`, `bccId`, `bccpId`, `asccId`, `asccpId`, `dtId`

Auto-generated database primary keys used exclusively for FK joins during tree traversal. The OAGIS GUID (`guid`) serves as the portable, specification-level unique identifier and is emitted as `x-oagis-guid`.

#### Category 4: Foreign Key Join Columns

**Fields**: `BCC.fromAccId`, `BCC.toBccpId`, `ASCC.fromAccId`, `ASCC.toAsccpId`, `ASCCP.roleOfAccId`, `DataType.basedDtId`, `DataType.previousVersionDtId`

Structural join columns used by `CcTreeWalker` to navigate the CC tree. Their information is already captured by the resulting schema structure itself (`$ref`, `allOf`, property containment).

#### Category 5: Redundant Association-Entity Fields

**Fields**: `BCC.guid`, `ASCC.guid`, `BCC.den`, `ASCC.den`

BCC and ASCC are association-table entities linking an ACC to a BCCP/ASCCP. Their GUIDs and DENs are redundant with the property-level equivalents:
- **GUIDs**: BCCP and ASCCP GUIDs are the canonical, externally referenceable identifiers (emitted as `x-oagis-guid` on properties).
- **DENs**: The BCCP and ASCCP DENs are emitted as `x-oagis-den` on properties. The BCC/ASCC DENs are a computed superset (parent object class + property DEN) that adds no new information.

#### Category 6: ACC Internal Classification

**Fields**: `ACC.oagisComponentType`

OAGIS ontology classification enum (Base, Semantics, Extension, SemanticGroup, UserExtensionGroup, Embedded, OAGIS10Nouns, OAGIS10BODs). This is an internal OAGIS architecture discriminator used during the CC tree import and type hierarchy construction. The schema-level `x-oagis-component-type: ACC` already identifies the component kind for API consumers. The finer-grained OAGIS classification is not meaningful outside the OAGIS editorial toolchain.

#### Category 7: DataType Internal Fields

**Fields**: `DataType.revisionDoc`, `DataType.dataTypeTerm`, `DataType.den`, `DataType.type`, `DataType.guid`, `DataType.deprecated`

- `revisionDoc`: Change notes for the DT, internal to the OAGIS editorial process.
- `dataTypeTerm`: Used internally for type matching (e.g., "Amount", "Code"), already represented via `x-oagis-representation-term` from BCCP.
- `den`: Already represented via the property-level `x-oagis-den` from BCCP.
- `type`: Internal discriminator (BDT=1 / CDT=0), not a consumer-facing attribute.
- `guid`: DT-level GUID is not needed; the property-level GUID from BCCP/ASCCP provides sufficient traceability.
- `deprecated`: DT deprecation is surfaced through the BCC/BCCP `deprecated` flag at the consumer-facing level.

#### Category 8: Module/Namespace at Property Level

**Fields**: `BCCP.moduleId`, `BCCP.namespaceId`, `ASCCP.moduleId`, `ASCCP.namespaceId`, `DataType.moduleId`

Module and namespace provenance is emitted at the ACC (schema) level via `x-oagis-module` and `x-oagis-namespace`. Repeating it at every property would be redundant since properties inherit the namespace of their containing schema. ASCC properties reference schemas that carry their own namespace metadata.

#### Category 9: Property-Template Fields Shadowed by Association Context

**Fields**: `BCCP.nillable`, `BCCP.defaultValue`, `ASCCP.nillable`

In CCTS, BCCP and ASCCP define reusable **property templates**, while BCC and ASCC customize them **per context** (per-ACC usage). The pipeline correctly reads `nillable` and `defaultValue` from the association entity (BCC), which carries the context-specific override. The BCCP-level values are the template defaults, superseded by `BCC.isNillable()`/`BCC.getDefaultValue()` in any concrete usage. For ASCCP, association-level nillability is always `false` (object references are not nillable in the target schema).

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

## Complete Transformation Example

This section traces a single BCC property end-to-end: from the database entities through the pipeline to the final OpenAPI YAML. The example uses a hypothetical `totalAmount` property of a `PurchaseOrder` ACC.

### Step 1: Database Entities

```
ASCCP  asccpId=100  propertyTerm="Purchase Order"  roleOfAccId=200  guid="guid-asccp-po"
  ACC  accId=200    objectClassTerm="Purchase Order"  definition="A document..."  namespaceId=5  moduleId=3
    BCC  bccId=301  toBccpId=400  fromAccId=200  cardinalityMin=0  cardinalityMax=1
         entityType=1(Element)  nillable=true  defaultValue=null  definition="The total monetary..."
      BCCP  bccpId=400  propertyTerm="Total Amount"  representationTerm="Amount"
             bdtId=500  definition="A monetary value."  guid="guid-bccp-total"  den="Total Amount. Amount"
        DT  dtId=500  dataTypeTerm="Amount"  qualifier=null  versionNum="1.0"
            contentComponentDen="Amount. Content"  contentComponentDefinition="The numeric amount value."
            definition="A number of monetary units specified using a given unit of currency."
          BDT_PRI_RESTRI  bdtPriRestriId=600  bdtId=500  isDefault=true
                          cdtAwdPriXpsTypeMapId=700  codeListId=0  agencyIdListId=0
            CDT_AWD_PRI_XPS_TYPE_MAP  cdtAwdPriXpsTypeMapId=700  xbtId=800
              XBT  xbtId=800  builtInType="xsd:decimal"
          DT_SC  dtScId=901  ownerDtId=500  propertyTerm="Currency"  representationTerm="Code"
                 cardinalityMin=0  cardinalityMax=1  definition="The currency designator."
```

### Step 2: CcTreeWalker Output (CcNode)

The walker produces a `CcNode` for the BCC:

```java
CcNode {
  kind          = BCC_ELEMENT       // entityType=1 -> Element
  propertyName  = "totalAmount"     // camelCase("Total Amount")
  description   = "A monetary value.\n\nThe total monetary..."  // BCCP.def + BCC.def
  cardinalityMin = 0
  cardinalityMax = 1
  bdtId          = 500
  nillable       = true
  deprecated     = false
  defaultValue   = null
  componentType  = "BCC"
  entityType     = "Element"
  representationTerm = "Amount"
  guid           = "guid-bccp-total"
  den            = "Total Amount. Amount"
}
```

### Step 3: TypeMapper Resolution

```
resolve(bdtId=500)
  -> BDT_PRI_RESTRI: no codeListId, no agencyIdListId
  -> default restriction: cdtAwdPriXpsTypeMapId=700
  -> CDT_AWD_PRI_XPS_TYPE_MAP -> XBT: builtInType="xsd:decimal"
  -> XSD_TO_OPENAPI["xsd:decimal"] = {type: "number", format: null}
  -> enrichWithDataType(bdtId=500):
       dtDesc = "A number of monetary units...\n\nThe numeric amount value."
       dtVersion = "1.0"
       supplementaryComponents = [{name: "Currency", type: "string", description: "The currency...", required: false}]
```

**Result:** `TypeResolution(type="number", format=null, enumValues=null, dataTypeDescription="...", dataTypeVersion="1.0", supplementaryComponents=[...])`

### Step 4: OpenApiSchemaBuilder Output

```yaml
# Schema level (from ACC)
PurchaseOrder:
  type: object
  description: "A document..."
  x-oagis-component-type: ACC
  x-oagis-namespace:
    uri: "http://www.openapplications.org/oagis/10"
    prefix: oa
  x-oagis-guid: "guid-acc-po"
  x-oagis-den: "Purchase Order. Details"
  x-oagis-module: "Model/Platform/2_7/Common/Components/Components.xsd"
  properties:
    # Property level (from BCC + BCCP + TypeResolution)
    totalAmount:
      description: >-
        A monetary value.

        The total monetary...

        A number of monetary units specified using a given unit of currency.

        The numeric amount value.
      type:
        - number
        - "null"
      x-oagis-component-type: BCC
      x-oagis-entity-type: Element
      x-oagis-representation-term: Amount
      x-oagis-guid: "guid-bccp-total"
      x-oagis-den: "Total Amount. Amount"
      x-oagis-version: "1.0"
      x-oagis-supplementary-components:
        - name: Currency
          representationTerm: Code
          type: string
          description: "The currency designator."
          required: false
      x-oagis-content-component-den: "Amount. Content"
      x-oagis-content-component-definition: "The numeric amount value."
```

This example demonstrates:
1. **Description concatenation**: BCCP.definition + BCC.definition + DT.definition + DT.contentComponentDefinition
2. **Nullable type**: `nillable=true` -> `type: [number, "null"]`
3. **Arbitrary-precision decimal**: `xsd:decimal` -> `number` without format
4. **Supplementary components**: DT_SC emitted as `x-oagis-supplementary-components`
5. **CCTS metadata**: `x-oagis-component-type`, `x-oagis-entity-type`, `x-oagis-representation-term`, `x-oagis-den`
6. **Traceability**: `x-oagis-guid` from BCCP, `x-oagis-version` from DT

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
| `x-oagis-qualifier` count | 1,367               |
| `x-oagis-module` count    | 2,230               |
| `x-oagis-reusable` count  | 4,818               |
| `x-oagis-content-component-den` count | 1,553   |
| `x-oagis-content-component-definition` count | 27 |
| Enum extensions     | `x-oagis-enum-descriptions`, `x-oagis-enum-labels`, `x-oagis-enum-source`, `x-oagis-enum-agency` |
| Redocly lint errors | 0                   |
| Output file         | `oagis-super-schema.openapi.yaml` |
| File size           | ~10 MB (247,892 lines) |

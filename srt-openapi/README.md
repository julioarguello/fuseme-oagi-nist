# srt-openapi — CC-to-OpenAPI 3.0 Generator

Generates [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3) schemas from OAGIS **Core Components (CC)** stored in a MariaDB/MySQL database. The module walks the CC tree (ASCCP → ACC → BCC/ASCC) and produces a fully valid, lint-clean YAML specification.

## Key Features

- **Full CC tree traversal** — recursive walk from a root ASCCP through ACCs, BCCs, and ASCCs
- **`allOf` composition** — ACC inheritance via `based_acc_id` emitted as `allOf` references
- **Alias detection** — identical ACCs referenced under different ASCCP names produce thin `allOf` wrappers instead of duplicate schemas
- **XSD-to-OpenAPI type mapping** — 30+ XSD built-in types resolved through the BDT → CDT → XBT chain
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
| `TypeMapper`               | Resolves BDT → CDT → XBT chain to OpenAPI `type`/`format` pairs          |

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

### Type Resolution (TypeMapper)

```
bdtId → BDT_PRI_RESTRI (default) → CDT_AWD_PRI_XPS_TYPE_MAP → XBT.builtInType
                                                                     ↓
                                                         XSD_TO_OPENAPI table
                                                         (xsd:decimal → number/double)
```

Covers 30+ XSD types including numerics, dates, binary, URI, and string variants.

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

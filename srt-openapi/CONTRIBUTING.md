# Contributing to srt-openapi

## Development Setup

Follow the [README](README.md) "Getting Started" section to set up your environment. In summary:

1. JDK 1.8.0_211 as `$JAVA_HOME`
2. MariaDB 10.6 on `127.0.0.1:3306` with `oagi` database populated
3. `mvn -pl srt-openapi compile -q` to build

## Module Structure

This module depends on `srt-import` for:
- JPA entities (`org.oagi.srt.repository.entity.*`)
- Repositories (`org.oagi.srt.repository.*`)
- `ImportedDataProvider` (in-memory CC data cache)

**Do not** add Spring Boot Maven plugin — execution is via `java -cp` with a manually assembled classpath, identical to the pattern used by `srt-import`.

## Adding a New ASCCP Target

The generator is parameterized via `openapi.asccp`. To test with a different business noun:

```bash
-Dopenapi.asccp="Invoice"
```

No code changes needed — the pipeline is fully generic.

## Extending the Generator

### Adding new CC node types

1. Add the node kind to `CcNode.Kind` enum in `CcTreeWalker.java`
2. Handle it in `CcTreeWalker.walkAcc()` during child collection
3. Add a `build*Property()` method in `OpenApiSchemaBuilder`

### Adding new XSD type mappings

Edit the `XSD_TO_OPENAPI` static map in `TypeMapper.java`.

### Changing the generated paths

Edit `OpenApiSchemaBuilder.buildPaths()` to modify the REST endpoint structure.

## Code Style

- Java 8 source level (no lambdas beyond what Java 8 supports)
- `LinkedHashMap` for all maps (preserves insertion order in YAML)
- Javadoc on all public methods
- No Lombok (project convention)

## Testing

### Quick Validation

```bash
# Generate + lint in one shot
$JAVA_HOME/bin/java -cp "$CLASSPATH" \
  -Dspring.profiles.active=generate-openapi \
  -Dspring.datasource.url="jdbc:mysql://127.0.0.1:3306/oagi?useSSL=false&allowPublicKeyRetrieval=true" \
  -Dspring.datasource.username=oagi -Dspring.datasource.password=oagi \
  -Dspring.datasource.driver-class-name=com.mysql.jdbc.Driver \
  -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL5InnoDBDialect \
  org.oagi.srt.openapi.OpenApiApplication

npx -y @redocly/cli@latest lint openapi-output/PurchaseOrder.openapi.yaml \
  --skip-rule info-license --skip-rule no-unused-components
```

**Acceptance criteria:** 0 lint errors.

### Visual Verification

```bash
npx -y @redocly/cli@latest build-docs \
  openapi-output/PurchaseOrder.openapi.yaml \
  -o /tmp/purchase-order-docs.html
open /tmp/purchase-order-docs.html
```

Verify that schemas render correctly and the inheritance tree is navigable.

## Known Limitations

- **No array-level composition** — BCCs with `maxOccurs="unbounded"` use `type: array` with `items.$ref` but no individual item descriptions
- **Code list values** — BCCs restricted to a code list emit `type: string` without `enum` values (enums are not yet extracted from the DB)
- **Single-noun generation** — each run produces one YAML per ASCCP; batch generation is not yet implemented

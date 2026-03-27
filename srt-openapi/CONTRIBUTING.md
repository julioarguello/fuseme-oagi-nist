# Contributing to srt-openapi

## Development Setup

Follow the [README](README.md) "Getting Started" section to set up your environment. In summary:

1. JDK 1.8.0_211 as `$JAVA_HOME`
2. MariaDB 10.6 on `127.0.0.1:3306` with `oagi` database populated
3. `mvn -pl srt-openapi compile -q` to build

## Pipeline: `scripts/srt-openapi.sh`

The pipeline script automates the full lifecycle: **compile → generate → validate → distribute**.

```bash
# Full pipeline (compile + generate + lint + copy to dist/)
./scripts/srt-openapi.sh

# Skip compilation (reuse existing build)
./scripts/srt-openapi.sh --skip-compile
```

**What the pipeline does:**

1. **Compile** — `mvn -pl srt-openapi compile -q` (skippable with `--skip-compile`)
2. **Generate** — runs `OpenApiApplication` against the MariaDB `oagi` database
3. **Validate** — `npx @redocly/cli lint` against `redocly.yaml`
4. **Distribute** — copies validated spec to `dist/oagis-super-schema.yaml`

**Acceptance criteria:** The pipeline must exit 0 with `"Your API description is valid."` and 0 errors, 0 warnings.

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
Each root schema gets three operations with distinct `operationId` values:

| Verb | Path | `operationId` | Responses |
|:-----|:-----|:---------------|:----------|
| GET  | `/{noun}` | `list{Noun}` | 200, 400 |
| POST | `/{noun}` | `create{Noun}` | 201, 400 |
| GET  | `/{noun}/{id}` | `get{Noun}` | 200, 404 |

### Enum extraction

`TypeMapper` extracts `enum` values from `CodeList` and `AgencyIdList` restrictions
via `BDT_PRI_RESTRI` → `CODE_LIST_VALUE` / `AGENCY_ID_LIST_VALUE`. These are
injected as `enum` arrays in the generated schema properties.

Resolution chain: `BCCP.bdtId` → `BDT_PRI_RESTRI` (scan all) → `TypeResolution`
(with enums from `CodeListValue`/`AgencyIdListValue`).

### Security

The generator emits a global `security` block referencing a `bearerAuth` scheme
declared under `components/securitySchemes`. This is a placeholder — downstream
overlays should replace it with the actual authentication mechanism.

## Code Style

- Java 8 source level (no lambdas beyond what Java 8 supports)
- `LinkedHashMap` for all maps (preserves insertion order in YAML)
- Javadoc on all public methods
- No Lombok (project convention)

## Linting Configuration

See `redocly.yaml` at the project root. The only suppressed operational rule is
`no-server-example.com` because the spec uses a placeholder URL by design.

## Known Limitations

- **No array-level composition** — BCCs with `maxOccurs="unbounded"` use `type: array` with `items.$ref` but no individual item descriptions
- **Single-noun generation** — each run produces one YAML per ASCCP; batch generation is not yet implemented
- **`srt-webapp` dependency** — full regeneration requires the `srt-webapp` module, which has stale PrimeFaces dependencies. We bypass this by compiling only `srt-openapi`

#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# srt-openapi.sh -- OAGIS Super-Schema Generation Pipeline
#
# Orchestrates: compile → generate → validate (Redocly) → distribute.
# The validated spec is copied to dist/ for consumption via GitHub raw URL.
#
# Usage:
#   ./scripts/srt-openapi.sh                # full pipeline
#   ./scripts/srt-openapi.sh --skip-compile # reuse existing target/
#   ./scripts/srt-openapi.sh --skip-dist    # generate + validate only
#
# Prerequisites:
#   - JDK 1.8 (auto-detected via java_home)
#   - Maven 3.x
#   - MariaDB/MySQL running on localhost:3306 with 'oagi' database
#   - npx (for Redocly CLI)
# -----------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/openapi-output"
DIST_DIR="$PROJECT_ROOT/dist"
# Filename is now version-dependent (e.g. oagis-10.3-super-schema.openapi.yaml);
# resolved after generation in Step 2.
SPEC_NAME=""
JAVA8_HOME="$(/usr/libexec/java_home -v 1.8.0_211 2>/dev/null || /usr/libexec/java_home -v 1.8 2>/dev/null || echo "")"

# Database config
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_NAME="oagi"
DB_USER="oagi"
DB_PASS="oagi"

# Flags
SKIP_COMPILE=false
SKIP_DIST=false

for arg in "$@"; do
    case "$arg" in
        --skip-compile) SKIP_COMPILE=true ;;
        --skip-dist)    SKIP_DIST=true ;;
        *)              echo "Unknown flag: $arg" >&2; exit 1 ;;
    esac
done

# -- Preflight ---------------------------------------------------------------

if [[ -z "$JAVA8_HOME" ]]; then
    echo "ERROR: JDK 1.8 not found. Install Java 8 or configure jenv." >&2
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    echo "ERROR: Maven is not installed or not in PATH." >&2
    exit 1
fi

if ! command -v npx &>/dev/null; then
    echo "ERROR: npx is not installed or not in PATH." >&2
    exit 1
fi

# Verify DB connectivity
if ! mysql -u"$DB_USER" -p"$DB_PASS" -h "$DB_HOST" -P "$DB_PORT" --skip-ssl \
    -e "SELECT 1" "$DB_NAME" >/dev/null 2>&1; then
    echo "ERROR: Cannot connect to MySQL at $DB_HOST:$DB_PORT/$DB_NAME." >&2
    echo "  Start MariaDB: ./scripts/srt-import.sh (or docker start oagis-mariadb)" >&2
    exit 1
fi

echo "=== OAGIS Super-Schema Pipeline ==="
echo "  Project root : $PROJECT_ROOT"
echo "  Java 8 home  : $JAVA8_HOME"
echo "  Database     : $DB_HOST:$DB_PORT/$DB_NAME"
echo ""

# -- Step 1: Compile ---------------------------------------------------------

if $SKIP_COMPILE; then
    echo ">>> Step 1: Compile SKIPPED (--skip-compile)"
else
    echo ">>> Step 1: Compiling srt-openapi modules..."
    cd "$PROJECT_ROOT"
    JAVA_HOME="$JAVA8_HOME" mvn -pl srt-openapi -am compile -q
    echo "    Compilation complete."
fi

# -- Step 2: Generate --------------------------------------------------------

echo ">>> Step 2: Generating super-schema..."
cd "$PROJECT_ROOT"
mkdir -p "$OUTPUT_DIR"

CLASSPATH="$(JAVA_HOME="$JAVA8_HOME" mvn -pl srt-openapi dependency:build-classpath -q \
    -DincludeScope=compile -Dmdep.outputFile=/dev/stdout)"
CLASSPATH="$CLASSPATH:srt-openapi/target/classes:srt-import/target/classes"
CLASSPATH="$CLASSPATH:srt-repository/target/classes:srt-common/target/classes"
CLASSPATH="$CLASSPATH:$JAVA8_HOME/lib/tools.jar"

"$JAVA8_HOME/bin/java" -cp "$CLASSPATH" \
    -Dspring.profiles.active=generate-openapi \
    -Dopenapi.mode=super \
    -Dopenapi.output="$OUTPUT_DIR" \
    -Dspring.datasource.url="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME?useSSL=false&allowPublicKeyRetrieval=true" \
    -Dspring.datasource.username="$DB_USER" \
    -Dspring.datasource.password="$DB_PASS" \
    -Dspring.datasource.driver-class-name=com.mysql.jdbc.Driver \
    -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL5InnoDBDialect \
    org.oagi.srt.openapi.OpenApiApplication

# Auto-detect the versioned filename produced by the generator
SPEC_FILE=$(ls "$OUTPUT_DIR"/oagis-*-super-schema.openapi.yaml 2>/dev/null | head -1)
if [[ -z "$SPEC_FILE" ]]; then
    echo "ERROR: No oagis-*-super-schema.openapi.yaml found in $OUTPUT_DIR" >&2
    exit 1
fi
SPEC_NAME=$(basename "$SPEC_FILE")
echo "    Generated: $OUTPUT_DIR/$SPEC_NAME"

# -- Step 3: Validate --------------------------------------------------------

echo ">>> Step 3: Validating with Redocly..."
LINT_RC=0
npx @redocly/cli lint "$OUTPUT_DIR/$SPEC_NAME" \
    --config "$PROJECT_ROOT/redocly.yaml" \
    --format summary 2>&1 || LINT_RC=$?

if [[ "$LINT_RC" -ne 0 ]]; then
    echo ""
    echo "VALIDATION FAILED (exit code $LINT_RC)." >&2
    echo "  Fix errors in redocly.yaml or the generator, then re-run." >&2
    exit 1
fi
echo "    Validation passed (0 errors)."

# -- Step 4: Distribute ------------------------------------------------------

if $SKIP_DIST; then
    echo ">>> Step 4: Distribute SKIPPED (--skip-dist)"
else
    echo ">>> Step 4: Copying validated spec to dist/..."
    mkdir -p "$DIST_DIR"
    cp "$OUTPUT_DIR/$SPEC_NAME" "$DIST_DIR/$SPEC_NAME"
    echo "    Distributed: $DIST_DIR/$SPEC_NAME"
fi

# -- Summary -----------------------------------------------------------------

echo ""
echo "=== Pipeline Summary ==="
LINES=$(wc -l < "$OUTPUT_DIR/$SPEC_NAME" | tr -d ' ')
SIZE=$(du -h "$OUTPUT_DIR/$SPEC_NAME" | cut -f1)
ENUM_COUNT=$(grep -c "enum:" "$OUTPUT_DIR/$SPEC_NAME" || echo 0)
SCHEMA_COUNT=$(grep -c "^    [A-Z]" "$OUTPUT_DIR/$SPEC_NAME" || echo "?")
echo "  Lines       : $LINES"
echo "  File size   : $SIZE"
echo "  Enum arrays : $ENUM_COUNT"
echo "  Schemas     : ~$SCHEMA_COUNT"

if ! $SKIP_DIST; then
    echo ""
    echo "  GitHub raw URL (after push):"
    echo "  https://raw.githubusercontent.com/julioarguello/fuseme-oagi-nist/\$(git branch --show-current)/dist/$SPEC_NAME"
fi

echo ""
echo "=== Pipeline completed successfully ==="

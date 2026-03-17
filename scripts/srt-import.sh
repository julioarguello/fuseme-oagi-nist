#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# srt-import.sh -- OAGIS 10.3 CC Table Import Script
#
# Orchestrates the full import pipeline: MariaDB setup, schema creation,
# Maven build, and RunAll execution. Designed to be idempotent -- safe to
# re-run after failures.
#
# Usage:
#   ./srt-import.sh          # full import
#   ./srt-import.sh --reset  # drop + recreate database before import
#
# Prerequisites:
#   - Docker (for MariaDB)
#   - jenv with JDK 1.8 configured
#   - Maven 3.x
# -----------------------------------------------------------------------------
set -euo pipefail

# (JAF), 20260317, Orchestration script to prevent ordering errors during import
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTAINER_NAME="oagis-mariadb"
DB_NAME="oagi"
DB_USER="oagi"
DB_PASS="oagi"
DB_ROOT_PASS="oagi"
MARIADB_IMAGE="mariadb:10.6"
SCHEMA_SQL="$PROJECT_ROOT/srt-import/src/main/resources/schema-mysql.sql"
JAVA8_HOME="$(/usr/libexec/java_home -v 1.8.0_211 2>/dev/null || /usr/libexec/java_home -v 1.8 2>/dev/null || echo "")"

# -- Preflight checks --------------------------------------------------------

if [[ -z "$JAVA8_HOME" ]]; then
    echo "ERROR: JDK 1.8 not found. Install Java 8 or configure jenv." >&2
    exit 1
fi

if ! command -v docker &>/dev/null; then
    echo "ERROR: Docker is not installed or not in PATH." >&2
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    echo "ERROR: Maven is not installed or not in PATH." >&2
    exit 1
fi

echo "=== OAGIS CC Import Pipeline ==="
echo "  Project root : $PROJECT_ROOT"
echo "  Java 8 home  : $JAVA8_HOME"
echo "  MariaDB image: $MARIADB_IMAGE"
echo ""

# -- Step 1: MariaDB ---------------------------------------------------------

RESET=false
if [[ "${1:-}" == "--reset" ]]; then
    RESET=true
fi

if $RESET || ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo ">>> Step 1: Starting MariaDB ($CONTAINER_NAME)..."

    # Remove existing container if resetting
    if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
        docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true
    fi

    docker run -d \
        --name "$CONTAINER_NAME" \
        -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASS" \
        -e MYSQL_DATABASE="$DB_NAME" \
        -e MYSQL_USER="$DB_USER" \
        -e MYSQL_PASSWORD="$DB_PASS" \
        -p 3306:3306 \
        "$MARIADB_IMAGE" \
        --lower_case_table_names=1

    echo "    Waiting for MariaDB to be ready..."
    for i in $(seq 1 30); do
        if docker exec "$CONTAINER_NAME" mysql -u"$DB_USER" -p"$DB_PASS" -e "SELECT 1" >/dev/null 2>&1; then
            echo "    MariaDB is ready."
            break
        fi
        if [[ $i -eq 30 ]]; then
            echo "ERROR: MariaDB did not start within 30 seconds." >&2
            exit 1
        fi
        sleep 1
    done
else
    echo ">>> Step 1: MariaDB ($CONTAINER_NAME) already running."
fi

# -- Step 2: Schema ----------------------------------------------------------

echo ">>> Step 2: Applying schema (${SCHEMA_SQL##*/})..."
docker exec "$CONTAINER_NAME" mysql -uroot -p"$DB_ROOT_PASS" -e \
    "DROP DATABASE IF EXISTS $DB_NAME; CREATE DATABASE $DB_NAME; GRANT ALL ON ${DB_NAME}.* TO '${DB_USER}'@'%';" 2>/dev/null

docker cp "$SCHEMA_SQL" "$CONTAINER_NAME:/tmp/schema.sql"
docker exec "$CONTAINER_NAME" mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -e "source /tmp/schema.sql;" 2>/dev/null

TABLE_COUNT=$(docker exec "$CONTAINER_NAME" mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -sN \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';" 2>/dev/null)
echo "    Created $TABLE_COUNT tables."

# -- Step 3: Build -----------------------------------------------------------

echo ">>> Step 3: Building srt-import modules (Java 8)..."
cd "$PROJECT_ROOT"
JAVA_HOME="$JAVA8_HOME" mvn clean install \
    -pl srt-common,srt-repository,srt-service,srt-import \
    -DskipTests -q

echo "    Build complete."

# -- Step 4: Generate classpath and run import --------------------------------

echo ">>> Step 4: Running RunAll import..."
JAVA_HOME="$JAVA8_HOME" mvn -f srt-import/pom.xml dependency:build-classpath \
    -Dmdep.outputFile=/tmp/srt-cp.txt -q

"$JAVA8_HOME/bin/java" \
    -cp "srt-import/target/classes:$(cat /tmp/srt-cp.txt)" \
    org.oagi.srt.persistence.populate.RunAll

echo "    Import complete."

# -- Step 5: Verify ----------------------------------------------------------

echo ">>> Step 5: Verifying row counts..."
echo ""
docker exec "$CONTAINER_NAME" mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -e \
    "SELECT 'acc' AS tbl, COUNT(*) AS cnt FROM acc
     UNION ALL SELECT 'ascc', COUNT(*) FROM ascc
     UNION ALL SELECT 'bcc', COUNT(*) FROM bcc
     UNION ALL SELECT 'asccp', COUNT(*) FROM asccp
     UNION ALL SELECT 'bccp', COUNT(*) FROM bccp
     UNION ALL SELECT 'dt', COUNT(*) FROM dt
     UNION ALL SELECT 'dt_sc', COUNT(*) FROM dt_sc
     UNION ALL SELECT 'code_list', COUNT(*) FROM code_list
     UNION ALL SELECT 'code_list_value', COUNT(*) FROM code_list_value
     UNION ALL SELECT 'module', COUNT(*) FROM module;" 2>/dev/null

echo ""

# -- Step 6: Backup -----------------------------------------------------------

BACKUP_FILE="$PROJECT_ROOT/backup-oagis-10.3.sql"
echo ">>> Step 6: Creating backup at ${BACKUP_FILE##*/}..."
docker exec "$CONTAINER_NAME" mysqldump -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" \
    > "$BACKUP_FILE" 2>/dev/null
echo "    Backup created ($(wc -l < "$BACKUP_FILE") lines)."

echo ""
echo "=== Import pipeline completed successfully ==="

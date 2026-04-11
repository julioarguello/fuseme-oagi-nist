#!/usr/bin/env python3
"""
GitLab Operations Script (dbt-upc)
Automatiza sincronización y subida de cambios al repositorio GitLab.

Uso:
    python gitlab_ops.py diff              # Ver cambios agrupados por tipo dbt
    python gitlab_ops.py commit_push "msg" # Commit y push
    python gitlab_ops.py sync              # Pull cambios remotos
    python gitlab_ops.py status            # Estado simple
"""

import argparse
import json
import subprocess
from collections import defaultdict
from pathlib import Path
from typing import Optional


def get_remote_name():
    """Detecta el nombre del remote configurado (origin, upc, etc.)."""
    result = subprocess.run(
        ['git', 'remote'],
        capture_output=True, text=True
    )
    remotes = result.stdout.strip().split('\n')
    if not remotes or remotes == ['']:
        return None
    # Preferir 'origin', si no usar el primero disponible
    if 'origin' in remotes:
        return 'origin'
    return remotes[0]

# Constantes
ERR_NO_REMOTE = "   [ERR] No hay remote configurado"


# Categorías de archivos dbt
DBT_CATEGORIES = {
    'models_sql': {
        'patterns': ['models/**/*.sql'],
        'icon': '[SQL]',
        'name': 'Modelos SQL'
    },
    'schemas_yaml': {
        'patterns': ['models/**/*.yml', 'models/**/*.yaml'],
        'icon': '[YML]',
        'name': 'Schemas YAML'
    },
    'docs_md': {
        'patterns': ['models/**/*.md'],
        'icon': '[DOC]',
        'name': 'Docs MD'
    },
    'sources': {
        'patterns': ['models/**/*sources*.yml'],
        'icon': '[SRC]',
        'name': 'Sources'
    },
    'macros': {
        'patterns': ['macros/**/*.sql'],
        'icon': '[MAC]',
        'name': 'Macros'
    },
    'tests': {
        'patterns': ['tests/**/*.sql', 'tests/**/*.yml'],
        'icon': '[TST]',
        'name': 'Tests'
    },
    'seeds': {
        'patterns': ['seeds/**/*'],
        'icon': '[SED]',
        'name': 'Seeds'
    },
    'snapshots': {
        'patterns': ['snapshots/**/*.sql'],
        'icon': '[SNP]',
        'name': 'Snapshots'
    },
    'agent': {
        'patterns': ['.agent/**/*'],
        'icon': '[AGT]',
        'name': 'Config Agente'
    },
    'docs_ddl': {
        'patterns': ['docs/**/*.sql', 'docs/**/*.md'],
        'icon': '[DDL]',
        'name': 'Docs/DDL'
    },
    'config': {
        'patterns': ['dbt_project.yml', 'profiles.yml', 'packages.yml'],
        'icon': '[CFG]',
        'name': 'Config dbt'
    },
    'other': {
        'patterns': ['*'],
        'icon': '[OTH]',
        'name': 'Otros'
    }
}

# Prefix → category mapping for categorize_file (reduces Cognitive Complexity)
_MODELS_PREFIX = 'models/'
_PREFIX_CATEGORY_MAP = [
    (_MODELS_PREFIX, 'models_sql', '.sql'),
    (_MODELS_PREFIX, 'schemas_yaml', '.yml'),
    (_MODELS_PREFIX, 'schemas_yaml', '.yaml'),
    (_MODELS_PREFIX, 'docs_md', '.md'),
    ('macros/', 'macros', None),
    ('tests/', 'tests', None),
    ('seeds/', 'seeds', None),
    ('snapshots/', 'snapshots', None),
    ('docs/', 'docs_ddl', None),
]

_CONFIG_FILES = {'dbt_project.yml', 'profiles.yml', 'packages.yml'}


def get_current_branch():
    """Obtiene la rama actual de Git."""
    result = subprocess.run(
        ['git', 'branch', '--show-current'],
        capture_output=True, text=True
    )
    return result.stdout.strip()

def get_git_status_porcelain():
    """Obtiene el estado de Git en formato porcelain."""
    result = subprocess.run(
        ['git', 'status', '--porcelain'],
        capture_output=True, text=True
    )
    return result.stdout.strip().split('\n') if result.stdout.strip() else []


def _parse_porcelain_line(line: str) -> tuple:
    """Parse a git porcelain status line into (status, filepath)."""
    status = str(line[0:2]).strip()  # pyre-ignore[16]
    filepath = str(line[3:]).strip()  # pyre-ignore[16]
    if ' -> ' in filepath:
        filepath = filepath.split(' -> ')[1]
    return status, filepath


def categorize_file(filepath: str) -> str:
    """Categoriza un archivo según las reglas de dbt."""
    # Priority rules (order matters)
    if '.agent/' in filepath or '.agent\\' in filepath:
        return 'agent'
    if 'sources' in filepath.lower() and filepath.endswith('.yml'):
        return 'sources'
    if filepath in _CONFIG_FILES:
        return 'config'

    # Prefix-based lookup
    for prefix, category, ext in _PREFIX_CATEGORY_MAP:
        if filepath.startswith(prefix) or filepath.startswith(prefix.replace('/', '\\')):
            if ext is None or filepath.endswith(ext):
                return category

    return 'other'

def get_status_icon(status_code: str) -> str:
    """Convierte código de estado a icono."""
    icons = {
        'M': '[M]',  # Modified
        'A': '[A]',  # Added
        'D': '[D]',  # Deleted
        'R': '[R]',  # Renamed
        '?': '[?]',  # Untracked
        'U': '[U]',  # Updated
    }
    return icons.get(status_code[0], f'[{status_code}]')

def show_diff():
    """Muestra cambios pendientes agrupados por categoría dbt."""
    changes = get_git_status_porcelain()

    if not changes:
        print("\n[OK] No hay cambios pendientes.\n")
        return {}

    # Agrupar por categoría
    categorized = defaultdict(list)

    for line in changes:
        if not line.strip():
            continue
        status, filepath = _parse_porcelain_line(line)
        category = categorize_file(filepath)
        categorized[category].append((status, filepath))

    # Mostrar
    branch = get_current_branch()
    total_changes = sum(len(files) for files in categorized.values())

    print(f"\n[PKG] Cambios en dbt-upc [{branch}] ({total_changes} archivos)")
    print("=" * 60)

    # Orden de presentación
    order = ['models_sql', 'schemas_yaml', 'docs_md', 'sources', 'macros',
             'tests', 'seeds', 'snapshots', 'agent', 'docs_ddl', 'config', 'other']

    for cat_key in order:
        if cat_key not in categorized:
            continue

        files = categorized[cat_key]
        cat = DBT_CATEGORIES[cat_key]

        print(f"\n{cat['icon']} {cat['name']} ({len(files)})")
        for status, filepath in files:
            icon = get_status_icon(status)
            print(f"   {icon} {filepath}")

    print("\n" + "=" * 60)

    # Sugerir tipo de commit
    suggestion = suggest_commit_type(categorized)
    print(f"\n[TIP] Sugerencia de commit: {suggestion}")

    return dict(categorized)

def suggest_commit_type(categorized: dict) -> str:
    """Sugiere el tipo de commit basado en los cambios."""
    categories = set(categorized.keys())

    # Solo agente
    if categories == {'agent'}:
        return "chore(agent): ..."

    # Solo docs
    if categories <= {'docs_md', 'docs_ddl'}:
        return "docs: ..."

    # Nuevos modelos (archivos añadidos en models_sql)
    if 'models_sql' in categories:
        has_new = any(s.startswith('A') or s.startswith('?')
                      for s, _ in categorized.get('models_sql', []))
        if has_new:
            return "feat(modelo): add new model..."
        return "fix(modelo): ..."

    # Solo YAML/config
    if categories <= {'schemas_yaml', 'config', 'sources'}:
        if 'sources' in categories:
            return "feat(sources): add/update source..."
        return "refactor: update schema configuration"

    # Macros
    if 'macros' in categories:
        return "feat(macros): ..."

    # Tests
    if categories == {'tests'}:
        return "test: ..."

    # Múltiples categorías
    return "feat: multiple updates"

def sync_repo():
    """Sincroniza el repositorio con el remoto."""
    branch = get_current_branch()
    remote = get_remote_name()

    if not remote:
        print(ERR_NO_REMOTE)
        return False

    print(f"\n[SYNC] Sincronizando rama '{branch}'...")

    # Fetch
    print(f"   Fetching {remote}...")
    result = subprocess.run(['git', 'fetch', remote], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en fetch: {result.stderr}")
        return False

    # Pull
    print("   Pulling...")
    result = subprocess.run(['git', 'pull', remote, branch], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en pull: {result.stderr}")
        return False

    print("   [OK] Sincronizacion completada\n")
    return True

def commit_and_push(message: str):
    """Hace commit y push de todos los cambios."""
    branch = get_current_branch()
    remote = get_remote_name()

    if not remote:
        print(ERR_NO_REMOTE)
        return False

    print(f"\n[GIT] Commit y Push a '{branch}'")
    print("=" * 60)

    # Add all
    print("   Adding files...")
    result = subprocess.run(['git', 'add', '-A'], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en add: {result.stderr}")
        return False

    # Commit
    print(f"   Committing: {message}")
    result = subprocess.run(['git', 'commit', '-m', message], capture_output=True, text=True)
    if result.returncode != 0:
        if 'nothing to commit' in result.stdout:
            print("   [WARN] No hay cambios para commit")
            return True
        print(f"   [ERR] Error en commit: {result.stderr}")
        return False
    print(result.stdout)

    # Push
    print(f"   Pushing to {remote}/{branch}...")
    result = subprocess.run(['git', 'push', remote, branch], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en push: {result.stderr}")
        return False

    print("   [OK] Push completado!")
    print("=" * 60 + "\n")
    return True

def show_status():
    """Muestra estado simple de Git."""
    branch = get_current_branch()
    changes = get_git_status_porcelain()

    print(f"\n[STATUS] Estado: {branch}")
    print(f"   Archivos modificados: {len(changes)}")

    if changes:
        print("\n   Cambios:")
        for line in list(changes)[0:10]:  # pyre-ignore[16]
            print(f"      {line}")
        if len(changes) > 10:
            print(f"      ... y {len(changes) - 10} más")
    print()

def _ensure_feature_branch(_current: str, new_branch: str) -> Optional[str]:
    """Create or checkout feature branch. Returns branch name or None on failure."""
    print(f"\n   [2/3] Creando rama '{new_branch}'...")
    result = subprocess.run(['git', 'checkout', '-b', new_branch], capture_output=True, text=True)
    if result.returncode == 0:
        print(f"         [OK] Ahora en rama '{new_branch}'")
        return new_branch

    if 'already exists' not in result.stderr:
        print(f"   [ERR] Error creando rama: {result.stderr}")
        return None

    print("         Rama existe, haciendo checkout...")
    result = subprocess.run(['git', 'checkout', new_branch], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en checkout: {result.stderr}")
        return None
    print(f"         [OK] Ahora en rama '{new_branch}'")
    return new_branch


def _merge_remote_main(remote: str) -> bool:
    """Merge with remote main/master branch. Returns True on success."""
    remote_main = f'{remote}/master'
    check = subprocess.run(['git', 'rev-parse', '--verify', f'{remote}/master'],
                          capture_output=True, text=True)
    if check.returncode != 0:
        remote_main = f'{remote}/main'

    print(f"\n   [3/3] Merge con {remote_main}...")
    result = subprocess.run(['git', 'merge', remote_main, '--no-edit'],
                           capture_output=True, text=True)
    if result.returncode == 0:
        print(f"         [OK] Merge con {remote_main} completado")
        return True

    if 'Already up to date' in result.stdout:
        print(f"         [OK] Ya actualizado con {remote_main}")
        return True
    if 'CONFLICT' in result.stdout or 'CONFLICT' in result.stderr:
        print("   [ERR] Conflictos detectados. Resuelve manualmente.")
        print(result.stdout)
        return False
    print(f"   [ERR] Error en merge: {result.stderr}")
    return False


def prepare_repo(new_branch: Optional[str] = None):
    """Prepara el repositorio: fetch, branch, merge."""
    current = get_current_branch()

    print("\n[PREP] Preparando repositorio...")
    print(f"   Rama actual: {current}")
    print("=" * 60)

    remote = get_remote_name()
    if not remote:
        print(ERR_NO_REMOTE)
        return False

    print(f"\n   [1/3] Fetching {remote}...")
    result = subprocess.run(['git', 'fetch', remote], capture_output=True, text=True)
    if result.returncode != 0:
        print(f"   [ERR] Error en fetch: {result.stderr}")
        return False
    print("         [OK] Fetch completado")

    if current in ('master', 'main'):
        if not new_branch:
            print("   [ERR] Estas en master/main. Debes especificar --branch <nombre>")
            return False
        current = _ensure_feature_branch(current, new_branch)
        if current is None:
            return False
    else:
        print(f"\n   [2/3] Ya en rama feature '{current}', saltando creacion...")

    if not _merge_remote_main(remote):
        return False

    print("\n" + "=" * 60)
    print(f"[OK] Repositorio preparado. Rama actual: {current}")
    print("     Ahora puedes hacer commit_push\n")
    return True

def generate_report():
    """Genera un reporte JSON del estado de Git."""
    changes = get_git_status_porcelain()
    categorized = defaultdict(list)

    for line in changes:
        if not line.strip():
            continue
        status, filepath = _parse_porcelain_line(line)
        category = categorize_file(filepath)
        categorized[category].append({'status': status, 'file': filepath})

    report = {
        'branch': get_current_branch(),
        'total_changes': len(changes),
        'changes_by_category': dict(categorized)
    }

    report_path = Path('.agent/git_status_report.json')
    report_path.write_text(json.dumps(report, indent=2))
    print(f"[FILE] Reporte generado: {report_path}")

    return report

def main():
    parser = argparse.ArgumentParser(description='GitLab Operations (dbt-upc)')
    subparsers = parser.add_subparsers(dest='command')

    # diff
    subparsers.add_parser('diff', help='Ver cambios agrupados por tipo dbt')

    # prepare
    prep_parser = subparsers.add_parser('prepare', help='Preparar repo: fetch + branch + merge')
    prep_parser.add_argument('--branch', '-b', help='Nombre rama feature (requerido si en master)')

    # commit_push
    cp_parser = subparsers.add_parser('commit_push', help='Commit y push')
    cp_parser.add_argument('message', help='Mensaje del commit')

    # sync
    subparsers.add_parser('sync', help='Sincronizar con remoto')

    # status
    subparsers.add_parser('status', help='Estado simple')

    # report
    subparsers.add_parser('report', help='Generar reporte JSON')

    args = parser.parse_args()

    if args.command == 'diff':
        show_diff()
    elif args.command == 'prepare':
        prepare_repo(getattr(args, 'branch', None))
    elif args.command == 'commit_push':
        commit_and_push(args.message)
    elif args.command == 'sync':
        sync_repo()
    elif args.command == 'status':
        show_status()
    elif args.command == 'report':
        generate_report()
    else:
        # Default: mostrar diff
        show_diff()

if __name__ == '__main__':
    main()

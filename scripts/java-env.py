#!/usr/bin/env python3
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional


def _parse_env_file(filepath: Path) -> dict[str, str]:
    """Parse a KEY=VALUE env file, ignoring comments and blank lines.

    Accepts both plain `KEY=VALUE` and `export KEY=VALUE` formats
    (consistent with dbt-env.py).
    """
    env_vars: dict[str, str] = {}
    if not filepath.exists():
        return env_vars
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                # Strip optional 'export ' prefix (Bash shell source compatibility)
                if line.startswith("export "):
                    line = line[7:]  # len("export ") == 7
                key, val = line.split("=", 1)
                env_vars[key.strip()] = val.strip()
    return env_vars


def _expand_vars(env_vars: dict[str, str]) -> dict[str, str]:
    """Expand ${VAR} references within env_vars values."""
    expanded: dict[str, str] = {}
    for key, val in env_vars.items():
        expanded[key] = re.sub(
            r"\$\{(\w+)\}",
            lambda m: env_vars.get(m.group(1), os.environ.get(m.group(1), m.group(0))),
            val,
        )
    return expanded


def _resolve_env_file(config_dir: Path, stem: str) -> Path:
    """Resolve an env file path, preferring .env but falling back to .properties.

    This allows Karaf/Maven projects to use .{env}.properties instead of .{env}.env,
    eliminating the 'Unknown properties resource extension' warning from
    properties-maven-plugin.
    """
    for ext in (".env", ".properties"):
        candidate = config_dir / (stem + ext)
        if candidate.exists():
            return candidate
    return config_dir / (stem + ".env")  # default path (may not exist — caller handles)


def load_env(base_dir: Path, env_name: str) -> dict[str, str]:
    """Load dual-file env: .{env}.env|.properties (secrets) + {env}.env|.properties (config).

    Either file alone is valid (backward compatible).
    Both .env and .properties extensions are accepted (tried in that order).
    Loading order: secrets first, then config — config can reference secret keys via ${VAR}.
    """
    config_dir = base_dir / "src" / "main" / "config"
    secrets_file = _resolve_env_file(config_dir, f".{env_name}")
    config_file = _resolve_env_file(config_dir, env_name)

    if not secrets_file.exists() and not config_file.exists():
        print(f"Error: No env files found for '{env_name}' in {config_dir}", file=sys.stderr)
        if config_dir.exists():
            available = sorted({f.stem.lstrip(".") for f in config_dir.iterdir()
                                if f.suffix in (".env", ".properties")})
            if available:
                print(f"Available environments: {', '.join(available)}", file=sys.stderr)
        sys.exit(1)

    # Load secrets first, then config (config can reference secrets via ${VAR})
    env_vars = _parse_env_file(secrets_file)
    env_vars.update(_parse_env_file(config_file))
    return _expand_vars(env_vars)


def _resolve_java_home(base_dir: Path, env_vars: dict[str, str]) -> Optional[str]:
    """Resolve JAVA_HOME from .java-version (jenv) when env files don't set it.

    The jenv export plugin only works in interactive shells (precmd hook), so
    non-interactive contexts (Antigravity, subprocess.run) inherit a stale JAVA_HOME.
    """
    if "JAVA_HOME" in env_vars:
        return None
    java_version_file = base_dir / ".java-version"
    if not java_version_file.exists():
        return None
    try:
        result = subprocess.run(
            ["jenv", "javahome"],
            capture_output=True, text=True, check=True, cwd=str(base_dir),
        )
        jenv_home = result.stdout.strip()
        if jenv_home and Path(jenv_home).is_dir():
            return jenv_home
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass  # jenv not installed or failed — fall through to inherited JAVA_HOME
    return None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Java Environment Setup Tool (java-env)",
        epilog="Examples:\n"
               "  . java-env.sh -e dev            # Export env vars for dev\n"
               "  java-env.py -e dev -r 'mvn test' # Run maven test in dev context\n",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("-e", "--env", default="dev",
                        help="Target environment workspace (default: dev)")
    parser.add_argument("-b", "-d", "--base-dir", default=None,
                        help="Root directory of the module containing src/main/config/. "
                             "Auto-detected in cwd if not specified.")
    parser.add_argument("-r", "--run", type=str, default=None,
                        help="Run a command within the configured environment context")

    args = parser.parse_args()

    base_dir = Path(args.base_dir).resolve() if args.base_dir else Path.cwd().resolve()
    env_vars = load_env(base_dir, args.env)

    if args.run:
        run_env = os.environ.copy()
        run_env["DEPLOY_ENV"] = args.env
        run_env.update(env_vars)
        java_home = _resolve_java_home(base_dir, env_vars)
        if java_home:
            run_env["JAVA_HOME"] = java_home
            print(f"  JAVA_HOME={java_home} (from .java-version via jenv)")
        print(f"Running: {args.run} in workspace '{args.env}'")
        try:
            subprocess.run(args.run, shell=True, check=True, env=run_env)
        except subprocess.CalledProcessError as e:
            print(f"Command failed with exit code {e.returncode}", file=sys.stderr)
            sys.exit(e.returncode)
    else:
        # Export DEPLOY_ENV so downstream scripts auto-detect the active environment
        print(f"export DEPLOY_ENV='{args.env}';")
        for k, v in env_vars.items():
            escaped_v = str(v).replace("'", "'\\''")
            print(f"export {k}='{escaped_v}';")
        print(f"echo \"Workspace '{args.env}' environment loaded.\" >&2;")


if __name__ == "__main__":
    main()

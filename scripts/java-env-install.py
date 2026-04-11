#!/usr/bin/env python3
"""
java-install-env.py — Install java-env skill resources into the project's .vscode/ directory.

Copies formatter configs, extensions, and code snippets from the skill's
resources/ directory into the project's .vscode/ directory. For JSON settings
files, performs a smart merge:

  - New keys: added silently.
  - Same value: skipped silently.
  - Different value: prompts the user to overwrite (unless --force).
  - Keys in current but NOT in template: preserved silently.

Run from anywhere inside the project:
    python .agent/skills/java-env/scripts/java-install-env.py
    java-install-env              # (if scripts/ is in PATH via setup.py)
    java-install-env --force      # non-interactive mode

Automatically invoked by .agent/setup.py during initial setup.
"""

import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, List, Optional, Tuple


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_SCRIPT_DIR = Path(__file__).resolve().parent
_RESOURCES_DIR = _SCRIPT_DIR.parent / "resources"

_JAVA_HOME_PLACEHOLDER = "<JAVA_HOME>"

# Files that are copied verbatim (canonical, no user customisation expected).
_VERBATIM_FILES = [
    "camel-eclipse-formatter-config.xml",
    "serverstartup-eclipse-formatter-config.xml",
    "extensions.json",
]


# ---------------------------------------------------------------------------
# Terminal Colors & Logging
# ---------------------------------------------------------------------------


class C:
    RESET = '\033[0m'
    BOLD = '\033[1m'
    BLUE = '\033[34m'
    GREEN = '\033[32m'
    YELLOW = '\033[33m'
    RED = '\033[31m'
    DIM = '\033[2m'


def p_head(msg: str): print(f"\n{C.BOLD}{C.BLUE}==>{C.RESET} {C.BOLD}{msg}{C.RESET}")
def p_info(msg: str): print(f"{msg}")
def p_ok(msg: str):   print(f"  {C.GREEN}+{C.RESET} {msg}")
def p_warn(msg: str): print(f"  {C.YELLOW}~{C.RESET} {msg}")
def p_err(msg: str):  print(f"  {C.RED}!{C.RESET} {msg}", file=sys.stderr)
def p_eq(msg: str):   print(f"  {C.DIM}={C.RESET} {C.DIM}{msg}{C.RESET}")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _git_config(key: str, cwd: Optional[Path] = None) -> str:
    """
    Read a git config value.

    When *cwd* is provided, ``git config`` runs in that directory so it
    picks up the repository-local config before falling back to global.

    Returns the value or an empty string if unavailable.
    """
    try:
        result = subprocess.run(
            ["git", "config", key],
            capture_output=True, text=True, check=True,
            cwd=cwd,
        )

        return result.stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def _detect_java_home() -> str:
    """
    Attempt to detect a JDK 21 installation.

    Returns the detected path or the placeholder ``<JAVA_HOME>``.
    """
    candidates: List[Path] = []
    if platform.system() == "Darwin":
        # macOS standard JDK locations
        candidates = [
            c / "Contents" / "Home"
            for c in sorted(
                Path("/Library/Java/JavaVirtualMachines").glob("temurin-21*"),
                reverse=True,
            )
        ]
    else:
        # Linux common locations
        for base in [Path("/usr/lib/jvm"), Path("/opt/java")]:
            if base.exists():
                candidates.extend(sorted(base.glob("*21*"), reverse=True))

    for candidate in candidates:
        if (candidate / "bin" / "java").exists():
            return str(candidate)

    return _JAVA_HOME_PLACEHOLDER


def _strip_line_comment(line: str) -> Optional[str]:
    """
    Strip a single-line ``//`` comment from *line*.

    Returns ``None`` if the entire line is a comment, or the cleaned line.
    """
    stripped = line.lstrip()
    if stripped.startswith("//"):
        return None

    # Inline comment: find // not inside a string (heuristic: even quote count).
    idx = line.find("//")
    if idx > 0 and line[:idx].count('"') % 2 == 0:  # pyre-ignore[16]
        return line[:idx].rstrip()  # pyre-ignore[16]

    return line


def _strip_json_comments(text: str) -> str:
    """
    Strip single-line ``//`` comments and trailing commas from JSON-with-comments.

    This makes VS Code ``settings.json`` (which allows comments) parseable
    by the standard ``json`` module.
    """
    lines = text.splitlines()
    cleaned = [_strip_line_comment(line) for line in lines]
    cleaned = [line for line in cleaned if line is not None]

    result = "\n".join(cleaned)

    # Remove trailing commas before } or ] (common in VS Code JSON).
    result = re.sub(r",\s*([}\]])", r"\1", result)

    return result


def _load_jsonc(path: Path) -> dict:
    """
    Load a JSON-with-comments file.

    Returns an empty dict if the file does not exist or is not valid JSON.
    """
    if not path.exists():
        return {}
    try:
        text = path.read_text(encoding="utf-8")
        clean = _strip_json_comments(text)

        return json.loads(clean)
    except (json.JSONDecodeError, OSError):
        return {}


def _prompt_overwrite(full_key: str, cur_value: Any, tpl_value: Any,
                      force: bool) -> Tuple[bool, str]:
    """
    Decide whether to overwrite *cur_value* with *tpl_value*.

    Returns ``(should_overwrite, log_message)``.
    """
    if force:
        return True, f"  {C.YELLOW}~{C.RESET} Overwritten (--force): {full_key}"

    p_warn(f"\"{full_key}\" differs:")
    p_info(f"      Current:  {json.dumps(cur_value, ensure_ascii=False)}")
    p_info(f"      Template: {json.dumps(tpl_value, ensure_ascii=False)}")
    answer = input("      Overwrite? [y/N]: ").strip().lower()

    if answer == "y":
        return True, f"  {C.YELLOW}~{C.RESET} Overwritten: {full_key}"
    return False, f"  {C.DIM}={C.RESET} {C.DIM}Kept current value: {full_key}{C.RESET}"


def _deep_merge(current: dict, template: dict, path_prefix: str,
                force: bool) -> dict:
    """
    Recursively merge *template* into *current*.

    For each key in *template*:
      - Missing in current → add silently.
      - Same value → skip.
      - Different value → prompt user (or overwrite if *force*).

    Keys in *current* but NOT in *template* are preserved.

    Returns the merged dict.
    """
    merged = dict(current)

    for key, tpl_value in template.items():
        full_key = f"{path_prefix}.{key}" if path_prefix else key

        if key not in current:
            merged[key] = tpl_value
            p_ok(f"Added: {full_key}")
        elif current[key] == tpl_value:
            continue
        elif isinstance(current[key], dict) and isinstance(tpl_value, dict):
            merged[key] = _deep_merge(current[key], tpl_value, full_key,
                                      force)
        else:
            overwrite, msg = _prompt_overwrite(
                full_key, current[key], tpl_value, force,
            )
            if overwrite:
                merged[key] = tpl_value
            print(msg)

    return merged


def _write_json(path: Path, data: dict) -> None:
    """
    Write *data* as pretty-printed JSON to *path*.
    """
    path.write_text(
        json.dumps(data, indent=4, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


# ---------------------------------------------------------------------------
# Installers
# ---------------------------------------------------------------------------

def _install_verbatim(resources_dir: Path, vscode_dir: Path) -> None:
    """
    Copy canonical files that don't need merging.
    """
    for filename in _VERBATIM_FILES:
        src = resources_dir / filename
        dst = vscode_dir / filename
        if not src.exists():
            p_err(f"Resource not found: {src.name}")
            continue
        shutil.copy2(src, dst)
        p_ok(f"Copied: {filename}")


def _install_settings(resources_dir: Path, vscode_dir: Path,
                      java_home: str, force: bool) -> None:
    """
    Merge settings.json.template into .vscode/settings.json.
    """
    template_path = resources_dir / "settings.json.template"
    target_path = vscode_dir / "settings.json"

    if not template_path.exists():
        p_err("settings.json.template not found in resources.")
        return

    # Load and render template (replace <JAVA_HOME>).
    template_text = template_path.read_text(encoding="utf-8")
    template_text = template_text.replace("<JAVA_HOME>", java_home)
    template_data = json.loads(_strip_json_comments(template_text))

    current_data = _load_jsonc(target_path)

    if not current_data:
        # No existing settings — write directly.
        _write_json(target_path, template_data)
        p_ok("Created: settings.json")
    else:
        # Merge with conflict detection.
        p_head("Merging settings.json")
        merged = _deep_merge(current_data, template_data, "", force)
        _write_json(target_path, merged)
        p_eq("Merged: settings.json")


def _install_snippets(resources_dir: Path, vscode_dir: Path,
                      author_name: str, author_email: str,
                      force: bool) -> None:
    """
    Render java.code-snippets.template and merge into .vscode/java.code-snippets.
    """
    template_path = resources_dir / "java.code-snippets.template"
    target_path = vscode_dir / "java.code-snippets"

    if not template_path.exists():
        p_err("java.code-snippets.template not found in resources.")
        return

    # Render template.
    template_text = template_path.read_text(encoding="utf-8")
    template_text = template_text.replace("<AUTHOR_NAME>", author_name)
    template_text = template_text.replace("<AUTHOR_EMAIL>", author_email)
    template_data = json.loads(template_text)

    current_data = _load_jsonc(target_path)

    if not current_data:
        _write_json(target_path, template_data)
        p_ok("Created: java.code-snippets")
    else:
        p_head("Merging java.code-snippets")
        merged = _deep_merge(current_data, template_data, "", force)
        _write_json(target_path, merged)
        p_eq("Merged: java.code-snippets")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    """
    Entry point — orchestrates resource installation.
    """
    force = "--force" in sys.argv

    # Locate project root (walk up from script to .agent → parent).
    base_dir = _SCRIPT_DIR.parent.parent.parent  # scripts → java-env → skills → base_dir
    
    if base_dir.name == ".agent":
        agent_dir = base_dir
        project_dir = agent_dir.parent
    else:
        # Inception mode: we are running inside the skills repo itself
        project_dir = base_dir
        agent_dir = project_dir / ".agent"
        if not agent_dir.is_dir():
            p_err(f"Expected .agent directory, got {base_dir.name}")
            return 1

    vscode_dir = project_dir / ".vscode"

    p_head("Java Environment Installation")
    p_info(f"  Project:   {project_dir}")
    p_info(f"  Resources: {_RESOURCES_DIR}")
    p_info(f"  Target:    {vscode_dir}")

    if not _RESOURCES_DIR.exists():
        p_err(f"Resources directory not found: {_RESOURCES_DIR}")
        return 1

    # Ensure .vscode/ exists.
    vscode_dir.mkdir(parents=True, exist_ok=True)

    # 1. Detect git user identity (run in project_dir so repo-local config
    #    takes precedence over global — .agent/ is a separate git repo).
    author_email = _git_config("user.email", cwd=project_dir) or "your.email@example.com"
    author_name = _git_config("user.name", cwd=project_dir) or "Your Name"
    p_head(f"Git Identity: {author_name} <{author_email}>")

    # 2. Detect JAVA_HOME.
    java_home = _detect_java_home()
    if java_home == _JAVA_HOME_PLACEHOLDER:
        p_warn(f"Could not auto-detect JDK 21. Using placeholder {_JAVA_HOME_PLACEHOLDER}.")
        p_info("  Edit .vscode/settings.json manually after installation.")
    else:
        p_info(f"  JDK 21 detected: {java_home}")

    # 3. Install verbatim files (formatter, extensions).
    p_head("Installing Canonical Files")
    _install_verbatim(_RESOURCES_DIR, vscode_dir)

    # 4. Merge settings.json.
    p_head("Installing settings.json")
    _install_settings(_RESOURCES_DIR, vscode_dir, java_home, force)

    # 5. Merge code snippets.
    p_head("Installing code snippets")
    _install_snippets(_RESOURCES_DIR, vscode_dir, author_name, author_email,
                      force)

    p_head("Installation Complete!")

    return 0


if __name__ == "__main__":
    sys.exit(main())

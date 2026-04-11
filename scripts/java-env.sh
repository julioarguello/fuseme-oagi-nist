#!/bin/bash
# java-env.sh - Minimal wrapper that evaluates java-env.py output
# Usage: source java-env.sh [args]
#   . java-env.sh -e dev

if [ -n "${BASH_SOURCE:-}" ]; then
    _JAVA_SCRIPT_PATH="${BASH_SOURCE[0]}"
elif [ -n "${ZSH_VERSION:-}" ]; then
    _JAVA_SCRIPT_PATH="${(%):-%x}"
else
    _JAVA_SCRIPT_PATH="$0"
fi
_JAVA_SCRIPT_DIR="$(cd "$(dirname "$_JAVA_SCRIPT_PATH")" && pwd)"

has_run=0
for arg in "$@"; do
    if [ "$arg" = "--run" ] || [ "$arg" = "-r" ]; then
        has_run=1
        break
    fi
done

if [ $has_run -eq 1 ]; then
    python3 "$_JAVA_SCRIPT_DIR/java-env.py" "$@"
else
    eval "$(python3 "$_JAVA_SCRIPT_DIR/java-env.py" "$@")"
fi

unset _JAVA_SCRIPT_PATH _JAVA_SCRIPT_DIR has_run arg

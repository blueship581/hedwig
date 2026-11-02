#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend-react"
BACKEND_DIR="$ROOT_DIR/backend"
BUILD_DIR="$ROOT_DIR/build"
PREBUILD_STAGE_PATHS=()
UNDEPLOYED_RUNTIME_PATHS=()

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    exit 1
  fi
}

configure_java_home() {
  if [[ "$(uname -s)" != "Darwin" ]] || [[ ! -x /usr/libexec/java_home ]]; then
    return
  fi

  local mac_java_home
  mac_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -n "$mac_java_home" ]]; then
    export JAVA_HOME="$mac_java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
}

is_excluded_stage_path() {
  local path="$1"
  case "$path" in
    build/*|backend/target/*|frontend-react/dist/*|frontend-react/node_modules/*|frontend-react/.vite/*|release/*|data/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_undeployed_runtime_path() {
  local path="$1"
  case "$path" in
    deploy/*|scripts/run-http.sh|scripts/install-systemd.sh)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

collect_prebuild_paths() {
  local path
  local raw_paths
  raw_paths="$({
    git diff --name-only
    git diff --cached --name-only
    git ls-files --others --exclude-standard
  } | sed '/^$/d' | sort -u)"

  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    if is_excluded_stage_path "$path"; then
      continue
    fi
    PREBUILD_STAGE_PATHS+=("$path")
    if is_undeployed_runtime_path "$path"; then
      UNDEPLOYED_RUNTIME_PATHS+=("$path")
    fi
  done <<< "$raw_paths"
}

print_sha256() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file"
    return
  fi
  shasum -a 256 "$file"
}

echo "[1/4] Checking build tools"
configure_java_home
require_command java
require_command node
require_command npm
require_command mvn
require_command git
collect_prebuild_paths

echo "[2/4] Building frontend"
(cd "$FRONTEND_DIR" && npm ci && npm run build)
bash "$ROOT_DIR/scripts/verify-frontend-dist.sh" "$FRONTEND_DIR/dist"

echo "[3/4] Building backend"
(cd "$BACKEND_DIR" && mvn clean package -DskipTests -q)

echo "[4/4] Staging artifact"
mkdir -p "$BUILD_DIR"
cp "$BACKEND_DIR/target/hedwig.jar" "$BUILD_DIR/hedwig.jar"

if [[ "${#PREBUILD_STAGE_PATHS[@]}" -gt 0 ]]; then
  git -C "$ROOT_DIR" add -A -- "${PREBUILD_STAGE_PATHS[@]}"
fi
git -C "$ROOT_DIR" add -- build/hedwig.jar

echo "Done: build/hedwig.jar ($(du -h "$BUILD_DIR/hedwig.jar" | cut -f1))"
print_sha256 "$BUILD_DIR/hedwig.jar"
echo
echo "Staged for commit:"
git -C "$ROOT_DIR" diff --cached --name-status -- build/hedwig.jar "${PREBUILD_STAGE_PATHS[@]}"

if [[ "${#UNDEPLOYED_RUNTIME_PATHS[@]}" -gt 0 ]]; then
  echo
  echo "Warning: these staged files are not uploaded by the current CI deploy pipeline:"
  printf '  - %s\n' "${UNDEPLOYED_RUNTIME_PATHS[@]}"
  echo "They still need manual rollout on the server, or a broader deploy pipeline."
fi

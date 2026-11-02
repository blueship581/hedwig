#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend-react"
BACKEND_DIR="$ROOT_DIR/backend"
RELEASE_ROOT="$ROOT_DIR/release"
PACKAGE_DIR="$RELEASE_ROOT/hedwig"
ARCHIVE_NAME="hedwig-$(date +%Y%m%d-%H%M%S).tar.gz"

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

echo "[1/4] Checking build tools"
configure_java_home
require_command java
require_command node
require_command npm
require_command mvn
require_command tar

echo "[2/4] Building frontend"
(cd "$FRONTEND_DIR" && npm ci && npm run build)
bash "$ROOT_DIR/scripts/verify-frontend-dist.sh" "$FRONTEND_DIR/dist"

echo "[3/4] Building backend"
if [[ "${SKIP_TESTS:-0}" == "1" ]]; then
  (cd "$BACKEND_DIR" && mvn clean package -DskipTests)
else
  (cd "$BACKEND_DIR" && mvn clean package)
fi

echo "[4/4] Packaging release"
rm -rf "$PACKAGE_DIR"
mkdir -p "$PACKAGE_DIR"

cp "$BACKEND_DIR/target/hedwig.jar" "$PACKAGE_DIR/app.jar"
cp "$ROOT_DIR/deploy/app.env.example" "$PACKAGE_DIR/app.env.example"
cp "$ROOT_DIR/scripts/run-http.sh" "$PACKAGE_DIR/run-http.sh"
cp "$ROOT_DIR/scripts/install-systemd.sh" "$PACKAGE_DIR/install-systemd.sh"
chmod +x "$PACKAGE_DIR/run-http.sh" "$PACKAGE_DIR/install-systemd.sh"
mkdir -p "$PACKAGE_DIR/data" "$PACKAGE_DIR/logs"

tar -czf "$RELEASE_ROOT/$ARCHIVE_NAME" -C "$RELEASE_ROOT" hedwig

echo "Release directory: $PACKAGE_DIR"
echo "Release archive: $RELEASE_ROOT/$ARCHIVE_NAME"

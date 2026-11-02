#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────
# ci-deploy.sh  —  Woodpecker CI deploy step
#
# Required env vars (set by .woodpecker.yml):
#   SSH_KEY          base64-encoded ed25519 private key
#   DEPLOY_HOST      target server IP / hostname
#   DEPLOY_PORT      SSH port (default 22)
#   DEPLOY_USER      remote user (default deploy)
#   APP_DIR          remote application directory
#   HEALTH_URL       health-check URL on the server
#   HEALTH_TIMEOUT   max seconds to wait for healthy (default 60)
# ──────────────────────────────────────────────────────────────
set -euo pipefail

# ── validate required env vars ────────────────────────────────
: "${SSH_KEY:?ERROR: SSH_KEY secret is not set}"
: "${DEPLOY_HOST:?ERROR: DEPLOY_HOST is not set}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
APP_DIR="${APP_DIR:-/opt/hedwig}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"

LOCAL_JAR="build/hedwig.jar"
REMOTE_TARGET="${DEPLOY_USER}@${DEPLOY_HOST}"
SSH_OPTS="-o StrictHostKeyChecking=yes -o ConnectTimeout=10 -p ${DEPLOY_PORT}"
SCP_OPTS="-o StrictHostKeyChecking=yes -o ConnectTimeout=10 -P ${DEPLOY_PORT}"

# ── pre-flight: local artifact check ─────────────────────────
if [[ ! -s "${LOCAL_JAR}" ]]; then
  echo "ERROR: ${LOCAL_JAR} is missing or empty." >&2
  exit 1
fi
echo "==> Local artifact checksum:"
sha256sum "${LOCAL_JAR}"

# ── setup SSH ─────────────────────────────────────────────────
setup_ssh() {
  mkdir -p ~/.ssh
  chmod 700 ~/.ssh

  echo "${SSH_KEY}" | base64 -d > ~/.ssh/id_ed25519
  chmod 600 ~/.ssh/id_ed25519

  echo "==> Fetching host keys for ${DEPLOY_HOST}:${DEPLOY_PORT} ..."
  ssh-keyscan -p "${DEPLOY_PORT}" "${DEPLOY_HOST}" >> ~/.ssh/known_hosts 2>&1
  if [[ ! -s ~/.ssh/known_hosts ]]; then
    echo "ERROR: ssh-keyscan returned no keys for ${DEPLOY_HOST}:${DEPLOY_PORT}." >&2
    cleanup_ssh
    exit 1
  fi
  echo "==> Host keys captured."
}

cleanup_ssh() {
  rm -f ~/.ssh/id_ed25519
  rm -f ~/.ssh/known_hosts
}

# always clean up SSH material on exit
trap cleanup_ssh EXIT

setup_ssh

# ── upload artifact ───────────────────────────────────────────
echo "==> Uploading ${LOCAL_JAR} -> ${REMOTE_TARGET}:${APP_DIR}/app.jar.new"
scp ${SCP_OPTS} "${LOCAL_JAR}" "${REMOTE_TARGET}:${APP_DIR}/app.jar.new"

# ── remote deploy with health check & auto-rollback ───────────
echo "==> Running remote deploy ..."
# shellcheck disable=SC2087
ssh ${SSH_OPTS} "${REMOTE_TARGET}" bash -s -- \
  "${APP_DIR}" "${HEALTH_URL}" "${HEALTH_TIMEOUT}" << 'REMOTE_EOF'
set -euo pipefail

APP_DIR="$1"
HEALTH_URL="$2"
HEALTH_TIMEOUT="$3"

cd "${APP_DIR}"

echo "--- Remote artifact checksum (app.jar.new):"
sha256sum app.jar.new

# ── verify systemctl access ──────────────────────────────────
SYSTEMCTL_PATH="$(command -v systemctl)"
if [[ -z "${SYSTEMCTL_PATH}" ]]; then
  echo "ERROR: systemctl not found on server." >&2
  exit 1
fi

SUDO_CHECK_ERR="$(mktemp)"
trap 'rm -f "${SUDO_CHECK_ERR}"' EXIT

if ! sudo -n "${SYSTEMCTL_PATH}" is-active hedwig >/dev/null 2>"${SUDO_CHECK_ERR}"; then
  if grep -Eq "password is required|a terminal is required|not allowed to execute" "${SUDO_CHECK_ERR}"; then
    echo "ERROR: deploy user needs passwordless sudo for ${SYSTEMCTL_PATH}." >&2
    echo "Run on server:  sudo visudo -f /etc/sudoers.d/hedwig-deploy" >&2
    echo "Then add:  deploy ALL=(root) NOPASSWD: ${SYSTEMCTL_PATH} restart hedwig, ${SYSTEMCTL_PATH} is-active hedwig" >&2
    exit 1
  fi
fi

# ── guard: current app.jar must exist ─────────────────────────
if [[ ! -f app.jar ]]; then
  echo "ERROR: current app.jar missing; refusing to replace blindly." >&2
  exit 1
fi

# ── backup → replace → restart ────────────────────────────────
cp app.jar app.jar.bak
mv app.jar.new app.jar

echo "--- Restarting hedwig ..."
sudo -n "${SYSTEMCTL_PATH}" restart hedwig

# ── health check with retry ───────────────────────────────────
echo "--- Waiting up to ${HEALTH_TIMEOUT}s for health check (${HEALTH_URL}) ..."
HEALTHY=false
ELAPSED=0
INTERVAL=3

while [[ ${ELAPSED} -lt ${HEALTH_TIMEOUT} ]]; do
  sleep "${INTERVAL}"
  ELAPSED=$((ELAPSED + INTERVAL))

  # try curl first, fall back to wget (alpine minimal may have wget only)
  if command -v curl >/dev/null 2>&1; then
    if curl -sf --max-time 5 "${HEALTH_URL}" >/dev/null 2>&1; then
      HEALTHY=true
      break
    fi
  elif command -v wget >/dev/null 2>&1; then
    if wget -q --spider --timeout=5 "${HEALTH_URL}" 2>/dev/null; then
      HEALTHY=true
      break
    fi
  else
    # no http client — fall back to systemd status
    if sudo -n "${SYSTEMCTL_PATH}" is-active hedwig >/dev/null 2>&1; then
      echo "WARNING: no curl/wget on server, falling back to systemd is-active check."
      HEALTHY=true
      break
    fi
  fi
  echo "  ... still waiting (${ELAPSED}s / ${HEALTH_TIMEOUT}s)"
done

if [[ "${HEALTHY}" == true ]]; then
  echo "--- Health check passed."
  sudo -n "${SYSTEMCTL_PATH}" is-active hedwig
  echo "--- Deployed artifact checksum:"
  sha256sum app.jar
  # clean up backup on success
  rm -f app.jar.bak
  exit 0
fi

# ── rollback ──────────────────────────────────────────────────
echo "ERROR: health check failed after ${HEALTH_TIMEOUT}s — rolling back!" >&2
mv app.jar app.jar.failed
mv app.jar.bak app.jar
sudo -n "${SYSTEMCTL_PATH}" restart hedwig

echo "--- Waiting for rollback to become healthy ..."
ROLLBACK_OK=false
for i in $(seq 1 10); do
  sleep 3
  if command -v curl >/dev/null 2>&1; then
    curl -sf --max-time 5 "${HEALTH_URL}" >/dev/null 2>&1 && ROLLBACK_OK=true && break
  elif command -v wget >/dev/null 2>&1; then
    wget -q --spider --timeout=5 "${HEALTH_URL}" 2>/dev/null && ROLLBACK_OK=true && break
  else
    sudo -n "${SYSTEMCTL_PATH}" is-active hedwig >/dev/null 2>&1 && ROLLBACK_OK=true && break
  fi
done

if [[ "${ROLLBACK_OK}" == true ]]; then
  echo "--- Rollback succeeded. Service restored to previous version."
else
  echo "CRITICAL: rollback also failed! Manual intervention required." >&2
fi

echo "--- Failed artifact preserved as app.jar.failed for investigation."
exit 1
REMOTE_EOF

echo "==> Deploy completed successfully."

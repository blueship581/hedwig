#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="${1:-$ROOT_DIR/frontend-react/dist}"
INDEX_FILE="$DIST_DIR/index.html"

if [[ ! -f "$INDEX_FILE" ]]; then
  echo "ERROR: frontend index not found: $INDEX_FILE" >&2
  exit 1
fi

missing_assets=0
while IFS= read -r asset_url; do
  asset_path="${asset_url#/}"
  if [[ ! -f "$DIST_DIR/$asset_path" ]]; then
    echo "ERROR: frontend asset referenced by index.html is missing: $asset_url" >&2
    missing_assets=1
  fi
done < <(grep -oE '(src|href)="/assets/[^"]+"' "$INDEX_FILE" | sed -E 's/^(src|href)="//; s/"$//')

if [[ "$missing_assets" -ne 0 ]]; then
  echo "ERROR: frontend dist is inconsistent; rebuild the frontend before packaging." >&2
  exit 1
fi

echo "Frontend dist verified: $DIST_DIR"

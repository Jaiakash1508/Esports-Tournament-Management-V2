#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven 3.9+ is required. Install Maven, then run this script again." >&2
  exit 1
fi

exec mvn compile exec:java

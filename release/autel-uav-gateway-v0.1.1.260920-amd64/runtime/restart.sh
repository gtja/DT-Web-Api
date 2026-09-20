#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER_NAME="${CONTAINER_NAME:-autel-uav-gateway}"

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    docker restart --timeout 30 "${CONTAINER_NAME}"
else
    "${SCRIPT_DIR}/start.sh" "$@"
fi

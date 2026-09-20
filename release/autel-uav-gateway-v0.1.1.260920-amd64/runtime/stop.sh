#!/usr/bin/env bash

set -Eeuo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-autel-uav-gateway}"

if ! docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    echo "容器 ${CONTAINER_NAME} 不存在。"
    exit 0
fi

docker stop --timeout 30 "${CONTAINER_NAME}"

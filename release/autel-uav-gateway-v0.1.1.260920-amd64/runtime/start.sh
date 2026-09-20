#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="${SCRIPT_DIR}/config"
CONFIG_FILE="${CONFIG_DIR}/application.yml"
IMAGE_REF="${1:-${IMAGE_REF:-autel-uav-gateway:latest-amd64}}"
CONTAINER_NAME="${CONTAINER_NAME:-autel-uav-gateway}"

if [[ "${EUID}" -ne 0 ]]; then
    echo "错误：请使用 sudo ./start.sh [镜像名称:标签] 启动。" >&2
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "错误：服务器未安装 Docker。" >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "错误：Docker daemon 未运行。" >&2
    exit 1
fi

if [[ ! -f "${CONFIG_FILE}" ]]; then
    echo "错误：真实配置不存在：${CONFIG_FILE}" >&2
    exit 1
fi

# 容器固定以 10001:10001 运行；使用组只读权限保护生产配置。
chown 0:10001 "${CONFIG_DIR}" "${CONFIG_FILE}"
chmod 0750 "${CONFIG_DIR}"
chmod 0640 "${CONFIG_FILE}"

if grep -Eq '^[[:space:]]*ffmpeg-path:[[:space:]]*/opt/homebrew/' "${CONFIG_FILE}"; then
    echo "错误：ffmpeg-path 不能使用 macOS Homebrew 路径，应设置为 ffmpeg。" >&2
    exit 1
fi

if ! docker image inspect "${IMAGE_REF}" >/dev/null 2>&1; then
    echo "错误：找不到镜像 ${IMAGE_REF}，请先手动构建或 docker load。" >&2
    echo "也可以指定镜像：sudo ./start.sh your-image:tag" >&2
    exit 1
fi

image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "${IMAGE_REF}")"
if [[ "${image_platform}" != "linux/amd64" ]]; then
    echo "错误：镜像平台为 ${image_platform}，预期为 linux/amd64。" >&2
    exit 1
fi

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    running="$(docker container inspect --format '{{.State.Running}}' "${CONTAINER_NAME}")"
    if [[ "${running}" == "true" ]]; then
        echo "容器 ${CONTAINER_NAME} 已在运行。"
    else
        docker start "${CONTAINER_NAME}"
    fi
    exit 0
fi

if ! docker run --rm \
    --platform linux/amd64 \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --entrypoint sh \
    --mount "type=bind,src=${CONFIG_DIR},dst=/opt/app/config,readonly" \
    "${IMAGE_REF}" -c 'test -r /opt/app/config/application.yml'; then
    echo "错误：容器用户无法读取 config/application.yml，请检查目录和文件权限。" >&2
    exit 1
fi

container_id="$(docker run \
    --detach \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --platform linux/amd64 \
    --init \
    --stop-timeout 30 \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=128m \
    --log-opt max-size=50m \
    --log-opt max-file=5 \
    --add-host host.docker.internal:host-gateway \
    --env TZ=Asia/Shanghai \
    --mount "type=bind,src=${CONFIG_DIR},dst=/opt/app/config,readonly" \
    "${IMAGE_REF}")"

echo "容器已启动：${CONTAINER_NAME} (${container_id:0:12})"
echo "镜像：${IMAGE_REF} (${image_platform})"
echo "配置目录：${CONFIG_DIR} -> /opt/app/config（只读）"
echo "查看日志：sudo ${SCRIPT_DIR}/logs.sh"

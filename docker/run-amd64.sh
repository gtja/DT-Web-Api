#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

IMAGE_REF="${1:-${IMAGE_REF:-autel-uav-gateway:latest-amd64}}"
CONTAINER_NAME="${CONTAINER_NAME:-autel-uav-gateway}"
CONFIG_DIR="${CONFIG_DIR:-${SCRIPT_DIR}/config}"
CONFIG_FILE="${CONFIG_FILE:-}"
LOG_DIR="${LOG_DIR:-}"
DOCKER_NETWORK="${DOCKER_NETWORK:-}"

if ! command -v docker >/dev/null 2>&1; then
    echo "错误：未找到 docker 命令。" >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "错误：Docker daemon 未运行，请先启动 Docker Desktop 或 Docker 服务。" >&2
    exit 1
fi

if [[ -n "${CONFIG_FILE}" ]]; then
    if [[ ! -f "${CONFIG_FILE}" ]]; then
        echo "错误：配置文件不存在：${CONFIG_FILE}" >&2
        exit 1
    fi
    config_parent="$(cd -- "$(dirname -- "${CONFIG_FILE}")" && pwd)"
    CONFIG_PATH="${config_parent}/$(basename -- "${CONFIG_FILE}")"
    CONFIG_MOUNT="type=bind,src=${CONFIG_PATH},dst=/opt/app/config/application.yml,readonly"
    CONFIG_DESCRIPTION="${CONFIG_PATH} -> /opt/app/config/application.yml（只读）"
else
    if [[ ! -d "${CONFIG_DIR}" ]]; then
        echo "错误：配置目录不存在：${CONFIG_DIR}" >&2
        echo "请执行：mkdir -p '${CONFIG_DIR}'" >&2
        echo "然后把 application.yml 放入该目录。" >&2
        exit 1
    fi
    CONFIG_DIR_PATH="$(cd -- "${CONFIG_DIR}" && pwd)"
    CONFIG_PATH="${CONFIG_DIR_PATH}/application.yml"
    if [[ ! -f "${CONFIG_PATH}" ]]; then
        echo "错误：配置文件不存在：${CONFIG_PATH}" >&2
        echo "请将 application.yml 放入配置目录，或通过 CONFIG_DIR 指定其他目录。" >&2
        exit 1
    fi
    CONFIG_MOUNT="type=bind,src=${CONFIG_DIR_PATH},dst=/opt/app/config,readonly"
    CONFIG_DESCRIPTION="${CONFIG_DIR_PATH} -> /opt/app/config（只读目录）"
fi

if grep -Eq '^[[:space:]]*ffmpeg-path:[[:space:]]*/opt/homebrew/' "${CONFIG_PATH}"; then
    echo "错误：配置中的 ffmpeg-path 指向 macOS Homebrew 路径，容器内不可用。" >&2
    echo "请改为：ffmpeg-path: ffmpeg" >&2
    exit 1
fi

if grep -Eq '^[[:space:]]*enabled:[[:space:]]*false([[:space:]]*(#.*)?)?$' "${CONFIG_PATH}"; then
    echo "警告：配置中检测到 enabled: false，网关启动后不会连接设备。" >&2
fi

if ! docker image inspect "${IMAGE_REF}" >/dev/null 2>&1; then
    echo "错误：本机不存在镜像 ${IMAGE_REF}，请先构建或 docker load。" >&2
    exit 1
fi

image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "${IMAGE_REF}")"
if [[ "${image_platform}" != "linux/amd64" ]]; then
    echo "错误：镜像平台为 ${image_platform}，不是 linux/amd64。" >&2
    exit 1
fi

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    echo "错误：容器 ${CONTAINER_NAME} 已存在。" >&2
    echo "如需重建，请先执行：docker rm -f '${CONTAINER_NAME}'" >&2
    exit 1
fi

if ! docker run --rm \
    --platform linux/amd64 \
    --network none \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --entrypoint sh \
    --mount "${CONFIG_MOUNT}" \
    "${IMAGE_REF}" -c 'test -r /opt/app/config/application.yml'; then
    echo "错误：容器用户 UID 10001 无法读取 /opt/app/config/application.yml。" >&2
    echo "请检查配置目录的执行权限和 application.yml 的读取权限。" >&2
    exit 1
fi

docker_args=(
    run
    --detach
    --name "${CONTAINER_NAME}"
    --restart unless-stopped
    --platform linux/amd64
    --init
    --stop-timeout 30
    --cap-drop ALL
    --security-opt no-new-privileges:true
    --read-only
    --tmpfs /tmp:rw,noexec,nosuid,size=128m
    --log-opt max-size=50m
    --log-opt max-file=5
    --add-host host.docker.internal:host-gateway
    --env TZ=Asia/Shanghai
    --mount "${CONFIG_MOUNT}"
)

if [[ -n "${LOG_DIR}" ]]; then
    mkdir -p -- "${LOG_DIR}"
    LOG_PATH="$(cd -- "${LOG_DIR}" && pwd)"
    docker_args+=(--mount "type=bind,src=${LOG_PATH},dst=/opt/app/logs")
fi

if [[ -n "${JAVA_TOOL_OPTIONS:-}" ]]; then
    docker_args+=(--env "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS}")
fi

if [[ -n "${DOCKER_NETWORK}" ]]; then
    docker_args+=(--network "${DOCKER_NETWORK}")
fi

docker_args+=("${IMAGE_REF}")

container_id="$(docker "${docker_args[@]}")"

echo "容器已启动：${CONTAINER_NAME} (${container_id:0:12})"
echo "镜像：${IMAGE_REF} (${image_platform})"
echo "配置：${CONFIG_DESCRIPTION}"
if [[ -n "${LOG_DIR}" ]]; then
    echo "文件日志目录：${LOG_PATH} -> /opt/app/logs"
fi
echo "日志：docker logs --follow '${CONTAINER_NAME}'"

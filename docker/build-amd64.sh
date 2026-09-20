#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-autel-uav-gateway}"
IMAGE_TAG="${IMAGE_TAG:-v0.1.0.$(date +'%y%m%d')}"
IMAGE_REF="${1:-${IMAGE_REPOSITORY}:${IMAGE_TAG}}"
IMAGE_VERSION="${IMAGE_VERSION:-${IMAGE_REF##*:}}"
LOCAL_ALIAS="${LOCAL_ALIAS:-autel-uav-gateway:latest-amd64}"
PLATFORM="linux/amd64"
SAVE_TAR="${SAVE_TAR:-true}"
DIST_DIR="${DIST_DIR:-${SCRIPT_DIR}/dist}"
MAVEN_IMAGE="${MAVEN_IMAGE:-docker.m.daocloud.io/library/maven:3.9.11-eclipse-temurin-11}"
RUNTIME_IMAGE="${RUNTIME_IMAGE:-docker.m.daocloud.io/library/eclipse-temurin:11-jre-jammy}"
MAVEN_SETTINGS_FILE="${MAVEN_SETTINGS_FILE:-${HOME}/.m2/settings.xml}"

safe_image_name="${IMAGE_REF//\//_}"
safe_image_name="${safe_image_name//:/_}"
ARCHIVE_PATH="${ARCHIVE_PATH:-${DIST_DIR}/${safe_image_name}-linux-amd64.tar.gz}"

if ! command -v docker >/dev/null 2>&1; then
    echo "错误：未找到 docker 命令。" >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "错误：Docker daemon 未运行，请先启动 Docker Desktop 或 Docker 服务。" >&2
    exit 1
fi

if ! docker buildx version >/dev/null 2>&1; then
    echo "错误：当前 Docker 未安装 buildx，无法构建 ${PLATFORM} 镜像。" >&2
    exit 1
fi

if [[ ! -f "${MAVEN_SETTINGS_FILE}" ]]; then
    echo "错误：Maven settings.xml 不存在：${MAVEN_SETTINGS_FILE}" >&2
    echo "项目依赖私有 Maven 仓库，请通过 MAVEN_SETTINGS_FILE 指定含仓库凭据的 settings.xml。" >&2
    exit 1
fi

echo "开始构建 ${PLATFORM} 镜像：${IMAGE_REF}"
build_args=(
    buildx build
    --platform "${PLATFORM}"
    --build-arg "MAVEN_IMAGE=${MAVEN_IMAGE}"
    --build-arg "RUNTIME_IMAGE=${RUNTIME_IMAGE}"
    --label "org.opencontainers.image.title=autel-uav-gateway"
    --label "org.opencontainers.image.version=${IMAGE_VERSION}"
    --tag "${IMAGE_REF}"
    --tag "${LOCAL_ALIAS}"
    --file "${SCRIPT_DIR}/Dockerfile"
    --provenance=false
    --load
    --secret "id=maven_settings,src=${MAVEN_SETTINGS_FILE}"
)

build_args+=("${PROJECT_ROOT}")
docker "${build_args[@]}"

image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "${IMAGE_REF}")"
if [[ "${image_platform}" != "${PLATFORM}" ]]; then
    echo "错误：镜像平台为 ${image_platform}，预期为 ${PLATFORM}。" >&2
    exit 1
fi

echo "镜像构建完成：${IMAGE_REF} (${image_platform})"
echo "本地固定别名：${LOCAL_ALIAS}"

case "${SAVE_TAR}" in
    1|true|TRUE|yes|YES|y|Y)
        mkdir -p -- "$(dirname -- "${ARCHIVE_PATH}")"
        docker save "${IMAGE_REF}" "${LOCAL_ALIAS}" | gzip -c > "${ARCHIVE_PATH}"
        archive_dir="$(cd -- "$(dirname -- "${ARCHIVE_PATH}")" && pwd)"
        archive_name="$(basename -- "${ARCHIVE_PATH}")"
        if command -v sha256sum >/dev/null 2>&1; then
            (cd -- "${archive_dir}" && sha256sum "${archive_name}" > "${archive_name}.sha256")
        else
            (cd -- "${archive_dir}" && shasum -a 256 "${archive_name}" > "${archive_name}.sha256")
        fi
        echo "AMD64 镜像包：${ARCHIVE_PATH}"
        echo "校验文件：${ARCHIVE_PATH}.sha256"
        echo "服务器导入命令：gzip -dc '${ARCHIVE_PATH}' | docker load"
        ;;
    0|false|FALSE|no|NO|n|N)
        echo "已按 SAVE_TAR=${SAVE_TAR} 跳过 docker save。"
        ;;
    *)
        echo "错误：SAVE_TAR 仅支持 true/false。" >&2
        exit 1
        ;;
esac

echo "运行脚本：IMAGE_REF='${IMAGE_REF}' '${SCRIPT_DIR}/run-amd64.sh'"

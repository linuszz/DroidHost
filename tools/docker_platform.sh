docker_platform=${DROIDHOST_DOCKER_PLATFORM:-}
if [[ -z "${docker_platform}" ]]; then
    case "$(uname -m)" in arm64|aarch64) docker_platform=linux/arm64 ;; *) docker_platform=linux/amd64 ;; esac
fi
case "${docker_platform}" in linux/arm64|linux/arm64/*) docker_platform=linux/arm64 ;; esac
if [[ -z "${DROIDHOST_BUILD_IMAGE:-}" ]]; then
    DROIDHOST_BUILD_IMAGE=droiddeck-local-cross:24.04-v4
    [[ "${docker_platform}" == linux/arm64 ]] && DROIDHOST_BUILD_IMAGE+=-arm64
fi
export DROIDHOST_DOCKER_PLATFORM=${docker_platform} DROIDHOST_BUILD_IMAGE

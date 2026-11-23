#!/usr/bin/env bash
set -euo pipefail

DESKTOP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${DESKTOP_ROOT}/src-tauri/resources/mpv"
mkdir -p "${DEST_DIR}"

echo "Checking Linux desktop prerequisites and libmpv runtime..."
for pkg in gtk+-3.0 webkit2gtk-4.1 libsecret-1; do
  if ! pkg-config --exists "${pkg}"; then
    echo "Missing required development package: ${pkg}" >&2
    exit 1
  fi
done

LIBMPV_PATH="$(ldconfig -p 2>/dev/null | awk '/libmpv\.so\.2 /{print $NF; exit}' || true)"
if [[ -z "${LIBMPV_PATH}" ]]; then
  echo "libmpv.so.2 was not found on this host. Install libmpv2/libmpv-dev (Ubuntu 22.04+) or build mpv 0.42.0 from source." >&2
  exit 1
fi

cp -L "${LIBMPV_PATH}" "${DEST_DIR}/libmpv.so.2"
sha256sum "${DEST_DIR}/libmpv.so.2" > "${DEST_DIR}/libmpv.so.2.sha256"
echo "Staged ${LIBMPV_PATH} -> ${DEST_DIR}/libmpv.so.2"

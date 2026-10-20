#!/usr/bin/env bash
#
# Downloads the sing-box libbox AAR used as Umihi's built-in proxy core.
#
# The AAR is ~112 MB compressed (four ABIs), so it is not committed. Builds expect it at
# app/libs/libbox.aar; CI runs this script before assembling.
#
set -euo pipefail

VERSION="${LIBBOX_VERSION:-1.14.3}"
# Optional mirror prefix for users behind the GFW, e.g. LIBBOX_MIRROR=https://gh-proxy.com/
MIRROR="${LIBBOX_MIRROR:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/libbox.aar"

if [ -f "$DEST" ]; then
  echo "libbox.aar already present: $DEST"
  ls -lh "$DEST"
  exit 0
fi

mkdir -p "$(dirname "$DEST")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="${MIRROR}https://github.com/proother/sing-box-lib/releases/download/v${VERSION}/libbox-android.aar.zip"

echo "Downloading libbox v${VERSION} from:"
echo "  $URL"
curl -fL --retry 3 --retry-delay 2 -o "$TMP/libbox.zip" "$URL"

unzip -o -q "$TMP/libbox.zip" -d "$TMP"

SRC="$(find "$TMP" -name 'libbox.aar' -type f | head -n 1)"
if [ -z "$SRC" ]; then
  echo "error: libbox.aar not found inside the downloaded archive" >&2
  exit 1
fi

cp "$SRC" "$DEST"
echo "Installed:"
ls -lh "$DEST"

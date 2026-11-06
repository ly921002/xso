#!/usr/bin/env bash
# Build Xray-core as a c-shared library for one target.
#
# Usage: ./scripts/build.sh <goos> <goarch> [outfile]
#   ./scripts/build.sh linux amd64
#   ./scripts/build.sh linux arm64 x-linux-arm64.so
#
# Env:
#   XRAY_VERSION   git tag/branch of XTLS/Xray-core (default: latest)
#   CC             C compiler / cross compiler (default: auto by GOARCH)
#   WORKDIR        checkout dir (default: .work/xray-core)
set -euo pipefail

GOOS_ARG="${1:?usage: build.sh <goos> <goarch> [outfile]}"
GOARCH_ARG="${2:?usage: build.sh <goos> <goarch> [outfile]}"
XRAY_VERSION="${XRAY_VERSION:-latest}"
WORKDIR="${WORKDIR:-.work/xray-core}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- map target -> file extension ------------------------------------------
case "$GOOS_ARG" in
  linux)  EXT="so" ;;
  darwin) EXT="dylib" ;;
  windows) EXT="dll" ;;
  *) EXT="so" ;;
esac
OUT="${3:-x-${GOOS_ARG}-${GOARCH_ARG}.${EXT}}"
mkdir -p "$REPO_ROOT/dist"
OUT="$REPO_ROOT/dist/$(basename "$OUT")"

# --- fetch xray source ------------------------------------------------------
if [ ! -d "$WORKDIR/.git" ]; then
  echo ">> cloning XTLS/Xray-core ($XRAY_VERSION)"
  mkdir -p "$(dirname "$WORKDIR")"
  git clone --depth 1 --branch "$XRAY_VERSION" \
    https://github.com/XTLS/Xray-core.git "$WORKDIR" ||
  git clone --depth 1 https://github.com/XTLS/Xray-core.git "$WORKDIR"
else
  echo ">> reusing $WORKDIR"
fi

XRAY_COMMIT="$(git -C "$WORKDIR" rev-parse HEAD)"
XRAY_DESC="$(git -C "$WORKDIR" describe --tags --always 2>/dev/null || echo "$XRAY_COMMIT")"
echo ">> xray: $XRAY_DESC ($XRAY_COMMIT)"

# --- install our entrypoint -------------------------------------------------
mkdir -p "$WORKDIR/cshared"
cp "$REPO_ROOT/cshared/main.go"      "$WORKDIR/cshared/main.go"
cp "$REPO_ROOT/cshared/main_nocgo.go" "$WORKDIR/cshared/main_nocgo.go"

# --- toolchain --------------------------------------------------------------
if [ "$GOOS_ARG" = "windows" ] && [ "$GOARCH_ARG" = "amd64" ]; then
  export CC="${CC:-x86_64-w64-mingw32-gcc}"
elif [ "$GOOS_ARG" = "linux" ] && [ "$GOARCH_ARG" = "arm64" ]; then
  export CC="${CC:-aarch64-linux-gnu-gcc}"
elif [ "$GOOS_ARG" = "linux" ] && [ "$GOARCH_ARG" = "amd64" ]; then
  export CC="${CC:-gcc}"
fi

export CGO_ENABLED=1
export GOOS="$GOOS_ARG"
export GOARCH="$GOARCH_ARG"

echo ">> building $GOOS_ARG/$GOARCH_ARG with CC=${CC:-<default>}"
cd "$WORKDIR"
# -buildvcs=false: the checkout may not have VCS info after a shallow CI clone
go build -buildvcs=false -trimpath -ldflags "-s -w" \
  -buildmode=c-shared -o "$OUT" ./cshared

cd "$REPO_ROOT"
echo ">> wrote $OUT"
ls -lh "$OUT"

# --- record metadata --------------------------------------------------------
SHA="$(sha256sum "$OUT" | awk '{print $1}')"
cat > "${OUT}.meta" <<EOF
file=$OUT
goos=$GOOS_ARG
goarch=$GOARCH_ARG
xray=$XRAY_DESC
xray_commit=$XRAY_COMMIT
sha256=$SHA
go_version=$(go version)
built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
cat "${OUT}.meta"

echo "$SHA  $(basename "$OUT")" >> "$REPO_ROOT/dist/SHA256SUMS"

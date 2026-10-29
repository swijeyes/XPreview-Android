#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Fetches third-party files that are not on Maven, all pinned and checksum-verified:
#   - PDFium prebuilt Android binaries (BSD-3/Apache-2.0) from bblanchon/pdfium-binaries
#   - Tesseract English model, tessdata_fast 4.1.0 (Apache-2.0)
#   - Fallback fonts Arimo, Tinos, Cousine (Apache-2.0); Carlito, Caladea (SIL OFL 1.1).
#     When run inside the XPreview-Plus repo, resources/fonts is reused instead.
# Usage: scripts/fetch-android-deps.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PDFIUM_TAG="chromium/8100"
# arch:abi:sha256 (bash 3.2 compatible, no associative arrays)
PDFIUM_PKGS="arm64:arm64-v8a:e9bab2929443d3d881166af9ec1d191c76ecdd37effb07a58743a7d7b507e149
x64:x86_64:b5cd96bc81c3414a84ce1f3eb928d6fe6981b13fe555f21bfa931bc478f51876"
TESS_SHA256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2
CROS_VER=1.31.0
CROS_SHA256=672c3487883ec1ef83d9254240d4327b014212abc823d06d15816095867315e1

sha_check() {  # file sha
  echo "$2  $1" | shasum -a 256 -c - >/dev/null || { echo "error: checksum mismatch for $1" >&2; exit 1; }
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pd=core-pdf/src/main/cpp/third_party/pdfium
for pkg in $PDFIUM_PKGS; do
  IFS=: read -r arch abi sha <<< "$pkg"
  if [[ ! -f "$pd/$abi/lib/libpdfium.so" ]]; then
    echo "Fetching PDFium $PDFIUM_TAG ($abi)..."
    curl -fsSL -o "$tmp/pdfium-$arch.tgz" \
      "https://github.com/bblanchon/pdfium-binaries/releases/download/${PDFIUM_TAG/\//%2F}/pdfium-android-$arch.tgz"
    sha_check "$tmp/pdfium-$arch.tgz" "$sha"
    mkdir -p "$pd/$abi"
    tar xzf "$tmp/pdfium-$arch.tgz" -C "$pd/$abi"
  fi
done

td=core-ocr/src/main/assets/tessdata
if [[ ! -f "$td/eng.traineddata" ]]; then
  echo "Fetching Tesseract English model..."
  mkdir -p "$td"
  curl -fsSL -o "$tmp/eng.traineddata" https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0/eng.traineddata
  sha_check "$tmp/eng.traineddata" "$TESS_SHA256"
  mv "$tmp/eng.traineddata" "$td/"
fi

fd=core-pdf/src/main/assets/fonts
if [[ ! -f "$fd/Tinos-Regular.ttf" ]]; then
  mkdir -p "$fd"
  if [[ -d ../resources/fonts ]]; then
    echo "Reusing ../resources/fonts"
    cp ../resources/fonts/*.ttf ../resources/fonts/LICENSE-*.txt "$fd/" 2>/dev/null || true
  else
    echo "Fetching fonts..."
    curl -fsSL -o "$tmp/cros.tar.bz2" \
      "https://commondatastorage.googleapis.com/chromeos-localmirror/distfiles/croscorefonts-$CROS_VER.tar.bz2"
    sha_check "$tmp/cros.tar.bz2" "$CROS_SHA256"
    tar xjf "$tmp/cros.tar.bz2" -C "$tmp"
    cp "$tmp/croscorefonts-$CROS_VER"/{Arimo,Tinos,Cousine}-*.ttf "$fd/"
    gf=https://github.com/google/fonts/raw/main/ofl
    for fam in carlito caladea; do
      Fam="$(tr '[:lower:]' '[:upper:]' <<< "${fam:0:1}")${fam:1}"
      for style in Regular Bold Italic BoldItalic; do
        curl -fsSL -o "$fd/$Fam-$style.ttf" "$gf/$fam/$Fam-$style.ttf" || echo "warning: could not fetch $Fam-$style.ttf"
      done
      curl -fsSL -o "$fd/LICENSE-$Fam.txt" "$gf/$fam/OFL.txt" || true
    done
  fi
fi
echo "Done."

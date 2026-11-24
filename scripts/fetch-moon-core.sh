#!/usr/bin/env bash
# Prepare third_party/MoonTerminal: the pinned MoonTerminal tag with this repository's patches
# applied. rust/Cargo.toml builds moon-core from there. Safe to run again: it starts clean.
set -euo pipefail

TAG="v0.67.3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/MoonTerminal"

rm -rf "$DEST"
mkdir -p "$ROOT/third_party"
git clone --quiet --depth 1 --branch "$TAG" https://github.com/Moonbot-Tech/MoonTerminal.git "$DEST"
for patch in "$ROOT"/patches/*.patch; do
  echo "applying $(basename "$patch")"
  git -C "$DEST" apply --whitespace=nowarn "$patch"
done
echo "moon-core $TAG ready at $DEST"

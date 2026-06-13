#!/usr/bin/env bash
#
# Builds and runs the preview-frame input pipeline regression test on the host
# (desktop g++, no Android NDK / device required).
#
# Heap allocations performed by the code under test are intercepted two ways:
#   * malloc/free via the linker's --wrap option (used by the real NativeImageUtil)
#   * new[]/delete[] via global operator overrides in the test (used by the legacy path)
#
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

g++ -std=c++14 -O2 -Wall -Wno-unused-function -Wno-class-memaccess \
    -fno-builtin-malloc -fno-builtin-free -fno-builtin-calloc -fno-builtin-realloc \
    -I"$DIR/../util" \
    -I"$DIR/stubs" \
    -Wl,--wrap=malloc,--wrap=free \
    "$DIR/frame_pipeline_test.cpp" \
    -o "$OUT"

"$OUT"

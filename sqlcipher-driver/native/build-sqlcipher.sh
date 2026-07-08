#!/bin/bash
# Builds static SQLCipher archives for the iOS targets this library publishes.
#
# Output: native/libs/<slice>/libsqlcipher.a, embedded into the cinterop klib (see
# src/nativeInterop/cinterop/sqlcipher.def) so consumers link the cipher transitively.
#
# Crypto provider is CommonCrypto (SQLCIPHER_CRYPTO_CC) — same as Zetetic's official iOS
# binaries — so no OpenSSL build or tracking is needed. Keep SQLCIPHER_VERSION in lockstep
# with `sqlcipher-android` in gradle/libs.versions.toml: one library version = one SQLCipher
# version on both platforms.
set -euo pipefail

SQLCIPHER_VERSION="4.16.0"
IOS_MIN_VERSION="13.0"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="$SCRIPT_DIR/build"
LIBS_DIR="$SCRIPT_DIR/libs"
SRC_DIR="$WORK_DIR/sqlcipher-$SQLCIPHER_VERSION"
TARBALL="$WORK_DIR/sqlcipher-$SQLCIPHER_VERSION.tar.gz"

# Definitions mirror Zetetic's recommended iOS build (SQLCipher.swift): codec on, in-memory temp
# store (plaintext temp files would leak), CommonCrypto provider wired via EXTRA_INIT.
CIPHER_CFLAGS=(
  -DNDEBUG
  -DSQLITE_HAS_CODEC
  -DSQLITE_TEMP_STORE=3
  -DSQLCIPHER_CRYPTO_CC
  -DSQLITE_EXTRA_INIT=sqlcipher_extra_init
  -DSQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
  -DSQLITE_ENABLE_FTS5
  -DSQLITE_ENABLE_RTREE
  -DSQLITE_ENABLE_COLUMN_METADATA
  -DSQLITE_THREADSAFE=1
  -O2
  -fno-common
)

mkdir -p "$WORK_DIR" "$LIBS_DIR"

if [ ! -d "$SRC_DIR" ]; then
  if [ ! -f "$TARBALL" ]; then
    echo "Downloading sqlcipher $SQLCIPHER_VERSION..."
    curl -fL "https://github.com/sqlcipher/sqlcipher/archive/refs/tags/v$SQLCIPHER_VERSION.tar.gz" -o "$TARBALL"
  fi
  tar -xzf "$TARBALL" -C "$WORK_DIR"
fi

# Generate the amalgamation (sqlite3.c/h with the SQLCipher codec) once, on the host.
if [ ! -f "$SRC_DIR/sqlite3.c" ]; then
  echo "Generating amalgamation..."
  (cd "$SRC_DIR" && ./configure --with-tempstore=yes >/dev/null && make sqlite3.c >/dev/null)
fi

build_slice() {
  local slice="$1" sdk="$2" target_triple="$3"
  local out_dir="$LIBS_DIR/$slice"
  local obj="$WORK_DIR/sqlite3-$slice.o"
  mkdir -p "$out_dir"
  echo "Building $slice ($target_triple)..."
  xcrun --sdk "$sdk" clang \
    -target "$target_triple" \
    -isysroot "$(xcrun --sdk "$sdk" --show-sdk-path)" \
    "${CIPHER_CFLAGS[@]}" \
    -c "$SRC_DIR/sqlite3.c" -o "$obj"
  rm -f "$out_dir/libsqlcipher.a"
  xcrun --sdk "$sdk" libtool -static -o "$out_dir/libsqlcipher.a" "$obj"
  cp "$SRC_DIR/sqlite3.h" "$out_dir/sqlite3.h"
}

build_slice "ios-arm64"           "iphoneos"        "arm64-apple-ios$IOS_MIN_VERSION"
build_slice "ios-simulator-arm64" "iphonesimulator" "arm64-apple-ios$IOS_MIN_VERSION-simulator"

echo
echo "Verification:"
for slice in ios-arm64 ios-simulator-arm64; do
  a="$LIBS_DIR/$slice/libsqlcipher.a"
  n_sqlite=$(nm "$a" 2>/dev/null | grep -c "T _sqlite3_open_v2" || true)
  n_cipher=$(nm "$a" 2>/dev/null | grep -c "T _sqlcipher_" || true)
  echo "  $slice: $(du -h "$a" | cut -f1)  sqlite3_open_v2 defs=$n_sqlite  sqlcipher_* defs=$n_cipher"
done
echo "Done."

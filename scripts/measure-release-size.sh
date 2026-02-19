#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Build first with: ./gradlew :app:assembleRelease" >&2
  exit 1
fi

echo "APK: $APK_PATH"
APK_BYTES=$(stat -c%s "$APK_PATH")
echo "apk_bytes=$APK_BYTES"

echo
printf "%-14s %12s\n" "entry" "bytes"
unzip -l "$APK_PATH" | awk '{print $1, $4}' | \
  rg 'classes[0-9]*\.dex|resources\.arsc|AndroidManifest.xml|^lib/arm64-v8a/' | \
  awk '{printf "%-14s %12s\n", $2, $1}'

echo
NATIVE_SUM=$(unzip -l "$APK_PATH" | awk '$4 ~ /^lib\/arm64-v8a\// {sum += $1} END {print sum+0}')
DEX_SUM=$(unzip -l "$APK_PATH" | awk '$4 ~ /^classes[0-9]*\.dex$/ {sum += $1} END {print sum+0}')
echo "native_bytes_arm64=$NATIVE_SUM"
echo "dex_bytes_total=$DEX_SUM"

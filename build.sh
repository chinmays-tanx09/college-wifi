#!/bin/bash
# Signing secrets never live in the source tree: put PARADOX_KS / PARADOX_KS_PASS in a file outside it (default $HOME/.paradox/sign.env, or set PARADOX_SIGN_ENV).
[ -f "${PARADOX_SIGN_ENV:-$HOME/.paradox/sign.env}" ] && . "${PARADOX_SIGN_ENV:-$HOME/.paradox/sign.env}"; export PARADOX_KS_PASS
: "${PARADOX_KS:?set PARADOX_KS (keystore path) in sign.env}"
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-35/android.jar"
mkdir -p build/classes build/dex build/gen
timeout -k 5 30 "$BT/aapt" package -f -m -M AndroidManifest.xml -S res -A assets -I "$AJ" -J build/gen -G build/aapt_rules.txt
find build/classes -type f -delete
find build/dex -type f -delete
timeout -k 5 90 javac --release 8 -cp "$AJ" -d build/classes $(find src build/gen -name '*.java')
(cd build/classes && timeout -k 5 20 zip -qr ../all.jar .)
timeout -k 5 240 java -Xmx2g -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 30 --lib "$AJ" --pg-conf proguard.pro --pg-conf build/aapt_rules.txt --pg-map-output build/mapping.txt --output build/dex build/all.jar
timeout -k 5 30 "$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F build/packed.apk
cp build/packed.apk build/resources.ap_
(cd build/dex && timeout -k 5 30 "$BT/aapt" add ../packed.apk classes.dex >/dev/null)
timeout -k 5 30 "$BT/zipalign" -f -p 4 build/packed.apk build/aligned.apk
OUT=${OUT:-build/College_WiFi.apk}
timeout -k 5 30 "$BT/apksigner" sign --ks "$PARADOX_KS" --ks-key-alias "${PARADOX_KS_ALIAS:-paradox}" --ks-pass env:PARADOX_KS_PASS --key-pass env:PARADOX_KS_PASS --v4-signing-enabled false --out "$OUT.tmp.apk" build/aligned.apk
mv "$OUT.tmp.apk" "$OUT"; rm -f "$OUT.tmp.apk.idsig"
timeout -k 5 15 "$BT/apksigner" verify --min-sdk-version 30 --verbose --print-certs "$OUT"
timeout -k 5 15 "$BT/zipalign" -c -p 4 "$OUT"

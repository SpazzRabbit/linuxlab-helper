#!/usr/bin/env bash
# Builds the test keyboard (see TestIme.java) the way the helper is built: aapt2, javac, d8, apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
OUT=$(mktemp -d)
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" build
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" "$OUT/res.zip"
javac -nowarn -source 11 -target 11 -cp "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v '^warning\|^Note' >&2 || true
"$BT/d8" --min-api 33 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -0 ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks ../../../keystore.jks --ks-pass pass:android --key-pass pass:android \
    --out build/testime.apk "$OUT/aligned.apk" 2>/dev/null
ls -la build/testime.apk

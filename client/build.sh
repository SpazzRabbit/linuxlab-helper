#!/usr/bin/env bash
# Build Linux Lab Helper without Gradle: aapt2, javac, d8, zipalign, apksigner.
# The Linux-side installer (module included) goes into the APK as an asset: it is made from the
# repository of the Linux side, linuxlab-ubuntu, checked out next to this one as ../gnome.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
OUT=build/helper
mkdir -p "$OUT/gen" "$OUT/dex" assets
# a fresh directory every time: classes that no longer exist in the sources must not end up in the APK
CLASSES=$(mktemp -d "$OUT/classes.XXXXXX")
../../gnome/host/make_installer.sh >/dev/null
cp ../../gnome/build/linuxlab-helper.sh assets/linuxlab-helper.sh
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets --java "$OUT/gen" "$OUT/res.zip"
# a failed compile must stop the build: stale classes from the last run would be packaged otherwise
if ! javac -nowarn -source 11 -target 11 -cp "$JAR" -d "$CLASSES" \
        $(find src "$OUT/gen" -name '*.java') > "$OUT/javac.log" 2>&1; then
    grep -v '^warning\|^Note' "$OUT/javac.log" >&2
    exit 1
fi
"$BT/d8" --min-api 33 --lib "$JAR" --output "$OUT/dex" $(find "$CLASSES" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -0 ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks ../../keystore.jks --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/linux-lab-helper.apk" "$OUT/aligned.apk"
ls -la "$OUT/linux-lab-helper.apk"

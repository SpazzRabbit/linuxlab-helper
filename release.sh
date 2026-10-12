#!/usr/bin/env bash
# Publishes a release of the app as the GitHub release v<version> of SpazzRabbit/linuxlab-helper, where
# the app looks (Updates.java): the APK and app.json (version, the APK's size and SHA-256, the notes).
#
#   ./release.sh    version from client/AndroidManifest.xml, notes from notes/<version>.json
#                   ({"ru": ..., "en": ...}, shown on the card of the update)
#
# The APK carries the installer of the Linux side, made from ../gnome (the repository linuxlab-ubuntu).
# That release has to be out first and made of the same files: the app offers its installer to Linux
# set up by an older one, and llupdate.py offers the release on GitHub.
set -euo pipefail
cd "$(dirname "$0")"
repo=SpazzRabbit/linuxlab-helper
linux=SpazzRabbit/linuxlab-ubuntu
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' client/AndroidManifest.xml)
code=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' client/AndroidManifest.xml)
notes=notes/$version.json
[ -f "$notes" ] || { echo "release: no $notes" >&2; exit 1; }
for dir in . ../gnome; do
    [ -z "$(git -C $dir status --porcelain)" ] || { echo "release: commit $dir first" >&2; exit 1; }
done

client/build.sh >/dev/null
bundled=$(sed -n 's/^payload=//p' client/assets/linuxlab-helper.sh | head -1)
published=$(curl -fsL "https://github.com/$linux/releases/latest/download/manifest.json" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["payload"])' 2>/dev/null || true)
[ "$bundled" = "$published" ] || { echo "release: the Linux side in the APK ($bundled) is not the published one ($published): ../gnome/host/release.sh first" >&2; exit 1; }

mkdir -p build
apk=LinuxLabHelper-$version.apk
cp client/build/helper/linux-lab-helper.apk "build/$apk"
python3 - "$version" "$code" "$apk" "$notes" >build/app.json <<'PY'
import hashlib, json, sys
version, code, apk, notes = sys.argv[1], int(sys.argv[2]), sys.argv[3], json.load(open(sys.argv[4]))
data = open("build/" + apk, "rb").read()
json.dump({"versionCode": code, "versionName": version, "tag": "v" + version, "apk": apk, "size": len(data),
           "sha256": hashlib.sha256(data).hexdigest(), "notes": {"ru": notes["ru"], "en": notes["en"]}},
          sys.stdout, ensure_ascii=False, indent=1)
print()
PY
body=$(python3 -c 'import json, sys; n = json.load(open(sys.argv[1])); print(n["ru"] + "\n\n" + n["en"])' "$notes")
git tag -a "v$version" -m "Linux Lab Helper $version"
git push origin "v$version"
gh release create "v$version" -R "$repo" --verify-tag --title "Linux Lab Helper $version" --notes "$body" \
    "build/$apk" build/app.json

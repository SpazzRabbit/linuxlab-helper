#!/bin/bash
# Builds llroot, statically for arm64, inside Linux Lab on the tablet (it has gcc and the static glibc),
# and puts it into the assets of the helper app: overlay/client/assets/llroot.
# Needs the development channel of gnome/dev (agent.sh running in Linux Lab).
set -e
D=${ADB_SERIAL:-$(adb get-serialno)}
here=$(dirname "$(readlink -f "$0")")
adb -s $D push "$here/llroot.c" /sdcard/llhost/llroot.c >/dev/null
"$here/../../gnome/dev/job.sh" llroot-build-$RANDOM 300 <<'EOF'
set -e
work=$(mktemp -d)
cp /tablet/llhost/llroot.c "$work/"
gcc -O2 -static -Wall -o "$work/llroot" "$work/llroot.c"
cp "$work/llroot" /tablet/llhost/llroot
rm -rf "$work"
echo built
EOF
adb -s $D pull /sdcard/llhost/llroot "$here/../client/assets/llroot" >/dev/null
ls -la "$here/../client/assets/llroot"

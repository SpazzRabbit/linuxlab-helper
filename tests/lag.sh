#!/bin/bash
# lag.sh x y dx dy [times]: a finger comes down at (x, y), rests, jumps by (dx, dy), rests and lifts; tells
# how long after the jump Android showed the first new frame of Linux Lab. With (x, y) on the title of a
# window that is the time from the finger to the window on the screen.
# Needs the display in its landscape rotation ROTATION_90 (see mtu.py).
D=${ADB_SERIAL:-$(adb get-serialno)}
here=$(dirname "$(readlink -f "$0")")
layer=$(adb -s $D shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep 'SurfaceView\[com.hihonor.pcengine' | grep BLAST | head -1 |
    sed -e 's/^RequestedLayerState{//' -e 's/ parentId=.*$//' -e 's/}$//')
[ -n "$layer" ] || { echo "Linux Lab is not on the screen"; exit 1; }
x=$1; y=$2; dx=$3; dy=$4
for i in $(seq "${5:-5}"); do
    # there and back, so that the window is where it was for the next round
    if [ $((i % 2)) = 1 ]; then a="$x,$y,$((x + dx)),$((y + dy))"; else a="$((x + dx)),$((y + dy)),$x,$y"; fi
    python3 "$here/mtu.py" gap:300 swipe:$a,1,500,600 | adb -s $D shell 'cat > /data/local/tmp/lag.json'
    adb -s $D shell '
        uinput /data/local/tmp/lag.json >/dev/null 2>&1 &
        sleep 0.9
        dev=$(getevent -pl 2>/dev/null | awk "/^add device/ {d=\$4} /LL test touch/ {print d; exit}")
        timeout 2.4 getevent -lt $dev > /data/local/tmp/lag.txt 2>/dev/null
        wait
        cat /data/local/tmp/lag.txt
        echo FRAMES
        dumpsys SurfaceFlinger --latency "'"$layer"'"' | python3 -c '
import sys
text = sys.stdin.read()
events, frames = text.split("FRAMES")
reports = [float(line.split("]")[0].strip("[ ")) for line in events.splitlines() if "SYN_REPORT" in line]
shown = sorted(int(r.split()[1]) / 1e9 for r in frames.strip().splitlines()[1:] if len(r.split()) == 3 and 0 < int(r.split()[1]) < 1 << 62)
if len(reports) < 2:
    print("the touch was not seen"); sys.exit()
jump = reports[1]
after = [t for t in shown if t > jump]
if not after:
    print("no frame after the jump"); sys.exit()
print("%5.0f ms from the jump of the finger to the first new frame (%d frames in the next 0.3 s)"
      % ((after[0] - jump) * 1000, len([t for t in after if t < jump + 0.3])))'
done

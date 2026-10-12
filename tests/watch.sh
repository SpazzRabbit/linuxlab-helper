#!/bin/bash
# watch.sh [seconds]: touches nothing; tells how many frames a second Android puts on the screen for the
# picture of Linux Lab right now (a video playing, an animation running).
D=${ADB_SERIAL:-$(adb get-serialno)}
layer=$(adb -s $D shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep 'SurfaceView\[com.hihonor.pcengine' | grep BLAST | head -1 |
    sed -e 's/^RequestedLayerState{//' -e 's/ parentId=.*$//' -e 's/}$//')
[ -n "$layer" ] || { echo "Linux Lab is not on the screen"; exit 1; }
adb -s $D shell "dumpsys SurfaceFlinger --latency-clear; sleep ${1:-1}; dumpsys SurfaceFlinger --latency '$layer'" | python3 -c '
import sys
rows = [line.split() for line in sys.stdin.read().strip().splitlines()]
shown = sorted(int(r[1]) for r in rows[1:] if len(r) == 3 and 0 < int(r[1]) < 1 << 62)
if len(shown) < 3:
    print("nothing moves: %d frames" % len(shown)); sys.exit()
gaps = sorted((b - a) / 1e6 for a, b in zip(shown, shown[1:]))
span = (shown[-1] - shown[0]) / 1e9
print("%3.0f frames a second (%3d frames in %.2f s); between frames: median %4.1f ms, 90%% under %4.1f ms, worst %4.1f ms"
      % ((len(shown) - 1) / span, len(shown), span, gaps[len(gaps) // 2], gaps[len(gaps) * 9 // 10], gaps[-1]))'

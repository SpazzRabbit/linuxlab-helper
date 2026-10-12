#!/bin/bash
# fps.sh x1 y1 x2 y2 [ms]   drags a finger across the picture of Linux Lab
# fps.sh x y                taps
# and tells how many frames a second Android put on the screen while the picture was changing (what
# SurfaceFlinger presented, not what Linux drew). The longest run of frames without a pause is judged.
# Examples: a window dragged by its title for two seconds: fps.sh 1500 440 2100 1100 2000
#           the overview of GNOME opening:                 fps.sh 45 28
D=${ADB_SERIAL:-$(adb get-serialno)}
# Android 16 lists "RequestedLayerState{<name> parentId=...}", the name is what --latency takes
layer=$(adb -s $D shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep 'SurfaceView\[com.hihonor.pcengine' | grep BLAST | head -1 |
    sed -e 's/^RequestedLayerState{//' -e 's/ parentId=.*$//' -e 's/}$//')
[ -n "$layer" ] || { echo "Linux Lab is not on the screen"; exit 1; }
adb -s $D shell dumpsys SurfaceFlinger --latency-clear
if [ $# -ge 4 ]; then
    adb -s $D shell input swipe "$1" "$2" "$3" "$4" "${5:-2000}"
else
    adb -s $D shell "input tap $1 $2; sleep 0.9"
fi
adb -s $D shell "dumpsys SurfaceFlinger --latency '$layer'" | python3 -c '
import sys
rows = [line.split() for line in sys.stdin.read().strip().splitlines()]
period = int(rows[0][0]) / 1e6
shown = sorted(int(r[1]) for r in rows[1:] if len(r) == 3 and 0 < int(r[1]) < 1 << 62)
runs, run = [], []
for t in shown:
    if run and t - run[-1] > 250e6:  # a slide show is still one run; a quarter of a second without a frame ends it
        runs.append(run); run = []
    run.append(t)
runs.append(run)
run = max(runs, key=len)
if len(run) < 3:
    print("too few frames: %d" % len(shown)); sys.exit()
gaps = sorted((b - a) / 1e6 for a, b in zip(run, run[1:]))
span = (run[-1] - run[0]) / 1e9
print("%3.0f frames a second (%3d frames in %.2f s); between frames: median %4.1f ms, worst %4.1f ms; the screen: every %.1f ms"
      % ((len(run) - 1) / span, len(run), span, gaps[len(gaps) // 2], gaps[-1], period))'

#!/bin/bash
# gest.sh <gesture...>: plays gestures of mtu.py through a virtual touchscreen and tells which windows got them.
# Example: gest.sh gap:1200 tapat:1500,1000 tapat:66,130
# The first touch of every run arrives cancelled: start with a tap that does not matter.
D=${ADB_SERIAL:-$(adb get-serialno)}
here=$(dirname "$(readlink -f "$0")")
python3 "$here/mtu.py" "$@" | adb -s $D shell 'cat > /data/local/tmp/g.json'
T0=$(adb -s $D shell 'date +"%m-%d %H:%M:%S.000"')
adb -s $D shell 'uinput /data/local/tmp/g.json' >/dev/null 2>&1
adb -s $D shell "logcat -b events -d -v time -t '$T0'" 2>/dev/null |
    grep -E "input_interaction|notification_clicked" |
    sed -E 's/\[Gesture Monitor\] [A-Za-z-]+, //g; s/PointerEventDispatcher0, //; s/I\/input_interaction\( *[0-9]+\): Interaction with: /TOUCH /' | cut -c7-170

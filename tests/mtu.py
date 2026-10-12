#!/usr/bin/env python3
"""Multi-touch test gestures for `adb shell uinput`: a virtual touchscreen in the panel's natural portrait
coordinates, so display (x, y) in landscape ROTATION_90 is device (1919 - y, x)."""
import json, sys

lines = [{"id": 1, "command": "register", "name": "LL test touch", "vid": 6353, "pid": 43981, "bus": "usb", "port": "usb:9",
          "configuration": [
              {"type": "UI_SET_EVBIT", "data": ["EV_KEY", "EV_ABS"]},
              {"type": "UI_SET_KEYBIT", "data": ["BTN_TOUCH"]},
              {"type": "UI_SET_ABSBIT", "data": ["ABS_MT_SLOT", "ABS_MT_TRACKING_ID", "ABS_MT_POSITION_X", "ABS_MT_POSITION_Y"]},
              {"type": "UI_SET_PROPBIT", "data": ["INPUT_PROP_DIRECT"]}],
          "abs_info": [
              {"code": "ABS_MT_SLOT", "info": {"value": 0, "minimum": 0, "maximum": 9, "fuzz": 0, "flat": 0, "resolution": 0}},
              {"code": "ABS_MT_TRACKING_ID", "info": {"value": 0, "minimum": 0, "maximum": 65535, "fuzz": 0, "flat": 0, "resolution": 0}},
              {"code": "ABS_MT_POSITION_X", "info": {"value": 0, "minimum": 0, "maximum": 1919, "fuzz": 0, "flat": 0, "resolution": 0}},
              {"code": "ABS_MT_POSITION_Y", "info": {"value": 0, "minimum": 0, "maximum": 2999, "fuzz": 0, "flat": 0, "resolution": 0}}]},
         {"id": 1, "command": "delay", "duration": 1500}]
state, tid = {}, [100]


def frame(contacts, pause_ms):
    ev = []
    for slot in sorted(set(state) | set(contacts)):
        if slot in contacts:
            x, y = contacts[slot]
            ev += ["EV_ABS", "ABS_MT_SLOT", slot]
            if slot not in state:
                tid[0] += 1
                ev += ["EV_ABS", "ABS_MT_TRACKING_ID", tid[0]]
            ev += ["EV_ABS", "ABS_MT_POSITION_X", 1919 - y, "EV_ABS", "ABS_MT_POSITION_Y", x]
        else:
            ev += ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_TRACKING_ID", -1]
    if contacts and not state:
        ev += ["EV_KEY", "BTN_TOUCH", 1]
    if state and not contacts:
        ev += ["EV_KEY", "BTN_TOUCH", 0]
    ev += ["EV_SYN", "SYN_REPORT", 0]
    state.clear(); state.update(contacts)
    lines.append({"id": 1, "command": "inject", "events": ev})
    if pause_ms:
        lines.append({"id": 1, "command": "delay", "duration": pause_ms})


def lerp(a, b, n):
    return [(round(a[0] + (b[0] - a[0]) * i / n), round(a[1] + (b[1] - a[1]) * i / n)) for i in range(n + 1)]


GAP = 2000
for g in sys.argv[1:]:
    if g == "tap":
        frame({0: (1500, 700)}, 50); frame({}, GAP)
    elif g == "rollover":  # A down, B down, A up, B up
        a, b = (1400, 700), (1800, 700)
        frame({0: a}, 60); frame({0: a, 1: b}, 60); frame({1: b}, 60); frame({}, GAP)
    elif g == "slide":  # one finger moves 300 px down
        for p in lerp((1500, 600), (1500, 900), 12): frame({0: p}, 20)
        frame({}, GAP)
    elif g == "scroll2":  # two fingers move 300 px down together
        pa, pb = lerp((1400, 600), (1400, 900), 12), lerp((1600, 600), (1600, 900), 12)
        frame({0: pa[0]}, 20)
        for p, q in zip(pa, pb): frame({0: p, 1: q}, 20)
        frame({}, GAP)
    elif g == "crosspane":  # A held in the big pane, B taps in the bottom strip, then A lifts
        a, b = (1500, 700), (1500, 1895)
        frame({0: a}, 80); frame({0: a, 1: b}, 60); frame({0: a}, 80); frame({}, GAP)
    elif g == "dragcross":  # A drags in the big pane while B taps in the bottom strip
        pts = lerp((1300, 500), (1300, 800), 8)
        for p in pts: frame({0: p}, 20)
        frame({0: pts[-1], 1: (1500, 1895)}, 60); frame({0: pts[-1]}, 60); frame({}, GAP)
    elif g == "longpress":
        frame({0: (1500, 800)}, 750); frame({}, GAP)
    elif g.startswith("swipe:"):  # swipe:x1,y1,x2,y2[,steps[,ms per step[,hold ms before lifting]]]
        v = [int(n) for n in g[6:].split(",")]
        steps, ms, hold = (v[4:] + [12, 20, 0][len(v) - 4:])[:3]
        pts = lerp((v[0], v[1]), (v[2], v[3]), steps)
        for p in pts[:-1]: frame({0: p}, ms)
        frame({0: pts[-1]}, max(ms, hold)); frame({}, GAP)
    elif g.startswith("tapat:"):  # tapat:x,y
        x, y = (int(n) for n in g[6:].split(","))
        frame({0: (x, y)}, 50); frame({}, GAP)
    elif g.startswith("gap:"):  # gap:ms, the pause after every gesture that follows
        GAP = int(g[4:])
print("\n".join(json.dumps(l) for l in lines))

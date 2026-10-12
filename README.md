# Linux Lab Helper

An Android app for HONOR Linux Lab (`com.hihonor.pcengine`) on HONOR tablets with Android 16, tried on
the MagicPad 4 (YLE-W09):

- real multitouch in Linux: an overlay over the picture of Linux Lab takes the fingers and sends them
  to `lltouch.so`, a module of Honor's X server, or turns the screen into a touchpad;
- Ubuntu 26.04 with the GNOME desktop of a fresh installation instead of Honor's LXQt, and back;
- on a rooted tablet, the desktop in a chroot instead of PRoot: files, installs and builds many times
  faster (`root/llroot.c`, the root service, started through `su`);
- the battery, the clipboard and the keyboard of Android in Linux.

The Linux side is the repository https://github.com/SpazzRabbit/linuxlab-ubuntu. The app carries its
installer, which the app puts into Downloads with the command to paste into a terminal of Linux Lab.

## Layout

| directory | what is in it |
| --- | --- |
| `client/` | the app: `build.sh` builds it without Gradle (aapt2, javac, d8, apksigner); `src/dev/linuxlab/helper` |
| `root/` | `llroot.c`, the root service of the chroot mode; `build.sh` builds it statically inside Linux Lab and puts it into `client/assets` |
| `tests/` | gestures through `adb shell uinput`, frame counters, a test keyboard |
| `notes/` | the notes of each release |

## Build

With linuxlab-ubuntu checked out next to this repository as `../gnome`, the Android SDK in
`~/Android/Sdk` (or `$ANDROID_HOME`) and the signing key as `../keystore.jks` (not in the repository):

    client/build.sh

## Updates

The app looks at the latest release of this repository when its screen opens. A release carries
`app.json` (version, the APK, its size and SHA-256) and the APK; a newer version shows a card with a
button, which downloads the APK and hands it to the package installer of Android. Android asks the
person and installs only an APK signed with the key of the app already installed.

The Linux side updates itself from its own releases (`llupdate.py` there). A release of both:
`../gnome/host/release.sh` first, then `./release.sh` here.

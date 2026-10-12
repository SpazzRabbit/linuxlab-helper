#!/usr/bin/env bash
# Types text in several languages through the test keyboard (testime/) and prints what was sent,
# one line per language. The other half of the check reads what arrived in a text field of GNOME.
#
# Before: the test keyboard is installed and current (ime set dev.linuxlab.testime/.TestIme), a
# text field of GNOME has the focus and the keyboard is up.
set -euo pipefail
A="adb -s ${ADB_SERIAL:-$(adb get-serialno)}"
do_ime() { # op [text] [n]
    local op=$1 text=${2-} n=${3-1}
    $A shell "am broadcast -a dev.linuxlab.testime.DO --es op $op --es b64 '$(printf '%s' "$text" | base64 -w0)' --ei n $n" >/dev/null
    sleep 0.25
}
line() { do_ime commit "$1"; do_ime commit $'\n'; printf '%s\n' "$1"; }

line "English: Hello, world! 123"
line "Русский: Привет, мир! Ёжик съел щи."
line "Українська: Привіт, їжак і ґанок"
line "Deutsch: Größe, Übermut, Straße"
line "Français: Ça va très bien, où est l'œuf ?"
line "Ελληνικά: Γειά σου, κόσμε"
line "中文: 你好，世界"
line "日本語: こんにちは、世界"
line "한국어: 안녕하세요 세계"
line "العربية: مرحبا بالعالم"
line "עברית: שלום עולם"
line "हिन्दी: नमस्ते दुनिया"
line "ไทย: สวัสดีชาวโลก"
line "Emoji: 👍🙂🚀"

# a word in the making, as a keyboard with completion types it
do_ime commit "Составной ввод: "
do_ime compose "на"; do_ime compose "напеч"; do_ime compose "напечатано"; do_ime finish
do_ime commit $'\n'
printf '%s\n' "Составной ввод: напечатано"

# pinyin: the letters are only ever shown as text in the making, the characters replace them
do_ime commit "拼音: "
do_ime compose "ni"; do_ime compose "nihao"; do_ime commit "你好"
do_ime commit $'\n'
printf '%s\n' "拼音: 你好"

# the backspace key (67), three times
do_ime commit "Backspace: abcXYZ"
do_ime key "" 67; do_ime key "" 67; do_ime key "" 67
do_ime commit $'\n'
printf '%s\n' "Backspace: abc"

# the keyboard asks to delete two characters before the cursor
do_ime commit "Delete: abcXY"
do_ime delete "" 2
do_ime commit $'\n'
printf '%s\n' "Delete: abc"

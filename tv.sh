#!/bin/bash
# Runs commands on the TV over the network with adb. Usage: TV=<tv ip address> ./tv.sh <command>
set -euo pipefail
cd "$(dirname "$0")"

: "${TV:?Set TV to the IP address of the TV, for example: TV=192.168.1.20 ./tv.sh install}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
OUR_HOME=io.github.shaunak3000.tvhome/.Home
STOCK_HOME=com.google.android.apps.tv.launcherx/.home.HomeActivity
# Google TV gives the Home button to the highest-priority system home screen and ignores the user's choice,
# so both of its home screens (the stock home, and the setup wizard's recovery screen) must be off for ours to get it.
SYSTEM_HOMES="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith"
# What debloat switched off. It lives on the TV itself, so restore works from any computer.
SNAPSHOT=/data/local/tmp/x80k-home-disabled.txt

tv() { "$ADB" -s "$TV:5555" "$@"; }
set_home() { tv shell cmd package set-home-activity "$1" && tv shell input keyevent KEYCODE_HOME; }
packages() { grep -oE '^[a-z][A-Za-z0-9_.]+' "$1"; }

case "${1:-}" in
  connect) "$ADB" connect "$TV:5555" ;;
  install) tv install -r build/app.apk ;;
  home)
    tv shell pm path "${OUR_HOME%/*}" > /dev/null || { echo "Install the app first: ./tv.sh install" >&2; exit 1; }
    for p in $SYSTEM_HOMES; do tv shell pm disable-user --user 0 "$p"; done
    set_home "$OUR_HOME" ;;
  stock)
    for p in $SYSTEM_HOMES; do tv shell pm enable --user 0 "$p"; done
    set_home "$STOCK_HOME" ;;
  debloat)
    keep=$(packages x80k/keep.txt)
    for p in $(packages x80k/disable.txt); do
      if grep -qx "$p" <<< "$keep"; then echo "Refusing: $p is on the never-touch list (x80k/keep.txt)" >&2; exit 1; fi
    done
    enabled=$(tv shell pm list packages -e | tr -d '\r' | sed 's/^package://')
    for p in $(packages x80k/disable.txt); do
      grep -qx "$p" <<< "$enabled" || continue   # not installed, or already off
      tv shell "echo $p >> $SNAPSHOT"
      tv shell pm disable-user --user 0 "$p" > /dev/null && echo "off  $p"
    done ;;
  restore)
    for p in $(tv shell cat "$SNAPSHOT" 2> /dev/null | tr -d '\r'); do
      tv shell pm enable --user 0 "$p" > /dev/null && echo "on   $p"
    done
    tv shell rm -f "$SNAPSHOT" ;;
  *)
    echo "usage: TV=<tv ip address> ./tv.sh connect|install|home|stock|debloat|restore" >&2
    echo "Run ./tv.sh stock before any factory reset: it turns the setup wizard back on." >&2
    exit 2 ;;
esac

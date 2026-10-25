#!/bin/bash
# Runs commands on the TV over the network with adb. Usage: TV=<tv ip address> ./tv.sh <command>
set -euo pipefail
cd "$(dirname "$0")"

: "${TV:?Set TV to the IP address of the TV, for example: TV=192.168.1.20 ./tv.sh install}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
OUR_HOME=io.github.shaunak3000.tvhome/.Home
STOCK_HOME=com.google.android.apps.tv.launcherx/.home.HomeActivity

tv() { "$ADB" -s "$TV:5555" "$@"; }
set_home() { tv shell cmd package set-home-activity "$1" && tv shell input keyevent KEYCODE_HOME; }

case "${1:-}" in
  connect) "$ADB" connect "$TV:5555" ;;
  install) tv install -r build/app.apk ;;
  home)    set_home "$OUR_HOME" ;;
  stock)   set_home "$STOCK_HOME" ;;
  *)       echo "usage: TV=<tv ip address> ./tv.sh connect|install|home|stock" >&2; exit 2 ;;
esac

#!/bin/bash
# Runs commands on the TV over the network with adb. Usage: TV=<tv ip address> ./tv.sh <command>
set -euo pipefail
cd "$(dirname "$0")"

: "${TV:?Set TV to the IP address of the TV, for example: TV=192.168.1.20 ./tv.sh install}"
ADB=$(command -v adb || echo "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb")
OUR_HOME=io.github.shaunak3000.tvhome/.Home
STOCK_HOME=com.google.android.apps.tv.launcherx/.home.HomeActivity
# Google TV gives the Home button to the highest-priority system home screen and ignores the user's choice,
# so both of its home screens (the stock home, and the setup wizard's recovery screen) must be off for ours to get it.
SYSTEM_HOMES="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith"

tv() { "$ADB" -s "$TV:5555" "$@"; }
set_home() { tv shell cmd package set-home-activity "$1" && tv shell input keyevent KEYCODE_HOME; }
packages() { grep -oE '^[a-z][A-Za-z0-9_.]+' "$1"; }
# The lists for this TV's platform, which the TV reports itself (BRAVIA_VH21 for the X80K).
model() {
  local device; device=$(tv shell getprop ro.product.device | tr -d '\r')
  [ -n "$device" ] || { echo "Can't reach the TV. Is it on, with USB debugging on? Try ./tv.sh connect" >&2; exit 1; }
  [ -d "models/$device" ] || { echo "No lists for this TV yet (models/$device). CONTRIBUTING.md explains how to add them." >&2; exit 1; }
  echo "models/$device"
}
on_list() { for p in $(packages "$1"); do grep -qx "$p" <<< "$2" && echo "$p"; done; true; }

case "${1:-}" in
  connect) "$ADB" connect "$TV:5555" ;;
  install) tv install -r "${2:-build/app.apk}" ;;
  home)
    tv shell pm path "${OUR_HOME%/*}" > /dev/null || { echo "Install the app first: ./tv.sh install" >&2; exit 1; }
    for p in $SYSTEM_HOMES; do tv shell pm disable-user --user 0 "$p"; done
    set_home "$OUR_HOME" ;;
  stock)
    for p in $SYSTEM_HOMES; do tv shell pm enable --user 0 "$p"; done
    set_home "$STOCK_HOME" ;;
  debloat)
    m=$(model)
    clash=$(on_list "$m/disable.txt" "$(packages "$m/keep.txt")")
    [ -z "$clash" ] || { echo "Refusing: on both lists in $m: $clash" >&2; exit 1; }
    for p in $(on_list "$m/disable.txt" "$(tv shell pm list packages -e | tr -d '\r' | sed 's/^package://')"); do
      tv shell pm disable-user --user 0 "$p" > /dev/null && echo "off  $p"
    done ;;
  restore)
    m=$(model)
    for p in $(on_list "$m/disable.txt" "$(tv shell pm list packages -d | tr -d '\r' | sed 's/^package://')"); do
      tv shell pm enable --user 0 "$p" > /dev/null && echo "on   $p"
    done ;;
  check)   # firmware updates can switch things back on, or give Home back to Google
    m=$(model) drift=0
    home=$(tv shell cmd package resolve-activity --components -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r')
    [ "$home" = "$OUR_HOME" ] || { echo "Home is $home, not Frame. Fix: ./tv.sh home"; drift=1; }
    back=$(on_list "$m/disable.txt" "$(tv shell pm list packages -e | tr -d '\r' | sed 's/^package://')")
    [ -z "$back" ] || { echo "Switched back on:" $back; echo "Fix: ./tv.sh debloat"; drift=1; }
    [ $drift = 0 ] && echo "All good: Frame is Home, and everything on $m/disable.txt is off." || exit 1 ;;
  *)
    echo "usage: TV=<tv ip address> ./tv.sh connect|install [apk]|home|stock|debloat|restore|check" >&2
    echo "Run ./tv.sh stock before any factory reset: it turns the setup wizard back on." >&2
    exit 2 ;;
esac

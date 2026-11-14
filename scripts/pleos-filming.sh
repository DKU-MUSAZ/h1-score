#!/bin/bash
# Explicit fixed-data rehearsal only. Never clears the package or production preferences.
set -euo pipefail
ADB="${ANDROID_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=ai.musaz.h1score
PROFILE=h1-filming-fixed-v1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
USER_ID="$("$ADB" shell am get-current-user | tr -d '\r')"
broadcast() { "$ADB" shell am broadcast --user "$USER_ID" -n "$PKG/.PleosRehearsalReceiver" -a "$PKG.REHEARSE_NAVIGATION" --es event "$1"; }
case "${1:-status}" in
  prepare)
    "$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null
    sleep 3
    config="$(broadcast configuration)"
    [[ "$config" == *"filming=true profile=$PROFILE"* ]] || { echo 'Install the debug APK built with -Pfilming=true first.'; exit 1; }
    status="$(broadcast status)"
    [[ "$status" == *"parked=true"* ]] || { echo 'Stop completely and select P before preparing.'; exit 1; }
    [[ "$status" != *'phase=RECORDING'* && "$status" != *'phase=RESERVED'* && "$status" != *'phase=AWAITING_PARK'* ]] || { echo 'Finish or cancel the current challenge first.'; exit 1; }
    backup="$ROOT/outputs/fixed-filming/backups/$(date +%Y%m%d-%H%M%S)-$$"
    mkdir -p "$backup"
    "$ADB" shell am force-stop --user "$USER_ID" "$PKG"
    "$ADB" shell run-as "$PKG" --user "$USER_ID" cat "shared_prefs/$PROFILE.xml" > "$backup/$PROFILE.xml"
    test -s "$backup/$PROFILE.xml"
    # Only the versioned filming profile is reset; all older filming and user profiles remain.
    "$ADB" shell run-as "$PKG" --user "$USER_ID" rm -f "shared_prefs/$PROFILE.xml" "shared_prefs/$PROFILE.xml.bak"
    "$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null
    sleep 3
    broadcast enable
    echo "Fixed baseline restored. Previous take backed up: $backup"
    ;;
  status|configuration|enable|10km|10km-live|arrive|announce-saved-result) broadcast "${1:-status}" ;;
  notifications) "$ADB" shell cmd statusbar expand-notifications ;;
  export)
    mkdir -p "$ROOT/outputs/fixed-filming"
    "$ADB" shell run-as "$PKG" --user "$USER_ID" cat "shared_prefs/$PROFILE.xml" > "$ROOT/outputs/fixed-filming/current.xml"
    echo "$ROOT/outputs/fixed-filming/current.xml"
    ;;
  *) echo 'Usage: scripts/pleos-filming.sh prepare|status|configuration|enable|10km|10km-live|arrive|announce-saved-result|notifications|export'; exit 2 ;;
esac

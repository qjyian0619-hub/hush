#!/system/bin/sh
STATE=/data/adb/regionguard
BB=/data/adb/ksu/bin/busybox
umask 077
mkdir -p "$STATE" || exit 1
if [ "$1" != --locked ]; then
  exec "$BB" flock -x "$STATE/control.lock" /system/bin/sh "$0" --locked "$@"
fi
shift
state_file=/data/user/0/dev.local.regionguard/files/ksu-state.json
error() { echo '{"ok":false,"error":"Backend communication failed. Check that LSPosed and Region Guard are enabled."}'; exit 1; }
call_backend() {
  result=$(/system/bin/content call --user 0 --uri content://dev.local.regionguard.control "$@" 2>&1) || error
  case "$result" in *'ok=true'*) ;; *) error ;; esac
}
# Prints the backend state. Also publishes the renderer time zone that the Zygisk companion hands to
# Chrome and Edge, only while protection, browser protection and the KSU module are on this boot.
publish() {
  state=$(cat "$state_file") || error
  zone=$(printf '%s' "$state" | sed -n 's/.*"zone":"\([^"]*\)".*/\1/p' | sed 's#\\/#/#g')
  boot=$(/system/bin/getprop ro.boottime.init)
  active=true
  for key in enabled browsers managerEnabled; do
    case "$state" in *"\"$key\":true"*) ;; *) active=false ;; esac
  done
  case "$zone" in America/Los_Angeles|America/New_York|Asia/Tokyo|Europe/London|UTC) ;; *) active=false ;; esac
  case "$boot" in ''|*[!0-9]*) active=false ;; esac
  previous=$(cat "$STATE/renderer-zone" 2>/dev/null)
  if [ "$active" = true ]; then
    printf '%s %s\n' "$zone" "$boot" >"$STATE/renderer-zone.tmp" && mv -f "$STATE/renderer-zone.tmp" "$STATE/renderer-zone"
  else
    rm -f "$STATE/renderer-zone"
  fi
  if [ "$previous" != "$(cat "$STATE/renderer-zone" 2>/dev/null)" ]; then
    # Browser app zygotes outlive a force-stop and keep the zone they started with. End them so the
    # next renderer comes from a new one; renderers already running are not affected.
    for zygote in com.android.chrome_zygote com.microsoft.emmx_zygote; do
      for pid in $(pidof "$zygote"); do kill "$pid" 2>/dev/null; done
    done
  fi
  printf '%s\n' "$state"
}
case "${1:-status}" in
  status)
    call_backend --method status
    publish
    ;;
  save)
    case "$2" in America/Los_Angeles|America/New_York|Asia/Tokyo|Europe/London|UTC) ;; *) error ;; esac
    case "$3:$4" in true:true|true:false|false:true|false:false) ;; *) error ;; esac
    call_backend --method save --extra "zone:s:$2" --extra "enabled:b:$3" --extra "browsers:b:$4"
    publish
    ;;
  module-state)
    case "$2" in true|false) ;; *) error ;; esac
    boot=$(/system/bin/getprop ro.boottime.init)
    case "$boot" in ''|*[!0-9]*) error ;; esac
    call_backend --method module-state --extra "boot:s:$boot" --extra "enabled:b:$2"
    publish
    ;;
  native-ui)
    /system/bin/am start --user 0 -n dev.local.regionguard/.MainActivity >/dev/null 2>&1 || error
    echo '{"ok":true}'
    ;;
  *) error ;;
esac

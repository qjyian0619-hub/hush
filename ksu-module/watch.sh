#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/regionguard
BB=/data/adb/ksu/bin/busybox
umask 077
mkdir -p "$STATE"
if [ "$1" != --locked ]; then
  exec "$BB" flock -n "$STATE/watch.lock" /system/bin/sh "$0" --locked
fi
echo $$ >"$STATE/watch.pid"
last=""
while [ -d "$MODDIR" ]; do
  enabled=true
  if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then enabled=false; fi
  if [ "$enabled" != "$last" ]; then
    if /system/bin/sh "$MODDIR/bridge.sh" module-state "$enabled" >"$STATE/last-state.json"; then last=$enabled; fi
  fi
  sleep 2
done

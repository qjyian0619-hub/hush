#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/regionguard
mkdir -p "$STATE"
chmod 0700 "$STATE"
nohup /data/adb/ksu/bin/busybox sh "$MODDIR/watch.sh" >"$STATE/watch.log" 2>&1 </dev/null &

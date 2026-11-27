#!/system/bin/sh
MODDIR=${0%/*}
sh "$MODDIR/service.sh"
exec /system/bin/am start --user 0 -n me.weishu.kernelsu/.ui.webui.WebUIActivity -d 'ksu://webui?id=regionguard'

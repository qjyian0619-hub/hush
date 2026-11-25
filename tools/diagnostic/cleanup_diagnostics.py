"""Close only this task's localhost diagnostic tabs; remove our temporary forwards."""
import json
import subprocess
import urllib.request
from urllib.parse import quote

closed = 0
for socket in ("chrome_devtools_remote", "chrome_devtools_remote_9307"):
    result = subprocess.run(["adb", "forward", "tcp:0", "localabstract:"+socket],capture_output=True,text=True)
    if result.returncode:
        continue
    port = result.stdout.strip()
    try:
        base = "http://127.0.0.1:"+port
        tabs = json.load(urllib.request.urlopen(base+"/json/list",timeout=3))
        for tab in tabs:
            if tab.get("url", "").startswith("http://127.0.0.1:8765/diagnostic.html?client="):
                urllib.request.urlopen(base+"/json/close/"+quote(tab["id"],safe=""),timeout=3).read()
                closed += 1
    except Exception as error:
        print(type(error).__name__+": optional tab cleanup unavailable")
    finally:
        subprocess.run(["adb", "forward", "--remove", "tcp:"+port],capture_output=True)
print(f"Closed {closed} local diagnostic tabs")

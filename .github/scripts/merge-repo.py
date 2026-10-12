import html
import json
from pathlib import Path
import shutil
import sys

REMOTE_REPO = Path.cwd()
LOCAL_REPO = Path(sys.argv[2]) if len(sys.argv) > 2 else REMOTE_REPO

# Copy new APKs and icons
if (LOCAL_REPO / "apk").exists():
    shutil.copytree(src=LOCAL_REPO / "apk", dst=REMOTE_REPO / "apk", dirs_exist_ok=True)
if (LOCAL_REPO / "icon").exists():
    shutil.copytree(src=LOCAL_REPO / "icon", dst=REMOTE_REPO / "icon", dirs_exist_ok=True)

remote_index = {}
if (REMOTE_REPO / "index.json").exists():
    try:
        with (REMOTE_REPO / "index.json").open(encoding="utf-8") as f:
            for item in json.load(f):
                remote_index[item["pkg"]] = item
    except Exception:
        pass

if (LOCAL_REPO / "index.json").exists():
    try:
        with (LOCAL_REPO / "index.json").open(encoding="utf-8") as f:
            for item in json.load(f):
                remote_index[item["pkg"]] = item
    except Exception:
        pass

index = list(remote_index.values())
index.sort(key=lambda x: x["name"])

with (REMOTE_REPO / "index.json").open("w", encoding="utf-8") as f:
    json.dump(index, f, ensure_ascii=False, indent=2)

with (REMOTE_REPO / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

with (REMOTE_REPO / "index.html").open("w", encoding="utf-8") as f:
    f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>Dantotsu Novel Extensions</title>\n</head>\n<body>\n<h1>Dantotsu Novel Extensions Repository</h1>\n<ul>\n')
    for entry in index:
        apk_link = 'apk/' + html.escape(entry["apk"])
        name = html.escape(entry["name"])
        version = html.escape(entry.get("version", ""))
        f.write(f'<li><a href="{apk_link}">{name} v{version}</a> ({entry["pkg"]})</li>\n')
    f.write('</ul>\n</body>\n</html>\n')

print(f"Successfully merged repo artifacts. Total extensions: {len(index)}")

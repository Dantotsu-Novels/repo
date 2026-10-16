import html
import json
from pathlib import Path
import shutil
import sys

REMOTE_REPO = Path.cwd()
LOCAL_REPO = Path(sys.argv[2]) if len(sys.argv) > 2 else REMOTE_REPO

def discover_valid_packages() -> set[str]:
    pkgs = set()
    src_dir = Path("src")
    if src_dir.exists():
        for mod in src_dir.iterdir():
            bg = mod / "build.gradle"
            if mod.is_dir() and bg.exists():
                try:
                    text = bg.read_text(encoding="utf-8", errors="ignore")
                    m = __import__("re").search(r"extSuffix\s*=\s*['\"]([^'\"]+)['\"]", text)
                    if m:
                        pkgs.add(f"ani.dantotsu.extension.novel.{m.group(1)}")
                except Exception:
                    pass
    for p in [LOCAL_REPO / "apk", REMOTE_REPO / "apk"]:
        if p.exists():
            for apk in p.glob("ani.dantotsu.extension.novel.*.apk"):
                pkgs.add(apk.stem)
    return pkgs

VALID_PACKAGES = discover_valid_packages()

# Copy new APKs, icons, and repo.json
if (LOCAL_REPO / "apk").exists():
    shutil.copytree(src=LOCAL_REPO / "apk", dst=REMOTE_REPO / "apk", dirs_exist_ok=True)
if (LOCAL_REPO / "icon").exists():
    shutil.copytree(src=LOCAL_REPO / "icon", dst=REMOTE_REPO / "icon", dirs_exist_ok=True)
if (LOCAL_REPO / "repo.json").exists():
    shutil.copy2(src=LOCAL_REPO / "repo.json", dst=REMOTE_REPO / "repo.json")

# Clean obsolete files from remote
for apk in (REMOTE_REPO / "apk").glob("*.apk"):
    if apk.stem not in VALID_PACKAGES:
        print(f"Purging obsolete remote APK: {apk}")
        apk.unlink()

for icon in (REMOTE_REPO / "icon").glob("*.*"):
    if icon.stem not in VALID_PACKAGES or icon.suffix != ".png":
        print(f"Purging obsolete remote icon: {icon}")
        icon.unlink()

# Purge duplicate nested repo/ directory if it exists
nested_repo = REMOTE_REPO / "repo"
if nested_repo.exists() and nested_repo.is_dir():
    print(f"Purging duplicate nested repo directory: {nested_repo}")
    shutil.rmtree(nested_repo, ignore_errors=True)

remote_index = {}

def load_clean(path: Path):
    if not path.exists():
        return
    try:
        with path.open(encoding="utf-8") as f:
            for item in json.load(f):
                pkg = item.get("pkg")
                if pkg in VALID_PACKAGES:
                    # Strip any bad icon property so Dantotsu relies on $cleanRepo/icon/${pkg}.png
                    item.pop("icon", None)
                    item.pop("iconUrl", None)
                    remote_index[pkg] = item
    except Exception as e:
        print(f"Error reading {path}: {e}")

load_clean(REMOTE_REPO / "index.json")
load_clean(LOCAL_REPO / "index.json")

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

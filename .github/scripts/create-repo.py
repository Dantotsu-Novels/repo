import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_REGEX = re.compile(r"^application-icon-(?:320|480|640|160):'([^']+)'", re.MULTILINE)

REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_DIR.mkdir(parents=True, exist_ok=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

# Extension source definitions for Dantotsu novel extensions
SOURCES_DATA = {
    "some.random.novels": {
        "name": "AnnasArchive",
        "lang": "all",
        "id": "4273510687828550053",
        "baseUrl": "https://annas-archive.gl"
    },
    "some.random.libgen": {
        "name": "Libgen",
        "lang": "all",
        "id": "0510365478052738285",
        "baseUrl": "https://libgen.la"
    }
}

DEFAULT_METAS = {
    "some.random.novels": {"name": "AnnaArchive", "code": 7, "version": "1.7"},
    "some.random.libgen": {"name": "Libgen", "code": 5, "version": "1.5"}
}

def find_aapt() -> Path | None:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        build_tools = Path(android_home) / "build-tools"
        if build_tools.exists():
            for v in sorted(build_tools.iterdir(), reverse=True):
                aapt = v / ("aapt.exe" if os.name == "nt" else "aapt")
                if aapt.exists():
                    return aapt
    which_aapt = shutil.which("aapt")
    return Path(which_aapt) if which_aapt else None

aapt_path = find_aapt()
index_data = []

# Load existing index if present to preserve unchanged extensions
existing_index = {}
if (REPO_DIR / "index.json").exists():
    try:
        with open(REPO_DIR / "index.json", "r", encoding="utf-8") as f:
            for item in json.load(f):
                existing_index[item["pkg"]] = item
    except Exception:
        pass

for apk in REPO_APK_DIR.glob("*.apk"):
    pkg_name = apk.stem
    label = DEFAULT_METAS.get(pkg_name, {}).get("name", pkg_name.split(".")[-1].capitalize())
    code = DEFAULT_METAS.get(pkg_name, {}).get("code", 1)
    version = DEFAULT_METAS.get(pkg_name, {}).get("version", "1.0")

    if aapt_path:
        try:
            badging = subprocess.check_output(
                [str(aapt_path), "dump", "--include-meta-data", "badging", str(apk)],
                text=True, encoding="utf-8", errors="ignore"
            )
            pkg_m = PACKAGE_NAME_REGEX.search(badging)
            if pkg_m:
                pkg_name = pkg_m.group(1)
            code_m = VERSION_CODE_REGEX.search(badging)
            if code_m:
                code = int(code_m.group(1))
            v_m = VERSION_NAME_REGEX.search(badging)
            if v_m:
                version = v_m.group(1)
            lbl_m = APPLICATION_LABEL_REGEX.search(badging)
            if lbl_m:
                label = lbl_m.group(1)

            icon_m = APPLICATION_ICON_REGEX.search(badging)
            if icon_m:
                icon_path = icon_m.group(1)
                try:
                    with ZipFile(apk) as z, z.open(icon_path) as src_icon:
                        with (REPO_ICON_DIR / f"{pkg_name}.png").open("wb") as dst_icon:
                            dst_icon.write(src_icon.read())
                except Exception as e:
                    print(f"Could not extract icon from {apk}: {e}")
        except Exception as e:
            print(f"aapt dump failed for {apk}: {e}")

    sources_list = []
    if pkg_name in SOURCES_DATA:
        sources_list.append(SOURCES_DATA[pkg_name])
    else:
        sources_list.append({
            "name": label,
            "lang": "all",
            "id": str(abs(hash(pkg_name))),
            "baseUrl": ""
        })

    entry = {
        "name": label,
        "pkg": pkg_name,
        "apk": apk.name,
        "lang": "all",
        "code": code,
        "version": version,
        "nsfw": 0,
        "hasReadme": 0,
        "hasChangelog": 0,
        "sources": sources_list
    }
    existing_index[pkg_name] = entry

index_data = list(existing_index.values())
index_data.sort(key=lambda x: x["name"])

# Write index.json (pretty printed)
with open(REPO_DIR / "index.json", "w", encoding="utf-8") as f:
    json.dump(index_data, f, indent=2, ensure_ascii=False)
print(f"Generated {REPO_DIR / 'index.json'} with {len(index_data)} extensions.")

# Write index.min.json (compact)
with open(REPO_DIR / "index.min.json", "w", encoding="utf-8") as f:
    json.dump(index_data, f, ensure_ascii=False, separators=(",", ":"))
print(f"Generated {REPO_DIR / 'index.min.json'}.")

# Write repo.json metadata
repo_meta = {
    "meta": {
        "name": "Dantotsu Novels",
        "shortName": "Dannovels",
        "website": "https://github.com/Dantotsu-Novels/repo"
    }
}
with open(REPO_DIR / "repo.json", "w", encoding="utf-8") as f:
    json.dump(repo_meta, f, indent=2, ensure_ascii=False)
print(f"Generated {REPO_DIR / 'repo.json'}.")

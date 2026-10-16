import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile
try:
    from PIL import Image
except ImportError:
    Image = None

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

def discover_modules() -> tuple[set[str], dict[str, dict]]:
    pkgs = set()
    metas = {}
    src_dir = Path("src")
    if src_dir.exists():
        for mod in src_dir.iterdir():
            bg = mod / "build.gradle"
            if mod.is_dir() and bg.exists():
                try:
                    text = bg.read_text(encoding="utf-8", errors="ignore")
                    suffix_m = re.search(r"extSuffix\s*=\s*['\"]([^'\"]+)['\"]", text)
                    if suffix_m:
                        suffix = suffix_m.group(1)
                        pkg = f"ani.dantotsu.extension.novel.{suffix}"
                        pkgs.add(pkg)
                        name_m = re.search(r"extName\s*=\s*['\"]([^'\"]+)['\"]", text)
                        code_m = re.search(r"extVersionCode\s*=\s*(\d+)", text)
                        ver_m = re.search(r"extVersionName\s*=\s*['\"]([^'\"]+)['\"]", text)
                        metas[pkg] = {
                            "name": name_m.group(1) if name_m else suffix.capitalize(),
                            "code": int(code_m.group(1)) if code_m else 1,
                            "version": ver_m.group(1) if ver_m else "1.0",
                            "module": mod.name
                        }
                except Exception as e:
                    print(f"Error discovering module in {mod}: {e}")
    # Also include existing novel APKs in repo/apk or apk/
    for p in [REPO_APK_DIR, Path("apk")]:
        if p.exists():
            for apk in p.glob("ani.dantotsu.extension.novel.*.apk"):
                pkgs.add(apk.stem)
    return pkgs, metas

VALID_PACKAGES, DEFAULT_METAS = discover_modules()

SOURCES_DATA = {
    "ani.dantotsu.extension.novel.annaarchive": {
        "name": "AnnasArchive",
        "lang": "all",
        "id": "4273510687828550053",
        "baseUrl": "https://annas-archive.gl"
    },
    "ani.dantotsu.extension.novel.libgen": {
        "name": "Libgen",
        "lang": "all",
        "id": "0510365478052738285",
        "baseUrl": "https://libgen.la"
    },
    "ani.dantotsu.extension.novel.calibre": {
        "name": "Calibre",
        "lang": "all",
        "id": "7182946102837461928",
        "baseUrl": "http://127.0.0.1:8080"
    },
    "ani.dantotsu.extension.novel.quotev": {
        "name": "Quotev",
        "lang": "all",
        "id": "3829104820194857201",
        "baseUrl": "https://www.quotev.com"
    },
    "ani.dantotsu.extension.novel.wtrlab": {
        "name": "WtrLab",
        "lang": "all",
        "id": "8472910482019482710",
        "baseUrl": "https://wtr-lab.com"
    }
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

def extract_icon_from_apk(apk: Path, icon_path_in_apk: str, pkg_name: str) -> bool:
    """Extract raster icon from APK. Returns True if a valid PNG was saved."""
    if icon_path_in_apk.endswith(".xml"):
        return False
    dst_png = REPO_ICON_DIR / f"{pkg_name}.png"
    try:
        with ZipFile(apk) as z, z.open(icon_path_in_apk) as src_icon:
            data = src_icon.read()
            if icon_path_in_apk.endswith(".webp") and Image:
                import io
                im = Image.open(io.BytesIO(data)).convert("RGBA")
                im.save(dst_png, "PNG")
            else:
                with dst_png.open("wb") as f:
                    f.write(data)
        return True
    except Exception as e:
        print(f"Failed to extract {icon_path_in_apk} from {apk}: {e}")
        return False

def ensure_raster_icon(pkg_name: str) -> None:
    """Fallback: copy pre-rendered PNG icon from src/<module>/res or existing icon/ folder."""
    dst_png = REPO_ICON_DIR / f"{pkg_name}.png"
    if dst_png.exists() and dst_png.stat().st_size > 1000:
        return
    module = DEFAULT_METAS.get(pkg_name, {}).get("module", "")
    src_candidates = [
        Path(f"src/{module}/res/mipmap-xxxhdpi/ic_launcher.png"),
        Path(f"src/{module}/res/mipmap-xxhdpi/ic_launcher.png"),
        Path(f"src/{module}/res/mipmap-xhdpi/ic_launcher.png"),
        Path(f"icon/{pkg_name}.png"),
    ]
    for candidate in src_candidates:
        if candidate.exists() and candidate.stat().st_size > 1000:
            shutil.copy2(candidate, dst_png)
            print(f"Copied icon from {candidate} -> {dst_png}")
            return

# Clean out any old/invalid APKs
for apk in REPO_APK_DIR.glob("*.apk"):
    if apk.stem not in VALID_PACKAGES:
        print(f"Removing obsolete APK: {apk}")
        apk.unlink()

# Clean out any old/invalid icons
for icon in REPO_ICON_DIR.glob("*.*"):
    if icon.stem not in VALID_PACKAGES or icon.suffix != ".png":
        print(f"Removing obsolete icon: {icon}")
        icon.unlink()

aapt_path = find_aapt()
existing_index = {}

# Clean load existing index, discarding obsolete packages
if (REPO_DIR / "index.json").exists():
    try:
        with open(REPO_DIR / "index.json", "r", encoding="utf-8") as f:
            for item in json.load(f):
                if item.get("pkg") in VALID_PACKAGES:
                    # Remove any bad icon field so Dantotsu uses default $cleanRepo/icon/${pkg}.png
                    item.pop("icon", None)
                    item.pop("iconUrl", None)
                    existing_index[item["pkg"]] = item
    except Exception:
        pass

for apk in sorted(REPO_APK_DIR.glob("*.apk")):
    pkg_name = apk.stem
    if pkg_name not in VALID_PACKAGES:
        continue

    label = DEFAULT_METAS.get(pkg_name, {}).get("name", pkg_name.split(".")[-1].capitalize())
    code = DEFAULT_METAS.get(pkg_name, {}).get("code", 1)
    version = DEFAULT_METAS.get(pkg_name, {}).get("version", "1.0")

    icon_extracted = False
    if aapt_path:
        try:
            badging = subprocess.check_output(
                [str(aapt_path), "dump", "--include-meta-data", "badging", str(apk)],
                text=True, encoding="utf-8", errors="ignore"
            )
            pkg_m = PACKAGE_NAME_REGEX.search(badging)
            if pkg_m and pkg_m.group(1) in VALID_PACKAGES:
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
                icon_path_in_apk = icon_m.group(1)
                icon_extracted = extract_icon_from_apk(apk, icon_path_in_apk, pkg_name)
        except Exception as e:
            print(f"aapt dump failed for {apk}: {e}")

    if not icon_extracted:
        ensure_raster_icon(pkg_name)

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

    # Do not set "icon" property — Dantotsu automatically uses $cleanRepo/icon/${pkg}.png
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

# Also mirror icons into root icon/ directory
Path("icon").mkdir(parents=True, exist_ok=True)
for icon in REPO_ICON_DIR.glob("*.png"):
    shutil.copy2(icon, Path("icon") / icon.name)

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

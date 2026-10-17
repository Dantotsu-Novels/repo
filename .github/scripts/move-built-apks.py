from pathlib import Path
import shutil

REPO_APK_DIR = Path("repo/apk")
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

# Search built APKs: first look in ~/apk-artifacts (CI download), else look in src/ (local build outputs)
artifacts_dir = Path.home() / "apk-artifacts"
search_paths = [artifacts_dir] if artifacts_dir.exists() else [Path("src")]

for base_dir in search_paths:
    if not base_dir.exists():
        continue
    for apk in base_dir.glob("**/*.apk"):
        if "repo" in apk.parts or "debug" in apk.parts:
            continue
        apk_name = apk.name.replace("-release.apk", ".apk").replace("-unsigned.apk", ".apk")
        destination = REPO_APK_DIR / apk_name
        shutil.copy2(apk, destination)
        print(f"Collected APK: {apk} -> {destination}")

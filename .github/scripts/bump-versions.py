#!/usr/bin/env python3

from concurrent.futures import Future, ThreadPoolExecutor, as_completed
import itertools
from pathlib import Path
import re
import subprocess
import sys

# Matches both:
# extra["extVersionCode"] = 7
# extVersionCode = 7
VERSION_CODE_REGEX = re.compile(r'(extra\["extVersionCode"\]\s*=\s*|extVersionCode\s*=\s*)(\d+)')
VERSION_NAME_REGEX = re.compile(r'(extra\["extVersionName"\]\s*=\s*"|extVersionName\s*=\s*")(\d+\.\d+)(")')
BUMPED_FILES: list[Path] = []

def has_match(query: str, file: Path) -> tuple[Path, bool]:
    try:
        return (file, query in file.read_text(encoding="utf-8"))
    except Exception:
        return (file, False)

def find_extension_build_files() -> list[Path]:
    files = list(Path("src").glob("*/build.gradle.kts")) + list(Path("src").glob("*/build.gradle"))
    return [f for f in files if f not in BUMPED_FILES]

def replace_version_code(match: re.Match) -> str:
    prefix = match.group(1)
    code = int(match.group(2))
    new_code = code + 1
    print(f"Code: {code} -> {new_code}")
    return f"{prefix}{new_code}"

def replace_version_name(match: re.Match) -> str:
    prefix = match.group(1)
    vname = match.group(2)
    suffix = match.group(3)
    parts = vname.split(".")
    if len(parts) == 2:
        new_vname = f"{parts[0]}.{int(parts[1]) + 1}"
    else:
        new_vname = vname
    print(f"Name: {vname} -> {new_vname}")
    return f"{prefix}{new_vname}{suffix}"

def bump_extension(file: Path):
    BUMPED_FILES.append(file)
    content = file.read_text(encoding="utf-8")
    print(f"Bumping {file}: ")
    new_content = VERSION_CODE_REGEX.sub(replace_version_code, content)
    new_content = VERSION_NAME_REGEX.sub(replace_version_name, new_content)
    file.write_text(new_content, encoding="utf-8")

def commit_changes(commit_msg: str):
    if not BUMPED_FILES:
        return
    paths = [str(p.resolve()) for p in BUMPED_FILES]
    subprocess.check_call(["git", "add"] + paths)
    if sys.argv[1] == "--stage-changes":
        print("\nProcessing complete. Changes staged.")
        return
    full_message = f"[skip ci] chore: Mass-bump novel extensions\n\nCaused by: {commit_msg}"
    subprocess.check_call(["git", "commit", "-m", full_message])

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: bump-versions.py [<COMMIT_MESSAGE> | --dry-run | --stage-changes] [changed_files...]")
        sys.exit(1)

    flag_or_msg = sys.argv[1]
    changed_files = sys.argv[2:]

    # Check if core/ or common/ was modified
    core_changed = any(f.startswith("core/") or f.startswith("common/") or "libs.versions.toml" in f for f in changed_files)

    if core_changed or not changed_files:
        print("Core/Common changed or mass bump triggered: bumping all extensions...")
        for ext_build_file in find_extension_build_files():
            if flag_or_msg == "--dry-run":
                print(f"[Dry Run] Would bump {ext_build_file}")
            else:
                bump_extension(ext_build_file)
    else:
        # Bump specific changed extensions
        for f in changed_files:
            p = Path(f)
            if p.parts and p.parts[0] == "src":
                ext_dir = Path("src") / p.parts[1]
                build_file = ext_dir / "build.gradle.kts"
                if not build_file.exists():
                    build_file = ext_dir / "build.gradle"
                if build_file.exists() and build_file not in BUMPED_FILES:
                    if flag_or_msg == "--dry-run":
                        print(f"[Dry Run] Would bump {build_file}")
                    else:
                        bump_extension(build_file)

    if BUMPED_FILES and flag_or_msg not in ("--dry-run", "--modify-only"):
        commit_changes(flag_or_msg)

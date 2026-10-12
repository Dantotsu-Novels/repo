#!/usr/bin/env python3

import json
import os
import re
import subprocess
import sys
from pathlib import Path

CORE_FILES_REGEX = re.compile(
    r"^(common/|core/|gradle/|build\.gradle\.kts|common\.gradle|gradle\.properties|settings\.gradle\.kts|\.github/scripts)"
)

def run_command(command: str) -> str:
    result = subprocess.run(command, capture_output=True, text=True, shell=True)
    if result.returncode != 0:
        print(result.stderr.strip(), file=sys.stderr)
        return ""
    return result.stdout.strip()

def get_all_modules() -> list[str]:
    src = Path("src")
    if not src.exists():
        return []
    modules = []
    for d in src.iterdir():
        if d.is_dir() and ((d / "build.gradle.kts").exists() or (d / "build.gradle").exists()):
            modules.append(f":src:{d.name}:assembleRelease")
    return sorted(modules)

def get_changed_modules(base_sha: str) -> list[str]:
    changed_files = run_command(f"git diff --name-only {base_sha} HEAD").splitlines()
    if not changed_files:
        return get_all_modules()

    # If any core/build file changed, rebuild all
    if any(CORE_FILES_REGEX.match(f) for f in changed_files):
        print("Core/Build files modified: triggering build for all extensions.")
        return get_all_modules()

    modules = set()
    for f in changed_files:
        p = Path(f)
        if len(p.parts) > 1 and p.parts[0] == "src":
            ext_name = p.parts[1]
            modules.add(f":src:{ext_name}:assembleRelease")

    return sorted(list(modules))

def main():
    base_sha = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else "HEAD~1"
    mode = sys.argv[2] if len(sys.argv) > 2 else "Release"

    modules = get_changed_modules(base_sha)
    if not modules:
        modules = get_all_modules()

    chunk_size = int(os.environ.get("CI_CHUNK_SIZE", 15))
    chunks = []
    for i in range(0, len(modules), chunk_size):
        chunk_modules = modules[i:i + chunk_size]
        chunks.append({
            "number": (i // chunk_size) + 1,
            "modules": chunk_modules
        })

    matrix = {"chunk": chunks}
    matrix_json = json.dumps(matrix)

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"matrix={matrix_json}\n")
            f.write("delete=[]\n")

    print(f"Generated build matrix with {len(chunks)} chunks and {len(modules)} modules.")

if __name__ == "__main__":
    main()

"""Read-only integrity cross-check of completed native UI fixture cases.

Usage: python3 verify_native_archives.py /path/to/pds-onboarding-v10 local remote
The intentionally corrupt 'blocked' case should not be passed as a valid-archive case.
"""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

base = Path(sys.argv[1])
results = []
for name in sys.argv[2:]:
    case = base / name
    commits = {}
    objects_checked = 0
    for path in sorted((case / "remote").glob("pdsync-v2-commit-*.json")):
        commit = json.loads(path.read_text())
        manifest = commit["snapshot"]
        archive = case / "remote" / f"pdsync-v2-object-{manifest['id']}.zip"
        assert archive.stat().st_size == commit["objectBytes"], (name, "object size")
        assert archive.stat().st_size <= 256 * 1024 * 1024
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["sha256"], (name, "archive hash")
        with zipfile.ZipFile(archive) as data:
            assert len(data.namelist()) == len(set(data.namelist())), (name, "duplicate ZIP entry")
            assert sum(entry.file_size for entry in data.infolist()) <= 256 * 1024 * 1024
            actual = {entry: hashlib.sha256(data.read(entry)).hexdigest() for entry in data.namelist()}
        assert actual == manifest["files"], (name, "snapshot files")
        assert path.stem.removeprefix("pdsync-v2-commit-") == commit["id"]
        commits[commit["id"]] = commit
        objects_checked += 1
    for commit in commits.values():
        assert set(commit["parents"]) <= commits.keys(), (name, "missing parent")
    complete = set()
    while True:
        ready = {identity for identity, commit in commits.items() if identity not in complete and set(commit["parents"]) <= complete}
        if not ready:
            break
        complete.update(ready)
    assert complete == commits.keys(), (name, "incomplete or cyclic ancestry")
    backups_checked = 0
    for manifest_path in (case / "workspace").rglob("backups/*.json"):
        manifest = json.loads(manifest_path.read_text())
        archive = manifest_path.with_suffix(".zip")
        assert archive.stat().st_size <= 256 * 1024 * 1024
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["sha256"], (name, "backup archive hash")
        with zipfile.ZipFile(archive) as data:
            assert len(data.namelist()) == len(set(data.namelist()))
            assert sum(entry.file_size for entry in data.infolist()) <= 256 * 1024 * 1024
            actual = {entry: hashlib.sha256(data.read(entry)).hexdigest() for entry in data.namelist()}
        assert actual == manifest["files"], (name, "backup files")
        backups_checked += 1
    baselines = [path.read_text() for path in (case / "workspace").rglob("baseline.txt")]
    assert all(baseline in commits for baseline in baselines), (name, "unknown baseline")
    root = case / "data/.shatteredpixel/shattered-pixel-dungeon"
    local_files = {}
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if re.fullmatch(r"game[0-9]+/.+", relative) or relative in {"rankings.dat", "badges.dat", "journal.dat"}:
            local_files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    assert all(commits[baseline]["snapshot"]["files"] == local_files for baseline in baselines), (name, "final local files differ from baseline")
    results.append({"case": name, "verified_objects": objects_checked, "verified_backups": backups_checked, "verified_baselines": baselines, "status": "pass"})
print(json.dumps(results, indent=2))

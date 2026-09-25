#!/usr/bin/env python3
"""Pull and validate a private NDJSON export from the debug Android app."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PACKAGE = "com.techlion.healthconnectexporter"
REMOTE_DIR = "files/health_export"
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / "exports"


def adb(*args: str, binary: bool = False, check: bool = True) -> bytes | str:
    adb_path = shutil.which("adb")
    if not adb_path:
        raise RuntimeError("adb is not installed or not on PATH")
    env = os.environ.copy()
    env.setdefault("ANDROID_USER_HOME", str(PROJECT_ROOT.parent / ".android"))
    result = subprocess.run(
        [adb_path, *args],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def ensure_device() -> dict[str, str]:
    lines = [line.strip() for line in str(adb("devices", "-l")).splitlines()[1:] if line.strip()]
    ready = [line for line in lines if " device " in f" {line} "]
    if len(ready) != 1:
        raise RuntimeError(f"Expected one authorized Android device, found: {lines or 'none'}")
    fields = ready[0].split()
    details = {"serial": fields[0]}
    for item in fields[2:]:
        if ":" in item:
            key, value = item.split(":", 1)
            details[key] = value
    return details


def read_status() -> dict[str, Any] | None:
    raw = str(adb("exec-out", "run-as", PACKAGE, "cat", f"{REMOTE_DIR}/status.json", check=False))
    if not raw.strip():
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"state": "invalid", "raw": raw.strip()}


def wait_for_export(timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        last = read_status()
        if last and last.get("state") == "complete":
            return last
        time.sleep(2)
    raise TimeoutError(f"Export did not complete within {timeout_seconds}s. Last status: {last}")


def safe_remote_files() -> list[str]:
    listing = str(adb("shell", "run-as", PACKAGE, "ls", "-1", REMOTE_DIR))
    names = []
    for name in listing.splitlines():
        name = name.strip()
        if re.fullmatch(r"[a-z0-9_]+\.(?:json|ndjson)", name):
            names.append(name)
    if not names:
        raise RuntimeError("No export files found in the app's private storage")
    return sorted(names)


def package_version(package: str) -> dict[str, str]:
    dump = str(adb("shell", "dumpsys", "package", package, check=False))
    result = {"package": package}
    for key, pattern in {
        "versionName": r"\bversionName=([^\s]+)",
        "versionCode": r"\bversionCode=(\d+)",
        "firstInstallTime": r"\bfirstInstallTime=(.+)",
        "lastUpdateTime": r"\blastUpdateTime=(.+)",
    }.items():
        match = re.search(pattern, dump)
        if match:
            result[key] = match.group(1).strip()
    return result


def parse_time(record: dict[str, Any]) -> str | None:
    return record.get("time") or record.get("startTime")


def analyze(export_dir: Path, device: dict[str, str]) -> dict[str, Any]:
    type_counts: Counter[str] = Counter()
    origin_counts: Counter[str] = Counter()
    stage_counts: Counter[str] = Counter()
    first_time: str | None = None
    last_time: str | None = None
    heart_rate_samples = 0
    malformed_lines: list[dict[str, Any]] = []

    for path in sorted(export_dir.glob("*.ndjson")):
        with path.open("r", encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as error:
                    malformed_lines.append({"file": path.name, "line": line_number, "error": str(error)})
                    continue
                type_counts[record.get("recordType", "unknown")] += 1
                origin = record.get("metadata", {}).get("dataOriginPackage", "unknown")
                origin_counts[origin] += 1
                timestamp = parse_time(record)
                if timestamp:
                    first_time = timestamp if first_time is None else min(first_time, timestamp)
                    last_time = timestamp if last_time is None else max(last_time, timestamp)
                if record.get("recordType") == "HeartRateRecord":
                    heart_rate_samples += len(record.get("samples", []))
                if record.get("recordType") == "SleepSessionRecord":
                    for stage in record.get("stages", []):
                        stage_counts[stage.get("stageName", "UNKNOWN")] += 1

    hashes = {}
    for path in sorted(export_dir.iterdir()):
        if path.is_file() and path.name != "checksums.sha256":
            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    checksum_file = export_dir / "checksums.sha256"
    checksum_file.write_text(
        "".join(f"{digest}  {name}\n" for name, digest in hashes.items()),
        encoding="utf-8",
    )

    summary = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "device": device,
        "software": {
            "exporter": package_version(PACKAGE),
        },
        "recordCounts": dict(sorted(type_counts.items())),
        "dataOriginCounts": dict(sorted(origin_counts.items())),
        "firstRecordTime": first_time,
        "lastRecordTime": last_time,
        "heartRateSampleCount": heart_rate_samples,
        "sleepStageCounts": dict(sorted(stage_counts.items())),
        "hasDetailedSleepStages": any(
            stage_counts[name] > 0 for name in ("AWAKE", "LIGHT", "DEEP", "REM", "AWAKE_IN_BED")
        ),
        "malformedLines": malformed_lines,
        "sha256": hashes,
    }
    (export_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return summary


def pull(output_root: Path, timeout_seconds: int) -> Path:
    device = ensure_device()
    wait_for_export(timeout_seconds)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output = output_root / stamp
    output.mkdir(parents=True, exist_ok=False)
    for name in safe_remote_files():
        payload = adb("exec-out", "run-as", PACKAGE, "cat", f"{REMOTE_DIR}/{name}", binary=True)
        assert isinstance(payload, bytes)
        (output / name).write_bytes(payload)
    summary = analyze(output, device)
    print(json.dumps({"output": str(output), "summary": summary}, ensure_ascii=False, indent=2))
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("status")
    pull_parser = subparsers.add_parser("pull")
    pull_parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    pull_parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()

    try:
        ensure_device()
        if args.command == "status":
            print(json.dumps(read_status(), ensure_ascii=False, indent=2))
        elif args.command == "pull":
            pull(args.output_root, args.timeout)
        return 0
    except (RuntimeError, TimeoutError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run only the synthetic ranking reproductions against the frozen Git commit."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET


HERE = Path(__file__).resolve().parent
JAVA_EXPECTED = {
    "RankingProtocolReproTest": 6,
    "RankingQueryReproTest": 1,
    "RankingAssetReproTest": 2,
    "RankingReadReproTest": 4,
    "QueryEngineUnitTest": 2,
}
HISTORICAL_METHODS = {
    "recoveredProjectionMustNotSendArchiveOnlyBlocksToExtractor",
    "sameCheckpointUuidWithChangedLegacyToolResultMustRetainVersions",
    "ordinarySavedCheckpointToolResultMustBeSearchableForExtraction",
    "imageOnlyUserRequirementMustNotPublishWithoutAnyVisualExtraction",
    "copiedManagedFileMustNotAlsoBeReportedAsAnExternalGap",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def invoke(argv, cwd, env, log, timeout):
    start = time.monotonic()
    with log.open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(argv, cwd=cwd, env=env, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout)
            code = result.returncode
        except subprocess.TimeoutExpired:
            output.write("\nRUNNER_TIMEOUT\n")
            code = 124
    return {"command": argv, "cwd": str(cwd), "returncode": code,
            "seconds": round(time.monotonic() - start, 2), "log": str(log)}


def check_java(report_dir, expected, historical=False):
    cases = {}
    for path in sorted(report_dir.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        short = suite.attrib.get("name", "").rsplit(".", 1)[-1]
        cases.setdefault(short, []).extend(suite.findall("testcase"))
    notes = []
    if set(cases) != set(expected):
        notes.append("Unexpected/missing suites: " + repr(sorted(cases)))
    for name, count in expected.items():
        found = cases.get(name, [])
        if len(found) != count:
            notes.append(f"{name}: expected {count} testcases, found {len(found)}")
        for case in found:
            failure, error, skipped = case.find("failure"), case.find("error"), case.find("skipped")
            if historical:
                kind = "" if failure is None else failure.attrib.get("type", "")
                if error is not None or skipped is not None or not (
                        failure is not None and (kind.endswith("AssertionFailedError")
                                                 or kind.endswith("AssertionError"))):
                    notes.append(f"{name}#{case.attrib.get('name')}: not an assertion-only failure")
            elif failure is not None or error is not None or skipped is not None:
                notes.append(f"{name}#{case.attrib.get('name')}: did not pass")
    if historical and {c.attrib.get("name") for c in cases.get("MergeReviewReproTest", [])} != HISTORICAL_METHODS:
        notes.append("Historical failing method names do not match the five fixed reproductions")
    return notes


def check_frontend(path, count, historical=False):
    if not path.is_file():
        return ["Vitest JSON result missing (compilation/environment failure is not a reproduction)"]
    result = json.loads(path.read_text(encoding="utf-8"))
    assertions = [a for suite in result.get("testResults", []) for a in suite.get("assertionResults", [])]
    notes = []
    if len(assertions) != count or result.get("numTotalTests") != count:
        notes.append(f"Expected {count} frontend testcases, found {len(assertions)}")
    if result.get("numRuntimeErrorTestSuites", 0) or result.get("numPendingTests", 0):
        notes.append("Frontend runtime-error suite or skipped test found")
    if any(suite.get("message") for suite in result.get("testResults", [])):
        notes.append("Frontend file-level failure found outside testcase assertions")
    for item in assertions:
        if historical:
            failures = "\n".join(item.get("failureMessages", []))
            if item.get("status") != "failed" or "AssertionError" not in failures:
                notes.append("Historical frontend test did not fail with AssertionError")
        elif item.get("status") != "passed":
            notes.append("Frontend observation did not pass: " + item.get("fullName", "unknown"))
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, help="Repository root; default: Git root containing this script")
    parser.add_argument("--java-home", type=Path, help="Java 21 JDK; defaults to JAVA_HOME or java on PATH")
    parser.add_argument("--node-modules", type=Path, help="Existing frontend node_modules; no installation is performed")
    parser.add_argument("--maven-repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--output-parent", type=Path, help="Parent for a new, preserved temporary run directory")
    parser.add_argument("--prepare-only", action="store_true", help="Archive, verify and stage fixtures without running tests")
    parser.add_argument("--timeout", type=int, default=900, help="Timeout in seconds per test batch")
    args = parser.parse_args()
    repo = (args.repo or Path(subprocess.check_output(
        ["git", "-C", str(HERE), "rev-parse", "--show-toplevel"], text=True).strip())).resolve()
    manifest = json.loads((HERE / "manifest.json").read_text(encoding="utf-8"))
    commit = manifest["head"]
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("manifest.head must be a full Git commit ID")
    run = Path(tempfile.mkdtemp(prefix="astra-merge-repro-", dir=args.output_parent)).resolve()
    work, logs = run / "workspace", run / "logs"
    work.mkdir(); logs.mkdir()
    summary = {"frozen_head": commit, "run_directory": str(run), "batches": [], "status": "preparing"}
    summary_path = run / "results.json"
    print(f"Run directory: {run}", flush=True)
    try:
        archive = run / "frozen-source.tar"
        subprocess.run(["git", "-C", str(repo), "archive", "--format=tar", "-o", str(archive),
                        commit, "backend", "frontend"], check=True)
        with tarfile.open(archive) as source:
            members = source.getmembers()
            for member in members:
                dest = (work / member.name).resolve()
                if work not in dest.parents or member.issym() or member.islnk():
                    raise ValueError("Unsafe/link archive entry: " + member.name)
            source.extractall(work, members=members)
        verified = []
        for entry in manifest["files"]:
            if entry["path"].startswith(("backend/", "frontend/")):
                if digest(work / entry["path"]) != entry["sha256"]:
                    raise ValueError("Frozen source hash mismatch: " + entry["path"])
                verified.append(entry["path"])
        summary["verified_manifest_files"] = verified
        staged = []
        for fixture in sorted((HERE / "reproductions").iterdir()):
            if fixture.suffix == ".java":
                package = re.search(r"^package ([\w.]+);", fixture.read_text(), re.MULTILINE)
                if not package:
                    raise ValueError("Java fixture missing package: " + fixture.name)
                dest = work / "backend/src/test/java" / package.group(1).replace(".", "/") / fixture.name
            elif fixture.name.endswith(".test.ts"):
                dest = work / "frontend/src/store" / fixture.name
            else:
                continue
            if dest.exists():
                raise ValueError("Fixture would overwrite a frozen file: " + str(dest))
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(fixture, dest)
            staged.append({"name": fixture.name, "sha256": digest(fixture), "destination": str(dest.relative_to(work))})
        summary["fixtures"] = staged
        if args.prepare_only:
            summary["status"] = "prepared_only"
            return 0

        jdk = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
        java = str(jdk / "bin/java") if jdk else shutil.which("java")
        version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
        if not re.search(r'version "21(?:[.\"]|$)', version):
            raise ValueError("Java 21 is required; use --java-home. Found: " + version.splitlines()[0])
        summary["java_version"] = version.strip()
        node = shutil.which("node")
        if not node:
            raise ValueError("Node.js must be on PATH")
        modules = args.node_modules.resolve() if args.node_modules else None
        if modules is None or not (modules / "vitest/vitest.mjs").is_file():
            raise ValueError("Pass --node-modules pointing to installed frontend dependencies; no automatic npm install")
        # Individual dependency links leave local cache directories isolated in the temporary tree.
        target_modules = work / "frontend/node_modules"
        target_modules.mkdir(exist_ok=True)
        for dependency in modules.iterdir():
            if dependency.name not in {".vite", ".vite-temp", ".cache"}:
                (target_modules / dependency.name).symlink_to(dependency, target_is_directory=dependency.is_dir())
        env = {"PATH": os.environ.get("PATH", ""), "LANG": "en_US.UTF-8", "CI": "true", "NO_COLOR": "1"}
        if jdk:
            env["JAVA_HOME"] = str(jdk.resolve())
            env["PATH"] = str(jdk.resolve() / "bin") + os.pathsep + env["PATH"]
        isolated_home, temp = run / "test-user", run / "tmp"
        isolated_home.mkdir(); temp.mkdir()
        env.update({"TMPDIR": str(temp), "LOG_DIR": str(logs), "MAVEN_USER_HOME": str(Path.home() / ".m2")})
        # No inherited API keys, application settings, Java agent options or provider environment.
        settings = run / "empty-maven-settings.xml"
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n')
        base = [str(work / "backend/mvnw"), "-o", "-B", "-q", "-s", str(settings),
                "-Dmaven.repo.local=" + str(args.maven_repository.resolve()),
                '-DargLine=-Duser.home="' + str(isolated_home) + '" -Djava.io.tmpdir="' + str(temp) + '"',
                "-DfailIfNoTests=true"]
        groups = [
            ("java-historical-expected-failures", "MergeReviewReproTest", {"MergeReviewReproTest": 5}, True),
            ("java-observations-and-isolation", ",".join([
                "RankingProtocolReproTest", "RankingQueryReproTest", "RankingAssetReproTest", "RankingReadReproTest",
                "QueryEngineUnitTest#handoffProjectionOnlyRunsForExplicitlyMergedSessions"]), JAVA_EXPECTED, False),
        ]
        for label, selection, expected, historical in groups:
            print("Running " + label, flush=True)
            reports = run / label
            batch = invoke(base + ["-Dtest=" + selection, "-Dsurefire.reportsDirectory=" + str(reports), "test"],
                           work / "backend", env, logs / (label + ".log"), args.timeout)
            notes = check_java(reports, expected, historical)
            if (historical and batch["returncode"] != 1) or (not historical and batch["returncode"] != 0):
                notes.append("Unexpected process exit code")
            batch.update({"name": label, "expectation_met": not notes, "diagnostics": notes})
            summary["batches"].append(batch)
            summary_path.write_text(json.dumps(summary, indent=2) + "\n")
        for name, count, historical in [("sessionMergeReviewRepro.test.ts", 1, True), ("rankingProtocolRepro.test.ts", 5, False)]:
            print("Running " + name, flush=True)
            result = run / (name + ".json")
            batch = invoke([node, str(target_modules / "vitest/vitest.mjs"), "run", "src/store/" + name,
                            "--reporter=default", "--reporter=json", "--outputFile=" + str(result)], work / "frontend", env,
                           logs / (name + ".log"), args.timeout)
            notes = check_frontend(result, count, historical)
            log_text = Path(batch["log"]).read_text(encoding="utf-8", errors="replace")
            if re.search(r"Unhandled Errors|Unhandled Rejection|Uncaught Exception|RUNNER_TIMEOUT", log_text):
                notes.append("Frontend unhandled/runtime error found in the complete log")
            if (historical and batch["returncode"] != 1) or (not historical and batch["returncode"] != 0):
                notes.append("Unexpected process exit code")
            batch.update({"name": name, "expectation_met": not notes, "diagnostics": notes})
            summary["batches"].append(batch)
            summary_path.write_text(json.dumps(summary, indent=2) + "\n")
        summary["status"] = "expectations_met" if all(b["expectation_met"] for b in summary["batches"]) else "unexpected_result"
        print(summary["status"] + ": " + str(summary_path), flush=True)
        return 0 if summary["status"] == "expectations_met" else 1
    except Exception as exc:
        summary["status"] = "runner_error"
        summary["error"] = str(exc)
        print("Runner error: " + str(exc), file=sys.stderr)
        return 2
    finally:
        summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())

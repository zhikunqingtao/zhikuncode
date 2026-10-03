"""Runner boundary tests: temporary repositories, fake Docker, no Office runtime."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import pytest


TOOLS = Path(__file__).resolve().parents[1]
IMAGE_ID = "sha256:" + "a" * 64


@pytest.fixture
def contract():
    spec = importlib.util.spec_from_file_location(
        "r12_toolchain_contract", TOOLS / "scripts/check_toolchain.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def manifests(tmp_path):
    roots = [tmp_path / name for name in ("repo", "image")]
    for root in roots:
        root.mkdir()
        for name in ("pins.env", "requirements.lock"):
            shutil.copyfile(TOOLS / name, root / name)
    return roots


def test_contract_accepts_semantic_reordering_and_quotes(contract, manifests):
    repo, image = manifests
    pins = contract.parse_file(image / "pins.env", "pins")
    (image / "pins.env").write_text("# reformatted\n\n" + "\n".join(
        '%s="%s" # comment with an unmatched "' % item
        for item in reversed(list(pins.items()))))
    rows = (image / "requirements.lock").read_text().splitlines()
    (image / "requirements.lock").write_text("\n".join(
        line + "  # retained pin" if line.strip() and not line.lstrip().startswith("#") else line
        for line in reversed(rows)))
    assert contract.parse_file(image / "pins.env", "pins") == pins
    report = contract.validate_contract(repo, image)
    assert report["repository_contract_sha256"] == report["image_contract_sha256"]


@pytest.mark.parametrize("value_form", [
    "{value}#different", '"{value}"#different',
    '"{value}#different"', r"{value}\#different",
])
def test_pin_hash_is_part_of_value_and_drift_is_rejected(contract, manifests, value_form):
    repo, image = manifests
    path = repo / "pins.env"
    original = contract.parse_file(path, "pins")["LIBREOFFICE_VERSION"]
    path.write_text("\n".join(
        "LIBREOFFICE_VERSION=" + value_form.format(value=original)
        if line.startswith("LIBREOFFICE_VERSION=") else line
        for line in path.read_text().splitlines()))
    actual = subprocess.check_output(
        ["bash", "-c", '. "$1"; printf "%s" "$LIBREOFFICE_VERSION"', "pins", str(path)],
        text=True)
    assert actual == original + "#different"
    assert contract.parse_file(path, "pins")["LIBREOFFICE_VERSION"] == actual
    with pytest.raises(ValueError, match="differ from image"):
        contract.validate_contract(repo, image)


@pytest.mark.parametrize("kind,content", [
    ("pins", "A_VERSION=1\nA_VERSION=1\n"),
    ("pins", "A_VERSION='unterminated\n"),
    ("pins", "A_VERSION=\n"),
    ("pins", "A_VERSION= # comment is not a value\n"),
    ("pins", "A_VERSION=1 extra\n"),
    ("pins", "not an assignment\n"),
    ("requirements", "pytest==1\npytest==1\n"),
    ("requirements", "pytest-timeout==1\npytest_timeout==1\n"),
    ("requirements", "pytest>=1\n"),
    ("requirements", "pytest==\n"),
    ("pins", "# only comments\n\n"),
    ("requirements", "\n# only comments\n"),
])
def test_contract_rejects_ambiguous_or_empty_input(contract, tmp_path, kind, content):
    path = tmp_path / "input"
    path.write_text(content)
    with pytest.raises(ValueError):
        contract.parse_file(path, kind)


@pytest.mark.parametrize("mutation", ["image_drift", "image_consistent_drift", "both_inconsistent", "missing"])
def test_contract_requires_matching_files_and_pin_lock_agreement(contract, manifests, mutation):
    repo, image = manifests
    if mutation == "missing":
        (image / "requirements.lock").unlink()
    else:
        for root in ([repo, image] if mutation == "both_inconsistent" else [image]):
            path = root / "requirements.lock"
            path.write_text("\n".join("pytest-timeout==99.0" if line.startswith("pytest-timeout==")
                                       else line for line in path.read_text().splitlines()))
        if mutation == "image_consistent_drift":
            path = image / "pins.env"
            path.write_text("\n".join("PYTEST_TIMEOUT_VERSION=99.0" if line.startswith("PYTEST_TIMEOUT_VERSION=")
                                       else line for line in path.read_text().splitlines()))
    with pytest.raises((ValueError, OSError)):
        contract.validate_contract(repo, image)


@pytest.mark.parametrize("pin,package", [("LIBREOFFICE_VERSION", None), ("PYTEST_VERSION", "pytest")])
def test_contract_rejects_internally_consistent_image_drift(contract, manifests, pin, package):
    repo, image = manifests
    path = image / "pins.env"
    path.write_text("\n".join(pin + "=99.0" if line.startswith(pin + "=") else line
                               for line in path.read_text().splitlines()))
    if package:
        path = image / "requirements.lock"
        path.write_text("\n".join(package + "==99.0" if line.startswith(package + "==") else line
                                   for line in path.read_text().splitlines()))
    # Self-consistency must succeed first; only the repository/image comparison rejects it.
    contract.validate_contract(image, image)
    with pytest.raises(ValueError, match="differ from image"):
        contract.validate_contract(repo, image)


@pytest.mark.parametrize("missing", [False, True])
def test_contract_main_writes_matched_or_blocked_report(
        contract, manifests, tmp_path, monkeypatch, missing):
    repo, image = manifests
    out = tmp_path / "output"
    monkeypatch.setenv("R12_TOOLS_DIR", str(repo))
    monkeypatch.setenv("OFFICE_REGRESSION_OUT", str(out))
    validate = contract.validate_contract

    def temporary_image(repo_dir, image_dir):
        assert image_dir == "/opt/r12"
        return validate(repo_dir, image)

    # Remap only the image mount; exercise real parsing, comparison and report writing.
    monkeypatch.setattr(contract, "validate_contract", temporary_image)
    if missing:
        (image / "requirements.lock").unlink()
    assert contract.main() == int(missing)
    report = json.loads((out / "toolchain-contract.json").read_text())
    assert report["status"] == ("blocked" if missing else "matched")


@pytest.fixture
def launcher(tmp_path):
    preexisting_defaults = {path.resolve() for path in Path("/tmp").glob("office-regression-*")}
    repo = tmp_path / "checkout"
    tools = repo / "tools/office-regression"
    tools.mkdir(parents=True)
    (repo / "backend").mkdir()
    (repo / "backend/pom.xml").touch()
    for name in ("run.sh", "pins.env", "Dockerfile"):
        shutil.copyfile(TOOLS / name, tools / name)
    binary = tmp_path / "bin"
    binary.mkdir()
    calls = tmp_path / "docker-calls.jsonl"
    stub = binary / "docker"
    stub.write_text("#!" + sys.executable + "\n" + '''
import json, os, pathlib, sys, time
args = sys.argv[1:]
with open(os.environ["STUB_CALLS"], "a") as output:
    output.write(json.dumps(args) + "\\n")
if args[:2] == ["image", "inspect"]:
    print(os.environ["STUB_IMAGE_ID"] if "{{.Id}}" in args else "linux/arm64")
if args and args[0] == "run":
    hold = os.environ.get("STUB_HOLD")
    deadline = time.monotonic() + 15
    while hold and pathlib.Path(hold).exists() and time.monotonic() < deadline:
        time.sleep(.02)
    print("stub container output", flush=True)
    sys.exit(int(os.environ.get("STUB_EXIT", "0")))
''')
    stub.chmod(0o755)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("R12_") and key != "OFFICE_REGRESSION_OUT"}
    env.update(PATH=str(binary) + os.pathsep + os.environ["PATH"],
               STUB_CALLS=str(calls), STUB_IMAGE_ID=IMAGE_ID, R12_SKIP_BUILD="1")

    def start(**overrides):
        return subprocess.Popen(["bash", str(tools / "run.sh")], env={**env, **overrides},
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    def runs():
        return [row for row in (json.loads(line) for line in calls.read_text().splitlines())
                if row and row[0] == "run"] if calls.exists() else []

    yield start, runs, repo
    # Only default directories created by these invocations; explicit ones live in tmp_path.
    for out in {output_dir(args) for args in runs()}:
        if (out.parent.resolve() == Path("/tmp").resolve()
                and out.name.startswith("office-regression-") and out.resolve() not in preexisting_defaults):
            shutil.rmtree(out)


def output_dir(args):
    return Path(next(value[:-5] for value in args if value.endswith(":/out")))


def finish(process):
    output, _ = process.communicate(timeout=20)
    return process.returncode, output


def await_runs(runs, count):
    deadline = time.monotonic() + 10
    while len(runs()) < count and time.monotonic() < deadline:
        time.sleep(.02)
    assert len(runs()) == count, "launcher never reached the expected container invocation"


def test_default_outputs_are_unique_for_sequential_and_concurrent_runs(launcher, tmp_path):
    start, runs, _ = launcher
    for _ in range(2):
        assert finish(start())[0] == 0
    hold = tmp_path / "hold"
    hold.touch()
    processes = [start(STUB_HOLD=str(hold)) for _ in range(2)]
    try:
        await_runs(runs, 4)
    finally:
        hold.unlink()
        results = [finish(process) for process in processes]
    assert all(code == 0 for code, _ in results)
    outputs = [output_dir(args) for args in runs()]
    assert len(set(outputs)) == 4
    assert all(path.parent.resolve() == Path("/tmp").resolve() for path in outputs)


@pytest.mark.parametrize("entry", ["payload.txt", ".hidden", None])
def test_explicit_nonempty_or_file_output_is_preserved(launcher, tmp_path, entry):
    start, runs, _ = launcher
    out = tmp_path / "evidence"
    if entry:
        out.mkdir()
        sentinel = out / entry
    else:
        sentinel = out
    sentinel.write_text("retain me")
    code, _ = finish(start(OFFICE_REGRESSION_OUT=str(out)))
    assert code != 0 and not runs()
    assert sentinel.read_text() == "retain me"


@pytest.mark.parametrize("symlink", [False, True])
def test_output_inside_repository_is_rejected_before_log_creation(launcher, tmp_path, symlink):
    start, runs, repo = launcher
    target = repo / "blocked-evidence"
    out = target
    if symlink:
        target.mkdir()
        out = tmp_path / "external-looking-output"
        out.symlink_to(target, target_is_directory=True)
    code, output = finish(start(OFFICE_REGRESSION_OUT=str(out)))
    assert code != 0 and not runs()
    assert "outside the repository" in output
    if symlink:
        assert out.is_symlink() and list(target.iterdir()) == []
    else:
        assert not target.exists()


def test_explicit_output_rejects_concurrent_owner(launcher, tmp_path):
    start, runs, _ = launcher
    out, hold = tmp_path / "evidence", tmp_path / "hold"
    hold.touch()
    first = start(OFFICE_REGRESSION_OUT=str(out), STUB_HOLD=str(hold))
    try:
        await_runs(runs, 1)
        code, _ = finish(start(OFFICE_REGRESSION_OUT=str(out)))
        assert code != 0 and len(runs()) == 1
    finally:
        hold.unlink()
        assert finish(first)[0] == 0


@pytest.mark.parametrize("exit_code,result", [(7, "failed"), (130, "interrupted")])
def test_exact_image_offline_gate_and_failure_evidence(launcher, tmp_path, exit_code, result):
    start, runs, repo = launcher
    out = tmp_path / "empty-output"
    out.mkdir()
    code, output = finish(start(OFFICE_REGRESSION_OUT=str(out), STUB_EXIT=str(exit_code),
                                R12_IMAGE_TAG="mutable:tag", R12_PYTEST_ARGS="-k docx"))
    assert code == exit_code, output
    args, = runs()
    assert args[args.index("--network") + 1] == "none"
    assert str(repo) + ":/work:ro" in args and IMAGE_ID in args
    assert "mutable:tag" not in args
    command = args[args.index("-c") + 1]
    assert command.index("check_toolchain.py") < command.index("pytest")
    assert '&&' in command and '"$@"' in command
    assert args[-2:] == ["-k", "docx"] or args[-3:] == ["-k", "docx", "tests"]
    assert "stub container output" in (out / "pytest.log").read_text()
    identity = (out / "image-identity.txt").read_text()
    assert "exit_code: %s" % exit_code in identity and IMAGE_ID in identity
    assert "result: " + result in identity

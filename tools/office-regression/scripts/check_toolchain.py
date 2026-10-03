"""Compare the repository's toolchain contract with the running image (stdlib only)."""

import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import sys


def parse_file(path, kind):
    values = {}
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.lstrip()
        if not line or line.startswith("#"):
            continue
        if kind == "pins":
            match = re.match(r"([A-Z][A-Z0-9_]*)=", line)
            if not match:
                raise ValueError("%s:%s: invalid pin" % (path, number))
            # A # inside the assignment is literal; only a following word can
            # start a shell comment. Do not parse quotes inside that comment.
            lexer = shlex.shlex(line, posix=True)
            lexer.whitespace_split = True
            lexer.commenters = ""
            value = lexer.get_token()[len(match[0]):]
            trailing = lexer.instream.read().lstrip()
            if not value or (trailing and not trailing.startswith("#")):
                raise ValueError("%s:%s: invalid pin" % (path, number))
            key = match[1]
        elif kind == "requirements":
            line = re.split(r"\s+#", line, maxsplit=1)[0].rstrip()
            match = re.fullmatch(r"([A-Za-z0-9][A-Za-z0-9._-]*)==([A-Za-z0-9][A-Za-z0-9.!+_-]*)", line)
            if not match:
                raise ValueError("%s:%s: expected name==version" % (path, number))
            key, value = re.sub(r"[-_.]+", "-", match[1]).lower(), match[2]
        else:
            raise ValueError("Unknown contract kind: " + kind)
        if key in values:
            raise ValueError("%s:%s: duplicate %s" % (path, number, key))
        values[key] = value
    if not values:
        raise ValueError("Empty contract: " + str(path))
    return values


def validate_contract(repo_dir, image_dir):
    report = {}
    contracts = []
    for label, directory in (("repository", Path(repo_dir)), ("image", Path(image_dir))):
        contract = {"pins": parse_file(directory / "pins.env", "pins"),
                    "requirements": parse_file(directory / "requirements.lock", "requirements")}
        for name, version in contract["requirements"].items():
            pin = name.replace("-", "_").upper() + "_VERSION"
            if contract["pins"].get(pin) != version:
                raise ValueError("%s: requirements.lock disagrees with pin %s" % (label, pin))
        contracts.append(contract)
        encoded = json.dumps(contract, sort_keys=True, separators=(",", ":")).encode()
        report[label + "_contract_sha256"] = hashlib.sha256(encoded).hexdigest()
    if contracts[0] != contracts[1]:
        raise ValueError("Repository pins/requirements differ from image; rebuild the regression image")
    report["declared_base_image"] = contracts[0]["pins"].get("BASE_IMAGE")
    report["declared_base_digest"] = contracts[0]["pins"].get("BASE_IMAGE_DIGEST")
    return report


def main():
    output = Path(os.environ.get("OFFICE_REGRESSION_OUT", "/out"))
    try:
        report = validate_contract(os.environ.get("R12_TOOLS_DIR", "/work/tools/office-regression"),
                                   "/opt/r12")
        report["status"] = "matched"
    except (OSError, ValueError) as exc:
        report = {"status": "blocked", "error": str(exc)}
    output.mkdir(parents=True, exist_ok=True)
    (output / "toolchain-contract.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if report["status"] != "matched":
        print("R12 ERROR: BLOCKED: " + report["error"], file=sys.stderr)
        return 1
    print("R12 toolchain contract matched: " + report["repository_contract_sha256"])
    return 0


if __name__ == "__main__":
    sys.exit(main())

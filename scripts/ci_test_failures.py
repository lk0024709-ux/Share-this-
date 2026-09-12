#!/usr/bin/env python3
"""Build a PR-comment summary of CI unit-test failures.

Usage: ci_test_failures.py <test-results-dir> <gradle-log-path>

Extracts every JUnit <failure>/<error> from the Gradle test-results XML and
compile/build errors from the captured Gradle log, emitting Markdown. Used by
build-apk.yml so failures are readable directly on the PR — no artifact or
runner-log download needed.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LOG_ERROR_PATTERNS = (
    "e: ",
    "error: ",
    "FAILED",
    "What went wrong",
    "Execution failed",
    "Caused by:",
)


def junit_failures(results_dir: Path) -> list[str]:
    lines: list[str] = []
    for xml_file in sorted(results_dir.rglob("*.xml")):
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError:
            continue
        for testcase in root.iter("testcase"):
            for fail in list(testcase.findall("failure")) + list(testcase.findall("error")):
                name = f'{testcase.get("classname", "?")}.{testcase.get("name", "?")}'
                message = (fail.get("message") or "").replace("`", "'")[:400]
                lines.append(f"**FAIL** `{name}`")
                if message:
                    lines.append(f"> {message}")
                text = (fail.text or "").strip().splitlines()
                for line in text[:10]:
                    lines.append(f"> {line[:240]}")
                lines.append("")
    return lines


def log_errors(log_path: Path) -> list[str]:
    if not log_path.exists():
        return []
    lines: list[str] = []
    for line in log_path.read_text(errors="replace").splitlines():
        if any(p in line for p in LOG_ERROR_PATTERNS):
            lines.append(line[:300])
            if len(lines) >= 60:
                break
    return lines


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    results_dir, log_path = Path(sys.argv[1]), Path(sys.argv[2])

    failures = junit_failures(results_dir)
    errors = log_errors(log_path)

    print("### ❌ CI unit-test failures")
    print()
    if failures:
        print(f"**{len(failures)} failing test(s):**")
        print()
        print("\n".join(failures))
    else:
        print("_No JUnit results found — likely a compile/configuration failure._")
        print()
    if errors:
        print("**Build log errors:**")
        print("```")
        print("\n".join(errors))
        print("```")
    return 0


if __name__ == "__main__":
    sys.exit(main())

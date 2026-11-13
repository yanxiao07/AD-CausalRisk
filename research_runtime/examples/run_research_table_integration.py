"""Exercise Java persistence against the constructed Python host."""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def expect_error(url, body, headers, status, code):
    try:
        with urlopen(Request(url, data=body, headers=headers), timeout=5):
            raise RuntimeError("Host accepted an invalid request")
    except HTTPError as error:
        with error:
            if error.code != status or json.load(error)["code"] != code:
                raise RuntimeError(f"Unexpected host refusal for {code}") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir()
    manifest = output / "manifest.json"
    env = dict(os.environ, RESEARCH_TABLE_TEST_TOKEN=secrets.token_urlsafe(32))
    root = Path(__file__).resolve().parents[2]
    with (output / "python.jsonl").open("w", encoding="utf-8") as calls, (
        output / "python.stderr"
    ).open("w", encoding="utf-8") as errors, subprocess.Popen(
        [sys.executable, "-B", "-m", "research_runtime.examples.research_table_http",
         "--manifest", str(manifest)],
        env=env, cwd=root, stdout=calls, stderr=errors,
    ) as host:
        try:
            for _ in range(120):
                if host.poll() is not None:
                    raise RuntimeError("Python host stopped during startup")
                if manifest.exists():
                    try:
                        ready = json.loads(manifest.read_text(encoding="utf-8"))
                        break
                    except json.JSONDecodeError:
                        pass
                time.sleep(0.25)
            else:
                raise RuntimeError("Python host startup timed out")
            url = f"http://127.0.0.1:{ready['port']}/api/research-table/v1/assessments"
            headers = {"Content-Type": "application/json"}
            expect_error(url, b"{}", headers, 403, "INDEPENDENT_EXECUTION_PERMISSION_REQUIRED")
            headers["Authorization"] = "Bearer " + env["RESEARCH_TABLE_TEST_TOKEN"]
            expect_error(url, b'{"runId":"x","runId":"y"}', headers, 400, "REQUEST_JSON_INVALID")
            expect_error(url, b"{}", dict(headers, **{"Content-Encoding": "gzip"}), 400, "REQUEST_JSON_INVALID")
            wrong = dict(ready["cases"]["seven"]["request"], subjectCode="CONSTRUCTED-WRONG")
            expect_error(url, json.dumps(wrong).encode(), headers, 403, "SUBJECT_NOT_BOUND")

            mvn = "mvnw.cmd" if os.name == "nt" else "./mvnw"
            command = [str(root / "backend" / mvn), "-B", "-ntp",
                       "-Dtest=ResearchTableAssessmentTest#callsIndependentPythonResearchHost",
                       f"-Dresearch.table.it.manifest={manifest}", "test"]
            with (output / "java.log").open("wb") as log:
                completed = subprocess.run(command, env=env, cwd=root / "backend",
                                           stdout=log, stderr=subprocess.STDOUT, timeout=300)
                if completed.returncode:
                    raise RuntimeError(f"Java integration failed. See {output / 'java.log'}")
        finally:
            host.terminate()
            host.wait(timeout=10)
    rows = [json.loads(line) for line in (output / "python.jsonl").read_text(encoding="utf-8").splitlines()]
    expected = {
        "run-constructed-http-seven": ("COMPLETED", 1, 4),
        "run-constructed-http-ten": ("COMPLETED", 1, 8),
        "run-constructed-http-blocked": ("BLOCKED", 0, 0),
    }
    if len(rows) != len(expected) or {
        row["runId"]: (row["status"], row["directCifCalls"], row["explanationCifCalls"]) for row in rows
    } != expected or (output / "python.stderr").stat().st_size:
        raise RuntimeError("Unexpected Python executions or host errors")
    print(json.dumps(rows, ensure_ascii=False))


if __name__ == "__main__":
    main()

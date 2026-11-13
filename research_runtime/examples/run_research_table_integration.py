"""Start an isolated Java/Python research demo or verify its real HTTP session."""

import argparse
from contextlib import contextmanager
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


TASKS = "/api/research/table-assessment-tasks"
DEMO_PASSWORD = "DemoPass!2026"
EXPECTED = {"seven": ("COMPLETED", 1, 4), "ten": ("COMPLETED", 1, 8), "blocked": ("BLOCKED", 0, 0)}


def require(condition, detail):
    if not condition:
        raise RuntimeError(detail)


@contextmanager
def process(command, *, log, stderr=None, **kwargs):
    with subprocess.Popen(command, stdout=log, stderr=stderr or subprocess.STDOUT, **kwargs) as child:
        try:
            yield child
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=10)


def ready_file(path, child, log):
    for _ in range(240):
        require(child.poll() is None, f"Process stopped before readiness; see {log}")
        if path.exists():
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass
        time.sleep(0.25)
    raise RuntimeError(f"Process did not become ready; see {log}")


def call(opener, url, *, data=None, headers=None, status=200, raw=False):
    request = Request(url, data=data if raw or data is None else json.dumps(data).encode(),
                      headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with opener.open(request, timeout=35) as response:
            actual, body = response.status, json.load(response)
    except HTTPError as response:
        with response:
            actual, body = response.code, json.load(response)
    require(actual == status, f"{request.get_method()} {request.selector}: expected {status}, got {actual}")
    if not raw:
        require((body["code"] == 0 and body["errorCode"] is None) if status == 200
                else (body["code"] != 0 and isinstance(body["errorCode"], str)),
                f"{request.selector}: unexpected Java envelope")
    return body


def verify_host(manifest, token):
    url = f"http://127.0.0.1:{manifest['port']}/api/research-table/v1/assessments"
    client = build_opener(ProxyHandler({}))
    headers = {"Authorization": "Bearer " + token}
    for data, auth, status, code in [
        (b"{}", {}, 403, "INDEPENDENT_EXECUTION_PERMISSION_REQUIRED"),
        (b'{"runId":"x","runId":"y"}', headers, 400, "REQUEST_JSON_INVALID"),
        (b"{}", {**headers, "Content-Encoding": "gzip"}, 400, "REQUEST_JSON_INVALID"),
        (json.dumps(dict(manifest["cases"]["seven"]["request"], subjectCode="CONSTRUCTED-WRONG")).encode(),
         headers, 403, "SUBJECT_NOT_BOUND"),
    ]:
        require(call(client, url, data=data, headers=auth, status=status, raw=True)["code"] == code,
                f"Expected host refusal {code}")


def verify_sessions(root, ready, bindings, trained):
    base = ready["javaBaseUrl"]

    def session(account):
        cookies = CookieJar()
        client = build_opener(ProxyHandler({}), HTTPCookieProcessor(cookies))
        call(client, base + "/api/auth/login", data={"username": account, "password": DEMO_PASSWORD})
        require(any(cookie.name == "JSESSIONID" for cookie in cookies), "No Java session cookie")
        return client

    owner, outsider, patient = (session(account) for account in ("researcher", "admin", "patient"))
    summary_url = base + "/api/patients/me/summary"
    before = call(patient, summary_url)["data"]
    require(before["subjectId"] == ready["subjectId"] and not before["hasAssessment"],
            "Patient must be bound to the same test subject without a course result")
    legacy = dict(subjectId=ready["subjectId"], modelVersion="deepagent-causal-warning-v1",
                  predictionWindowMonths=24)
    for endpoint in ("/api/assessment-tasks", "/api/assessment-tasks/submit"):
        require(call(owner, base + endpoint, data=legacy, headers={"Idempotency-Key": "retired"},
                     status=503)["errorCode"] == "RESEARCH_UNAVAILABLE", "Rule scoring was not retired")
    snapshots_url = base + TASKS + "/snapshots?subjectId=" + str(ready["subjectId"])
    expected_snapshots = [{"snapshotId": ready["snapshots"]["seven"], "taskId": None}] if trained else []
    require(call(owner, snapshots_url)["data"] == expected_snapshots, "Unexpected snapshot list")
    require(call(outsider, snapshots_url)["data"] == [], "Non-owner snapshot list disclosed a snapshot")
    call(patient, snapshots_url, status=403)
    results = {}
    for label in bindings["cases"]:
        status = EXPECTED[label][0]
        snapshot = {"snapshotId": ready["snapshots"][label]}
        key = {"Idempotency-Key": f"demo-{label}"}
        call(outsider, base + TASKS, data=snapshot, headers=key, status=403)
        task = call(owner, base + TASKS, data=snapshot, headers=key)["data"]
        require(task["status"] == status and task["snapshotId"] == snapshot["snapshotId"]
                and task["errorCode"] is None, f"{label}: wrong task state")
        result = task["result"]
        if trained:
            identity = bindings["cases"][label]["model"]
            require(result["model"] == identity and result["dataScope"] == "CONSTRUCTED_VALIDATION"
                    and result["modelKey"] == "trained-seven-logistic"
                    and result["computation"]["directCifCalls"] == 1
                    and result["computation"]["explanationCifCalls"] >= 2,
                    "Expected a bound trained-model prediction and explanation")
            expected_snapshots[0]["taskId"] = task["taskId"]
            require(call(owner, snapshots_url)["data"] == expected_snapshots, "Saved task not listed")
        else:
            fixture = root / "docs/contracts/examples/research-table-agent" / f"response-{label}.json"
            expected = json.loads(fixture.read_text(encoding="utf-8"))
            for field in ("prediction", "explanation", "admission", "computation"):
                require(result[field] == expected[field], f"{label}: unexpected {field}")
        if status == "BLOCKED":
            require(all(result[field] is None for field in ("prediction", "explanation", "binding")),
                    "Blocked result must explicitly preserve nulls")
        detail = f"{base}{TASKS}/{task['taskId']}"
        require(call(owner, detail)["data"] == task, "GET did not return the original task")
        require(call(owner, base + TASKS, data=snapshot, headers=key)["data"] == task,
                "Same key did not return the original task")
        call(owner, base + TASKS, data=snapshot, headers={"Idempotency-Key": f"other-{label}"}, status=409)
        call(outsider, detail, status=403)
        call(patient, detail, status=403)
        results[label] = {"taskId": task["taskId"], "status": status}
    require(call(patient, summary_url)["data"] == before, "Research result changed the patient summary")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--serve", action="store_true", help="Leave snapshots unconsumed and serve until Ctrl+C")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--trained-manifest", type=Path)
    mode.add_argument("--contract-test", action="store_true")
    parser.add_argument("--trusted-manifest-sha256")
    args = parser.parse_args()
    trained = args.trained_manifest is not None
    if trained != bool(args.trusted_manifest_sha256):
        parser.error("The trained model requires its trusted manifest SHA-256.")
    if args.serve and not trained:
        parser.error("--serve requires the trained model.")
    output = args.output.resolve()
    output.mkdir(parents=True)
    manifest = output / "manifest.json"
    handoff = output / "handoff.json"
    root = Path(__file__).resolve().parents[2]
    backend = root / "backend"
    token = secrets.token_urlsafe(32)
    env = dict(os.environ, RESEARCH_TABLE_TEST_TOKEN=token)
    cp_file = backend / "target" / "research-demo-classpath.txt"
    wrapper = backend / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    with (output / "build.log").open("wb") as log:
        completed = subprocess.run(
            [str(wrapper), "-B", "-ntp", "test-compile", "dependency:build-classpath",
             "-Dmdep.outputFile=target/research-demo-classpath.txt", "-Dmdep.includeScope=test"],
            cwd=backend, stdout=log, stderr=subprocess.STDOUT, timeout=300,
        )
    require(completed.returncode == 0, f"Java build failed; see {output / 'build.log'}")
    classpath = os.pathsep.join(["target/test-classes", "target/classes", cp_file.read_text(encoding="utf-8").strip()])

    with (output / "python.jsonl").open("w", encoding="utf-8") as calls, (
        output / "python.stderr"
    ).open("w", encoding="utf-8") as errors, (output / "java.log").open("w", encoding="utf-8") as java_log:
        mode_args = ["--trained-manifest", str(args.trained_manifest.resolve()),
                     "--trusted-manifest-sha256", args.trusted_manifest_sha256] if trained else ["--contract-test"]
        with process([sys.executable, "-B", "-m", "research_runtime.examples.research_table_http",
                      "--manifest", str(manifest), *mode_args], env=env, cwd=root, log=calls, stderr=errors) as host:
            bindings = ready_file(manifest, host, errors.name)
            if not args.serve:
                verify_host(bindings, token)
            # Do not let an inherited Spring setting select another profile or datasource.
            java_env = {key: value for key, value in env.items() if not key.upper().startswith(
                ("SPRING_", "SERVER_", "ADCR_", "RESEARCH_TABLE_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
            )}
            java_env["RESEARCH_TABLE_TEST_TOKEN"] = token
            java = ["java", "-cp", classpath, "com.adcausalrisk.backend.research.ResearchTableDemo",
                    str(manifest), str(handoff)]
            with process(java, env=java_env, cwd=backend, log=java_log) as server:
                ready = ready_file(handoff, server, java_log.name)
                if args.serve:
                    print(json.dumps(ready, ensure_ascii=False, indent=2), flush=True)
                    print("Demo credentials: researcher / " + DEMO_PASSWORD + "; stop with Ctrl+C.", flush=True)
                    try:
                        while server.poll() is None and host.poll() is None:
                            time.sleep(0.25)
                    except KeyboardInterrupt:
                        return
                    raise RuntimeError("Demo service stopped unexpectedly")
                results = verify_sessions(root, ready, bindings, trained)
    rows = [json.loads(line) for line in (output / "python.jsonl").read_text(encoding="utf-8").splitlines()]
    if trained:
        require(len(rows) == 1 and rows[0]["runId"] == "run-trained-demo-seven"
                and rows[0]["status"] == "COMPLETED" and rows[0]["directCifCalls"] == 1
                and rows[0]["explanationCifCalls"] >= 2, "Unexpected trained-model execution count")
    else:
        require(len(rows) == len(EXPECTED) and {
        row["runId"]: (row["status"], row["directCifCalls"], row["explanationCifCalls"]) for row in rows
        } == {f"run-constructed-http-{label}": expected for label, expected in EXPECTED.items()},
            "Unexpected Python executions")
    require(not (output / "python.stderr").stat().st_size, "Unexpected Python host errors")
    receipt = {"tasks": results, "pythonExecutions": rows,
               "mode": "trained" if trained else "contract-test"}
    (output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    main()

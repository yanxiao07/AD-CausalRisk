import json
import uuid
import os
import sys
import io
import urllib.request
import urllib.parse
import http.cookiejar

BASE = "http://localhost:8080"
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures")

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def request(method, path, body=None, headers=None):
    url = BASE + path
    data = None
    hdrs = headers or {}
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        elif isinstance(body, bytes):
            data = body
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with opener.open(req) as resp:
        raw = resp.read()
        ct = resp.headers.get("Content-Type", "")
        if "json" in ct or (raw and raw[:1] in (b"{", b"[")):
            return resp.status, json.loads(raw.decode("utf-8"))
        return resp.status, raw.decode("utf-8", errors="replace")

def login():
    status, body = request("POST", "/api/auth/login", {"username": "admin", "password": "DemoPass!2026"})
    assert status == 200, f"Login failed: {status} {body}"
    print(f"[1] Logged in as admin -> {status}")

def build_multipart(filepath, field_name="file", filename=None, extra_fields=None):
    boundary = "----PythonBoundary" + uuid.uuid4().hex
    lines = []

    with open(filepath, "rb") as f:
        file_bytes = f.read()
    fname = filename or os.path.basename(filepath)

    lines.append(f"--{boundary}\r\n".encode())
    lines.append(f'Content-Disposition: form-data; name="{field_name}"; filename="{fname}"\r\n'.encode())
    lines.append(b"Content-Type: text/csv\r\n\r\n")
    lines.append(file_bytes)
    lines.append(b"\r\n")

    if extra_fields:
        for k, v in extra_fields.items():
            lines.append(f"--{boundary}\r\n".encode())
            lines.append(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode())
            lines.append(str(v).encode())
            lines.append(b"\r\n")

    lines.append(f"--{boundary}--\r\n".encode())
    body = b"".join(lines)
    return boundary, body

def import_csv(filename):
    filepath = os.path.join(FIXTURES, filename)
    key = str(uuid.uuid4())
    boundary, body = build_multipart(filepath, extra_fields={"mapping": ""})
    headers = {
        "Idempotency-Key": key,
        "Content-Type": f"multipart/form-data; boundary={boundary}",
    }
    status, resp = request("POST", "/api/import-jobs", body=body, headers=headers)
    if status != 200:
        print(f"  PREVIEW FAILED {filename}: {status} {resp}")
        return
    if isinstance(resp, dict) and "data" in resp:
        data = resp["data"]
    else:
        data = resp
    print(f"  Raw preview response keys: {list(data.keys()) if isinstance(data, dict) else type(data)}")
    job_id = data.get("id") or data.get("jobId") or data.get("importJobId")
    if not job_id:
        print(f"  Could not find job id in response: {json.dumps(data, ensure_ascii=False)[:500]}")
        return
    print(f"  Preview job {job_id} for {filename}: accepted={data.get('acceptedRows','?')}, rejected={data.get('rejectedRows','?')}")

    status2, resp2 = request("POST", f"/api/import-jobs/{job_id}/commit")
    if status2 != 200:
        print(f"  COMMIT FAILED {filename}: {status2} {resp2}")
        return
    committed = resp2["data"].get("committedRows", "?")
    print(f"  -> Committed {committed} rows")

login()
import_csv("subjects.synthetic.csv")
import_csv("visits.synthetic.csv")
import_csv("features.synthetic.csv")

status, body = request("GET", "/api/subjects")
if status == 200:
    print(f"\n[DONE] Subject total: {body.get('data', {}).get('total', '?')}")
else:
    print(f"\n[DONE] /api/subjects -> {status} {body}")
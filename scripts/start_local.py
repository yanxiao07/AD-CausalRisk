"""Start the local Windows application and open its login page."""

import argparse
import base64
import hashlib
import json
import msvcrt
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
from urllib.request import ProxyHandler, Request, build_opener
import webbrowser


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime/desktop"
DESKTOP_DATABASE = "file:./../runtime/desktop/adcr_care_sandbox"
PREVIEW_DATABASE = "mem:adcr_care_sandbox_validation"
DESKTOP_PORTS = (5173, 8080, 16301)
PREVIEW_PORTS = (5174, 8081, 16302)
HTTP = build_opener(ProxyHandler({}))
ENV = {key: value for key, value in os.environ.items() if not key.upper().startswith((
    "SPRING_", "SERVER_", "ADCR_", "RESEARCH_", "APP_SECURITY_", "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
))}


def available(port):
    with socket.socket() as sock:
        sock.settimeout(0.4)
        return sock.connect_ex(("127.0.0.1", port)) != 0


def responds(url):
    with HTTP.open(url, timeout=2) as response:
        return response.status == 200


def model_responds(port, token):
    request = Request(f"http://127.0.0.1:{port}/api/research-table/v1/models",
                      headers={"Authorization": f"Bearer {token}"})
    with HTTP.open(request, timeout=2) as response:
        return response.status == 200


def wait_until(process, log, probe):
    deadline = time.monotonic() + 90
    while process.poll() is None and time.monotonic() < deadline:
        try:
            if probe():
                return
        except OSError:
            pass
        time.sleep(0.4)
    raise RuntimeError(f"服务未就绪，查看日志 {log}")


def launch(arguments, cwd, log, environment):
    with log.open("wb") as output:
        return subprocess.Popen(
            arguments, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
            stdout=output, stderr=subprocess.STDOUT,
            creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP,
        )


def owns_ports(expected):
    ports = ",".join(str(int(port)) for port in expected)
    result = subprocess.run([
        "powershell.exe", "-NoProfile", "-Command",
        f"Get-NetTCPConnection -State Listen -LocalPort {ports} | ForEach-Object {{ "
        "$owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $_.OwningProcess); "
        "'{0}={1}={2}' -f $_.LocalPort,$_.OwningProcess,$owner.ParentProcessId }",
    ], capture_output=True, text=True, check=True)
    listeners = [line.split("=") for line in result.stdout.splitlines()]
    return all(any(row[0] == port and str(pid) in row[1:] for row in listeners)
               for port, pid in expected.items())


def same_instance(state, ports, login):
    if not state:
        return False
    listeners = {str(port): state["listeners"][str(port)] for port in ports[:2]}
    return not any(available(port) for port in ports[:2]) and owns_ports(listeners) and responds(login)


def read_config():
    path = RUNTIME / "local.json"
    if not path.is_file():
        return {}
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(config, dict):
        raise ValueError(f"配置必须是 JSON 对象 {path}")
    return config


def model_config(config):
    if not any(config.get(key) for key in ("manifest", "trustedManifestSha256")):
        return None
    manifest = (ROOT / config.get("manifest", "")).resolve()
    python = (ROOT / config.get("modelPython", ".venv/Scripts/python.exe")).resolve()
    trusted = config.get("trustedManifestSha256")
    if not manifest.is_file() or not python.is_file() or not trusted:
        raise RuntimeError("模型启动配置不完整，请核对模型清单、可信摘要和 Python 环境。")
    if hashlib.sha256(manifest.read_bytes()).hexdigest() != trusted:
        raise RuntimeError("模型清单与配置中的可信摘要不一致。")
    return manifest, python, trusted


def local_credential(data, *, protect):
    # The persisted token can only be reopened by the Windows account that started Java.
    operation = "Protect" if protect else "Unprotect"
    script = (
        "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
        "$data=[Convert]::FromBase64String([Console]::In.ReadToEnd()); "
        f"$result=[Security.Cryptography.ProtectedData]::{operation}($data,$null,'CurrentUser'); "
        "[Console]::Write([Convert]::ToBase64String($result))"
    )
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
        input=base64.b64encode(data).decode("ascii"), capture_output=True, text=True, check=True,
    )
    return base64.b64decode(result.stdout, validate=True)


def save_state(path, state):
    pending = path.with_suffix(".tmp")
    pending.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    pending.replace(path)


def stop_child(child):
    if child.poll() is None:
        subprocess.run(["taskkill.exe", "/PID", str(child.pid), "/T", "/F"],
                       capture_output=True, check=False)
        child.wait(timeout=15)


def start_model(model, token, python_port, log, snapshot_dir, children):
    if not available(python_port):
        raise RuntimeError(f"模型端口 {python_port} 已被占用。")
    print("正在启动医生预测所需的模型服务…", flush=True)
    manifest, python, trusted = model
    handshake = snapshot_dir.parent / "dynamic-host-handshake.json"
    previous = handshake.stat().st_mtime_ns if handshake.is_file() else None
    child = launch([
        str(python), "-B", "-m", "research_runtime.dynamic_host",
        "--host", "127.0.0.1", "--port", str(python_port), "--manifest", str(manifest),
        "--trusted-manifest-sha256", trusted, "--snapshot-dir", str(snapshot_dir),
    ], ROOT, log, dict(ENV, RESEARCH_TABLE_TEST_TOKEN=token))
    children[python_port] = child
    wait_until(child, log, lambda: (
        handshake.is_file() and handshake.stat().st_mtime_ns != previous
        and not available(python_port) and owns_ports({str(python_port): child.pid})
        and model_responds(python_port, token)
    ))


def model_running(state, port):
    pid = state["listeners"].get(str(port))
    return bool(pid and not available(port) and owns_ports({str(port): pid}))


def snapshot_dir(preview, logs):
    return (logs if preview else RUNTIME / "rehearsal-model") / "snapshots"


def recover_model(state, state_path, preview, port):
    recovery = state.get("modelRecovery")
    if recovery is None:
        raise RuntimeError("此实例由旧版脚本启动，需要重启一次以启用模型恢复。")
    model = model_config(read_config())
    if model is None or model[2] != recovery["manifestSha256"]:
        raise RuntimeError("当前模型清单与运行中的 Java 配置不一致。")
    encrypted = base64.b64decode(recovery["protectedToken"], validate=True)
    token = local_credential(encrypted, protect=False).decode("ascii")
    logs = Path(state["logs"])
    log = logs / f"python-recovery-{secrets.token_hex(6)}.log"
    children = {}
    try:
        start_model(model, token, port, log, snapshot_dir(preview, logs), children)
        state = dict(state, listeners={**state["listeners"], str(port): children[port].pid},
                     modelLog=str(log))
        save_state(state_path, state)
        return state
    except BaseException:
        if port in children:
            stop_child(children[port])
        raise


def build_app(config):
    for executable in ("java.exe", "node.exe", "npm.cmd", "powershell.exe"):
        if shutil.which(executable) is None:
            raise RuntimeError(f"找不到 {executable}，请检查 JDK 21 或 Node.js 的 PATH。")
    print("正在准备 Java 后端…", flush=True)
    maven = ["cmd.exe", "/d", "/c", "mvnw.cmd", "-q", "compile", "dependency:build-classpath",
             "-Dmdep.includeScope=test", "-Dmdep.outputFile=target/desktop-classpath.txt"]
    if config.get("mavenRepository"):
        maven.append(f"-Dmaven.repo.local={ROOT / config['mavenRepository']}")
    subprocess.run(maven, cwd=ROOT / "backend", env=ENV, check=True)
    if not (ROOT / "frontend/node_modules/vite/bin/vite.js").is_file():
        print("首次启动，正在安装前端锁定依赖…", flush=True)
        subprocess.run(["cmd.exe", "/d", "/c", "npm.cmd", "ci"], cwd=ROOT / "frontend", env=ENV, check=True)


def start(model, config, preview, ports, database):
    vue_port, java_port, python_port = ports
    login = f"http://127.0.0.1:{vue_port}/login"
    occupied = [str(port) for port in ports[:2] if not available(port)]
    if occupied:
        raise RuntimeError("端口已由其他服务占用：" + ", ".join(occupied))
    build_app(config)
    logs = RUNTIME / ("run-" + secrets.token_hex(6))
    logs.mkdir()
    java_env = dict(ENV)
    dependencies = (ROOT / "backend/target/desktop-classpath.txt").read_text(encoding="utf-8").strip()
    validation_python = config.get("modelPython")
    validation_python = ROOT / validation_python if isinstance(validation_python, str) else ROOT / ".venv/Scripts/python.exe"
    if not validation_python.is_file():
        validation_python = Path(sys.executable)
    java_args = [
        "java.exe", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(("target/classes", dependencies)),
        "com.adcausalrisk.backend.BackendApplication",
        "--server.address=127.0.0.1", f"--server.port={java_port}",
        "--spring.profiles.active=synthetic-care",
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.username=sa", "--spring.datasource.password=",
        f"--spring.datasource.url=jdbc:h2:{database};MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        "CASE_INSENSITIVE_IDENTIFIERS=TRUE;WRITE_DELAY=0",
        f"--research.validation.python={validation_python.resolve()}",
        f"--research.validation.workspace={ROOT}",
    ]
    children = {}
    prediction = False
    recovery = None
    try:
        if model:
            try:
                lock = hashlib.sha256(
                    (ROOT / "research_runtime/requirements.lock").read_bytes().replace(b"\r\n", b"\n")
                ).hexdigest()
                token = secrets.token_hex(32)
                encrypted = local_credential(token.encode("ascii"), protect=True)
                start_model(model, token, python_port, logs / "python.log", snapshot_dir(preview, logs), children)
            except (RuntimeError, OSError, subprocess.SubprocessError) as error:
                if preview:
                    raise
                child = children.pop(python_port, None)
                if child is not None:
                    stop_child(child)
                print(f"模型未就绪，继续打开联调档案。{error}", flush=True)
            else:
                manifest, _, trusted = model
                java_args.extend([
                    f"--research.table.base-url=http://127.0.0.1:{python_port}",
                    f"--research.model.manifest-path={manifest}",
                    f"--research.model.trusted-manifest-sha256={trusted}",
                    f"--research.model.software-lock-sha256={lock}",
                ])
                java_env["SPRING_APPLICATION_JSON"] = json.dumps({"research": {"table": {"bearer-token": token}}})
                prediction = True
                recovery = {"manifestSha256": trusted, "protectedToken": base64.b64encode(encrypted).decode("ascii")}
        java_args.append(f"--research.care.prediction-enabled={str(prediction).lower()}")
        children[java_port] = launch(java_args, ROOT / "backend", logs / "java.log", java_env)
        wait_until(children[java_port], logs / "java.log", lambda: responds(f"http://127.0.0.1:{java_port}/api/auth/csrf"))
        children[vue_port] = launch([
            "node.exe", "node_modules/vite/bin/vite.js", "--host", "127.0.0.1", "--port", str(vue_port), "--strictPort",
        ], ROOT / "frontend", logs / "vue.log", dict(ENV, ADCR_VITE_BACKEND=f"http://127.0.0.1:{java_port}"))
        wait_until(children[vue_port], logs / "vue.log",
                   lambda: responds(login) and responds(f"http://127.0.0.1:{vue_port}/api/auth/csrf"))
        listeners = {str(port): child.pid for port, child in children.items()}
        if not owns_ports(listeners):
            raise RuntimeError("服务端口与启动进程不一致。")
        return {"database": database, "prediction": prediction, "logs": str(logs),
                "listeners": listeners, "modelRecovery": recovery}
    except BaseException:
        for child in reversed(list(children.values())):
            stop_child(child)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode_flags = parser.add_mutually_exclusive_group()
    mode_flags.add_argument("--care-only", action="store_true", help="打开同一联调库，跳过启动模型")
    mode_flags.add_argument("--validation-preview", action="store_true", help="在 5174 启动独立的临时演练")
    parser.add_argument("--no-browser", action="store_true", help="保留服务并跳过打开浏览器")
    args = parser.parse_args()
    RUNTIME.mkdir(parents=True, exist_ok=True)
    with (RUNTIME / "startup.lock").open("a+b") as lock_file:
        lock_file.seek(0)
        try:
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise RuntimeError("已有一个启动操作在运行，等待浏览器打开即可。") from error
        # Use a separate browser cookie host for the preview and the default instance.
        ports = PREVIEW_PORTS if args.validation_preview else DESKTOP_PORTS
        database = PREVIEW_DATABASE if args.validation_preview else DESKTOP_DATABASE
        host = "localhost" if args.validation_preview else "127.0.0.1"
        login = f"http://{host}:{ports[0]}/login"
        state_path = RUNTIME / ("validation-preview.json" if args.validation_preview else "services.json")
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.is_file() else None
        if same_instance(state, ports, login):
            if state.get("database") != database:
                raise RuntimeError("旧版实例仍在运行。请先停止旧版实例，再启动新版联调库。")
            if not args.care_only and state["prediction"] and not model_running(state, ports[2]):
                try:
                    state = recover_model(state, state_path, args.validation_preview, ports[2])
                except (KeyError, RuntimeError, OSError, TypeError, ValueError, subprocess.SubprocessError) as error:
                    print(f"模型恢复失败。{error}", flush=True)
        else:
            config = {}
            model = None
            try:
                config = read_config()
                if not args.care_only:
                    model = model_config(config)
            except (RuntimeError, OSError, TypeError, ValueError) as error:
                if args.validation_preview:
                    raise RuntimeError(f"验证演练无法读取模型配置。{error}") from error
                print(f"模型配置不可用，继续打开联调档案。{error}", flush=True)
            if args.validation_preview and not model:
                raise RuntimeError("验证演练需要本机模型配置。")
            state = start(model, config, args.validation_preview, ports, database)
            save_state(state_path, state)
    label = ("临时验证演练（重启清空）" if args.validation_preview
             else f"本机联调（重启保留）\n数据文件 {RUNTIME / 'adcr_care_sandbox.mv.db'}")
    model_ready = state["prediction"] and model_running(state, ports[2])
    status = "已启动" if model_ready else "未就绪，可继续建档和查看已有结果"
    print(f"已启动 {label}\n模型服务{status}\n{login}\n运行日志 {state['logs']}", flush=True)
    if state.get("modelLog"):
        print(f"模型恢复日志 {state['modelLog']}", flush=True)
    if not args.no_browser:
        webbrowser.open(login)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as problem:
        raise SystemExit(str(problem)) from problem

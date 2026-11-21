"""Start the local Windows application and open its login page."""

import argparse
import hashlib
import json
import msvcrt
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
from urllib.request import ProxyHandler, build_opener
import webbrowser


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime/desktop"
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


def same_instance(state, login):
    return (state and not any(available(int(port)) for port in state["listeners"])
            and owns_ports(state["listeners"]) and responds(login))


def read_config():
    path = RUNTIME / "local.json"
    if not path.is_file():
        return {}
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(config, dict):
        raise ValueError(f"配置必须是 JSON 对象 {path}")
    return config


def model_config(config, care_only):
    if care_only or not any(config.get(key) for key in ("manifest", "trustedManifestSha256", "modelPython")):
        return None
    manifest = (ROOT / config.get("manifest", "")).resolve()
    python = (ROOT / config.get("modelPython", ".venv/Scripts/python.exe")).resolve()
    trusted = config.get("trustedManifestSha256")
    if not manifest.is_file() or not python.is_file() or not trusted:
        raise RuntimeError("模型启动配置不完整，请核对模型清单、可信摘要和 Python 环境。")
    if hashlib.sha256(manifest.read_bytes()).hexdigest() != trusted:
        raise RuntimeError("模型清单与配置中的可信摘要不一致。")
    return manifest, python, trusted


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


def start(model, config, preview):
    vue_port, java_port, python_port = (5174, 8081, 16302) if preview else (5173, 8080, 16301)
    ports = (vue_port, java_port, python_port) if model else (vue_port, java_port)
    login = f"http://127.0.0.1:{vue_port}/login"
    occupied = [str(port) for port in ports if not available(port)]
    if occupied:
        raise RuntimeError("端口已由其他服务占用：" + ", ".join(occupied))
    build_app(config)
    logs = RUNTIME / ("run-" + secrets.token_hex(6))
    logs.mkdir()
    java_env = dict(ENV)
    dependencies = (ROOT / "backend/target/desktop-classpath.txt").read_text(encoding="utf-8").strip()
    java_args = [
        "java.exe", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(("target/classes", dependencies)),
        "com.adcausalrisk.backend.BackendApplication",
        "--server.address=127.0.0.1", f"--server.port={java_port}",
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.username=sa", "--spring.datasource.password=",
    ]
    children = {}
    try:
        if model:
            print("正在启动医生预测演练所需的模型服务…", flush=True)
            manifest, python, trusted = model
            token = secrets.token_hex(32)
            lock = hashlib.sha256(
                (ROOT / "research_runtime/requirements.lock").read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            children[python_port] = launch([
                str(python), "-B", "-m", "research_runtime.dynamic_host",
                "--host", "127.0.0.1", "--port", str(python_port), "--manifest", str(manifest),
                "--trusted-manifest-sha256", trusted, "--snapshot-dir", str(logs / "snapshots"),
            ], ROOT, logs / "python.log", dict(ENV, RESEARCH_TABLE_TEST_TOKEN=token))
            wait_until(children[python_port], logs / "python.log",
                       lambda: (logs / "dynamic-host-handshake.json").is_file() and not available(python_port))
            database = "mem:adcr_care_sandbox_validation" if preview else "mem:adcr_care_sandbox"
            java_args += [
                "--spring.profiles.active=synthetic-care", f"--research.table.base-url=http://127.0.0.1:{python_port}",
                f"--research.model.manifest-path={manifest}", f"--research.model.trusted-manifest-sha256={trusted}",
                f"--research.model.software-lock-sha256={lock}", f"--research.validation.python={python}",
                f"--research.validation.workspace={ROOT}",
            ]
            java_env["SPRING_APPLICATION_JSON"] = json.dumps({"research": {"table": {"bearer-token": token}}})
        else:
            database = f"file:{(RUNTIME / 'care').as_posix()}"
        java_args.append(
            f"--spring.datasource.url=jdbc:h2:{database};MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
            "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        )
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
        return {"mode": "synthetic" if model else "care", "logs": str(logs),
                "listeners": listeners}
    except BaseException:
        for child in reversed(list(children.values())):
            if child.poll() is None:
                subprocess.run(["taskkill.exe", "/PID", str(child.pid), "/T", "/F"],
                               capture_output=True, check=False)
                child.wait(timeout=15)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode_flags = parser.add_mutually_exclusive_group()
    mode_flags.add_argument("--care-only", action="store_true", help="启动本地持久化业务演示")
    mode_flags.add_argument("--validation-preview", action="store_true", help="在 5174 启动隔离的验证演练")
    parser.add_argument("--no-browser", action="store_true", help="保留服务并跳过打开浏览器")
    args = parser.parse_args()
    RUNTIME.mkdir(parents=True, exist_ok=True)
    with (RUNTIME / "startup.lock").open("a+b") as lock_file:
        lock_file.seek(0)
        try:
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise RuntimeError("已有一个启动操作在运行，等待浏览器打开即可。") from error
        config = read_config()
        model = model_config(config, args.care_only)
        if args.validation_preview and not model:
            raise RuntimeError("验证演练需要本机模型配置。")
        # Use a separate browser cookie host for the preview and the default instance.
        login = "http://localhost:5174/login" if args.validation_preview else "http://127.0.0.1:5173/login"
        mode = "synthetic" if model else "care"
        state_path = RUNTIME / ("validation-preview.json" if args.validation_preview else "services.json")
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.is_file() else None
        if same_instance(state, login):
            if state["mode"] != mode:
                raise RuntimeError("项目已按另一种模式运行。当前实例的 PID、模式和日志在 " + str(state_path))
        else:
            state = start(model, config, args.validation_preview)
            state_path.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    label = "医生预测演练（仅人工样例，重启清空）" if mode == "synthetic" else "本地业务演示（档案跨重启保存）"
    print(f"已启动 {label}\n{login}\n运行日志 {state['logs']}", flush=True)
    if not args.no_browser:
        webbrowser.open(login)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as problem:
        raise SystemExit(str(problem)) from problem

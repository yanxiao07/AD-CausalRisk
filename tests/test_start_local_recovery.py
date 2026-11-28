"""Local launcher recovery decisions, isolated from processes, model files and credentials."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


class LocalRecoveryTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location(
            "local_launcher_test", Path(__file__).resolve().parents[1] / "scripts/start_local.py")
        self.launcher = importlib.util.module_from_spec(spec)
        # Only locking is Windows-specific; recovery orchestration is tested on all CI hosts.
        with patch.dict(sys.modules, {} if sys.platform == "win32" else {"msvcrt": types.ModuleType("msvcrt")}):
            spec.loader.exec_module(self.launcher)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.launcher.RUNTIME = self.root
        self.state_path = self.root / "services.json"
        self.state = {"database": "file:./fixture", "prediction": True, "logs": str(self.root),
                      "listeners": {"8080": 11, "5173": 12, "16301": 13},
                      "modelRecovery": {"manifestSha256": "a" * 64,
                                        "protectedToken": base64.b64encode(b"encrypted-fixture").decode()}}
        self.launcher.save_state(self.state_path, self.state)
        self.model = (self.root / "manifest.json", self.root / "python", "a" * 64)

    def test_recovery_keeps_business_processes_database_and_protected_credential(self):
        original = json.loads(json.dumps(self.state))
        child = Mock(pid=99)
        def started(model, token, port, log, snapshots, children):
            self.assertEqual(model, self.model)
            self.assertEqual(token, "fixture-secret")
            self.assertEqual(snapshots, self.root / "rehearsal-model/snapshots")
            children[port] = child
        with patch.object(self.launcher, "read_config", return_value={}), \
             patch.object(self.launcher, "model_config", return_value=self.model), \
             patch.object(self.launcher, "local_credential", return_value=b"fixture-secret"), \
             patch.object(self.launcher, "start_model", side_effect=started):
            restored = self.launcher.recover_model(self.state, self.state_path, False, 16301)
        self.assertEqual(self.state, original)
        self.assertEqual(restored["listeners"], {"8080": 11, "5173": 12, "16301": 99})
        self.assertEqual(restored["database"], original["database"])
        self.assertEqual(restored["modelRecovery"], original["modelRecovery"])
        self.assertNotIn("fixture-secret", self.state_path.read_text())
        self.assertEqual(json.loads(self.state_path.read_text()), restored)

    def test_changed_manifest_is_rejected_before_credentials_or_launch(self):
        with patch.object(self.launcher, "read_config", return_value={}), \
             patch.object(self.launcher, "model_config", return_value=(*self.model[:2], "b" * 64)), \
             patch.object(self.launcher, "local_credential") as credential, \
             patch.object(self.launcher, "start_model") as start:
            with self.assertRaisesRegex(RuntimeError, "清单"):
                self.launcher.recover_model(self.state, self.state_path, False, 16301)
        credential.assert_not_called()
        start.assert_not_called()
        self.assertEqual(json.loads(self.state_path.read_text()), self.state)

    def test_failed_decryption_never_launches_or_changes_saved_state(self):
        with patch.object(self.launcher, "read_config", return_value={}), \
             patch.object(self.launcher, "model_config", return_value=self.model), \
             patch.object(self.launcher, "local_credential", side_effect=ValueError("fixture")), \
             patch.object(self.launcher, "start_model") as start:
            with self.assertRaises(ValueError):
                self.launcher.recover_model(self.state, self.state_path, False, 16301)
        start.assert_not_called()
        self.assertEqual(json.loads(self.state_path.read_text()), self.state)

    def test_failed_recovery_stops_only_its_new_model_child(self):
        child = Mock(pid=99)
        def failed(model, token, port, log, snapshots, children):
            children[port] = child
            raise RuntimeError("fixture startup failure")
        with patch.object(self.launcher, "read_config", return_value={}), \
             patch.object(self.launcher, "model_config", return_value=self.model), \
             patch.object(self.launcher, "local_credential", return_value=b"fixture-secret"), \
             patch.object(self.launcher, "start_model", side_effect=failed), \
             patch.object(self.launcher, "stop_child") as stop:
            with self.assertRaises(RuntimeError):
                self.launcher.recover_model(self.state, self.state_path, False, 16301)
        stop.assert_called_once_with(child)
        self.assertEqual(json.loads(self.state_path.read_text()), self.state)

    def test_model_health_uses_authenticated_models_endpoint(self):
        response = Mock(status=200)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(self.launcher.HTTP, "open", return_value=response) as opened:
            self.assertTrue(self.launcher.model_responds(16301, "fixture-secret"))
        request = opened.call_args.args[0]
        self.assertEqual(request.full_url, "http://127.0.0.1:16301/api/research-table/v1/models")
        self.assertEqual(request.get_header("Authorization"), "Bearer fixture-secret")
        self.assertEqual(opened.call_args.kwargs["timeout"], 2)

    def test_windows_credential_input_uses_stdin_instead_of_command_arguments(self):
        completed = subprocess.CompletedProcess([], 0, stdout=base64.b64encode(b"encrypted-fixture").decode())
        with patch.object(self.launcher.subprocess, "run", return_value=completed) as run:
            self.assertEqual(self.launcher.local_credential(b"fixture-secret", protect=True), b"encrypted-fixture")
        self.assertNotIn("fixture-secret", str(run.call_args.args))
        self.assertNotIn(base64.b64encode(b"fixture-secret").decode(), str(run.call_args.args))
        self.assertEqual(base64.b64decode(run.call_args.kwargs["input"]), b"fixture-secret")


if __name__ == "__main__":
    unittest.main()

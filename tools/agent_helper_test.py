import contextlib
import importlib.util
import io
import json
import hashlib
import os
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("hermit_agent", Path(__file__).resolve().parents[1] / "app/src/main/assets/agent/hermit-agent.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class Handler(BaseHTTPRequestHandler):
    seen = []
    def log_message(self, *args):
        pass
    def do_GET(self):
        self.send_response(302)
        self.send_header("Location", "http://127.0.0.1:1/leak")
        self.end_headers()
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.seen.append((dict(self.headers), data))
        response = {"jsonrpc": "2.0", "id": data.get("id"), "result": {"tools": []}}
        self.send_response(200 if "id" in data else 202)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if "id" in data:
            self.wfile.write(json.dumps(response).encode())


class AgentHelperTest(unittest.TestCase):
    def test_rejects_public_urls_embedded_secrets_and_paths(self):
        for value in ["http://8.8.8.8:8766", "http://0.0.0.0:8766", "http://user:password@192.168.1.2:8766", "https://192.168.1.2:8766", "http://192.168.1.2:8766/mcp", "http://192.168.1.2:8766?password=123456"]:
            with self.assertRaises(ValueError): helper.address(value)
        self.assertEqual("http://192.168.1.2:8766", helper.address("http://192.168.1.2:8766/"))

    def test_credentials_are_private_and_rotation_replaces_one_value(self):
        with tempfile.TemporaryDirectory() as temp:
            client = helper.Device("http://127.0.0.1:8766", "123456", temp)
            client.save_password()
            self.assertEqual(0o600, client.credential_file.stat().st_mode & 0o777)
            client.password = "654321"; client.save_password()
            loaded = helper.Device(client.base, config_root=temp)
            self.assertTrue(loaded.password == client.password)
            client.credential_file.chmod(0o644)
            with self.assertRaises(RuntimeError): helper.Device(client.base, config_root=temp)

    def test_source_snapshot_excludes_secrets_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("native")
            (root / ".env").write_text("SECRET")
            (root / "private.key").write_text("SECRET")
            (root / "node_modules").mkdir(); (root / "node_modules/skip.js").write_text("skip")
            self.assertEqual(["index.html"], [name for name, _ in helper.source_files(root)])
            (root / "linked.html").symlink_to(root / "index.html")
            with self.assertRaises(ValueError): helper.source_files(root)

    def test_dynamic_skill_has_no_fixed_device_or_password_and_preserves_unowned(self):
        with tempfile.TemporaryDirectory() as temp:
            result = helper.install_skill(temp)
            file = Path(result["installed"])
            self.assertIn("Before every task", file.read_text())
            self.assertIn("/.well-known/hermit-agent", file.read_text())
            self.assertIn("hermit_enter_dev_mode", file.read_text())
            helper.install_skill(temp)
            file.write_text("user-owned skill")
            with self.assertRaises(RuntimeError): helper.install_skill(temp)
            self.assertEqual("user-owned skill", file.read_text())

    def test_http_auth_stdio_and_redirect_refusal(self):
        with tempfile.TemporaryDirectory() as temp:
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                client = helper.Device("http://127.0.0.1:" + str(server.server_port), "123456", temp)
                self.assertEqual({"tools": []}, client.rpc("tools/list"))
                headers, body = Handler.seen[-1]
                self.assertTrue(headers["Authorization"] == "Bearer " + client.password)
                self.assertNotIn("Mcp-Session-Id", headers)
                with self.assertRaises(RuntimeError): client.request("/redirect")
                output = io.StringIO()
                with patch("sys.stdin", io.StringIO('{"jsonrpc":"2.0","id":4,"method":"tools/list"}\n')), contextlib.redirect_stdout(output):
                    helper.stdio(client)
                reply = json.loads(output.getvalue())
                self.assertEqual(4, reply["id"])
                self.assertFalse(client.password in output.getvalue())
            finally:
                server.shutdown(); server.server_close(); thread.join()

    def test_watcher_refuses_newer_remote_release(self):
        class OtherComputer:
            def tool(self, *_): return {"activeReleaseId": "newer-release"}
        with self.assertRaisesRegex(RuntimeError, "Another computer"):
            helper.deploy(OtherComputer(), "app-id", "/unused", "my-release")

    def test_dev_sync_sends_only_changed_text_and_deletions(self):
        class Device:
            def __init__(self): self.calls = []
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "hermit_runtime_status":
                    return {"appId": "app-id", "launchChannel": "dev"}
                if name == "hermit_list_dev_files":
                    same = hashlib.sha256(b"same").hexdigest()
                    return {"revision": 7, "treeHash": "old", "files": [
                        {"path": "index.html", "sha256": same},
                        {"path": "removed.css", "sha256": "0" * 64},
                    ]}
                if name == "hermit_sync_dev_changes":
                    return {"revision": 8, "changedPaths": [item["path"] for item in arguments["files"]]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("same")
            (root / "app.js").write_text("changed")
            device = Device()
            result = helper.dev_sync(device, "app-id", root)
            self.assertEqual(8, result["revision"])
            self.assertEqual("hermit_runtime_status", device.calls[0][0])
            name, arguments = device.calls[-1]
            self.assertEqual("hermit_sync_dev_changes", name)
            self.assertEqual(7, arguments["expectedDevRevision"])
            self.assertEqual([
                {"path": "app.js", "content": "changed"},
                {"path": "removed.css", "delete": True},
            ], arguments["files"])

    def test_dev_sync_prepares_and_opens_target_only_when_needed(self):
        class Device:
            def __init__(self): self.calls = []
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "hermit_runtime_status":
                    return {"appId": "other-app", "launchChannel": "stable"}
                if name == "hermit_enter_dev_mode":
                    return {"appId": "app-id", "launchChannel": "dev", "revision": 3,
                            "runtime": {"state": "opening"}}
                if name == "hermit_list_dev_files":
                    return {"revision": 3, "treeHash": "same", "files": [
                        {"path": "index.html", "sha256": hashlib.sha256(b"same").hexdigest()}
                    ]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, "index.html").write_text("same")
            device = Device()
            result = helper.dev_sync(device, "app-id", temp)
            self.assertEqual("unchanged", result["refreshState"])
            self.assertEqual(["hermit_runtime_status", "hermit_enter_dev_mode", "hermit_list_dev_files"],
                             [name for name, _ in device.calls])
            self.assertEqual("app-id", device.calls[1][1]["appId"])
            self.assertIn("requestId", device.calls[1][1])


if __name__ == "__main__": unittest.main()

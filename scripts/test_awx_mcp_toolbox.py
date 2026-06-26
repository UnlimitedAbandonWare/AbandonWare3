import importlib.util
import datetime as dt
import io
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOLBOX_PATH = ROOT / "scripts" / "awx_mcp_toolbox.py"
SPEC = importlib.util.spec_from_file_location("awx_mcp_toolbox", TOOLBOX_PATH)
toolbox = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(toolbox)
sys.path.insert(0, str(ROOT / "scripts"))
STDIO_SPEC = importlib.util.spec_from_file_location("awx_mcp_stdio_server", ROOT / "scripts" / "awx_mcp_stdio_server.py")
stdio_server = importlib.util.module_from_spec(STDIO_SPEC)
assert STDIO_SPEC.loader is not None
STDIO_SPEC.loader.exec_module(stdio_server)
COMPLETION_AUDIT_SPEC = importlib.util.spec_from_file_location(
    "awx_mcp_completion_audit",
    ROOT / "scripts" / "awx_mcp_completion_audit.py",
)
completion_audit = importlib.util.module_from_spec(COMPLETION_AUDIT_SPEC)
assert COMPLETION_AUDIT_SPEC.loader is not None
COMPLETION_AUDIT_SPEC.loader.exec_module(completion_audit)


class HarmonyBreakStatusTest(unittest.TestCase):
    def test_control_tower_secret_patterns_include_supabase_sb_keys(self):
        text = (
            ("sb_secret_" + "1234567890abcdef")
            + " "
            + ("sb_publishable_" + "1234567890abcdef")
            + " "
            + ("sbp_" + "1234567890abcdef")
        )

        self.assertEqual(3, toolbox.high_conf_secret_count(text))
        self.assertEqual(3, len(completion_audit.SECRET_PATTERN.findall(text)))

    def test_mcp_runner_secret_patterns_include_supabase_sb_keys(self):
        text = (
            ("sb_secret_" + "1234567890abcdef")
            + " "
            + ("sb_publishable_" + "1234567890abcdef")
            + " "
            + ("sbp_" + "1234567890abcdef")
        )
        modules = {
            "awx_mcp_node_smoke": ROOT / "scripts" / "awx_mcp_node_smoke.py",
            "awx_mcp_node_setup": ROOT / "scripts" / "awx_mcp_node_setup.py",
            "awx_mcp_producer_handoff": ROOT / "scripts" / "awx_mcp_producer_handoff.py",
            "awx_mcp_control_tower_pipeline": ROOT / "scripts" / "awx_mcp_control_tower_pipeline.py",
        }

        for module_name, module_path in modules.items():
            spec = importlib.util.spec_from_file_location(module_name, module_path)
            module = importlib.util.module_from_spec(spec)
            assert spec.loader is not None
            spec.loader.exec_module(module)
            self.assertEqual(3, len(module.SECRET_RE.findall(text)), module_name)

    def test_control_tower_pipeline_completion_audit_command_matches_cli(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_control_tower_pipeline.py"),
                "--root",
                str(ROOT),
                "--plan-only",
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        plan = json.loads(completed.stdout)
        command = plan["commands"]["desktop_completion_audit"]
        self.assertIn("python scripts/awx_mcp_completion_audit.py --root .", command)
        self.assertIn(
            "--output var/codex-smoke/awx-mcp-completion-audit-control-tower-current.json",
            command,
        )
        self.assertNotIn("--patchdrop-root", command)

    def test_source_scan_ignores_python_bytecode_cache_for_secret_hits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main" / "java").mkdir(parents=True)
            (root / "main" / "resources").mkdir(parents=True)
            (root / "app" / "src" / "main" / "java_clean").mkdir(parents=True)
            (root / "app" / "src" / "main" / "resources").mkdir(parents=True)
            pycache = root / "scripts" / "__pycache__"
            pycache.mkdir(parents=True)
            fixture_prefix = b"sk-"
            fixture_suffix = b"testfixture012345678901234567890"
            (pycache / "fixture.cpython-311.pyc").write_bytes(
                b"not-source " + fixture_prefix + fixture_suffix
            )

            result = toolbox.source_scan({"root": str(root)})

        self.assertEqual(0, result["secretPatternHits"])

    def test_source_scan_redacts_active_sourceset_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main" / "java").mkdir(parents=True)

            result = toolbox.source_scan({"root": str(root)})
            rendered = json.dumps(result, sort_keys=True)

        main_java = result["activeSourceSets"]["mainJava"]
        self.assertNotIn("path", main_java)
        self.assertTrue(str(main_java["pathHash"]))
        self.assertGreater(main_java["pathLength"], 0)
        self.assertNotIn(str(root), rendered)

    def test_archive_search_missing_index_returns_structured_probe_next_actions(self):
        with tempfile.TemporaryDirectory() as tmp:
            index_path = Path(tmp) / "BackupsXS" / "index.jsonl"

            result = toolbox.archive_search({"index_path": str(index_path), "q": "cfvm"})
            rendered = json.dumps(result, sort_keys=True)

        self.assertEqual("archive_index_missing", result["decision"])
        self.assertEqual(0, result["passCount"])
        self.assertEqual([], result["results"])
        self.assertEqual("payload.index_path", result["archiveIndex"]["indexPathSource"])
        self.assertFalse(result["archiveIndex"]["exists"])
        self.assertEqual(toolbox.stable_hash(str(index_path)), result["archiveIndex"]["indexPathHash"])
        self.assertEqual(len(str(index_path)), result["archiveIndex"]["indexPathLength"])
        self.assertIn("payload.index_path", result["archiveIndex"]["candidateSources"])
        self.assertIn("ARCHIVE_INDEX", result["archiveIndex"]["candidateSources"])
        self.assertIn("NAS_ARCHIVE_ROOT/index.jsonl", result["archiveIndex"]["candidateSources"])
        self.assertIn("BackupsXS/index.jsonl", result["archiveIndex"]["candidateSources"])
        self.assertIn("archive_index_path_hash", result["archiveIndex"]["verifyHint"])
        self.assertNotIn(str(index_path), rendered)
        self.assertIn("create_or_point_archive_index", result["nextActions"])
        self.assertIn("run_archive_index_build", result["nextActions"])
        self.assertIn("set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT", result["nextActions"])
        self.assertIn("verify_archive_index_path", result["nextActions"])
        self.assertIn("rerun_archive_search_with_index_path", result["nextActions"])

    def test_archive_restore_missing_root_redacts_archive_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive_root = Path(tmp) / "private" / "BackupsXS"
            target_dir = Path(tmp) / "restore-target"
            audit_log = Path(tmp) / "audit" / "restore.jsonl"

            result = toolbox.archive_restore(
                {
                    "archive_root": str(archive_root),
                    "glob": "*.java",
                    "mode": "restore",
                    "target_dir": str(target_dir),
                    "audit_log": str(audit_log),
                }
            )
            rendered = json.dumps(result, sort_keys=True)

        self.assertEqual("archive_root_missing", result["decision"])
        self.assertFalse(result["ok"])
        self.assertEqual("archive-root-missing", result["failReason"])
        self.assertEqual(0, result["outputCount"])
        self.assertEqual(toolbox.stable_hash(str(archive_root)), result["archiveRootHash"])
        self.assertEqual(len(str(archive_root)), result["archiveRootLength"])
        self.assertIn("archiveRootHash", result["evidence_needed"])
        self.assertNotIn(str(archive_root), rendered)
        self.assertNotIn(str(target_dir), rendered)
        self.assertNotIn(str(audit_log), rendered)

    def test_archive_index_build_creates_secret_safe_searchable_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive_root = Path(tmp) / "archive"
            archive_root.mkdir()
            (archive_root / "cfvm-note.txt").write_text(
                "CFVM retrieval order and raw matrix checkpoint\nsecret sk-" + "THIS_SHOULD_NOT_LEAK_123456789",
                encoding="utf-8",
            )
            nested = archive_root / "nested"
            nested.mkdir()
            (nested / "hypernova.md").write_text("HYPERNOVA cvar risk-k fusion note\n", encoding="utf-8")
            index_path = Path(tmp) / "BackupsXS" / "index.jsonl"

            result = toolbox.archive_index_build(
                {
                    "archive_root": str(archive_root),
                    "index_path": str(index_path),
                    "include_globs": ["*.txt", "*.md"],
                }
            )
            search = toolbox.archive_search({"index_path": str(index_path), "q": "cfvm checkpoint", "top_k": 3})
            index_exists = index_path.is_file()
            rendered_index = index_path.read_text(encoding="utf-8")

        self.assertEqual("archive_index_built", result["decision"])
        self.assertTrue(index_exists)
        self.assertEqual(2, result["indexedCount"])
        self.assertNotIn("indexPath", result)
        self.assertEqual(toolbox.stable_hash(str(index_path)), result["indexPathHash"])
        self.assertEqual(len(str(index_path)), result["indexPathLength"])
        self.assertEqual(0, result["rawSecretPatternHits"])
        self.assertEqual("archive_search", search["decision"])
        self.assertGreaterEqual(search["outputCount"], 1)
        self.assertNotIn("path", search["results"][0])
        self.assertEqual("cfvm-note.txt", search["results"][0]["pathName"])
        self.assertTrue(str(search["results"][0]["pathHash"]))
        self.assertGreater(search["results"][0]["pathLength"], 0)
        self.assertNotIn("sk-" + "THIS_SHOULD_NOT_LEAK", rendered_index)
        self.assertNotIn("raw matrix checkpoint", rendered_index.lower())
        self.assertIn("archive_index_build", toolbox.TOOL_ALIASES.values())
        self.assertIn("archive_index_build", stdio_server.HANDLERS)
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        tool_names = {tool["name"] for tool in manifest["tools"]}
        self.assertIn("archive_index_build", tool_names)

    def test_archive_restore_review_redacts_candidate_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive_root = Path(tmp) / "private" / "BackupsXS"
            archive_root.mkdir(parents=True)
            (archive_root / "sensitive-name.java").write_text("class Example {}", encoding="utf-8")

            result = toolbox.archive_restore(
                {
                    "archive_root": str(archive_root),
                    "glob": "*.java",
                    "mode": "review",
                }
            )
            rendered = json.dumps(result, sort_keys=True)

        self.assertEqual("review_only", result["decision"])
        self.assertTrue(result["ok"])
        self.assertEqual("", result["failReason"])
        self.assertEqual(1, result["outputCount"])
        self.assertNotIn("path", result["candidates"][0])
        self.assertEqual("sensitive-name.java", result["candidates"][0]["pathName"])
        self.assertTrue(str(result["candidates"][0]["pathHash"]))
        self.assertGreater(result["candidates"][0]["pathLength"], 0)
        self.assertNotIn(str(archive_root), rendered)

    def test_mcp_manifest_archive_index_build_matches_redacted_path_contract(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        archive_index_tool = next(tool for tool in manifest["tools"] if tool["name"] == "archive_index_build")
        properties = archive_index_tool["output_schema"]["properties"]
        required = archive_index_tool["output_schema"]["required"]

        self.assertNotIn("indexPath", properties)
        self.assertNotIn("indexPath", required)
        self.assertIn("indexPathHash", properties)
        self.assertIn("indexPathLength", properties)
        self.assertIn("indexPathHash", required)
        self.assertIn("indexPathLength", required)

    def test_mcp_manifest_archive_restore_allows_review_without_target_paths(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        archive_restore_tool = next(tool for tool in manifest["tools"] if tool["name"] == "archive_restore")
        required = archive_restore_tool["input_schema"]["required"]

        self.assertEqual(["mode", "glob"], required)

    def test_root_supabase_mcp_config_is_secret_free(self):
        config_path = ROOT / ".mcp.json"
        self.assertTrue(config_path.exists(), ".mcp.json should define the Supabase MCP endpoint without secrets")

        config_text = config_path.read_text(encoding="utf-8")
        config = json.loads(config_text)
        supabase_config = config.get("mcpServers", {}).get("supabase", {})
        parsed = urllib.parse.urlparse(supabase_config.get("url", ""))
        query = urllib.parse.parse_qs(parsed.query)

        self.assertEqual("http", supabase_config.get("type"))
        self.assertEqual("https", parsed.scheme)
        self.assertEqual("mcp.supabase.com", parsed.netloc)
        self.assertEqual("/mcp", parsed.path)
        self.assertEqual(["true"], query.get("read_only"))
        self.assertEqual(["database,debugging,docs"], query.get("features"))
        self.assertEqual(["${SUPABASE_PROJECT_REF}"], query.get("project_ref"))
        self.assertNotIn("SUPABASE_ACCESS_TOKEN", config_text)
        self.assertNotIn("service_role", config_text)
        self.assertNotIn("api_key", config_text.lower())

    def test_supabase_mcp_probe_treats_remote_403_as_auth_gate(self):
        old_urlopen = toolbox.urllib.request.urlopen

        def fake_urlopen(request, timeout):
            raise toolbox.urllib.error.HTTPError(
                request.full_url,
                403,
                "Forbidden",
                {},
                io.BytesIO(b"authentication required"),
            )

        toolbox.urllib.request.urlopen = fake_urlopen
        try:
            result = toolbox.supabase_mcp_summary("https://mcp.supabase.com/mcp?read_only=true", 1)
        finally:
            toolbox.urllib.request.urlopen = old_urlopen

        self.assertTrue(result["reachable"])
        self.assertTrue(result["unauthenticatedExpected"])
        self.assertEqual("mcp_endpoint_auth_required", result["decision"])

    def test_stdio_bridge_can_call_supabase_context_probe(self):
        response = stdio_server.call_tool(
            {
                "name": "supabase_context_probe",
                "arguments": {
                    "root": str(ROOT),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                },
            }
        )

        self.assertFalse(response["isError"])
        self.assertEqual("supabase_context_probe", response["structuredContent"]["decision"])
        self.assertIn("cli", response["structuredContent"])
        self.assertIn("mcpConfig", response["structuredContent"])
        self.assertIsInstance(response["structuredContent"]["sensitiveEnvPresent"], list)
        self.assertEqual(0, response["structuredContent"]["mcpConfig"]["rawSecretPatternHits"])

    def test_toolbox_cli_accepts_dash_input_json_from_stdin_for_supabase_probe(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(TOOLBOX_PATH),
                "supabase_context_probe",
                "--input-json",
                "-",
            ],
            input=json.dumps(
                {
                    "root": str(ROOT),
                    "mcp_url": "https://mcp.supabase.com/mcp",
                    "timeout_sec": 1,
                    "skip_mcp_network_probe": True,
                }
            ),
            text=True,
            capture_output=True,
            cwd=ROOT,
        )

        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        response = json.loads(completed.stdout)
        self.assertTrue(response["ok"])
        self.assertEqual("supabase_context_probe", response["decision"])
        self.assertTrue(response["mcp"]["probeSkipped"])
        self.assertEqual(0, response["mcpConfig"]["rawSecretPatternHits"])

    def test_supabase_context_probe_is_registered_and_redacts_env_values(self):
        old_token = os.environ.get("SUPABASE_ACCESS_TOKEN")
        old_url = os.environ.get("SUPABASE_URL")
        fake_token = "unit-test-" + "supabase-" + "token"
        fake_project_host = "unit-test-" + "project"
        os.environ["SUPABASE_ACCESS_TOKEN"] = fake_token
        os.environ["SUPABASE_URL"] = "https://" + fake_project_host + ".supabase.co"
        try:
            self.assertIn("supabase_context_probe", toolbox.TOOL_ALIASES.values())
            manifest = (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(
                encoding="utf-8"
            )
            self.assertIn('"name": "supabase_context_probe"', manifest)
            self.assertIn('"command": "python scripts/awx_mcp_toolbox.py supabase_context_probe"', manifest)
            with tempfile.TemporaryDirectory() as tmp:
                result = toolbox.supabase_context_probe(
                    {
                        "root": tmp,
                        "mcp_url": "http://127.0.0.1:1/mcp",
                        "timeout_sec": 1,
                    }
                )
        finally:
            if old_token is None:
                os.environ.pop("SUPABASE_ACCESS_TOKEN", None)
            else:
                os.environ["SUPABASE_ACCESS_TOKEN"] = old_token
            if old_url is None:
                os.environ.pop("SUPABASE_URL", None)
            else:
                os.environ["SUPABASE_URL"] = old_url

        rendered = str(result)
        self.assertTrue(result["ok"])
        self.assertEqual("supabase_context_probe", result["decision"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", result["envPresent"])
        self.assertIn("SUPABASE_URL", result["envPresent"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", result["sensitiveEnvPresent"])
        self.assertFalse(result["mcp"]["reachable"])
        self.assertNotIn(fake_token, rendered)
        self.assertNotIn(fake_project_host, rendered)

    def test_supabase_context_probe_reports_read_only_mcp_config(self):
        result = toolbox.supabase_context_probe(
            {
                "root": str(ROOT),
                "mcp_url": "http://127.0.0.1:1/mcp",
                "timeout_sec": 1,
            }
        )

        self.assertTrue(result["mcpConfig"]["present"])
        self.assertTrue(result["mcpConfig"]["hasSupabaseMcpUrl"])
        self.assertTrue(result["mcpConfig"]["readOnlyMode"])
        self.assertTrue(result["mcpConfig"]["featuresScopedMode"])
        self.assertEqual(["database", "debugging", "docs"], result["mcpConfig"]["featureGroups"])
        self.assertFalse(result["mcpConfig"]["projectScopedMode"])
        self.assertTrue(result["mcpConfig"]["projectRefTemplateMode"])
        self.assertEqual("http", result["mcpConfig"]["serverType"])
        self.assertEqual(0, result["mcpConfig"]["rawSecretPatternHits"])
        self.assertIn(
            "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
            result["evidence_needed"],
        )
        self.assertNotIn(
            "Supabase MCP project_ref missing / set project_ref=<ref> after selecting the target project",
            result["evidence_needed"],
        )
        project_scope = result["projectScope"]
        self.assertEqual("project_ref_missing", project_scope["status"])
        self.assertEqual("set_SUPABASE_PROJECT_REF", project_scope["nextAction"])
        self.assertEqual(
            "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=<project_ref>",
            project_scope["scopedMcpUrlTemplate"],
        )
        self.assertTrue(project_scope["readOnlyMode"])
        self.assertFalse(project_scope["projectRefPresent"])
        self.assertTrue(project_scope["projectRefTemplateMode"])
        self.assertEqual(["database", "debugging", "docs"], project_scope["featureGroups"])
        self.assertEqual(0, project_scope["rawSecretPatternHits"])
        self.assertIn("nextActions", result)
        self.assertIn("set_SUPABASE_PROJECT_REF", result["nextActions"])
        self.assertIn("complete_supabase_mcp_oauth_flow", result["nextActions"])
        self.assertIn("install_supabase_cli_or_use_mcp_execute_sql", result["nextActions"])
        self.assertIn("run_supabase_cli_help_discovery", result["nextActions"])
        self.assertIn("link_supabase_cli_project_ref", result["nextActions"])
        self.assertIn("authenticate_supabase_mcp_or_cli", result["nextActions"])
        self.assertIn("run_supabase_context_probe", result["nextActions"])
        self.assertIn("run_supabase_schema_snapshot", result["nextActions"])
        auth_plan = result["authPlan"]
        self.assertTrue(auth_plan["mcpOAuthRequired"])
        self.assertEqual("complete_supabase_mcp_oauth_flow", auth_plan["mcpOAuthAction"])
        self.assertEqual("https://supabase.com/docs/guides/ai-tools/mcp", auth_plan["mcpSetupGuideUrl"])
        self.assertEqual("supabase link --project-ref <project_ref>", auth_plan["cliProjectLinkCommandTemplate"])
        self.assertIn("supabase link --help", auth_plan["cliDiscoveryCommands"])
        self.assertIn("execute_sql", auth_plan["mcpFallbackTools"])
        self.assertIn("project_ref=<project_ref>", auth_plan["scopedMcpUrlTemplate"])
        self.assertEqual("dynamic_client_registration_oauth", auth_plan["defaultHostedAuthMode"])
        self.assertEqual("manual_pat_header_for_ci", auth_plan["manualAuthMode"])
        self.assertEqual(["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"], auth_plan["manualAuthEnvRefs"])
        self.assertEqual("Bearer ${SUPABASE_ACCESS_TOKEN}", auth_plan["manualAuthHeaderTemplate"])
        self.assertEqual("${SUPABASE_PROJECT_REF}", auth_plan["manualProjectRefTemplate"])
        self.assertIn("manual_ci_pat_auth", auth_plan["supportedAuthModes"])
        self.assertIn("development_and_testing_only", auth_plan["securityWarnings"])
        self.assertFalse(auth_plan["manualAuthEnvPresent"]["SUPABASE_PROJECT_REF"])
        self.assertFalse(auth_plan["manualAuthEnvPresent"]["SUPABASE_ACCESS_TOKEN"])
        env_status = auth_plan["manualAuthEnvStatus"]
        self.assertIn(
            {"name": "SUPABASE_PROJECT_REF", "present": False, "sensitive": False},
            env_status,
        )
        self.assertIn(
            {"name": "SUPABASE_ACCESS_TOKEN", "present": False, "sensitive": True},
            env_status,
        )
        redacted_auth_plan = toolbox.redact(auth_plan)
        self.assertIn(
            {"name": "SUPABASE_ACCESS_TOKEN", "present": False, "sensitive": True},
            redacted_auth_plan["manualAuthEnvStatus"],
        )
        self.assertIs(redacted_auth_plan["manualAuthEnvPresent"]["SUPABASE_PROJECT_REF"], False)
        self.assertIs(redacted_auth_plan["manualAuthEnvPresent"]["SUPABASE_ACCESS_TOKEN"], False)
        self.assertNotIn("sbp_", json.dumps(auth_plan, sort_keys=True))
        self.assertNotIn("SUPABASE_ACCESS_TOKEN" + "=", json.dumps(auth_plan, sort_keys=True))

    def test_supabase_mcp_config_treats_project_ref_placeholders_as_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".mcp.json").write_text(
                json.dumps(
                    {
                        "mcpServers": {
                            "supabase": {
                                "type": "http",
                                "url": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=${SUPABASE_PROJECT_REF}",
                            }
                        }
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            old_ref = os.environ.get("SUPABASE_PROJECT_REF")
            try:
                os.environ.pop("SUPABASE_PROJECT_REF", None)
                toolbox_summary = toolbox.supabase_mcp_config_summary(root)
                audit_summary = completion_audit.supabase_mcp_root_config_summary(root)
            finally:
                if old_ref is None:
                    os.environ.pop("SUPABASE_PROJECT_REF", None)
                else:
                    os.environ["SUPABASE_PROJECT_REF"] = old_ref

        self.assertFalse(toolbox_summary["projectScopedMode"])
        self.assertFalse(audit_summary["projectScopedMode"])
        self.assertTrue(toolbox_summary["projectRefTemplateMode"])
        self.assertTrue(audit_summary["projectRefTemplateMode"])
        self.assertFalse(toolbox_summary["projectRefEnvPresent"])
        self.assertFalse(audit_summary["projectRefEnvPresent"])
        self.assertEqual("template_missing_env", toolbox_summary["projectScopeSource"])
        self.assertEqual("template_missing_env", audit_summary["projectScopeSource"])
        project_scope = toolbox.supabase_project_scope_summary(toolbox_summary)
        self.assertEqual("project_ref_missing", project_scope["status"])
        self.assertEqual("set_SUPABASE_PROJECT_REF", project_scope["nextAction"])
        self.assertTrue(project_scope["projectRefTemplateMode"])

    def test_supabase_mcp_config_treats_project_ref_env_template_as_project_scoped_without_value(self):
        fake_ref = "project-ref-should-not-print"
        old_ref = os.environ.get("SUPABASE_PROJECT_REF")
        try:
            os.environ["SUPABASE_PROJECT_REF"] = fake_ref
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / ".mcp.json").write_text(
                    json.dumps(
                        {
                            "mcpServers": {
                                "supabase": {
                                    "type": "http",
                                    "url": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=${SUPABASE_PROJECT_REF}",
                                }
                            }
                        },
                        sort_keys=True,
                    ),
                    encoding="utf-8",
                )

                toolbox_summary = toolbox.supabase_mcp_config_summary(root)
                audit_summary = completion_audit.supabase_mcp_root_config_summary(root)
        finally:
            if old_ref is None:
                os.environ.pop("SUPABASE_PROJECT_REF", None)
            else:
                os.environ["SUPABASE_PROJECT_REF"] = old_ref

        self.assertTrue(toolbox_summary["projectScopedMode"])
        self.assertTrue(audit_summary["projectScopedMode"])
        self.assertTrue(toolbox_summary["projectRefTemplateMode"])
        self.assertTrue(audit_summary["projectRefTemplateMode"])
        self.assertTrue(toolbox_summary["projectRefEnvPresent"])
        self.assertTrue(audit_summary["projectRefEnvPresent"])
        self.assertEqual("env_template", toolbox_summary["projectScopeSource"])
        self.assertEqual("env_template", audit_summary["projectScopeSource"])
        project_scope = toolbox.supabase_project_scope_summary(toolbox_summary)
        self.assertEqual("project_ref_present", project_scope["status"])
        self.assertEqual("run_readonly_schema_snapshot", project_scope["nextAction"])
        self.assertTrue(project_scope["projectRefPresent"])
        self.assertTrue(project_scope["projectRefTemplateMode"])
        self.assertTrue(project_scope["projectRefEnvPresent"])
        rendered = json.dumps(
            {"toolbox": toolbox_summary, "audit": audit_summary, "projectScope": project_scope},
            sort_keys=True,
        )
        self.assertNotIn(fake_ref, rendered)

    def test_supabase_context_probe_can_skip_mcp_network_probe(self):
        result = toolbox.supabase_context_probe(
            {
                "root": str(ROOT),
                "mcp_url": "https://mcp.supabase.com/mcp",
                "timeout_sec": 1,
                "skip_mcp_network_probe": True,
            }
        )

        self.assertTrue(result["ok"])
        self.assertTrue(result["mcp"]["probeSkipped"])
        self.assertFalse(result["mcp"]["reachable"])
        self.assertEqual("mcp_endpoint_probe_skipped", result["mcp"]["decision"])
        self.assertIn(
            "Supabase MCP endpoint probe skipped / rerun without skip_mcp_network_probe before claiming endpoint reachability",
            result["evidence_needed"],
        )
        self.assertNotIn(
            "Supabase MCP endpoint not reachable from this host / verify network and URL",
            result["evidence_needed"],
        )

    def test_supabase_context_probe_reports_mcp_auth_required_as_evidence_needed(self):
        original_mcp_summary = toolbox.supabase_mcp_summary
        try:
            toolbox.supabase_mcp_summary = lambda _url, _timeout: {
                "reachable": True,
                "httpStatus": 403,
                "endpointKind": "supabase_mcp",
                "unauthenticatedExpected": True,
                "decision": "mcp_endpoint_auth_required",
                "failReason": "http-403",
            }
            result = toolbox.supabase_context_probe(
                {
                    "root": str(ROOT),
                    "mcp_url": "https://mcp.supabase.com/mcp",
                    "timeout_sec": 1,
                }
            )
        finally:
            toolbox.supabase_mcp_summary = original_mcp_summary

        self.assertIn(
            "Supabase MCP endpoint auth required / complete OAuth MCP client flow before SQL/project probes",
            result["evidence_needed"],
        )
        self.assertIn("complete_supabase_mcp_oauth_flow", result["nextActions"])

    def test_supabase_context_probe_reports_project_ref_env_presence_without_value(self):
        old_ref = os.environ.get("SUPABASE_PROJECT_REF")
        old_token = os.environ.get("SUPABASE_ACCESS_TOKEN")
        fake_ref = "project-ref-should-not-print"
        fake_token = "sbp_" + "x" * 40
        try:
            os.environ["SUPABASE_PROJECT_REF"] = fake_ref
            os.environ["SUPABASE_ACCESS_TOKEN"] = fake_token
            result = toolbox.supabase_context_probe(
                {
                    "root": str(ROOT),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
        finally:
            if old_ref is None:
                os.environ.pop("SUPABASE_PROJECT_REF", None)
            else:
                os.environ["SUPABASE_PROJECT_REF"] = old_ref
            if old_token is None:
                os.environ.pop("SUPABASE_ACCESS_TOKEN", None)
            else:
                os.environ["SUPABASE_ACCESS_TOKEN"] = old_token

        auth_plan = result["authPlan"]
        rendered = json.dumps(result, sort_keys=True)
        self.assertTrue(result["mcpConfig"]["projectScopedMode"])
        self.assertTrue(result["mcpConfig"]["projectRefTemplateMode"])
        self.assertTrue(result["mcpConfig"]["projectRefEnvPresent"])
        self.assertEqual("env_template", result["mcpConfig"]["projectScopeSource"])
        self.assertEqual("project_ref_present", result["projectScope"]["status"])
        self.assertEqual("run_readonly_schema_snapshot", result["projectScope"]["nextAction"])
        self.assertTrue(result["projectScope"]["projectRefPresent"])
        self.assertTrue(result["projectScope"]["projectRefEnvPresent"])
        self.assertNotIn(
            "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
            result["evidence_needed"],
        )
        self.assertNotIn("set_SUPABASE_PROJECT_REF", result["nextActions"])
        self.assertTrue(auth_plan["manualAuthEnvPresent"]["SUPABASE_PROJECT_REF"])
        self.assertTrue(auth_plan["manualAuthEnvPresent"]["SUPABASE_ACCESS_TOKEN"])
        redacted_auth_plan = toolbox.redact(auth_plan)
        self.assertIs(redacted_auth_plan["manualAuthEnvPresent"]["SUPABASE_PROJECT_REF"], True)
        self.assertIs(redacted_auth_plan["manualAuthEnvPresent"]["SUPABASE_ACCESS_TOKEN"], True)
        self.assertIn(
            {"name": "SUPABASE_PROJECT_REF", "present": True, "sensitive": False},
            auth_plan["manualAuthEnvStatus"],
        )
        self.assertIn(
            {"name": "SUPABASE_ACCESS_TOKEN", "present": True, "sensitive": True},
            auth_plan["manualAuthEnvStatus"],
        )
        self.assertIn("SUPABASE_PROJECT_REF", result["envPresent"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", result["envPresent"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", result["sensitiveEnvPresent"])
        self.assertNotIn(fake_ref, rendered)
        self.assertNotIn(fake_token, rendered)

    def test_supabase_context_probe_reports_read_only_schema_snapshot_plan(self):
        result = toolbox.supabase_context_probe(
            {
                "root": str(ROOT),
                "mcp_url": "http://127.0.0.1:1/mcp",
                "timeout_sec": 1,
            }
        )

        plan = result["dbSnapshotPlan"]
        rendered = json.dumps(plan, sort_keys=True)
        query_names = {query["name"] for query in plan["sqlQueries"]}

        self.assertTrue(plan["readOnly"])
        self.assertFalse(plan["mutationAllowed"])
        self.assertEqual("data/db-gap-report/supabase-schema-snapshot.json", plan["recommendedOutputPath"])
        self.assertIn("supabase db query --help", plan["discoveryCommands"])
        self.assertIn("supabase db advisors --help", plan["discoveryCommands"])
        self.assertEqual(">=2.79.0", plan["cliVersionRequirements"]["supabase db query"])
        self.assertEqual(">=2.81.3", plan["cliVersionRequirements"]["supabase db advisors"])
        self.assertEqual("execute_sql", plan["cliFallbackContract"]["supabase db query"]["mcpTool"])
        self.assertEqual("get_advisors", plan["cliFallbackContract"]["supabase db advisors"]["mcpTool"])
        self.assertIn("cli_missing_or_below_minimum", plan["cliFallbackContract"]["supabase db query"]["when"])
        self.assertIn("cli_missing_or_below_minimum", plan["cliFallbackContract"]["supabase db advisors"]["when"])
        docs_refs = {entry["id"]: entry["url"] for entry in plan["docsRefs"]}
        breaking_changes = {entry["id"]: entry for entry in plan["breakingChanges"]}
        security_contracts = set(plan["securityContracts"])
        self.assertEqual(
            "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
            docs_refs["data-api-grants-breaking-change"],
        )
        self.assertEqual("2026-04-28", breaking_changes["data-api-explicit-grants"]["effectiveDate"])
        self.assertIn("https://supabase.com/docs/guides/ai-tools/mcp", docs_refs["supabase-mcp"])
        self.assertIn("https://supabase.com/docs/guides/getting-started/api-keys", docs_refs["api-keys"])
        self.assertIn("https://supabase.com/docs/guides/api/securing-your-api", docs_refs["securing-data-api"])
        self.assertIn("https://supabase.com/docs/guides/security/product-security", docs_refs["product-security"])
        self.assertIn("https://supabase.com/docs/guides/database/postgres/row-level-security", docs_refs["rls"])
        self.assertIn("data_api_grants_control_reachability", security_contracts)
        self.assertIn("rls_controls_visible_rows_after_grant", security_contracts)
        self.assertIn("mcp_project_ref_should_scope_access", security_contracts)
        self.assertIn("secret_keys_backend_only", security_contracts)
        self.assertIn("information_schema.tables", rendered)
        self.assertIn("pg_policies", rendered)
        self.assertIn("pg_extension", rendered)
        self.assertIn("data_api_role_grants", query_names)
        self.assertIn("exposed_tables_without_rls", query_names)
        self.assertIn("rls_user_metadata_policies", query_names)
        self.assertIn("update_policies_without_select_policy", query_names)
        self.assertIn("storage_upsert_policy_gaps", query_names)
        self.assertIn("views_missing_security_invoker", query_names)
        self.assertIn("exposed_security_definer_functions", query_names)
        self.assertIn("information_schema.role_table_grants", rendered)
        self.assertIn("anon", rendered)
        self.assertIn("authenticated", rendered)
        self.assertIn("raw_user_meta_data", rendered)
        self.assertIn("user_metadata", rendered)
        self.assertIn("storage", rendered)
        self.assertIn("objects", rendered)
        self.assertIn("security_invoker=true", rendered)
        self.assertIn("prosecdef", rendered)
        self.assertNotIn("apply_migration", rendered)
        self.assertNotIn("service_role", rendered)

    def test_supabase_context_probe_manifest_exposes_schema_snapshot_plan(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        supabase_tool = next(tool for tool in manifest["tools"] if tool["name"] == "supabase_context_probe")

        self.assertTrue(supabase_tool["readOnly"])
        output_properties = supabase_tool["output_schema"]["properties"]
        self.assertIn("dbSnapshotPlan", output_properties)
        self.assertIn("projectScope", output_properties)
        self.assertIn("projectRefTemplateMode", output_properties["mcpConfig"]["properties"])
        self.assertIn("projectRefTemplateMode", output_properties["projectScope"]["properties"])
        self.assertIn("authPlan", supabase_tool["output_schema"]["properties"])
        self.assertIn("nextActions", supabase_tool["output_schema"]["properties"])

    def test_supabase_schema_snapshot_writes_redacted_plan_artifact_without_cli(self):
        old_token = os.environ.get("SUPABASE_ACCESS_TOKEN")
        fake_token = "unit-test-" + "supabase-" + "token"
        os.environ["SUPABASE_ACCESS_TOKEN"] = fake_token
        try:
            with tempfile.TemporaryDirectory() as tmp:
                output_path = Path(tmp) / "supabase-schema-snapshot.json"
                result = toolbox.supabase_schema_snapshot(
                    {
                        "root": str(ROOT),
                        "output_path": str(output_path),
                        "mcp_url": "http://127.0.0.1:1/mcp",
                        "timeout_sec": 1,
                    }
                )

                artifact = json.loads(output_path.read_text(encoding="utf-8"))
                sql_bundle = output_path.with_name("supabase-readonly-snapshot.sql")
                sql_text = sql_bundle.read_text(encoding="utf-8")
                result_template = output_path.with_name("supabase-query-results.template.json")
                result_template_data = json.loads(result_template.read_text(encoding="utf-8"))
                collection_packet = output_path.with_name("supabase-execute-sql-collection.packet.json")
                collection_packet_data = json.loads(collection_packet.read_text(encoding="utf-8"))
        finally:
            if old_token is None:
                os.environ.pop("SUPABASE_ACCESS_TOKEN", None)
            else:
                os.environ["SUPABASE_ACCESS_TOKEN"] = old_token

        rendered = json.dumps({"result": result, "artifact": artifact}, sort_keys=True)
        self.assertTrue(result["ok"])
        self.assertTrue(result["readOnly"])
        self.assertFalse(result["mutationAllowed"])
        self.assertFalse(result["schemaSnapshotAvailable"])
        self.assertEqual("supabase_schema_snapshot_evidence_needed", result["decision"])
        self.assertEqual(0, result["rawSecretPatternHits"])
        self.assertEqual("awx.mcp.supabase_schema_snapshot.v1", artifact["schemaVersion"])
        self.assertTrue(artifact.get("generatedAt"))
        self.assertTrue(result.get("generatedAt"))
        self.assertTrue(result["apiKeysDocs"])
        self.assertTrue(result["secretKeysBackendOnly"])
        self.assertGreaterEqual(len(result["docsRefs"]), 5)
        self.assertGreaterEqual(len(result["securityContracts"]), 7)
        self.assertIn("run_supabase_get_advisors_readonly", result["nextActions"])
        self.assertEqual(">=2.79.0", result["cliVersionRequirements"]["supabase db query"])
        self.assertEqual(">=2.81.3", result["cliVersionRequirements"]["supabase db advisors"])
        self.assertEqual("execute_sql", result["cliFallbackContract"]["supabase db query"]["mcpTool"])
        self.assertEqual("get_advisors", result["cliFallbackContract"]["supabase db advisors"]["mcpTool"])
        self.assertEqual("data/db-gap-report/supabase-schema-snapshot.json", artifact["recommendedOutputPath"])
        self.assertEqual(">=2.79.0", artifact["dbSnapshotPlan"]["cliVersionRequirements"]["supabase db query"])
        self.assertEqual(">=2.81.3", artifact["dbSnapshotPlan"]["cliVersionRequirements"]["supabase db advisors"])
        self.assertEqual("execute_sql", artifact["dbSnapshotPlan"]["cliFallbackContract"]["supabase db query"]["mcpTool"])
        self.assertEqual("get_advisors", artifact["dbSnapshotPlan"]["cliFallbackContract"]["supabase db advisors"]["mcpTool"])
        self.assertFalse(artifact["mutationAllowed"])
        advisor_plan = artifact["advisors"]["collectionPlan"]
        self.assertFalse(artifact["advisors"]["available"])
        self.assertEqual([], artifact["advisors"]["rows"])
        self.assertTrue(advisor_plan["readOnly"])
        self.assertFalse(advisor_plan["mutationAllowed"])
        self.assertEqual("get_advisors", advisor_plan["mcpTool"])
        self.assertEqual("debugging", advisor_plan["featureGroup"])
        self.assertEqual("SUPABASE_PROJECT_REF", advisor_plan["projectRefEnv"])
        self.assertEqual("SUPABASE_ACCESS_TOKEN", advisor_plan["authEnv"])
        self.assertIn("supabase-advisors.json", advisor_plan["resultPathRecommendation"])
        self.assertEqual("project_ref_missing", artifact["projectScope"]["status"])
        self.assertEqual("set_SUPABASE_PROJECT_REF", artifact["projectScope"]["nextAction"])
        self.assertIn("project_ref=<project_ref>", artifact["projectScope"]["scopedMcpUrlTemplate"])
        self.assertEqual(artifact["nextActions"], result["nextActions"])
        self.assertIn("set_SUPABASE_PROJECT_REF", result["nextActions"])
        self.assertNotIn("set_project_ref_in_mcp_config", result["nextActions"])
        self.assertIn("complete_supabase_mcp_oauth_flow", result["nextActions"])
        self.assertIn("install_supabase_cli_or_use_mcp_execute_sql", result["nextActions"])
        self.assertIn("run_supabase_cli_help_discovery", result["nextActions"])
        self.assertIn("link_supabase_cli_project_ref", result["nextActions"])
        self.assertIn("authenticate_supabase_mcp_or_cli", result["nextActions"])
        self.assertIn("run_supabase_readonly_sql_bundle", result["nextActions"])
        self.assertIn("import_supabase_query_results", result["nextActions"])
        self.assertIn("populate_supabase_query_results_file", result["nextActions"])
        self.assertIn("rerun_supabase_schema_snapshot_import", result["nextActions"])
        self.assertIn("rerun_db_gap_scanner", result["nextActions"])
        self.assertGreater(
            result["nextActions"].index("rerun_db_gap_scanner"),
            result["nextActions"].index("rerun_supabase_schema_snapshot_import"),
        )
        self.assertIn("pathHash=", result["snapshotPath"])
        self.assertIn("pathLength=", result["snapshotPath"])
        self.assertEqual(toolbox.stable_hash(str(output_path)), result["snapshotPathHash"])
        self.assertEqual(len(str(output_path)), result["snapshotPathLength"])
        self.assertIn("pathHash=", result["sqlBundlePath"])
        self.assertIn("pathLength=", result["sqlBundlePath"])
        self.assertEqual(toolbox.stable_hash(str(sql_bundle)), result["sqlBundlePathHash"])
        self.assertEqual(0, result["sqlBundleSecretPatternHits"])
        self.assertIn("pathHash=", artifact["sqlBundlePath"])
        self.assertIn("pathLength=", artifact["sqlBundlePath"])
        self.assertEqual(toolbox.stable_hash(str(sql_bundle)), artifact["sqlBundlePathHash"])
        self.assertIn("pathHash=", result["resultTemplatePath"])
        self.assertIn("pathLength=", result["resultTemplatePath"])
        self.assertEqual(toolbox.stable_hash(str(result_template)), result["resultTemplatePathHash"])
        self.assertEqual(0, result["resultTemplateSecretPatternHits"])
        self.assertIn("pathHash=", artifact["resultTemplatePath"])
        self.assertIn("pathLength=", artifact["resultTemplatePath"])
        self.assertEqual(toolbox.stable_hash(str(result_template)), artifact["resultTemplatePathHash"])
        self.assertIn("pathHash=", result["collectionPacketPath"])
        self.assertIn("pathLength=", result["collectionPacketPath"])
        self.assertEqual(toolbox.stable_hash(str(collection_packet)), result["collectionPacketPathHash"])
        self.assertEqual(0, result["collectionPacketSecretPatternHits"])
        self.assertIn("pathHash=", artifact["collectionPacketPath"])
        self.assertIn("pathLength=", artifact["collectionPacketPath"])
        self.assertEqual(toolbox.stable_hash(str(collection_packet)), artifact["collectionPacketPathHash"])
        self.assertNotIn(str(output_path), rendered)
        self.assertNotIn(str(sql_bundle), rendered)
        self.assertNotIn(str(result_template), rendered)
        self.assertNotIn(str(collection_packet), rendered)
        self.assertEqual("awx.mcp.supabase_query_results_template.v1", result_template_data["schemaVersion"])
        self.assertTrue(result_template_data["readOnly"])
        self.assertFalse(result_template_data["mutationAllowed"])
        self.assertEqual("supabase_schema_snapshot_import", result_template_data["importTool"])
        collection_plan = result_template_data["collectionPlan"]
        self.assertEqual("get_advisors", result_template_data["advisorCollectionPlan"]["mcpTool"])
        self.assertTrue(collection_plan["readOnly"])
        self.assertFalse(collection_plan["mutationAllowed"])
        self.assertEqual("execute_sql", collection_plan["mcpTool"])
        self.assertEqual("SUPABASE_PROJECT_REF", collection_plan["projectRefEnv"])
        self.assertEqual("SUPABASE_ACCESS_TOKEN", collection_plan["authEnv"])
        self.assertEqual("supabase_schema_snapshot_import", collection_plan["importTool"])
        self.assertIn("supabase-readonly-snapshot.sql", collection_plan["sourceSqlBundlePath"])
        self.assertIn("supabase-query-results.json", collection_plan["resultFileRecommendation"])
        self.assertGreaterEqual(len(collection_plan["supportedResultShapes"]), 4)
        self.assertIn("named MCP execute_sql transcript entries", collection_plan["supportedResultShapes"])
        self.assertIn("named execute_sql_result wrapper entries", collection_plan["supportedResultShapes"])
        self.assertIn("records[] or data.rows[] wrapper entries", collection_plan["supportedResultShapes"])
        self.assertIn("named empty rows[] result sets", collection_plan["supportedResultShapes"])
        self.assertIn("redacted MCP error transcripts", collection_plan["supportedResultShapes"])
        self.assertIn("project_ref=${SUPABASE_PROJECT_REF}", collection_plan["mcpEndpointTemplate"])
        self.assertNotIn("Bearer", json.dumps(collection_plan, sort_keys=True))
        self.assertGreaterEqual(len(result_template_data["queries"]), 10)
        self.assertEqual("schemas_and_tables", result_template_data["queries"][0]["name"])
        self.assertIn("sqlHash", result_template_data["queries"][0])
        self.assertEqual(
            {"name": "schemas_and_tables", "rows": []},
            result_template_data["queries"][0]["collectAs"],
        )
        self.assertEqual("-- schemas_and_tables", result_template_data["queries"][0]["sourceSqlBundleMarker"])
        self.assertEqual(
            "awx.mcp.supabase_execute_sql_collection_packet.v1",
            collection_packet_data["schemaVersion"],
        )
        self.assertTrue(collection_packet_data["readOnly"])
        self.assertFalse(collection_packet_data["mutationAllowed"])
        self.assertEqual("execute_sql", collection_packet_data["mcpTool"])
        self.assertEqual("query", collection_packet_data["executeSqlInputField"])
        self.assertEqual("one_query_per_execute_sql_call", collection_packet_data["executionMode"])
        self.assertEqual("SUPABASE_PROJECT_REF", collection_packet_data["projectRefEnv"])
        self.assertEqual("SUPABASE_ACCESS_TOKEN", collection_packet_data["authEnv"])
        self.assertEqual("supabase_schema_snapshot_import", collection_packet_data["importTool"])
        self.assertEqual("get_advisors", collection_packet_data["advisorCollection"]["mcpTool"])
        self.assertEqual("debugging", collection_packet_data["advisorCollection"]["featureGroup"])
        self.assertEqual(len(collection_packet_data["queries"]), collection_packet_data["queryCount"])
        self.assertIn("execute_each_query_once", collection_packet_data["nextActions"])
        self.assertIn("collect_get_advisors_rows", collection_packet_data["nextActions"])
        self.assertIn("run_supabase_schema_snapshot_import", collection_packet_data["nextActions"])
        self.assertIn("supabase-query-results.json", collection_packet_data["resultPathRecommendation"])
        self.assertEqual(len(result_template_data["queries"]), len(collection_packet_data["queries"]))
        self.assertEqual("schemas_and_tables", collection_packet_data["queries"][0]["name"])
        self.assertEqual(result_template_data["queries"][0]["sqlHash"], collection_packet_data["queries"][0]["sqlHash"])
        self.assertIn("information_schema.tables", collection_packet_data["queries"][0]["sql"])
        self.assertEqual(collection_packet_data["queries"][0]["sql"], collection_packet_data["queries"][0]["executeSqlInput"]["query"])
        self.assertEqual(
            {"name": "schemas_and_tables", "rows": []},
            collection_packet_data["queries"][0]["collectAs"],
        )
        self.assertNotIn("Bearer", json.dumps(collection_packet_data, sort_keys=True))
        self.assertNotIn(fake_token, json.dumps(collection_packet_data, sort_keys=True))
        self.assertNotIn("service_role", json.dumps(collection_packet_data, sort_keys=True))
        self.assertNotIn("information_schema.tables", json.dumps(result_template_data, sort_keys=True))
        self.assertNotIn("pg_policies", json.dumps(result_template_data, sort_keys=True))
        self.assertIn("START TRANSACTION READ ONLY", sql_text)
        self.assertIn("-- Docs: supabase-mcp https://supabase.com/docs/guides/ai-tools/mcp", sql_text)
        self.assertIn("-- Breaking change: data-api-explicit-grants effective=2026-04-28", sql_text)
        self.assertIn("-- Security contract: data_api_grants_control_reachability", sql_text)
        self.assertIn("-- data_api_role_grants", sql_text)
        self.assertIn("-- rls_user_metadata_policies", sql_text)
        self.assertIn("-- storage_upsert_policy_gaps", sql_text)
        self.assertIn("COMMIT", sql_text)
        self.assertIn("information_schema.tables", rendered)
        self.assertIn("pg_policies", rendered)
        self.assertIn("supabase CLI missing", " ".join(result["evidence_needed"]))
        self.assertNotIn(fake_token, rendered)
        self.assertNotIn(fake_token, sql_text)

    def test_supabase_schema_snapshot_import_rejects_unfilled_result_template(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            result_template = snapshot_path.with_name("supabase-query-results.template.json")

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(result_template),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertTrue(result["ok"])
        self.assertFalse(result["schemaSnapshotAvailable"])
        self.assertEqual(0, result["importedResultCount"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertIn("unfilled result template", " ".join(result["evidence_needed"]))
        self.assertEqual(result["nextActions"], artifact["nextActions"])
        self.assertIn("set_SUPABASE_PROJECT_REF", result["nextActions"])
        self.assertNotIn("set_project_ref_in_mcp_config", result["nextActions"])
        self.assertIn("populate_supabase_query_results_file", result["nextActions"])
        self.assertIn("rerun_supabase_schema_snapshot_import", result["nextActions"])
        self.assertIn("rerun_db_gap_scanner", result["nextActions"])
        self.assertGreater(
            result["nextActions"].index("rerun_db_gap_scanner"),
            result["nextActions"].index("rerun_supabase_schema_snapshot_import"),
        )
        self.assertEqual("evidence_needed", artifact["snapshotImport"]["status"])
        self.assertEqual("object", result["inputShape"]["payloadKind"])
        self.assertGreaterEqual(result["inputShape"]["topLevelKeyCount"], 1)
        self.assertEqual(0, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(result["inputShape"], artifact["snapshotImport"]["inputShape"])
        self.assertNotIn("schemas_and_tables", json.dumps(result["inputShape"], sort_keys=True))
        self.assertNotIn("snapshots", artifact["snapshotImport"])

    def test_supabase_schema_snapshot_import_defaults_to_snapshot_result_template_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual("object", result["inputShape"]["payloadKind"])
        self.assertEqual(0, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(result["inputShape"], artifact["snapshotImport"]["inputShape"])
        self.assertIn("unfilled result template", " ".join(result["evidence_needed"]))

    def test_supabase_schema_snapshot_is_registered_for_stdio_and_manifest(self):
        self.assertIn("supabase_schema_snapshot", toolbox.TOOL_ALIASES.values())
        self.assertIn("supabase_schema_snapshot", stdio_server.HANDLERS)

        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        snapshot_tool = next(tool for tool in manifest["tools"] if tool["name"] == "supabase_schema_snapshot")

        self.assertTrue(snapshot_tool["readOnly"])
        self.assertIn("supabase.schema_snapshot", snapshot_tool["aliases"])
        self.assertIn("snapshotPath", snapshot_tool["output_schema"]["properties"])
        self.assertIn("sqlBundlePath", snapshot_tool["output_schema"]["properties"])
        self.assertIn("sqlBundleSecretPatternHits", snapshot_tool["output_schema"]["properties"])
        self.assertIn("resultTemplatePath", snapshot_tool["output_schema"]["properties"])
        self.assertIn("resultTemplateSecretPatternHits", snapshot_tool["output_schema"]["properties"])
        self.assertIn("collectionPacketPath", snapshot_tool["output_schema"]["properties"])
        self.assertIn("collectionPacketSecretPatternHits", snapshot_tool["output_schema"]["properties"])
        self.assertIn("docsRefs", snapshot_tool["output_schema"]["properties"])
        self.assertIn("securityContracts", snapshot_tool["output_schema"]["properties"])
        self.assertIn("apiKeysDocs", snapshot_tool["output_schema"]["properties"])
        self.assertIn("secretKeysBackendOnly", snapshot_tool["output_schema"]["properties"])
        self.assertIn("projectScope", snapshot_tool["output_schema"]["properties"])
        self.assertIn(
            "projectRefTemplateMode",
            snapshot_tool["output_schema"]["properties"]["projectScope"]["properties"],
        )
        self.assertIn("authPlan", snapshot_tool["output_schema"]["properties"])
        self.assertIn("nextActions", snapshot_tool["output_schema"]["properties"])
        self.assertIn("sqlBundlePath", snapshot_tool["output_schema"]["required"])
        import_tool = next(tool for tool in manifest["tools"] if tool["name"] == "supabase_schema_snapshot_import")
        self.assertIn("nextActions", import_tool["output_schema"]["properties"])
        self.assertIn("schemaSnapshotComplete", import_tool["output_schema"]["properties"])
        self.assertIn("resultSetComplete", import_tool["output_schema"]["properties"])

    def test_supabase_schema_snapshot_import_redacts_and_summarizes_query_results(self):
        fake_secret = "sk-" + ("A" * 24)
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-results.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "name": "schemas_and_tables",
                                "rows": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "notes",
                                        "table_type": "BASE TABLE",
                                        "accidental_secret": fake_secret,
                                    }
                                ],
                            },
                            {
                                "name": "exposed_tables_without_rls",
                                "rows": [{"schemaname": "public", "tablename": "notes", "exposed_roles": ["anon"]}],
                            },
                            {
                                "name": "rls_user_metadata_policies",
                                "rows": [{"schemaname": "public", "tablename": "profiles", "policyname": "bad"}],
                            },
                            {
                                "name": "storage_upsert_policy_gaps",
                                "rows": [{"roles": ["authenticated"], "commands": ["INSERT"]}],
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact_text = snapshot_path.read_text(encoding="utf-8")
            artifact = json.loads(artifact_text)

        self.assertTrue(result["ok"])
        self.assertTrue(result["schemaSnapshotAvailable"])
        self.assertFalse(result["schemaSnapshotComplete"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(4, result["importedResultCount"])
        self.assertEqual(1, result["rawSecretPatternHits"])
        self.assertFalse(result["storedRawRows"])
        self.assertEqual(1, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertEqual(1, result["riskSummary"]["metadataPolicyRisk"])
        self.assertEqual(1, result["riskSummary"]["storageUpsertPolicyGap"])
        self.assertTrue(artifact["schemaSnapshotAvailable"])
        self.assertFalse(artifact["schemaSnapshotComplete"])
        self.assertFalse(artifact["snapshotImport"]["resultSetComplete"])
        self.assertEqual("partial_evidence_needed", artifact["snapshotImport"]["status"])
        self.assertEqual(4, len(artifact["snapshots"]))
        self.assertIn("rowHashes", artifact["snapshots"][0])
        self.assertNotIn("rows", artifact["snapshots"][0])
        self.assertNotIn(fake_secret, artifact_text)

    def test_supabase_schema_snapshot_import_marks_partial_result_set_as_evidence_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-results.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "name": "schemas_and_tables",
                                "rows": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "notes",
                                        "table_type": "BASE TABLE",
                                    }
                                ],
                            }
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertTrue(result["ok"])
        self.assertTrue(result["schemaSnapshotAvailable"])
        self.assertFalse(result["schemaSnapshotComplete"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(1, result["importedResultCount"])
        self.assertGreater(result["missingResultCount"], 0)
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual("partial_evidence_needed", artifact["snapshotImport"]["status"])
        self.assertFalse(artifact["snapshotImport"]["resultSetComplete"])
        self.assertIn("missing expected Supabase result sets", " ".join(result["evidence_needed"]))

    def test_supabase_schema_snapshot_import_accepts_mcp_style_data_payloads(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-results.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "name": "views_missing_security_invoker",
                                "result": {
                                    "data": [
                                        {
                                            "schemaname": "public",
                                            "viewname": "unsafe_view",
                                        }
                                    ]
                                },
                            },
                            {
                                "name": "exposed_security_definer_functions",
                                "data": [
                                    {
                                        "function_schema": "public",
                                        "function_name": "unsafe_fn",
                                    }
                                ],
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["viewsMissingSecurityInvoker"])
        self.assertEqual(1, result["riskSummary"]["exposedSecurityDefinerFunctions"])
        self.assertIn("views_missing_security_invoker", names)
        self.assertIn("exposed_security_definer_functions", names)
        self.assertNotIn("data", names)

    def test_supabase_schema_snapshot_import_accepts_wrapped_results_array_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-wrapped-results-array.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "result": {
                                    "name": "schemas_and_tables",
                                    "data": {
                                        "rows": [
                                            {
                                                "table_schema": "public",
                                                "table_name": "chat_sessions",
                                                "table_type": "BASE TABLE",
                                            }
                                        ]
                                    },
                                }
                            },
                            {
                                "structuredContent": {
                                    "name": "exposed_tables_without_rls",
                                    "records": [
                                        {
                                            "schemaname": "public",
                                            "tablename": "chat_sessions",
                                        }
                                    ],
                                }
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(2, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(1, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertIn("schemas_and_tables", names)
        self.assertIn("exposed_tables_without_rls", names)
        rendered = json.dumps(artifact["snapshots"], sort_keys=True)
        self.assertNotIn("rows", rendered)
        self.assertNotIn("records", rendered)

    def test_supabase_schema_snapshot_import_preserves_named_empty_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-empty-named-results.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "structuredContent": {
                                    "name": "exposed_tables_without_rls",
                                    "rows": [],
                                }
                            },
                            {
                                "result": {
                                    "structuredContent": {
                                        "name": "views_missing_security_invoker",
                                        "records": [],
                                    }
                                }
                            },
                            {
                                "name": "storage_upsert_policy_gaps",
                                "execute_sql_result": [],
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        snapshots = {snapshot["name"]: snapshot for snapshot in artifact["snapshots"]}
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(3, result["importedResultCount"])
        self.assertEqual(3, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(0, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertEqual(0, result["riskSummary"]["viewsMissingSecurityInvoker"])
        self.assertEqual(0, result["riskSummary"]["storageUpsertPolicyGap"])
        self.assertIn("exposed_tables_without_rls", snapshots)
        self.assertIn("views_missing_security_invoker", snapshots)
        self.assertIn("storage_upsert_policy_gaps", snapshots)
        self.assertEqual(0, snapshots["exposed_tables_without_rls"]["rowCount"])
        self.assertEqual(0, snapshots["views_missing_security_invoker"]["rowCount"])
        self.assertEqual(0, snapshots["storage_upsert_policy_gaps"]["rowCount"])
        self.assertNotIn("rows", snapshots)
        rendered = json.dumps(artifact["snapshots"], sort_keys=True)
        self.assertNotIn("execute_sql_result", rendered)
        self.assertNotIn("records", rendered)

    def test_supabase_schema_snapshot_import_accepts_top_level_result_arrays(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-results-array.json"
            results_path.write_text(
                json.dumps(
                    [
                        {
                            "name": "update_policies_without_select_policy",
                            "rows": [
                                {
                                    "schemaname": "public",
                                    "tablename": "profiles",
                                    "policyname": "update_own_profile",
                                }
                            ],
                        },
                        {
                            "name": "schemas_and_tables",
                            "rows": [
                                {
                                    "table_schema": "public",
                                    "table_name": "profiles",
                                }
                            ],
                        },
                    ],
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["updateSelectPolicyGap"])
        self.assertIn("update_policies_without_select_policy", names)
        self.assertIn("schemas_and_tables", names)
        self.assertNotIn("rows", json.dumps(artifact["snapshots"], sort_keys=True))

    def test_supabase_schema_snapshot_import_accepts_json_rpc_structured_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-json-rpc-structured-content.json"
            results_path.write_text(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": 7,
                        "result": {
                            "structuredContent": {
                                "results": [
                                    {
                                        "name": "exposed_tables_without_rls",
                                        "rows": [
                                            {
                                                "schemaname": "public",
                                                "tablename": "notes",
                                            }
                                        ],
                                    },
                                    {
                                        "name": "storage_upsert_policy_gaps",
                                        "rows": [
                                            {
                                                "schema": "storage",
                                                "table": "objects",
                                            }
                                        ],
                                    },
                                ]
                            }
                        },
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertEqual(1, result["riskSummary"]["storageUpsertPolicyGap"])
        self.assertIn("exposed_tables_without_rls", names)
        self.assertIn("storage_upsert_policy_gaps", names)
        self.assertNotIn("rows", json.dumps(artifact["snapshots"], sort_keys=True))

    def test_supabase_schema_snapshot_import_accepts_mcp_content_text_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-mcp-content-text.json"
            results_path.write_text(
                json.dumps(
                    {
                        "content": [
                            {
                                "type": "text",
                                "text": json.dumps(
                                    {
                                        "name": "views_missing_security_invoker",
                                        "rows": [
                                            {
                                                "schemaname": "public",
                                                "viewname": "unsafe_view",
                                            }
                                        ],
                                    },
                                    sort_keys=True,
                                ),
                            }
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(1, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["viewsMissingSecurityInvoker"])
        self.assertIn("views_missing_security_invoker", names)
        self.assertNotIn("rows", json.dumps(artifact["snapshots"], sort_keys=True))

    def test_supabase_schema_snapshot_import_accepts_named_mcp_execute_sql_transcripts(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-execute-sql-transcript.json"
            results_path.write_text(
                json.dumps(
                    [
                        {
                            "name": "schemas_and_tables",
                            "tool": "execute_sql",
                            "arguments": {"sqlHash": "hash-only-no-raw-sql"},
                            "result": {
                                "content": [
                                    {
                                        "type": "text",
                                        "text": json.dumps(
                                            [
                                                {
                                                    "table_schema": "public",
                                                    "table_name": "notes",
                                                }
                                            ],
                                            sort_keys=True,
                                        ),
                                    }
                                ]
                            },
                        },
                        {
                            "name": "views_missing_security_invoker",
                            "tool": "execute_sql",
                            "result": {
                                "content": [
                                    {
                                        "type": "text",
                                        "text": json.dumps(
                                            {
                                                "data": [
                                                    {
                                                        "schemaname": "public",
                                                        "viewname": "unsafe_view",
                                                    }
                                                ]
                                            },
                                            sort_keys=True,
                                        ),
                                    }
                                ]
                            },
                        },
                    ],
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["viewsMissingSecurityInvoker"])
        self.assertEqual(2, result["inputShape"]["candidateResultItemCount"])
        self.assertEqual(2, result["inputShape"]["supportedResultItemCount"])
        self.assertIn("schemas_and_tables", names)
        self.assertIn("views_missing_security_invoker", names)
        rendered = json.dumps(artifact["snapshots"], sort_keys=True)
        self.assertNotIn("rows", rendered)
        self.assertNotIn("execute_sql", rendered)

    def test_supabase_schema_snapshot_import_reports_mcp_error_transcripts(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-mcp-error-transcript.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "name": "schemas_and_tables",
                                "isError": True,
                                "content": [
                                    {
                                        "type": "text",
                                        "text": (
                                            "permission denied for table private_notes where token "
                                            "sk-" "THIS_SHOULD_NOT_LEAK_123456789"
                                        ),
                                    }
                                ],
                            },
                            {
                                "name": "rls_and_table_flags",
                                "result": {
                                    "error": {
                                        "code": "PGRST301",
                                        "message": "JWT expired",
                                    }
                                },
                            },
                            {
                                "jsonrpc": "2.0",
                                "id": 9,
                                "error": {
                                    "code": -32000,
                                    "message": "project_ref missing",
                                },
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(0, result["importedResultCount"])
        self.assertEqual(3, result["mcpErrorCount"])
        self.assertEqual(result["mcpErrorCount"], artifact["snapshotImport"]["mcpErrorCount"])
        self.assertIn("PGRST301", result["mcpErrorCodes"])
        self.assertIn("-32000", result["mcpErrorCodes"])
        self.assertIn("mcp_error", result["mcpErrorCodes"])
        self.assertIn("Supabase MCP returned error transcripts", " ".join(result["evidence_needed"]))
        rendered = json.dumps(result, sort_keys=True) + json.dumps(artifact, sort_keys=True)
        self.assertNotIn("sk-" + "THIS_SHOULD_NOT_LEAK", rendered)
        self.assertNotIn("permission denied for table private_notes", rendered)

    def test_supabase_schema_snapshot_import_accepts_execute_sql_result_wrappers(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-execute-sql-result-wrapper.json"
            results_path.write_text(
                json.dumps(
                    [
                        {
                            "name": "schemas_and_tables",
                            "execute_sql_result": {
                                "data": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "notes",
                                    }
                                ]
                            },
                        },
                        {
                            "name": "exposed_tables_without_rls",
                            "execute_sql_result": [
                                {
                                    "schemaname": "public",
                                    "tablename": "notes",
                                }
                            ],
                        },
                    ],
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(2, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(1, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertIn("schemas_and_tables", names)
        self.assertIn("exposed_tables_without_rls", names)
        rendered = json.dumps(artifact["snapshots"], sort_keys=True)
        self.assertNotIn("rows", rendered)
        self.assertNotIn("execute_sql_result", rendered)

    def test_supabase_schema_snapshot_import_accepts_advisor_result_wrappers(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-execute-sql-result-wrapper.json"
            results_path.write_text(
                json.dumps(
                    [
                        {
                            "name": "schemas_and_tables",
                            "execute_sql_result": {
                                "data": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "notes",
                                    }
                                ]
                            },
                        },
                    ],
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            advisors_path = Path(tmp) / "supabase-advisors.json"
            advisors_path.write_text(
                json.dumps(
                    {
                        "structuredContent": {
                            "advisors": [
                                {
                                    "category": "security",
                                    "level": "WARN",
                                    "name": "rls disabled on public.notes",
                                    "details": "token sk-" + "THIS_SHOULD_NOT_LEAK_123456789",
                                }
                            ]
                        }
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                    "advisors_path": str(advisors_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(1, result["advisorRowCount"])
        self.assertEqual(1, result["advisorWarningCount"])
        self.assertEqual("imported", artifact["advisors"]["status"])
        self.assertTrue(artifact["advisors"]["available"])
        self.assertEqual(1, artifact["advisors"]["rowCount"])
        self.assertEqual(1, artifact["advisors"]["warningCount"])
        self.assertEqual(["SECURITY"], artifact["advisors"]["categories"])
        self.assertEqual("get_advisors", artifact["advisors"]["collectionPlan"]["mcpTool"])
        rendered = json.dumps(result, sort_keys=True) + json.dumps(artifact, sort_keys=True)
        self.assertNotIn("sk-" + "THIS_SHOULD_NOT_LEAK", rendered)
        self.assertNotIn("details", artifact["advisors"]["rows"][0])

    def test_supabase_schema_snapshot_import_accepts_nested_data_rows_and_records(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-data-rows-records.json"
            results_path.write_text(
                json.dumps(
                    [
                        {
                            "name": "schemas_and_tables",
                            "data": {
                                "rows": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "notes",
                                    }
                                ]
                            },
                        },
                        {
                            "name": "storage_upsert_policy_gaps",
                            "records": [
                                {
                                    "schema": "storage",
                                    "table": "objects",
                                    "operation": "upsert",
                                }
                            ],
                        },
                    ],
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(2, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(1, result["riskSummary"]["storageUpsertPolicyGap"])
        self.assertIn("schemas_and_tables", names)
        self.assertIn("storage_upsert_policy_gaps", names)
        rendered = json.dumps(artifact["snapshots"], sort_keys=True)
        self.assertNotIn("records", rendered)

    def test_supabase_schema_snapshot_import_accepts_query_name_mapped_result_objects(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-name-map.json"
            results_path.write_text(
                json.dumps(
                    {
                        "exposed_security_definer_functions": {
                            "rows": [
                                {
                                    "function_schema": "public",
                                    "function_name": "unsafe_fn",
                                }
                            ]
                        },
                        "update_policies_without_select_policy": {
                            "result": {
                                "data": [
                                    {
                                        "schemaname": "public",
                                        "tablename": "profiles",
                                        "policyname": "update_own_profile",
                                    }
                                ]
                            }
                        },
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["exposedSecurityDefinerFunctions"])
        self.assertEqual(1, result["riskSummary"]["updateSelectPolicyGap"])
        self.assertIn("exposed_security_definer_functions", names)
        self.assertIn("update_policies_without_select_policy", names)
        self.assertNotIn("rows", json.dumps(artifact["snapshots"], sort_keys=True))

    def test_supabase_schema_snapshot_import_accepts_jsonl_result_exports(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            results_path = Path(tmp) / "supabase-query-results.jsonl"
            results_path.write_text(
                "\n".join(
                    [
                        json.dumps(
                            {
                                "name": "exposed_tables_without_rls",
                                "rows": [{"schemaname": "public", "tablename": "notes"}],
                            },
                            sort_keys=True,
                        ),
                        json.dumps(
                            {
                                "name": "rls_user_metadata_policies",
                                "data": [{"schemaname": "public", "tablename": "profiles"}],
                            },
                            sort_keys=True,
                        ),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        names = {snapshot["name"] for snapshot in artifact["snapshots"]}
        self.assertEqual(2, result["importedResultCount"])
        self.assertEqual(1, result["riskSummary"]["exposedTablesWithoutRls"])
        self.assertEqual(1, result["riskSummary"]["metadataPolicyRisk"])
        self.assertIn("exposed_tables_without_rls", names)
        self.assertIn("rls_user_metadata_policies", names)
        self.assertNotIn("rows", json.dumps(artifact["snapshots"], sort_keys=True))

    def test_supabase_schema_snapshot_import_reports_missing_results_as_evidence_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            missing_results_path = Path(tmp) / "missing-supabase-query-results.json"

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(missing_results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertFalse(result["schemaSnapshotAvailable"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(0, result["importedResultCount"])
        self.assertIn("results_path missing", " ".join(result["evidence_needed"]))
        self.assertNotEqual("imported", artifact.get("snapshotImport", {}).get("status"))
        self.assertIn("results_path missing", " ".join(artifact["evidence_needed"]))

    def test_supabase_schema_snapshot_import_reports_empty_results_as_evidence_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            empty_results_path = Path(tmp) / "empty-supabase-query-results.json"
            empty_results_path.write_text(
                json.dumps({"message": "query completed but no named results were exported"}, sort_keys=True),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(empty_results_path),
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))

        self.assertFalse(result["schemaSnapshotAvailable"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(0, result["importedResultCount"])
        self.assertEqual("evidence_needed", artifact["snapshotImport"]["status"])
        self.assertIn("no supported Supabase query result rows", " ".join(result["evidence_needed"]))
        self.assertIn("no supported Supabase query result rows", " ".join(artifact["evidence_needed"]))
        self.assertNotIn("rows", json.dumps(artifact.get("snapshots", []), sort_keys=True))
        self.assertEqual("object", result["inputShape"]["payloadKind"])
        self.assertEqual(1, result["inputShape"]["topLevelKeyCount"])
        self.assertFalse(result["inputShape"]["resultsArrayPresent"])
        self.assertEqual(0, result["inputShape"]["supportedResultItemCount"])
        self.assertEqual(result["inputShape"], artifact["snapshotImport"]["inputShape"])
        self.assertNotIn("message", json.dumps(result["inputShape"], sort_keys=True))

    def test_supabase_schema_snapshot_import_reports_missing_expected_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            partial_results_path = Path(tmp) / "partial-supabase-query-results.json"
            partial_results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "name": "schemas_and_tables",
                                "rows": [
                                    {
                                        "table_schema": "public",
                                        "table_name": "private_table",
                                    }
                                ],
                            }
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(partial_results_path),
                }
            )
            artifact_text = snapshot_path.read_text(encoding="utf-8")
            artifact = json.loads(artifact_text)

        self.assertTrue(result["schemaSnapshotAvailable"])
        self.assertFalse(result["schemaSnapshotComplete"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(1, result["importedResultCount"])
        self.assertGreater(result["missingResultCount"], 0)
        self.assertIn("rls_and_table_flags", result["missingResultNames"])
        self.assertEqual(result["missingResultNames"], artifact["snapshotImport"]["missingResultNames"])
        self.assertIn("missing expected Supabase result sets", " ".join(result["evidence_needed"]))
        self.assertNotIn("private_table", json.dumps(result["missingResultNames"], sort_keys=True))
        self.assertNotIn("private_table", artifact_text)

    def test_supabase_schema_snapshot_import_reports_unexpected_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))
            query_names = [
                query["name"]
                for query in artifact["dbSnapshotPlan"]["sqlQueries"]
            ]
            results_path = Path(tmp) / "supabase-query-results-with-extra.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            *[
                                {
                                    "name": name,
                                    "rows": [{"probe": name}],
                                }
                                for name in query_names
                            ],
                            {
                                "name": "typo_extra_probe",
                                "rows": [{"table_name": "private_table"}],
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact_text = snapshot_path.read_text(encoding="utf-8")
            imported_artifact = json.loads(artifact_text)

        self.assertTrue(result["schemaSnapshotAvailable"])
        self.assertFalse(result["schemaSnapshotComplete"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(len(query_names) + 1, result["importedResultCount"])
        self.assertEqual(0, result["missingResultCount"])
        self.assertEqual(1, result["unexpectedResultCount"])
        self.assertEqual(["typo_extra_probe"], result["unexpectedResultNames"])
        self.assertEqual(result["unexpectedResultNames"], imported_artifact["snapshotImport"]["unexpectedResultNames"])
        self.assertIn("unexpected Supabase result sets", " ".join(result["evidence_needed"]))
        self.assertNotIn("private_table", json.dumps(result["unexpectedResultNames"], sort_keys=True))
        self.assertNotIn("private_table", artifact_text)

    def test_supabase_schema_snapshot_import_reports_duplicate_result_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            snapshot_path = Path(tmp) / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )
            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))
            query_names = [
                query["name"]
                for query in artifact["dbSnapshotPlan"]["sqlQueries"]
            ]
            duplicate_name = query_names[0]
            results_path = Path(tmp) / "supabase-query-results-with-duplicate.json"
            results_path.write_text(
                json.dumps(
                    {
                        "results": [
                            *[
                                {
                                    "name": name,
                                    "rows": [{"probe": name}],
                                }
                                for name in query_names
                            ],
                            {
                                "name": duplicate_name,
                                "rows": [{"table_name": "private_table"}],
                            },
                        ]
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            result = toolbox.supabase_schema_snapshot_import(
                {
                    "root": str(ROOT),
                    "snapshot_path": str(snapshot_path),
                    "results_path": str(results_path),
                }
            )
            artifact_text = snapshot_path.read_text(encoding="utf-8")
            imported_artifact = json.loads(artifact_text)

        self.assertTrue(result["schemaSnapshotAvailable"])
        self.assertFalse(result["schemaSnapshotComplete"])
        self.assertFalse(result["resultSetComplete"])
        self.assertEqual("supabase_schema_snapshot_import_evidence_needed", result["decision"])
        self.assertEqual(len(query_names) + 1, result["importedResultCount"])
        self.assertEqual(0, result["missingResultCount"])
        self.assertEqual(0, result["unexpectedResultCount"])
        self.assertEqual(1, result["duplicateResultCount"])
        self.assertEqual([duplicate_name], result["duplicateResultNames"])
        self.assertEqual(1, result["inputShape"]["duplicateResultItemCount"])
        self.assertEqual(result["inputShape"], imported_artifact["snapshotImport"]["inputShape"])
        self.assertEqual(result["duplicateResultNames"], imported_artifact["snapshotImport"]["duplicateResultNames"])
        self.assertIn("duplicate Supabase result sets", " ".join(result["evidence_needed"]))
        self.assertNotIn("private_table", json.dumps(result["duplicateResultNames"], sort_keys=True))
        self.assertNotIn("private_table", artifact_text)

    def test_supabase_schema_snapshot_import_is_registered_for_stdio_and_manifest(self):
        self.assertIn("supabase_schema_snapshot_import", toolbox.TOOL_ALIASES.values())
        self.assertIn("supabase_schema_snapshot_import", stdio_server.HANDLERS)

        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        import_tool = next(tool for tool in manifest["tools"] if tool["name"] == "supabase_schema_snapshot_import")

        self.assertTrue(import_tool["readOnly"])
        self.assertIn("supabase.schema_snapshot_import", import_tool["aliases"])
        self.assertIn("JSONL", import_tool["description"])
        self.assertIn("structuredContent", import_tool["description"])
        self.assertIn("execute_sql transcript", import_tool["description"])
        self.assertIn("results_path", import_tool["input_schema"]["properties"])
        self.assertIn("snapshotPath", import_tool["output_schema"]["properties"])
        self.assertIn("riskSummary", import_tool["output_schema"]["properties"])
        self.assertIn("inputShape", import_tool["output_schema"]["properties"])
        self.assertIn("missingResultCount", import_tool["output_schema"]["properties"])
        self.assertIn("missingResultNames", import_tool["output_schema"]["properties"])
        self.assertIn("unexpectedResultCount", import_tool["output_schema"]["properties"])
        self.assertIn("unexpectedResultNames", import_tool["output_schema"]["properties"])
        self.assertIn("duplicateResultCount", import_tool["output_schema"]["properties"])
        self.assertIn("duplicateResultNames", import_tool["output_schema"]["properties"])
        self.assertIn("mcpErrorCount", import_tool["output_schema"]["properties"])
        self.assertIn("mcpErrorCodes", import_tool["output_schema"]["properties"])
        self.assertIn("decision", import_tool["output_schema"]["properties"])
        self.assertIn("evidence_needed", import_tool["output_schema"]["properties"])

    def test_mcp_client_sample_exposes_supabase_env_names_only(self):
        config_path = ROOT / "main" / "resources" / "mcp" / "awx-control-tower-mcp-client.sample.json"
        config_text = config_path.read_text(encoding="utf-8")
        config = json.loads(config_text)

        allowed_refs = config["allowedEnvRefs"]
        self.assertIn("SUPABASE_PROJECT_REF", allowed_refs)
        self.assertIn("SUPABASE_ACCESS_TOKEN", allowed_refs)
        self.assertIn("NAVER_KEYS", allowed_refs)
        self.assertNotIn("Bearer ", config_text)
        self.assertNotIn("service_role", config_text)
        self.assertNotRegex(
            config_text,
            r"sk-[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}",
        )

    def test_producer_kit_export_exposes_supabase_env_names_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            patchdrop = Path(tmp) / "__patch_drop__"
            result = toolbox.producer_kit_export(
                {
                    "root": str(ROOT),
                    "patchdrop_root": str(patchdrop),
                    "topic": "supabase-db-audit",
                    "nodeRole": "desktop",
                }
            )
            manifest = json.loads(Path(result["manifestPath"]).read_text(encoding="utf-8"))

        self.assertTrue(result["ok"])
        self.assertIn("SUPABASE_PROJECT_REF", result["allowedEnvRefs"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", result["allowedEnvRefs"])
        self.assertIn("SUPABASE_PROJECT_REF", manifest["allowedEnvRefs"])
        self.assertIn("SUPABASE_ACCESS_TOKEN", manifest["allowedEnvRefs"])
        rendered = json.dumps({"result": result, "manifest": manifest}, sort_keys=True)
        self.assertNotIn("Bearer ", rendered)
        self.assertNotIn("service_role", rendered)
        self.assertNotRegex(
            rendered,
            r"sk-[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}",
        )

    def test_completion_audit_reports_supabase_read_only_db_probe(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        self.assertIsInstance(audit.get("generatedAt"), str)
        self.assertRegex(audit["generatedAt"], r"^\d{4}-\d{2}-\d{2}T")
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["supabase.mcp-config"]["ok"])
        self.assertTrue(checks["mcp.client-config"]["ok"])
        self.assertIn("supabaseEnvRefs=True", checks["mcp.client-config"]["evidence"])
        self.assertIn("readOnlyMode=True", checks["supabase.mcp-config"]["evidence"])
        self.assertIn("type=http", checks["supabase.mcp-config"]["evidence"])
        self.assertTrue(checks["supabase.mcp-scope"]["ok"])
        self.assertIn("featuresScopedMode=True", checks["supabase.mcp-scope"]["evidence"])
        self.assertIn("projectScopedMode=False", checks["supabase.mcp-scope"]["evidence"])
        self.assertIn("projectRefTemplateMode=True", checks["supabase.mcp-scope"]["evidence"])
        self.assertIn("projectScopeNextAction=set_SUPABASE_PROJECT_REF", checks["supabase.mcp-scope"]["evidence"])
        self.assertIn("scopedMcpUrlTemplate=True", checks["supabase.mcp-scope"]["evidence"])
        self.assertTrue(checks["supabase.schema-snapshot-plan"]["ok"])
        self.assertIn("dbSnapshotPlan=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("information_schema=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("dataApiGrants=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("tablesWithoutRls=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("metadataPolicyRisk=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("updateSelectPolicyGap=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("storageUpsertPolicyGap=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("securityInvokerViews=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("securityDefinerFunctions=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("cliQueryMinVersion=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("cliAdvisorsMinVersion=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertIn("mcpFallbackContract=True", checks["supabase.schema-snapshot-plan"]["evidence"])
        self.assertTrue(checks["supabase.schema-snapshot-tool"]["ok"])
        self.assertIn("supabase_schema_snapshot=True", checks["supabase.schema-snapshot-tool"]["evidence"])
        self.assertIn("apiKeysDocsSchema=True", checks["supabase.schema-snapshot-tool"]["evidence"])
        self.assertIn("secretKeysBackendOnlySchema=True", checks["supabase.schema-snapshot-tool"]["evidence"])
        self.assertIn("cliVersionRequirementsSchema=True", checks["supabase.schema-snapshot-tool"]["evidence"])
        self.assertIn("cliFallbackContractSchema=True", checks["supabase.schema-snapshot-tool"]["evidence"])
        self.assertTrue(checks["supabase.schema-snapshot-import-tool"]["ok"])
        self.assertIn("supabase_schema_snapshot_import=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("storedRawRows=False", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("inputShape=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("missingResultSummary=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("unexpectedResultSummary=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("duplicateResultSummary=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertIn("mcpErrorSummary=True", checks["supabase.schema-snapshot-import-tool"]["evidence"])
        self.assertTrue(checks["supabase.schema-snapshot-artifact"]["ok"])
        self.assertIn("schemaVersion=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("readOnly=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("mutationAllowed=False", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundle=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("docsRefs=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("apiKeysDocs=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("breakingChanges=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("authPlan=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn(
            "defaultHostedAuthMode=dynamic_client_registration_oauth",
            checks["supabase.schema-snapshot-artifact"]["evidence"],
        )
        self.assertIn(
            "supportedAuthModes=dynamic_oauth,manual_ci_pat_auth",
            checks["supabase.schema-snapshot-artifact"]["evidence"],
        )
        self.assertIn("mcpOAuthRequired=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("manualAuthMode=manual_pat_header_for_ci", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("manualAuthEnvStatus=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("manualAuthSensitiveEnvRefs=SUPABASE_ACCESS_TOKEN", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("securityWarnings=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("secretKeysBackendOnly=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundleDocs=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundleBreakingChange=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplate=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateSchema=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateCollectionPlan=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateCollectionPlanReadOnly=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateCollectionPlanMcpTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateCollectionPlanEnvNames=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateCollectionPlanImportTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("advisorsCollectionPlan=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("advisorsCollectionPlanReadOnly=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("advisorsCollectionPlanMcpTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("advisorsCollectionPlanEnvNames=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateAdvisorCollectionPlan=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateAdvisorCollectionMcpTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateAdvisorCollectionEnvNames=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateSupportedResultShapes=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacket=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketSchema=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketReadOnly=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketMcpTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketEnvNames=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketImportTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketAdvisorCollection=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketAdvisorCollectionMcpTool=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketAdvisorCollectionEnvNames=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketExecutionMode=True", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("planSqlQueryCount=12", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundleQueryCount=12", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateQueryCount=12", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketQueryCount=12", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("planDuplicateQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundleDuplicateQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateDuplicateQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketDuplicateQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("resultTemplateMissingQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("sqlBundleMissingQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("collectionPacketMissingQueryNames=", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["supabase.schema-snapshot-artifact"]["evidence"])
        self.assertTrue(checks["supabase.readonly-snapshot-smoke"]["ok"])
        self.assertIn("script=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("artifactDir=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        smoke_evidence = checks["supabase.readonly-snapshot-smoke"]["evidence"]
        self.assertRegex(smoke_evidence, r"mcpReachable=(True|False)")
        self.assertRegex(smoke_evidence, r"mcpProbeSkipped=(True|False)")
        self.assertRegex(
            smoke_evidence,
            r"mcpDecision=(mcp_endpoint_probe_skipped|mcp_endpoint_auth_required)",
        )
        self.assertIn("snapshotDecision=supabase_schema_snapshot_evidence_needed", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("importDecision=supabase_schema_snapshot_import_evidence_needed", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("schemaSnapshotAvailable=False", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("importSchemaSnapshotComplete=False", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("importResultSetComplete=False", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("mutationAllowed=False", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summaryArtifact=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summaryGeneratedAt=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summaryFresh=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summaryFreshnessStatus=current", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("docsRefCount=6", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("securityContractCount=7", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("cliQueryMinVersion=>=2.79.0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("cliAdvisorsMinVersion=>=2.81.3", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("mcpFallbackContract=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("dataApiGrantProofRequired=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("rlsPolicyProofRequired=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("secretKeysBackendOnly=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summarySecretHits=0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("summaryRawSecretPatternHits=0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("bearerPatternHits=0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("windowsAbsPathHits=0", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("reportsMissingResultNames=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("reportsDataApiEvidenceMissing=True", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("envPresentCount=", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("projectRefEnvPresent=", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("accessTokenEnvPresent=", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("cliPresent=", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertIn("contextEvidenceNeededCount=", checks["supabase.readonly-snapshot-smoke"]["evidence"])
        self.assertTrue(checks["computer-use.gui-proof-boundary"]["ok"])
        self.assertIn("promptPack=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("guiOnly=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("noTerminalAutomation=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("supportingOnly=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("helperArtifact=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("helperReachable=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("helperGeneratedAt=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("helperFresh=True", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("helperFreshnessStatus=current", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("storesAppNames=False", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertIn("storesWindowTitles=False", checks["computer-use.gui-proof-boundary"]["evidence"])
        self.assertEqual("satisfied", requirements["supabase-readonly-db-probe"]["status"])
        self.assertRegex(requirements["supabase-readonly-db-probe"]["evidence"], r"mcpProbeSkipped=(True|False)")
        self.assertRegex(
            requirements["supabase-readonly-db-probe"]["evidence"],
            r"mcpDecision=(mcp_endpoint_probe_skipped|mcp_endpoint_auth_required)",
        )
        self.assertEqual("evidence_needed", requirements["supabase-project-scope"]["status"])
        self.assertIn("project_ref", requirements["supabase-project-scope"]["evidence"])

    def test_completion_audit_accepts_goal_next_computer_use_smoke_boundary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prompt_dir = root / "agent-prompts" / "agents" / "demo1_quant_harmony_9h_safe_patch"
            prompt_dir.mkdir(parents=True)
            prompt_text = (
                "Computer Use\n"
                "GUI/browser proof\n"
                "explicit Windows GUI proof\n"
                "do not automate PowerShell or command prompts\n"
                "terminal/source edits\n"
                "SUPPORTING_ONLY\n"
                "supporting/not-required\n"
            )
            (prompt_dir / "system_ko.md").write_text(prompt_text, encoding="utf-8")
            out_dir = root / "agent-prompts" / "out"
            out_dir.mkdir(parents=True)
            (out_dir / "demo1_quant_harmony_9h_safe_patch.prompt").write_text(prompt_text, encoding="utf-8")
            smoke_dir = root / "var" / "codex-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "computer-use-smoke.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.computer_use_smoke.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "ok": True,
                        "decision": "ok",
                        "reachable": True,
                        "appCount": 3,
                        "runningCount": 2,
                        "windowCount": 4,
                        "storesRawAppNames": False,
                        "storesWindowTitles": False,
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.computer_use_gui_boundary_summary(root)

        self.assertTrue(summary["ready"])
        self.assertEqual("var/codex-smoke/computer-use-smoke.json", summary["artifactPath"])
        self.assertTrue(summary["helperPresent"])
        self.assertTrue(summary["helperFresh"])
        self.assertTrue(summary["helperCountOnly"])
        self.assertTrue(summary["helperBoundary"])
        self.assertFalse(summary["storesAppNames"])
        self.assertFalse(summary["storesWindowTitles"])
        self.assertEqual(0, summary["helperSecretPatternHits"])

    def test_completion_audit_accepts_fresh_count_only_computer_use_smoke_over_stale_helper(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            prompt_dir = root / "agent-prompts" / "agents" / "demo1_quant_harmony_9h_safe_patch"
            prompt_dir.mkdir(parents=True)
            prompt_text = (
                "Computer Use\n"
                "GUI/browser proof\n"
                "explicit Windows GUI proof\n"
                "do not automate PowerShell or command prompts\n"
                "terminal/source edits\n"
                "SUPPORTING_ONLY\n"
                "supporting/not-required\n"
            )
            (prompt_dir / "system_ko.md").write_text(prompt_text, encoding="utf-8")
            out_dir = root / "agent-prompts" / "out"
            out_dir.mkdir(parents=True)
            (out_dir / "demo1_quant_harmony_9h_safe_patch.prompt").write_text(prompt_text, encoding="utf-8")
            helper_dir = root / "var" / "codex-smoke" / "computer-use"
            helper_dir.mkdir(parents=True)
            stale_generated_at = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=2)).isoformat(
                timespec="seconds"
            )
            (helper_dir / "helper-smoke.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.computer_use_helper_smoke.v1",
                        "generatedAt": stale_generated_at,
                        "ok": True,
                        "reachable": True,
                        "guiOnly": True,
                        "noTerminalAutomation": True,
                        "supportingOnly": True,
                        "appCount": 3,
                        "targetableWindowCount": 4,
                        "storesAppNames": False,
                        "storesRawAppNames": False,
                        "storesWindowTitles": False,
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )
            smoke_dir = root / "var" / "codex-smoke"
            (smoke_dir / "computer-use-smoke.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.computer_use_smoke.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "ok": True,
                        "decision": "ok",
                        "reachable": True,
                        "appCount": 5,
                        "runningCount": 2,
                        "windowCount": 7,
                        "secretHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.computer_use_gui_boundary_summary(root)

        self.assertTrue(summary["ready"])
        self.assertEqual("var/codex-smoke/computer-use-smoke.json", summary["artifactPath"])
        self.assertTrue(summary["helperFresh"])
        self.assertTrue(summary["helperCountOnly"])
        self.assertFalse(summary["storesAppNames"])
        self.assertFalse(summary["storesWindowTitles"])

    def test_completion_audit_detects_standard_jdbc_urls_in_supabase_smoke_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script_dir = root / "scripts"
            artifact_dir = root / "var" / "codex-smoke" / "supabase-readonly-snapshot"
            script_dir.mkdir(parents=True)
            artifact_dir.mkdir(parents=True)
            (script_dir / "smoke_supabase_readonly_snapshot.ps1").write_text(
                "supabase_context_probe supabase_schema_snapshot supabase_schema_snapshot_import "
                "RequireProjectScope SkipNetworkProbe",
                encoding="utf-8",
            )
            (artifact_dir / "supabase-context-probe.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_context_probe",
                        "mcp": {
                            "reachable": True,
                            "httpStatus": "401",
                            "unauthenticatedExpected": True,
                            "decision": "evidence_needed",
                        },
                        "projectScope": {"status": "project_ref_missing"},
                    }
                ),
                encoding="utf-8",
            )
            (artifact_dir / "supabase-schema-snapshot.result.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_schema_snapshot_evidence_needed",
                        "schemaSnapshotAvailable": False,
                        "mutationAllowed": False,
                    }
                ),
                encoding="utf-8",
            )
            (artifact_dir / "supabase-schema-snapshot-import.result.json").write_text(
                json.dumps(
                    {
                        "decision": "supabase_schema_snapshot_import_evidence_needed",
                        "schemaSnapshotAvailable": False,
                        "mutationAllowed": False,
                        "importedResultCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            (artifact_dir / "supabase-schema-snapshot.json").write_text(
                json.dumps(
                    {
                        "readOnly": True,
                        "mutationAllowed": False,
                        "diagnostic": "jdbc:postgresql://db.example.internal/postgres",
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.supabase_readonly_snapshot_smoke_summary(root)

        self.assertEqual(1, summary["rawJdbcUrlHits"])
        self.assertIn("rawJdbcUrlHits", completion_audit.supabase_readonly_snapshot_smoke_ready_fail_reason(summary))

    def test_completion_audit_reports_next_actions_for_remaining_evidence(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        self.assertIn("nextActions", audit)
        self.assertEqual(audit["nextActions"], list(dict.fromkeys(audit["nextActions"])))
        archive_requirement = {
            row["id"]: row
            for row in audit.get("requirements", [])
        }.get("archive-search-two-pass-index", {})
        if archive_requirement.get("status") == "evidence_needed":
            self.assertIn("create_or_point_archive_index", audit["nextActions"])
            self.assertIn("run_archive_index_build", audit["nextActions"])
            self.assertIn("set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT", audit["nextActions"])
            self.assertIn("verify_archive_index_path", audit["nextActions"])
            self.assertIn("rerun_archive_search_with_index_path", audit["nextActions"])
            self.assertIn("rerun_archive_search", audit["nextActions"])
        else:
            self.assertEqual("satisfied", archive_requirement.get("status"))
            self.assertNotIn("create_or_point_archive_index", audit["nextActions"])
            self.assertNotIn("run_archive_index_build", audit["nextActions"])
        self.assertIn("set_SUPABASE_PROJECT_REF", audit["nextActions"])
        self.assertNotIn("set_project_ref_in_mcp_config", audit["nextActions"])
        self.assertIn("complete_supabase_mcp_oauth_flow", audit["nextActions"])
        self.assertIn("install_supabase_cli_or_use_mcp_execute_sql", audit["nextActions"])
        self.assertIn("run_supabase_cli_help_discovery", audit["nextActions"])
        self.assertIn("link_supabase_cli_project_ref", audit["nextActions"])
        self.assertIn("authenticate_supabase_mcp_or_cli", audit["nextActions"])
        self.assertIn("run_supabase_readonly_snapshot_smoke", audit["nextActions"])
        self.assertIn("run_supabase_context_probe", audit["nextActions"])
        self.assertIn("run_supabase_schema_snapshot", audit["nextActions"])
        self.assertIn("run_supabase_readonly_sql_bundle", audit["nextActions"])
        self.assertIn("execute_each_query_once", audit["nextActions"])
        self.assertIn("collect_get_advisors_rows", audit["nextActions"])
        self.assertIn("run_supabase_schema_snapshot_import", audit["nextActions"])
        self.assertIn("run_supabase_get_advisors_readonly", audit["nextActions"])
        self.assertIn("import_supabase_query_results", audit["nextActions"])
        self.assertIn("populate_supabase_query_results_file", audit["nextActions"])
        self.assertIn("rerun_supabase_schema_snapshot_import", audit["nextActions"])
        self.assertIn("rerun_db_gap_scanner", audit["nextActions"])
        requirements = {row["id"]: row for row in audit.get("requirements", [])}
        if requirements.get("harmony-runtime-proof-live", {}).get("status") == "satisfied":
            self.assertNotIn("start_spring_runtime_for_harmony_probe", audit["nextActions"])
            self.assertNotIn("rerun_harmony_scan_with_runtime_probe", audit["nextActions"])
        else:
            self.assertIn("start_spring_runtime_for_harmony_probe", audit["nextActions"])
            self.assertIn("set_AGENT_DB_CONTEXT_ENABLED_true", audit["nextActions"])
            self.assertIn("set_AWX_AGENT_DB_CONTEXT_BASE_URL", audit["nextActions"])
            self.assertIn("set_AWX_TRACE_SNAPSHOT_BASE_URL", audit["nextActions"])
            self.assertIn("rerun_harmony_scan_with_runtime_probe", audit["nextActions"])
        self.assertIn("verify_or_override_producer_roots", audit["nextActions"])
        self.assertIn("copy_macmini_node_smoke_json_to_desktop_evidence_path", audit["nextActions"])
        self.assertIn("copy_macmini_producer_handoff_json_to_desktop_evidence_path", audit["nextActions"])
        self.assertIn("copy_notebook_node_smoke_json_to_desktop_evidence_path", audit["nextActions"])
        self.assertIn("copy_notebook_producer_handoff_json_to_desktop_evidence_path", audit["nextActions"])
        self.assertIn("run_external_node_smoke_on_producer_hosts", audit["nextActions"])
        self.assertIn("collect_producer_handoff_json", audit["nextActions"])
        self.assertIn("submit_patchdrop_v3_bundle_sidecars", audit["nextActions"])
        self.assertIn("run_external_evidence_intake", audit["nextActions"])
        self.assertIn("run_external_evidence_audit", audit["nextActions"])
        self.assertEqual("rerun_completion_audit", audit["nextActions"][-1])

    def test_completion_audit_reports_phase2_local_hard_gates(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        requirements = {row["id"]: row for row in audit.get("requirements", [])}
        phase2 = requirements["phase2-local-hard-gates"]

        self.assertEqual("satisfied", phase2["status"])
        self.assertIn("sourceScore=100/100", phase2["evidence"])
        self.assertIn("activeLegacySignalsTarget=200", phase2["evidence"])
        evidence_pairs = dict(
            item.split("=", 1)
            for item in phase2["evidence"].split(";")
            if "=" in item
        )
        self.assertLessEqual(
            int(evidence_pairs["activeLegacySignals"]),
            int(evidence_pairs["activeLegacySignalsTarget"]),
        )
        self.assertIn("harmonyHbDone=12/12", phase2["evidence"])
        self.assertIn("duplicateFqcn=0", phase2["evidence"])
        self.assertIn("secretPatternHits=0", phase2["evidence"])
        self.assertEqual([], phase2["evidenceNeeded"])

    def test_completion_audit_reports_supabase_import_actions_when_project_scope_is_present(self):
        requirements = [
            {
                "id": "supabase-project-scope",
                "status": "satisfied",
                "evidence": "projectScopedMode=True",
                "evidenceNeeded": [],
            },
            {
                "id": "supabase-live-db-structure-proof",
                "status": "evidence_needed",
                "evidence": "snapshotImportStatus=evidence_needed;projectRefPresent=True",
                "evidenceNeeded": [
                    "Supabase live schema snapshot not imported / run read-only execute_sql collection and import results",
                    "Supabase advisor rows not imported / run read-only get_advisors and update advisors.rows",
                    "Supabase execute_sql result sets missing / populate a results JSON file and rerun supabase_schema_snapshot_import",
                ],
            },
        ]

        next_actions = completion_audit.completion_audit_next_actions(requirements, [])

        self.assertIn("run_supabase_readonly_sql_bundle", next_actions)
        self.assertIn("run_supabase_get_advisors_readonly", next_actions)
        self.assertIn("import_supabase_query_results", next_actions)
        self.assertIn("populate_supabase_query_results_file", next_actions)
        self.assertIn("rerun_supabase_schema_snapshot_import", next_actions)
        self.assertIn("rerun_db_gap_scanner", next_actions)
        self.assertEqual("rerun_completion_audit", next_actions[-1])

    def test_completion_audit_splits_external_producer_proof_by_role(self):
        requirements = completion_audit.build_requirement_matrix(
            [],
            [completion_audit.EXTERNAL_NODE_EVIDENCE_NEEDED],
            ROOT / "BackupsXS" / "index.jsonl",
            {"macmini"},
            {"macmini"},
            set(),
            ROOT,
        )
        by_id = {row["id"]: row for row in requirements}

        self.assertEqual("evidence_needed", by_id["producer-external-proof"]["status"])
        self.assertEqual("evidence_needed", by_id["producer-external-proof-macmini"]["status"])
        self.assertEqual("evidence_needed", by_id["producer-external-proof-notebook"]["status"])
        aggregate_needed = " ".join(by_id["producer-external-proof"]["evidenceNeeded"])
        self.assertIn("macmini-node-smoke.json", aggregate_needed)
        self.assertIn("macmini-producer-handoff.json", aggregate_needed)
        self.assertIn("notebook-node-smoke.json", aggregate_needed)
        self.assertIn("notebook-producer-handoff.json", aggregate_needed)
        self.assertIn("__patch_drop__/external-node-proof", aggregate_needed)
        self.assertNotIn("<role>", aggregate_needed)
        self.assertIn("role=macmini", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("nodeSmoke=True", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("handoff=True", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("bundle=False", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("desktopEvidencePath=data/agent-handoff/mcp-control-tower/macmini-node-smoke.json", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("patchdropProofDir=__patch_drop__/external-node-proof", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("requiredSourceIsolation=guard=PASS,sourceRootKind=local-worktree,directCanonicalSourceEdit=false", by_id["producer-external-proof-macmini"]["evidence"])
        self.assertIn("role=notebook", by_id["producer-external-proof-notebook"]["evidence"])
        self.assertIn("nodeSmoke=False", by_id["producer-external-proof-notebook"]["evidence"])
        self.assertIn(
            "external macmini PatchDrop v3 sidecars missing",
            " ".join(by_id["producer-external-proof-macmini"]["evidenceNeeded"]),
        )
        macmini_needed = " ".join(by_id["producer-external-proof-macmini"]["evidenceNeeded"])
        self.assertIn("sourceIsolation.guard=PASS", macmini_needed)
        self.assertNotIn("<role>", macmini_needed)
        notebook_needed = " ".join(by_id["producer-external-proof-notebook"]["evidenceNeeded"])
        self.assertIn("external notebook node smoke output missing", notebook_needed)
        self.assertIn("external notebook producer handoff JSON missing", notebook_needed)
        self.assertIn("external notebook PatchDrop v3 sidecars missing", notebook_needed)
        self.assertIn("notebook-node-smoke.json", notebook_needed)
        self.assertIn("notebook-producer-handoff.json", notebook_needed)
        self.assertIn("sourceIsolation.guard=PASS", notebook_needed)
        self.assertNotIn("<role>", notebook_needed)

    def test_completion_audit_next_actions_include_role_specific_external_proof_steps(self):
        requirements = [
            {
                "id": "producer-external-proof",
                "status": "evidence_needed",
                "evidence": "nodeSmokeRoles=macmini;handoffRoles=;bundleRoles=",
                "evidenceNeeded": [],
            },
            {
                "id": "producer-external-proof-macmini",
                "status": "evidence_needed",
                "evidence": "role=macmini;nodeSmoke=True;handoff=False;bundle=False",
                "evidenceNeeded": [
                    "external macmini producer handoff JSON missing / run scripts/awx_mcp_producer_handoff.py on producer host",
                    "external macmini PatchDrop v3 sidecars missing / submit patch bundle sidecars",
                ],
            },
            {
                "id": "producer-external-proof-notebook",
                "status": "evidence_needed",
                "evidence": "role=notebook;nodeSmoke=False;handoff=False;bundle=False",
                "evidenceNeeded": [
                    "external notebook node smoke output missing / run scripts/awx_mcp_node_smoke.py on producer host",
                    "external notebook producer handoff JSON missing / run scripts/awx_mcp_producer_handoff.py on producer host",
                    "external notebook PatchDrop v3 sidecars missing / submit patch bundle sidecars",
                ],
            },
        ]

        next_actions = completion_audit.completion_audit_next_actions(requirements, [])

        self.assertIn("collect_macmini_producer_handoff_json", next_actions)
        self.assertIn("submit_macmini_patchdrop_v3_bundle_sidecars", next_actions)
        self.assertIn("run_notebook_external_node_smoke", next_actions)
        self.assertIn("collect_notebook_producer_handoff_json", next_actions)
        self.assertIn("submit_notebook_patchdrop_v3_bundle_sidecars", next_actions)
        self.assertEqual("rerun_completion_audit", next_actions[-1])

    def test_desktop_control_loop_exposes_completion_audit_next_actions(self):
        result = toolbox.desktop_control_loop(
            {
                "root": str(ROOT),
                "canonical_root": str(ROOT),
                "topic": "mcp-control-loop",
            }
        )

        self.assertIn("completionAuditNextActions", result)
        self.assertIn("set_SUPABASE_PROJECT_REF", result["completionAuditNextActions"])
        self.assertNotIn("set_project_ref_in_mcp_config", result["completionAuditNextActions"])
        self.assertIn("complete_supabase_mcp_oauth_flow", result["completionAuditNextActions"])
        self.assertIn("install_supabase_cli_or_use_mcp_execute_sql", result["completionAuditNextActions"])
        self.assertIn("run_supabase_cli_help_discovery", result["completionAuditNextActions"])
        self.assertIn("link_supabase_cli_project_ref", result["completionAuditNextActions"])
        self.assertIn("authenticate_supabase_mcp_or_cli", result["completionAuditNextActions"])
        self.assertIn("run_supabase_readonly_sql_bundle", result["completionAuditNextActions"])
        self.assertIn("import_supabase_query_results", result["completionAuditNextActions"])
        if "create_or_point_archive_index" in result["completionAuditNextActions"]:
            self.assertIn("run_archive_index_build", result["completionAuditNextActions"])
            self.assertIn("set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT", result["completionAuditNextActions"])
            self.assertIn("verify_archive_index_path", result["completionAuditNextActions"])
            self.assertIn("rerun_archive_search_with_index_path", result["completionAuditNextActions"])
        else:
            self.assertNotIn("run_archive_index_build", result["completionAuditNextActions"])
        self.assertIn("run_external_evidence_audit", result["completionAuditNextActions"])
        self.assertEqual("rerun_completion_audit", result["completionAuditNextActions"][-1])
        optional_actions = {action.get("action"): action for action in result["optionalNextActions"]}
        if "provide-archive-index" in optional_actions:
            archive_action = optional_actions["provide-archive-index"]
            self.assertIn("archive_index_path_hash", archive_action["verifyHint"])
            self.assertIn("expectedPathHash", archive_action)
            self.assertNotIn("expectedPath", archive_action)
            rendered_action = json.dumps(archive_action, sort_keys=True)
            self.assertNotIn("BackupsXS", rendered_action)
            self.assertIn("rerun_archive_search_with_index_path", archive_action["nextActions"])
        else:
            self.assertNotIn("create_or_point_archive_index", result["completionAuditNextActions"])

    def test_desktop_control_loop_exposes_structured_supabase_live_proof_action(self):
        result = toolbox.desktop_control_loop(
            {
                "root": str(ROOT),
                "canonical_root": str(ROOT),
                "topic": "mcp-control-loop",
            }
        )

        actions = [
            action
            for action in result["nextActions"]
            if action.get("action") == "collect-supabase-live-proof"
        ]
        self.assertEqual(1, len(actions))
        action = actions[0]
        self.assertEqual("desktop", action["nodeRole"])
        self.assertEqual("supabase", action["targetService"])
        self.assertEqual("evidence_needed", action["decision"])
        self.assertTrue(action["readOnly"])
        self.assertFalse(action["mutationAllowed"])
        self.assertIn("project_ref=${SUPABASE_PROJECT_REF}", action["mcpEndpointTemplate"])
        self.assertIn("read_only=true", action["mcpEndpointTemplate"])
        self.assertIn("features=database,debugging,docs", action["mcpEndpointTemplate"])
        self.assertEqual(
            [
                {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True},
            ],
            action["requiredEnv"],
        )
        self.assertEqual(["execute_sql", "get_advisors"], action["requiredMcpTools"])
        self.assertEqual(12, action["queryCount"])
        self.assertEqual("supabase_schema_snapshot_import", action["importTool"])
        self.assertEqual("data/db-gap-report/supabase-query-results.json", action["resultPathRecommendation"])
        self.assertIn("data/db-gap-report/supabase-execute-sql-collection.packet.json", action["artifactPaths"])
        self.assertIn("data/db-gap-report/supabase-query-results.template.json", action["artifactPaths"])
        self.assertIn("data/db-gap-report/supabase-readonly-snapshot.sql", action["artifactPaths"])
        self.assertIn("schemas_and_tables", action["requiredResultNames"])
        self.assertIn("extensions", action["requiredResultNames"])
        self.assertIn("https://supabase.com/docs/guides/security/product-security", action["docsRefs"])
        self.assertIn("set_SUPABASE_PROJECT_REF", action["nextActions"])
        self.assertIn("execute_each_query_once", action["nextActions"])
        self.assertIn("collect_get_advisors_rows", action["nextActions"])
        self.assertIn("run_supabase_schema_snapshot_import", action["nextActions"])
        rendered = json.dumps(action, sort_keys=True)
        self.assertNotIn("Bearer", rendered)
        self.assertNotIn("sbp_", rendered)

    def test_source_contract_next_actions_for_root_exposes_pending_action(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(
                    {
                        "nextSourceActionDetails": [
                            {
                                "action": "run-cross-subsystem-contract-tests",
                                "scope": "active_source",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "targetFiles": ["main/java/demo/RuntimeSeam.java"],
                                "affectedSubsystems": ["S05_ExtremeZ", "S08_Adapter"],
                                "focusedTests": [
                                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                                ],
                                "commands": [
                                    ".\\gradlew.bat test --tests \"ai.abandonware.nova.orch.aop.AspectOrderingContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.orchestration.StrategyConflictResolverTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.orchestration.ExecutionPlanApplierTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.service.rag.burst.ExtremeZTriggerTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                ],
                                "requiredTraceKeys": ["boosterMode.active", "extremeZ.bypassReason"],
                                "decision": "local_contract_ready",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            actions = toolbox.source_contract_next_actions_for_root(str(root))

        self.assertEqual(1, len(actions))
        action = actions[0]
        self.assertEqual("desktop", action["nodeRole"])
        self.assertEqual("active_source", action["scope"])
        self.assertEqual("local_contract_ready", action["decision"])
        self.assertTrue(action["readOnly"])
        self.assertFalse(action["mutationAllowed"])
        self.assertIn("S05_ExtremeZ", action["affectedSubsystems"])
        self.assertIn("S08_Adapter", action["affectedSubsystems"])
        self.assertIn(
            "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
            action["focusedTests"],
        )
        self.assertIn(
            "com.example.lms.orchestration.StrategyConflictResolverTest",
            action["focusedTests"],
        )
        self.assertIn("boosterMode.active", action["requiredTraceKeys"])
        self.assertIn("extremeZ.bypassReason", action["requiredTraceKeys"])
        self.assertGreaterEqual(len(action["commands"]), 4)
        rendered = json.dumps(action, sort_keys=True)
        self.assertNotIn("C:\\", rendered)
        self.assertNotIn("Bearer", rendered)
        self.assertNotIn("sbp_", rendered)

    def test_completion_audit_exposes_structured_supabase_live_proof_action(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        actions = [
            action
            for action in report.get("nextActionDetails", [])
            if action.get("action") == "collect-supabase-live-proof"
        ]
        self.assertEqual(1, len(actions))
        action = actions[0]
        self.assertEqual("desktop", action["nodeRole"])
        self.assertEqual("supabase", action["targetService"])
        self.assertEqual("evidence_needed", action["decision"])
        self.assertTrue(action["readOnly"])
        self.assertFalse(action["mutationAllowed"])
        self.assertIn("project_ref=${SUPABASE_PROJECT_REF}", action["mcpEndpointTemplate"])
        self.assertIn("read_only=true", action["mcpEndpointTemplate"])
        self.assertIn("features=database,debugging,docs", action["mcpEndpointTemplate"])
        self.assertEqual(
            [
                {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True},
            ],
            action["requiredEnv"],
        )
        self.assertEqual(["execute_sql", "get_advisors"], action["requiredMcpTools"])
        self.assertEqual(12, action["queryCount"])
        self.assertEqual("supabase_schema_snapshot_import", action["importTool"])
        self.assertEqual("data/db-gap-report/supabase-query-results.json", action["resultPathRecommendation"])
        self.assertIn("data/db-gap-report/supabase-execute-sql-collection.packet.json", action["artifactPaths"])
        self.assertIn("data/db-gap-report/supabase-query-results.template.json", action["artifactPaths"])
        self.assertIn("data/db-gap-report/supabase-readonly-snapshot.sql", action["artifactPaths"])
        self.assertIn("schemas_and_tables", action["requiredResultNames"])
        self.assertIn("extensions", action["requiredResultNames"])
        self.assertIn("https://supabase.com/docs/guides/security/product-security", action["docsRefs"])
        self.assertIn("set_SUPABASE_PROJECT_REF", action["nextActions"])
        self.assertIn("execute_each_query_once", action["nextActions"])
        self.assertIn("collect_get_advisors_rows", action["nextActions"])
        self.assertIn("run_supabase_schema_snapshot_import", action["nextActions"])
        rendered = json.dumps(action, sort_keys=True)
        self.assertNotIn("Bearer", rendered)
        self.assertNotIn("sbp_", rendered)
        requirements = {row["id"]: row for row in report.get("requirements", [])}
        if requirements.get("archive-search-two-pass-index", {}).get("status") == "evidence_needed":
            archive_actions = [
                action
                for action in report.get("nextActionDetails", [])
                if action.get("action") == "collect-archive-index-proof"
            ]
            self.assertEqual(1, len(archive_actions))
            archive_action = archive_actions[0]
            self.assertEqual("desktop", archive_action["nodeRole"])
            self.assertEqual("archive", archive_action["targetService"])
            self.assertTrue(archive_action["readOnly"])
            self.assertFalse(archive_action["mutationAllowed"])
            self.assertEqual(["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"], archive_action["requiredEnvNames"])
            self.assertEqual("BackupsXS/index.jsonl", archive_action["indexPathRecommendation"])
            self.assertEqual("BackupsXS", archive_action["archiveRootRecommendation"])
            self.assertIn("archive.search", archive_action["requiredMcpTools"])
            self.assertIn("verify_archive_index_path", archive_action["nextActions"])
            self.assertIn("rerun_archive_search", archive_action["nextActions"])
            archive_rendered = json.dumps(archive_action, sort_keys=True)
            self.assertNotIn(str(ROOT), archive_rendered)
            self.assertNotIn("Bearer", archive_rendered)
            self.assertNotIn("sbp_", archive_rendered)

    def test_completion_audit_exposes_pending_source_contract_action_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(
                    {
                        "nextSourceActionDetails": [
                            {
                                "action": "run-cross-subsystem-contract-tests",
                                "scope": "active_source",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "targetFiles": ["main/java/demo/RuntimeSeam.java"],
                                "affectedSubsystems": ["S05_ExtremeZ", "S08_Adapter"],
                                "focusedTests": [
                                    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                                    "com.example.lms.orchestration.StrategyConflictResolverTest",
                                    "com.example.lms.orchestration.ExecutionPlanApplierTest",
                                    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
                                ],
                                "commands": [
                                    ".\\gradlew.bat test --tests \"ai.abandonware.nova.orch.aop.AspectOrderingContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.orchestration.StrategyConflictResolverTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.orchestration.ExecutionPlanApplierTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                    ".\\gradlew.bat test --tests \"com.example.lms.service.rag.burst.ExtremeZTriggerTest\" --no-daemon --project-cache-dir <desktop-cache>",
                                ],
                                "requiredTraceKeys": ["boosterMode.active", "extremeZ.bypassReason"],
                                "decision": "local_contract_ready",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            actions = completion_audit.source_contract_next_action_details(root)

        self.assertEqual(1, len(actions))
        action = actions[0]
        self.assertEqual("desktop", action["nodeRole"])
        self.assertEqual("active_source", action["scope"])
        self.assertEqual("local_contract_ready", action["decision"])
        self.assertTrue(action["readOnly"])
        self.assertFalse(action["mutationAllowed"])
        self.assertIn("S05_ExtremeZ", action["affectedSubsystems"])
        self.assertIn("S08_Adapter", action["affectedSubsystems"])
        self.assertIn(
            "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
            action["focusedTests"],
        )
        self.assertIn("boosterMode.active", action["requiredTraceKeys"])
        self.assertIn("extremeZ.bypassReason", action["requiredTraceKeys"])
        self.assertGreaterEqual(len(action["commands"]), 4)
        rendered = json.dumps(action, sort_keys=True)
        self.assertNotIn("C:\\", rendered)
        self.assertNotIn("Bearer", rendered)
        self.assertNotIn("sbp_", rendered)

    def test_completion_audit_exposes_structured_external_producer_proof_actions(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        actions = [
            action
            for action in report.get("nextActionDetails", [])
            if action.get("action") == "collect-external-evidence-files"
        ]
        by_role = {action.get("targetRole"): action for action in actions}
        self.assertEqual({"macmini", "notebook"}, set(by_role))
        for role, action in by_role.items():
            self.assertEqual("desktop", action["nodeRole"])
            self.assertEqual("mcp-control-loop", action["topic"])
            self.assertEqual("evidence_needed", action["decision"])
            self.assertTrue(action["desktopEvidencePaths"]["nodeSmoke"].endswith(f"{role}-node-smoke.json"))
            self.assertTrue(
                action["desktopEvidencePaths"]["producerHandoff"].endswith(f"{role}-producer-handoff.json")
            )
            self.assertTrue(action["patchdropProofDir"].endswith("__patch_drop__\\external-node-proof"))
            self.assertIn(".patch", action["requiredSidecars"])
            self.assertIn(".report.md", action["requiredSidecars"])
            self.assertIn(".verify.log", action["requiredSidecars"])
            self.assertIn(".sha256.txt", action["requiredSidecars"])
            self.assertIn(".manifest.json", action["requiredSidecars"])
            self.assertIn("pendingNotice", action["requiredSidecars"])
            self.assertEqual(
                {
                    "sourceIsolation.guard": "PASS",
                    "sourceRootKind": "local-worktree",
                    "directCanonicalSourceEdit": False,
                    "desktopFinalProof": "evidence_needed",
                    "rawSecretPatternHits": 0,
                },
                action["requiredSourceIsolation"],
            )
            rendered = json.dumps(action, sort_keys=True)
            self.assertIn("scripts/awx_mcp_node_smoke.py", rendered)
            self.assertIn("scripts/awx_mcp_producer_handoff.py", rendered)
            self.assertEqual(
                "powershell -NoProfile -ExecutionPolicy Bypass -File "
                "scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop",
                action["applyCollectedEvidenceCommand"],
            )
            self.assertIn(f"--node-role {role}", rendered)
            self.assertNotIn("Bearer", rendered)
            self.assertNotIn("sbp_", rendered)

    def test_external_evidence_audit_names_external_proof_collection_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence_dir = root / "evidence"
            patchdrop_root = root / "__patch_drop__"
            evidence_dir.mkdir()
            patchdrop_root.mkdir()

            result = toolbox.external_evidence_audit(
                {
                    "evidence_dir": str(evidence_dir),
                    "patchdrop_root": str(patchdrop_root),
                    "required_roles": ["macmini"],
                    "topic": "mcp-control-loop",
                }
            )

        actions = [
            action
            for action in result["nextActions"]
            if action.get("action") == "collect-external-evidence-files"
        ]
        self.assertEqual(1, len(actions))
        action = actions[0]
        self.assertEqual("desktop", action["nodeRole"])
        self.assertEqual("macmini", action["targetRole"])
        self.assertEqual("mcp-control-loop", action["topic"])
        self.assertEqual("evidence_needed", action["decision"])
        self.assertEqual(str(evidence_dir / "macmini-node-smoke.json"), action["desktopEvidencePaths"]["nodeSmoke"])
        self.assertEqual(str(evidence_dir / "macmini-producer-handoff.json"), action["desktopEvidencePaths"]["producerHandoff"])
        self.assertEqual(str(patchdrop_root / "external-node-proof"), action["patchdropProofDir"])
        self.assertEqual(list(toolbox.PATCHDROP_HANDOFF_REQUIRED_ARTIFACTS), action["requiredSidecars"])
        self.assertEqual(
            {
                "sourceIsolation.guard": "PASS",
                "sourceRootKind": "local-worktree",
                "directCanonicalSourceEdit": False,
                "desktopFinalProof": "evidence_needed",
                "rawSecretPatternHits": 0,
            },
            action["requiredSourceIsolation"],
        )
        rendered = json.dumps(action, sort_keys=True)
        self.assertIn("scripts/awx_mcp_node_smoke.py", rendered)
        self.assertIn("scripts/awx_mcp_producer_handoff.py", rendered)

    def test_external_evidence_audit_manifest_names_collection_action_contract(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        external_audit = next(tool for tool in manifest["tools"] if tool["name"] == "external_evidence_audit")
        contract_text = json.dumps(external_audit, sort_keys=True)

        self.assertIn("collect-external-evidence-files", contract_text)
        self.assertIn("desktopEvidencePaths", contract_text)
        self.assertIn("patchdropProofDir", contract_text)
        self.assertIn("requiredSidecars", contract_text)
        self.assertIn("requiredSourceIsolation", contract_text)

    def test_completion_audit_reports_external_apply_collected_wrapper(self):
        audit = json.loads(
            subprocess.check_output(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                    "--root",
                    str(ROOT),
                ],
                text=True,
            )
        )
        check = next(
            row
            for row in audit["checked"]
            if row["id"] == "external.apply-collected-evidence-wrapper"
        )

        self.assertTrue(check["ok"], check.get("failReason", ""))
        self.assertIn("external_evidence_intake", check["evidence"])
        self.assertIn("summaryArtifact=True", check["evidence"])
        self.assertIn("completionAudit=True", check["evidence"])
        self.assertIn("rawSecretPatternHits=0", check["evidence"])

    def test_completion_audit_reports_supabase_apply_collected_wrapper(self):
        audit = json.loads(
            subprocess.check_output(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                    "--root",
                    str(ROOT),
                ],
                text=True,
            )
        )
        check = next(
            row
            for row in audit["checked"]
            if row["id"] == "supabase.apply-collected-evidence-wrapper"
        )

        self.assertTrue(check["ok"], check.get("failReason", ""))
        self.assertIn("supabase_schema_snapshot_import", check["evidence"])
        self.assertIn("summaryArtifact=True", check["evidence"])
        self.assertIn("completionAudit=True", check["evidence"])
        self.assertIn("rawSecretPatternHits=0", check["evidence"])

    def test_desktop_control_loop_manifest_names_supabase_live_proof_action_contract(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        desktop_control_loop = next(tool for tool in manifest["tools"] if tool["name"] == "desktop_control_loop")
        contract_text = json.dumps(desktop_control_loop, sort_keys=True)

        self.assertIn("collect-supabase-live-proof", contract_text)
        self.assertIn("requiredEnv", contract_text)
        self.assertIn("requiredMcpTools", contract_text)
        self.assertIn("artifactPaths", contract_text)
        self.assertIn("requiredResultNames", contract_text)
        self.assertIn("run-cross-subsystem-contract-tests", contract_text)
        self.assertIn("focusedTests", contract_text)
        self.assertIn("requiredTraceKeys", contract_text)

    def test_desktop_control_loop_manifest_names_role_specific_external_proof_actions(self):
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        desktop_control_loop = next(tool for tool in manifest["tools"] if tool["name"] == "desktop_control_loop")
        contract_text = json.dumps(desktop_control_loop, sort_keys=True)

        self.assertIn("run_macmini_external_node_smoke", contract_text)
        self.assertIn("collect_macmini_producer_handoff_json", contract_text)
        self.assertIn("submit_macmini_patchdrop_v3_bundle_sidecars", contract_text)
        self.assertIn("run_notebook_external_node_smoke", contract_text)
        self.assertIn("collect_notebook_producer_handoff_json", contract_text)
        self.assertIn("submit_notebook_patchdrop_v3_bundle_sidecars", contract_text)

    def test_desktop_control_loop_reports_pathspec_recovery_next_action(self):
        result = toolbox.desktop_control_loop(
            {
                "root": str(ROOT),
                "canonical_root": str(ROOT),
                "topic": "mcp-control-loop",
            }
        )

        actions = {action.get("action"): action for action in result["nextActions"]}
        self.assertIn("assign-producer-role-pathspec", actions)
        pathspec_action = actions["assign-producer-role-pathspec"]
        self.assertEqual(["macmini", "notebook"], pathspec_action["missingRoles"])
        self.assertIn("role_pathspec", pathspec_action["hint"])
        self.assertIn("nonOverlapRule", pathspec_action)
        self.assertIn("exactly one producer role", pathspec_action["nonOverlapRule"])
        self.assertIn("examplePayload", pathspec_action)
        self.assertIn("role_pathspec", pathspec_action["examplePayload"])
        self.assertEqual(["<relative/source/path-owned-by-macmini>"],
                         pathspec_action["examplePayload"]["role_pathspec"]["macmini"])
        self.assertEqual(["<relative/source/path-owned-by-notebook>"],
                         pathspec_action["examplePayload"]["role_pathspec"]["notebook"])
        self.assertTrue(pathspec_action["examplePayload"]["write_dispatch"])
        self.assertIn("desktop_control_loop", pathspec_action["rerunCommand"])
        self.assertTrue(pathspec_action["rerunCommand"].startswith("@'\n"))
        self.assertIn("\n'@ | python .\\scripts\\awx_mcp_toolbox.py desktop_control_loop",
                      pathspec_action["rerunCommand"])

    def test_desktop_control_loop_exposes_harmony_runtime_next_actions(self):
        old_agent_url = os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        old_trace_url = os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL")
        try:
            os.environ.pop("AWX_AGENT_DB_CONTEXT_BASE_URL", None)
            os.environ.pop("AWX_TRACE_SNAPSHOT_BASE_URL", None)
            result = toolbox.desktop_control_loop(
                {
                    "root": str(ROOT),
                    "canonical_root": str(ROOT),
                    "topic": "mcp-control-loop",
                }
            )
        finally:
            if old_agent_url is not None:
                os.environ["AWX_AGENT_DB_CONTEXT_BASE_URL"] = old_agent_url
            if old_trace_url is not None:
                os.environ["AWX_TRACE_SNAPSHOT_BASE_URL"] = old_trace_url

        self.assertIn("harmonyScan", result)
        self.assertEqual(12, result["harmonyScan"]["outputCount"])
        score = result["harmonyScan"]["harmonyScore"]["score"]
        self.assertGreaterEqual(score, 0.0)
        self.assertLessEqual(score, 100.0)
        self.assertIn("openWeight", result["harmonyScan"]["harmonyScore"])
        self.assertIn("reviewWeight", result["harmonyScan"]["harmonyScore"])
        self.assertIn("harmonyScanNextActions", result)
        self.assertIn("start_spring_runtime_for_harmony_probe", result["harmonyScanNextActions"])
        self.assertIn("rerun_harmony_scan_with_runtime_probe", result["harmonyScanNextActions"])

    def test_completion_audit_reports_control_loop_harmony_action_contract(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checked = {row["id"]: row for row in audit["checked"]}

        self.assertTrue(checked["desktop.control-loop"]["ok"])
        self.assertIn("completionAuditNextActions", checked["desktop.control-loop"]["evidence"])
        self.assertIn("harmonyScan", checked["desktop.control-loop"]["evidence"])
        self.assertIn("harmonyScanNextActions", checked["desktop.control-loop"]["evidence"])

    def test_completion_audit_reports_trace_snapshot_probe_contract(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["trace.snapshot-probe"]["ok"])
        self.assertIn("registered=True", checks["trace.snapshot-probe"]["evidence"])
        self.assertIn("alias=True", checks["trace.snapshot-probe"]["evidence"])
        self.assertIn("manifestLocalFallback=True", checks["trace.snapshot-probe"]["evidence"])
        self.assertIn("toolboxLocalFallback=True", checks["trace.snapshot-probe"]["evidence"])
        self.assertIn("stdioHandler=True", checks["trace.snapshot-probe"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["trace.snapshot-probe"]["evidence"])
        self.assertEqual("satisfied", requirements["trace-snapshot-probe-contract"]["status"])
        self.assertIn("trace.snapshot-probe", requirements["trace-snapshot-probe-contract"]["evidence"])

    def test_completion_audit_reports_agent_db_snapshot_contract(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["agent.db-snapshot"]["ok"])
        self.assertIn("registered=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("alias=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("manifestRootInput=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("manifestLocalFallback=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("toolboxLocalFallback=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("jpaSurfaceFallback=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("stdioHandler=True", checks["agent.db-snapshot"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["agent.db-snapshot"]["evidence"])
        self.assertEqual("satisfied", requirements["agent-db-snapshot-contract"]["status"])
        self.assertIn("agent.db-snapshot", requirements["agent-db-snapshot-contract"]["evidence"])

    def test_completion_audit_reports_harmony_runtime_proof_probe_contract(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["harmony.runtime-proof-probe"]["ok"])
        self.assertIn("runtimeBaseUrlEnv=True", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertIn("traceBaseUrlEnv=True", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertIn("runtimeProofSource=True", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertIn("runtimeProofDetails=True", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertIn("runtimeProofArtifactPath=True", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["harmony.runtime-proof-probe"]["evidence"])
        self.assertEqual("satisfied", requirements["harmony-runtime-proof-contract"]["status"])
        self.assertIn("harmony.runtime-proof-probe", requirements["harmony-runtime-proof-contract"]["evidence"])

    def test_completion_audit_uses_harmony_runtime_proof_artifact_for_next_actions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "data" / "agent-handoff" / "mcp-control-tower").mkdir(parents=True)
            artifact_path = root / "data" / "agent-handoff" / "mcp-control-tower" / "harmony-runtime-proof.json"
            artifact_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.harmony_runtime_proof.v1",
                        "runtimeProof": {
                            "traceStoreExportOk": True,
                            "agentDbSnapshotOk": True,
                        },
                        "runtimeProofSource": "live-runtime",
                        "runtimeProofDetails": {
                            "agentDbSnapshotDecision": "agent_db_snapshot_loaded",
                            "traceSnapshotDecision": "trace_snapshot_loaded",
                            "rawSecretPatternHits": 0,
                        },
                        "rawSecretPatternHits": 0,
                    }
                ),
                encoding="utf-8",
            )

            checks = []
            failures = []
            completion_audit.add_check(
                checks,
                failures,
                "harmony.runtime-proof-probe",
                True,
                "harmonyRuntimeProofArtifact=True",
                "",
            )
            requirements = completion_audit.build_requirement_matrix(
                checks,
                [],
                root / "BackupsXS" / "index.jsonl",
                set(),
                set(),
                set(),
                root,
            )
            next_actions = completion_audit.completion_audit_next_actions(requirements, [])

        requirement_by_id = {row["id"]: row for row in requirements}
        self.assertEqual("satisfied", requirement_by_id["harmony-runtime-proof-live"]["status"])
        self.assertNotIn("start_spring_runtime_for_harmony_probe", next_actions)
        self.assertNotIn("rerun_harmony_scan_with_runtime_probe", next_actions)

    def test_completion_audit_reports_verify_boot_cache_isolation_contract(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["verify.boot-cache-isolation"]["ok"])
        self.assertIn("verifyBootPs1=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("awxAgentHost=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("gradleUserHome=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("projectCacheDir=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("projectCacheArg=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("intentionalTimeoutStopGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("bootSuccessProofGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("bootStartedProofGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("applicationReadyEventProofGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("runtimePrecheckProofGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("probeModeGuard=True", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["verify.boot-cache-isolation"]["evidence"])
        self.assertEqual("satisfied", requirements["boot-verifier-cache-isolation"]["status"])
        self.assertIn("verify.boot-cache-isolation", requirements["boot-verifier-cache-isolation"]["evidence"])

    def test_verify_boot_ps1_uses_desktop_project_cache_dir(self):
        verifier = (ROOT / "verify_boot.ps1").read_text(encoding="utf-8")

        self.assertIn("AWX_AGENT_HOST", verifier)
        self.assertIn("GRADLE_USER_HOME", verifier)
        self.assertIn("AWX_GRADLE_USER_HOME", verifier)
        self.assertIn("AWX_PROJECT_CACHE_DIR", verifier)
        self.assertIn("--project-cache-dir", verifier)
        self.assertIn("$ProjectCacheDir", verifier)
        self.assertNotIn("IsNullOrWhiteSpace($env:GRADLE_USER_HOME)", verifier)

    def test_verify_boot_ps1_fails_on_logged_bootrun_or_gradle_failure(self):
        verifier = (ROOT / "verify_boot.ps1").read_text(encoding="utf-8")

        self.assertIn("Task :bootRun FAILED", verifier)
        self.assertIn("bootRun logged failure", verifier)

    def test_verify_boot_ps1_allows_intentional_timeout_stop(self):
        verifier = (ROOT / "verify_boot.ps1").read_text(encoding="utf-8")

        self.assertIn("$loggedBootRunFailure -and $completed", verifier)
        self.assertIn("bootRun logged failure after intentional timeout stop", verifier)
        self.assertIn("intentional-timeout-stop=true", verifier)
        self.assertIn("bootSuccessProven=$bootSuccessProven", verifier)
        self.assertIn("bootStartedProven=$bootStartedProven", verifier)
        self.assertIn("Started .*LmsApplication", verifier)
        self.assertIn("springStartedLogProven=$springStartedLogProven", verifier)
        self.assertIn("applicationReadyEventProven=$applicationReadyEventProven", verifier)
        self.assertIn("[AblationPenalty]", verifier)
        self.assertIn("[SynergyBootstrapper]", verifier)
        self.assertIn("application-ready-event-proven", verifier)
        self.assertIn("boot-started-proven", verifier)
        self.assertIn("webServerStartedProven=$webServerStartedProven", verifier)
        self.assertIn("runtimePrecheckProven=$runtimePrecheckProven", verifier)
        self.assertIn("Tomcat started on port", verifier)
        self.assertIn("[Precheck] done.", verifier)
        self.assertIn("runtime-precheck-proven", verifier)
        self.assertIn("probeMode=$probeMode", verifier)
        self.assertIn("blocker-scan-only", verifier)
        self.assertIn("Add-Content -Path $LogPath", verifier)

    def test_completion_audit_reports_db_gap_report_artifact(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["db.structure-gap-report"]["ok"])
        self.assertIn("subsystems=8", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("actionRequired=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("critical=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("review_runtime_only=3", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("residualVolatileStateCount=4", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("residualVolatileStateIds=S02_CFVM,S03_MOE,S04_MATRYOSHKA,S07_CIH_RAG", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseSnapshot=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseReadOnly=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseMutationAllowed=False", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseSqlBundle=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacket=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketTool=execute_sql", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketReadOnly=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketMutationAllowed=False", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketQueryCount=12", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketDeclaredQueryCount=12", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketQueryCountMismatch=False", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketNextActions=set_SUPABASE_PROJECT_REF", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCollectionPacketSecretHits=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisors=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisorsStatus=not_collected", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisorsAvailable=False", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisorRowCount=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisorCollectionTool=get_advisors", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAdvisorFeatureGroup=debugging", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseRiskSummary=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseRiskFindingCount=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseRiskFindingKeys=", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAuthPlan=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseDocsContracts=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseApiKeysDocs=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseSecretKeysBackendOnly=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn(
            "externalSupabaseDefaultHostedAuthMode=dynamic_client_registration_oauth",
            checks["db.structure-gap-report"]["evidence"],
        )
        self.assertIn(
            "externalSupabaseSupportedAuthModes=dynamic_oauth,manual_ci_pat_auth",
            checks["db.structure-gap-report"]["evidence"],
        )
        self.assertIn("externalSupabaseMcpOAuthRequired=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseManualAuthMode=manual_pat_header_for_ci", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseManualAuthSensitiveEnvRefs=SUPABASE_ACCESS_TOKEN", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseEvidenceNeededCount=", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseAuthRequired=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseCliMissing=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseSnapshotImportStatus=evidence_needed", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseSnapshotImportedCount=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseMissingResultCount=12", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseMissingResultNames=schemas_and_tables", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseUnexpectedResultCount=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseUnexpectedResultNames=", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseDuplicateResultCount=0", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseDuplicateResultNames=", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseProjectScopeStatus=project_ref_missing", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseProjectScopeNextAction=set_SUPABASE_PROJECT_REF", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseProjectRefPresent=False", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("externalSupabaseProjectRefTemplateMode=True", checks["db.structure-gap-report"]["evidence"])
        self.assertIn("rawSecretPatternHits=0", checks["db.structure-gap-report"]["evidence"])
        self.assertEqual("satisfied", requirements["db-structure-gap-audit"]["status"])
        self.assertEqual("evidence_needed", requirements["supabase-live-db-structure-proof"]["status"])
        self.assertIn(
            "snapshotImportStatus=evidence_needed",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "collectionPacketDeclaredQueryCount=12",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "collectionPacketQueryCountMismatch=False",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "collectionPacketNextActions=set_SUPABASE_PROJECT_REF",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "projectScopeStatus=project_ref_missing",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "missingResultCount=12",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "advisorStatus=not_collected",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "advisorTool=get_advisors",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "advisorFeatureGroup=debugging",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "defaultHostedAuthMode=dynamic_client_registration_oauth",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "supportedAuthModes=dynamic_oauth,manual_ci_pat_auth",
            requirements["supabase-live-db-structure-proof"]["evidence"],
        )
        self.assertIn(
            "Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project",
            " ".join(requirements["supabase-live-db-structure-proof"]["evidenceNeeded"]),
        )
        self.assertNotIn(
            "Supabase MCP project_ref missing / set project_ref=<ref> after selecting the target project",
            " ".join(requirements["supabase-live-db-structure-proof"]["evidenceNeeded"]),
        )
        self.assertIn(
            "Supabase advisor rows not imported",
            " ".join(requirements["supabase-live-db-structure-proof"]["evidenceNeeded"]),
        )
        self.assertIn(
            "Supabase MCP endpoint auth required",
            " ".join(requirements["supabase-live-db-structure-proof"]["evidenceNeeded"]),
        )
        self.assertIn(
            "supabase CLI missing",
            " ".join(requirements["supabase-live-db-structure-proof"]["evidenceNeeded"]),
        )

    def test_completion_audit_reports_source_health_scorecard_artifact(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        self.assertTrue(checks["source.health-scorecard"]["ok"])
        evidence = checks["source.health-scorecard"]["evidence"]
        self.assertIn("decision=source_health_scorecard", evidence)
        self.assertRegex(evidence, r"strictEvidenceAdjustedScore=\d+(?:\.\d+)?")
        self.assertIn("evidenceNeededCount=2", evidence)
        self.assertIn("generatedAt=True", evidence)
        self.assertIn("fresh=True", evidence)
        self.assertIn("freshnessStatus=current", evidence)
        self.assertIn(
            "nextSingleAction=provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
            evidence,
        )
        self.assertIn("nextSourceAction=", evidence)
        self.assertNotIn("nextSourceAction=repair_computer_use_gui_proof_boundary", evidence)
        self.assertIn("completionAudit=var/codex-smoke/awx-mcp-completion-audit", evidence)
        self.assertIn("nextActionDetailsCount=4", evidence)
        self.assertIn("supabaseLiveProofDetail=True", evidence)
        self.assertIn("supabaseLiveProofReadOnly=True", evidence)
        self.assertIn("supabaseLiveProofMutationBlocked=True", evidence)
        self.assertIn("supabaseLiveProofMcpEndpointTemplate=True", evidence)
        self.assertIn("requiredEnv=SUPABASE_PROJECT_REF,SUPABASE_ACCESS_TOKEN", evidence)
        self.assertIn("requiredMcpTools=execute_sql,get_advisors", evidence)
        self.assertIn("requiredResultNames=12", evidence)
        self.assertIn("queryCount=12", evidence)
        self.assertIn("applyCollectedEvidenceCommand=scripts\\supabase_apply_collected_evidence.ps1", evidence)
        self.assertIn("externalProducerProofDetailCount=2", evidence)
        self.assertIn("externalProducerProofRoles=macmini,notebook", evidence)
        self.assertIn("externalProducerProofSidecars=True", evidence)
        self.assertIn("externalProducerProofSourceIsolation=True", evidence)
        self.assertIn("externalProducerProofApplyCommand=True", evidence)
        self.assertIn("externalProducerProofNextActions=True", evidence)
        self.assertIn("externalProducerProofCommandTemplates=True", evidence)
        self.assertIn("nextSourceActionDetailsCount=1", evidence)
        self.assertRegex(evidence, r"sourceContractDetail=(True|False)")
        self.assertRegex(evidence, r"sourceContractProofPassed=(True|False)")
        self.assertRegex(evidence, r"sourceContractProofPassedCount=\d+")
        self.assertIn("sourceContractProofRequiredCount=4", evidence)
        self.assertIn("broadRuntimeTestProofPassed=True", evidence)
        self.assertRegex(evidence, r"broadRuntimeTestSuites=\d+")
        self.assertRegex(evidence, r"broadRuntimeTests=\d+")
        self.assertIn("broadRuntimeFailures=0", evidence)
        self.assertIn("broadRuntimeErrors=0", evidence)
        self.assertIn("rawSecretPatternHits=0", evidence)
        self.assertIn("nextActionDetailsWindowsAbsPathHits=0", evidence)
        self.assertIn("nextSourceActionDetailsWindowsAbsPathHits=0", evidence)
        self.assertEqual("satisfied", requirements["source-health-scorecard-contract"]["status"])

    def test_source_health_scorecard_summary_requires_fresh_generated_at(self):
        summary = {
            "present": True,
            "decision": "source_health_scorecard",
            "strictEvidenceAdjustedScore": 82.5,
            "evidenceNeededCount": 2,
            "nextSingleAction": "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
            "nextSourceAction": "source_runtime_proof_current",
            "completionAudit": "var/codex-smoke/awx-mcp-completion-audit-current.json",
            "nextActionDetailsCount": 3,
            "supabaseLiveProofDetail": True,
            "supabaseLiveProofReadOnly": True,
            "supabaseLiveProofMutationBlocked": True,
            "supabaseLiveProofMcpEndpointTemplate": True,
            "requiredEnv": "SUPABASE_PROJECT_REF,SUPABASE_ACCESS_TOKEN",
            "requiredMcpTools": "execute_sql,get_advisors",
            "requiredResultNameCount": 12,
            "queryCount": 12,
            "applyCollectedEvidenceCommand": "scripts\\supabase_apply_collected_evidence.ps1",
            "externalProducerProofDetailCount": 2,
            "externalProducerProofRoles": "macmini,notebook",
            "externalProducerProofSidecars": True,
            "externalProducerProofSourceIsolation": True,
            "externalProducerProofApplyCommand": True,
            "externalProducerProofNextActions": True,
            "externalProducerProofCommandTemplates": True,
            "sourceContractProofPassed": True,
            "sourceContractProofPassedCount": 4,
            "sourceContractProofRequiredCount": 4,
            "broadRuntimeTestProofPassed": True,
            "broadRuntimeTestSuiteCount": 12,
            "broadRuntimeTestCount": 200,
            "broadRuntimeTestFailureCount": 0,
            "broadRuntimeTestErrorCount": 0,
            "rawSecretPatternHits": 0,
            "nextActionDetailsWindowsAbsPathHits": 0,
            "nextSourceActionDetailsWindowsAbsPathHits": 0,
            "generatedAt": False,
            "fresh": False,
            "freshnessStatus": "missing_generated_at",
        }

        self.assertFalse(completion_audit.source_health_scorecard_ready(summary))
        fail_reason = completion_audit.source_health_scorecard_ready_fail_reason(summary)
        self.assertIn("generatedAt", fail_reason)
        self.assertIn("fresh", fail_reason)
        self.assertIn("freshnessStatus", fail_reason)

    def test_completion_audit_reports_goal_next_auto_command_packet(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        check = checks["goal-next.command-packet"]
        self.assertTrue(check["ok"], check["failReason"])
        evidence = check["evidence"]
        self.assertIn("latestPresent=True", evidence)
        self.assertIn("latestFresh=True", evidence)
        self.assertIn("commandPacketPresent=True", evidence)
        self.assertIn("commandCount=7", evidence)
        self.assertIn("lanes=supabase,external_desktop,external_producer", evidence)
        self.assertIn("archive", evidence)
        self.assertIn("producerRoles=macmini,notebook", evidence)
        self.assertIn("supabaseEnvNames=SUPABASE_PROJECT_REF,SUPABASE_ACCESS_TOKEN", evidence)
        self.assertIn("supabaseReadOnlyContract=True", evidence)
        self.assertIn("supabaseMcpEndpointTemplate=True", evidence)
        self.assertIn("supabaseDocsRefs=True", evidence)
        self.assertIn("producerPlaceholders=True", evidence)
        self.assertIn("computerUsePresent=True", evidence)
        self.assertIn("computerUseDecision=ok", evidence)
        self.assertIn("computerUseOk=True", evidence)
        self.assertIn("commandWindowsAbsPathHits=0", evidence)
        self.assertIn("rawSecretPatternHits=0", evidence)
        self.assertEqual("satisfied", requirements["goal-next-auto-command-packet"]["status"])

    def test_goal_next_command_packet_summary_requires_safe_lanes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke = root / "var" / "codex-smoke"
            packet_dir = smoke / "goal-next-auto-current"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "goal-next-auto.command-packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.command_packet.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "topic": "mcp-control-loop",
                        "commandCount": 2,
                        "lanes": ["supabase", "external_producer"],
                        "commands": [
                            {
                                "lane": "supabase",
                                "role": "desktop",
                                "command": "powershell -File scripts\\supabase_apply_collected_evidence.ps1",
                                "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                                "readOnly": True,
                                "mutationAllowed": False,
                                "mcpEndpointTemplate": (
                                    "https://mcp.supabase.com/mcp?"
                                    "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs"
                                ),
                                "docsRefs": ["https://supabase.com/docs/guides/ai-tools/mcp"],
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root C:\\shared\\bad "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (smoke / "goal-next-auto.latest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.latest.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "commandPacketPath": str(packet_path),
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.goal_next_command_packet_summary(root)

        self.assertFalse(completion_audit.goal_next_command_packet_ready(summary))
        fail_reason = completion_audit.goal_next_command_packet_ready_fail_reason(summary)
        self.assertIn("external_desktop", fail_reason)
        self.assertIn("producerRoles", fail_reason)
        self.assertIn("producerPlaceholders", fail_reason)
        self.assertIn("commandWindowsAbsPathHits", fail_reason)

    def test_goal_next_command_packet_summary_requires_supabase_smoke_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke = root / "var" / "codex-smoke"
            packet_dir = smoke / "goal-next-auto-current"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "goal-next-auto.command-packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.command_packet.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "topic": "mcp-control-loop",
                        "commandCount": 6,
                        "lanes": ["supabase", "external_desktop", "external_producer"],
                        "commands": [
                            {
                                "lane": "supabase",
                                "role": "desktop",
                                "command": "powershell -File scripts\\supabase_apply_collected_evidence.ps1",
                                "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                            },
                            {
                                "lane": "external_desktop",
                                "role": "desktop",
                                "command": (
                                    "powershell -File scripts\\external_apply_collected_evidence.ps1 "
                                    "-Root . -Topic mcp-control-loop"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )
            packet_path.with_suffix(".md").write_text("safe command packet\n", encoding="utf-8")
            (smoke / "goal-next-auto.latest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.latest.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "commandPacketPath": str(packet_path),
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.goal_next_command_packet_summary(root)

        self.assertFalse(completion_audit.goal_next_command_packet_ready(summary))
        self.assertFalse(summary["supabaseSmokePresent"])
        self.assertFalse(summary["supabaseSmokeMcpDecisionReady"])
        self.assertIn("supabaseSmoke", completion_audit.goal_next_command_packet_ready_fail_reason(summary))

    def test_goal_next_command_packet_summary_requires_computer_use_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke = root / "var" / "codex-smoke"
            packet_dir = smoke / "goal-next-auto-current"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "goal-next-auto.command-packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.command_packet.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "topic": "mcp-control-loop",
                        "commandCount": 6,
                        "lanes": ["supabase", "external_desktop", "external_producer"],
                        "commands": [
                            {
                                "lane": "supabase",
                                "role": "desktop",
                                "command": "powershell -File scripts\\supabase_apply_collected_evidence.ps1",
                                "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                            },
                            {
                                "lane": "external_desktop",
                                "role": "desktop",
                                "command": (
                                    "powershell -File scripts\\external_apply_collected_evidence.ps1 "
                                    "-Root . -Topic mcp-control-loop"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )
            packet_path.with_suffix(".md").write_text("safe command packet\n", encoding="utf-8")
            (smoke / "goal-next-auto.latest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.latest.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "commandPacketPath": str(packet_path),
                        "supabaseSmoke": {
                            "parsed": True,
                            "decision": "evidence_needed",
                            "mcpDecision": "mcp_endpoint_auth_required",
                            "projectScopeStatus": "project_ref_missing",
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.goal_next_command_packet_summary(root)

        self.assertFalse(completion_audit.goal_next_command_packet_ready(summary))
        self.assertFalse(summary["computerUsePresent"])
        self.assertIn("computerUse", completion_audit.goal_next_command_packet_ready_fail_reason(summary))

    def test_goal_next_command_packet_summary_requires_supabase_readonly_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke = root / "var" / "codex-smoke"
            packet_dir = smoke / "goal-next-auto-current"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "goal-next-auto.command-packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.command_packet.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "topic": "mcp-control-loop",
                        "commandCount": 6,
                        "lanes": ["supabase", "external_desktop", "external_producer"],
                        "computerUse": {
                            "present": True,
                            "parsed": True,
                            "ok": True,
                            "decision": "ok",
                            "reachable": True,
                            "stale": False,
                            "appCount": 3,
                        },
                        "commands": [
                            {
                                "lane": "supabase",
                                "role": "desktop",
                                "command": "powershell -File scripts\\supabase_apply_collected_evidence.ps1",
                                "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                                "requiredMcpTools": ["execute_sql", "get_advisors"],
                            },
                            {
                                "lane": "external_desktop",
                                "role": "desktop",
                                "command": (
                                    "powershell -File scripts\\external_apply_collected_evidence.ps1 "
                                    "-Root . -Topic mcp-control-loop"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )
            packet_path.with_suffix(".md").write_text("safe command packet\n", encoding="utf-8")
            (smoke / "goal-next-auto.latest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.latest.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "commandPacketPath": str(packet_path),
                        "supabaseSmoke": {
                            "parsed": True,
                            "decision": "evidence_needed",
                            "mcpDecision": "mcp_endpoint_auth_required",
                            "projectScopeStatus": "project_ref_missing",
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.goal_next_command_packet_summary(root)

        self.assertFalse(completion_audit.goal_next_command_packet_ready(summary))
        self.assertFalse(summary["supabaseReadOnlyContract"])
        self.assertIn("supabaseReadOnlyContract", completion_audit.goal_next_command_packet_ready_fail_reason(summary))

    def test_goal_next_command_packet_summary_reads_computer_use_from_packet(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke = root / "var" / "codex-smoke"
            packet_dir = smoke / "goal-next-auto-current"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "goal-next-auto.command-packet.json"
            packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.command_packet.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "topic": "mcp-control-loop",
                        "commandCount": 6,
                        "lanes": ["supabase", "external_desktop", "external_producer"],
                        "computerUse": {
                            "present": True,
                            "parsed": True,
                            "ok": True,
                            "decision": "ok",
                            "reachable": True,
                            "stale": False,
                            "appCount": 3,
                        },
                        "commands": [
                            {
                                "lane": "supabase",
                                "role": "desktop",
                                "command": "powershell -File scripts\\supabase_apply_collected_evidence.ps1",
                                "requiredEnvNames": ["SUPABASE_PROJECT_REF", "SUPABASE_ACCESS_TOKEN"],
                                "readOnly": True,
                                "mutationAllowed": False,
                                "mcpEndpointTemplate": (
                                    "https://mcp.supabase.com/mcp?"
                                    "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs"
                                ),
                                "docsRefs": ["https://supabase.com/docs/guides/ai-tools/mcp"],
                            },
                            {
                                "lane": "external_desktop",
                                "role": "desktop",
                                "command": (
                                    "powershell -File scripts\\external_apply_collected_evidence.ps1 "
                                    "-Root . -Topic mcp-control-loop"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "macmini",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role macmini"
                                ),
                            },
                            {
                                "lane": "external_producer",
                                "role": "notebook",
                                "command": (
                                    "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                    "--canonical-root <desktop-canonical-root> --node-role notebook"
                                ),
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )
            packet_path.with_suffix(".md").write_text("safe command packet\n", encoding="utf-8")
            (smoke / "goal-next-auto.latest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.goal_next_auto.latest.v1",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "decision": "evidence_needed",
                        "commandPacketPath": str(packet_path),
                        "supabaseSmoke": {
                            "parsed": True,
                            "decision": "evidence_needed",
                            "mcpDecision": "mcp_endpoint_auth_required",
                            "projectScopeStatus": "project_ref_missing",
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.goal_next_command_packet_summary(root)

        self.assertTrue(completion_audit.goal_next_command_packet_ready(summary))
        self.assertTrue(summary["computerUsePresent"])
        self.assertTrue(summary["computerUseOk"])
        self.assertEqual(3, summary["computerUseAppCount"])

    def test_completion_audit_cli_writes_explicit_output_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "completion-audit.result.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                    "--root",
                    str(ROOT),
                    "--output",
                    str(output_path),
                ],
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertTrue(output_path.is_file())
            stdout_audit = json.loads(completed.stdout)
            file_audit = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(stdout_audit["schemaVersion"], file_audit["schemaVersion"])
            self.assertEqual(stdout_audit["status"], file_audit["status"])
            self.assertEqual(0, file_audit["rawSecretPatternHits"])

    def test_completion_audit_reports_websoak_provider_disabled_runtime_smoke(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        audit = json.loads(completed.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        requirements = {row["id"]: row for row in audit["requirements"]}

        check = checks["websoak.provider-disabled-runtime-smoke"]
        self.assertTrue(check["ok"], check["failReason"])
        self.assertIn("status=200", check["evidence"])
        self.assertIn("providerDisabledOrSkipped=True", check["evidence"])
        self.assertIn("providerDisabledCount=1", check["evidence"])
        self.assertIn("outCount=5", check["evidence"])
        self.assertIn("rawInputCount=5", check["evidence"])
        self.assertIn("starvationFallback.trigger=BELOW_MIN_CITATIONS", check["evidence"])
        self.assertIn("secretPatternHits=0", check["evidence"])
        self.assertIn("rawQueryHits=0", check["evidence"])
        self.assertIn("probeKeyHits=0", check["evidence"])
        self.assertIn("rawSecretPatternHits=0", check["evidence"])
        self.assertEqual("satisfied", requirements["runtime-provider-websoak-proof"]["status"])

    def test_websoak_provider_disabled_summary_treats_malformed_counts_as_not_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            smoke_dir = root / "verification" / "websoak-kpi-smoke"
            smoke_dir.mkdir(parents=True)
            (smoke_dir / "websoak-kpi-provider-disabled.json").write_text(
                json.dumps(
                    {
                        "summary": {
                            "ok": True,
                            "status": "200",
                            "providerDisabledOrSkipped": True,
                            "providerDisabledCount": "one",
                            "providerStates": ["skipped"],
                            "outCount": "five",
                            "rawInputCount": "five",
                            "cacheOnlyMergedCount": "none",
                            "rescueMergeUsed": False,
                            "starvationTrigger": "BELOW_MIN_CITATIONS",
                            "secretPatternHits": 0,
                            "rawQueryHits": 0,
                            "probeKeyHits": 0,
                        }
                    }
                ),
                encoding="utf-8",
            )
            (smoke_dir / "websoak-kpi-provider-disabled.summary.txt").write_text(
                "ok=True status=200",
                encoding="utf-8",
            )

            summary = completion_audit.websoak_provider_disabled_artifact_summary(root)

        self.assertEqual(0, summary["providerDisabledCount"])
        self.assertEqual(0, summary["outCount"])
        self.assertEqual(0, summary["rawInputCount"])
        self.assertEqual(0, summary["cacheOnlyMergedCount"])
        self.assertFalse(completion_audit.websoak_provider_disabled_ready(summary))
        reason = completion_audit.websoak_provider_disabled_ready_fail_reason(summary)
        self.assertIn("providerDisabledCount", reason)
        self.assertIn("outCount", reason)
        self.assertIn("rawInputCount", reason)

    def test_source_health_scorecard_ready_accepts_passed_source_contract_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            verification = root / "verification"
            verification.mkdir(parents=True)
            (verification / "source-health-scorecard.json").write_text(
                json.dumps(
                    {
                        "decision": "source_health_scorecard",
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "strictEvidenceAdjustedScore": 82.8772,
                        "evidenceNeededCount": 2,
                        "nextSingleAction": "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
                        "nextSourceAction": "run_broad_test_runtime_proof",
                        "inputArtifacts": {
                            "completionAudit": "var/codex-smoke/awx-mcp-completion-audit-latest.json"
                        },
                        "nextActionDetails": [
                            {
                                "action": "collect-supabase-live-proof",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "requiredEnv": [
                                    {"name": "SUPABASE_PROJECT_REF", "sensitive": False},
                                    {"name": "SUPABASE_ACCESS_TOKEN", "sensitive": True},
                                ],
                                "requiredMcpTools": ["execute_sql", "get_advisors"],
                                "mcpEndpointTemplate": (
                                    "https://mcp.supabase.com/mcp?"
                                    "project_ref=${SUPABASE_PROJECT_REF}"
                                    "&read_only=true"
                                    "&features=database,debugging,docs"
                                ),
                                "requiredResultNames": [f"query_{idx}" for idx in range(12)],
                                "queryCount": 12,
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\supabase_apply_collected_evidence.ps1"
                                ),
                                "resultPathRecommendation": "data/db-gap-report/supabase-query-results.json",
                                "advisorResultPathRecommendation": "data/db-gap-report/supabase-advisors.json",
                            },
                            {
                                "action": "collect-external-evidence-files",
                                "targetRole": "macmini",
                                "requiredSidecars": [
                                    ".patch",
                                    ".report.md",
                                    ".verify.log",
                                    ".sha256.txt",
                                    ".manifest.json",
                                    "pendingNotice",
                                ],
                                "requiredSourceIsolation": {
                                    "guard": "PASS",
                                    "sourceRootKind": "local-worktree",
                                    "directCanonicalSourceEdit": False,
                                    "desktopFinalProof": "evidence_needed",
                                    "rawSecretPatternHits": 0,
                                },
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
                                ),
                                "nextActions": [
                                    "run_macmini_external_node_smoke",
                                    "collect_macmini_producer_handoff_json",
                                    "submit_macmini_patchdrop_v3_bundle_sidecars",
                                ],
                                "producerCommandTemplates": [
                                    (
                                        "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                        "--canonical-root <desktop-canonical-root> --node-role macmini"
                                    ),
                                    (
                                        "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                        "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
                                        "--producer-script <PatchDrop>\\producer_bundle.py --node-role macmini "
                                        "--topic mcp-control-loop --pathspec <relative/source/path>"
                                    ),
                                ],
                            },
                            {
                                "action": "collect-external-evidence-files",
                                "targetRole": "notebook",
                                "requiredSidecars": [
                                    ".patch",
                                    ".report.md",
                                    ".verify.log",
                                    ".sha256.txt",
                                    ".manifest.json",
                                    "pendingNotice",
                                ],
                                "requiredSourceIsolation": {
                                    "guard": "PASS",
                                    "sourceRootKind": "local-worktree",
                                    "directCanonicalSourceEdit": False,
                                    "desktopFinalProof": "evidence_needed",
                                    "rawSecretPatternHits": 0,
                                },
                                "applyCollectedEvidenceCommand": (
                                    "powershell -NoProfile -ExecutionPolicy Bypass "
                                    "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop"
                                ),
                                "nextActions": [
                                    "run_notebook_external_node_smoke",
                                    "collect_notebook_producer_handoff_json",
                                    "submit_notebook_patchdrop_v3_bundle_sidecars",
                                ],
                                "producerCommandTemplates": [
                                    (
                                        "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
                                        "--canonical-root <desktop-canonical-root> --node-role notebook"
                                    ),
                                    (
                                        "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
                                        "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
                                        "--producer-script <PatchDrop>\\producer_bundle.py --node-role notebook "
                                        "--topic mcp-control-loop --pathspec <relative/source/path>"
                                    ),
                                ],
                            }
                        ],
                        "nextSourceActionDetails": [],
                        "focusedCrossSubsystemContractProof": {
                            "passed": True,
                            "passedCount": 4,
                            "requiredCount": 4,
                        },
                        "broadRuntimeTestProof": {
                            "passed": True,
                            "suiteCount": 5,
                            "testCount": 10,
                            "failureCount": 0,
                            "errorCount": 0,
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.source_health_scorecard_artifact_summary(root)

        self.assertTrue(summary["sourceContractProofPassed"])
        self.assertEqual(4, summary["sourceContractProofPassedCount"])
        self.assertEqual(4, summary["sourceContractProofRequiredCount"])
        self.assertTrue(summary["broadRuntimeTestProofPassed"])
        self.assertEqual(5, summary["broadRuntimeTestSuiteCount"])
        self.assertEqual(10, summary["broadRuntimeTestCount"])
        self.assertEqual(2, summary["externalProducerProofDetailCount"])
        self.assertEqual("macmini,notebook", summary["externalProducerProofRoles"])
        self.assertTrue(summary["externalProducerProofSidecars"])
        self.assertTrue(summary["externalProducerProofSourceIsolation"])
        self.assertTrue(summary["externalProducerProofApplyCommand"])
        self.assertTrue(summary["externalProducerProofNextActions"])
        self.assertTrue(summary["externalProducerProofCommandTemplates"])
        self.assertEqual("", completion_audit.source_health_scorecard_ready_fail_reason(summary))

    def test_completion_audit_summarizes_db_gap_supabase_import_diagnostics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {},
                            "snapshotImport": {
                                "status": "imported",
                                "importedCount": 1,
                                "missingResultCount": 2,
                                "missingResultNames": ["rls_and_table_flags", "exposed_tables_without_rls"],
                                "unexpectedResultCount": 1,
                                "unexpectedResultNames": ["typo_extra_probe"],
                                "duplicateResultCount": 1,
                                "duplicateResultNames": ["schemas_and_tables"],
                                "resultsPath": "C:/private/results.json",
                            },
                            "inputShape": {
                                "payloadKind": "object",
                                "supportedResultItemCount": 0,
                                "rawTopLevelKeys": ["execute_sql_result"],
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertTrue(summary["generatedAt"])
        self.assertTrue(summary["fresh"])
        self.assertEqual("current", summary["freshnessStatus"])
        self.assertEqual("imported", summary["externalSupabaseSnapshotImportStatus"])
        self.assertEqual(1, summary["externalSupabaseSnapshotImportedCount"])
        self.assertEqual(2, summary["externalSupabaseMissingResultCount"])
        self.assertEqual("rls_and_table_flags,exposed_tables_without_rls", summary["externalSupabaseMissingResultNames"])
        self.assertEqual(1, summary["externalSupabaseUnexpectedResultCount"])
        self.assertEqual("typo_extra_probe", summary["externalSupabaseUnexpectedResultNames"])
        self.assertEqual(1, summary["externalSupabaseDuplicateResultCount"])
        self.assertEqual("schemas_and_tables", summary["externalSupabaseDuplicateResultNames"])
        self.assertIn(
            "externalSupabaseMissingResultNames(rls_and_table_flags,exposed_tables_without_rls)",
            completion_audit.db_gap_report_ready_fail_reason(summary),
        )
        self.assertIn(
            "externalSupabaseUnexpectedResultNames(typo_extra_probe)",
            completion_audit.db_gap_report_ready_fail_reason(summary),
        )
        self.assertIn(
            "externalSupabaseDuplicateResultNames(schemas_and_tables)",
            completion_audit.db_gap_report_ready_fail_reason(summary),
        )
        deferred_summary = dict(summary)
        deferred_summary.update(
            {
                "externalSupabaseSnapshotImportStatus": "evidence_needed",
                "externalSupabaseSnapshotImportedCount": 0,
                "externalSupabaseUnexpectedResultCount": 0,
                "externalSupabaseUnexpectedResultNames": "",
                "externalSupabaseDuplicateResultCount": 0,
                "externalSupabaseDuplicateResultNames": "",
                "externalSupabaseAuthRequired": True,
                "externalSupabaseCliMissing": True,
                "externalSupabaseProjectScopeStatus": "project_ref_missing",
            }
        )
        self.assertNotIn(
            "externalSupabaseMissingResult",
            completion_audit.db_gap_report_ready_fail_reason(deferred_summary),
        )
        self.assertTrue(summary["externalSupabaseInputShape"])
        self.assertEqual(0, summary["externalSupabaseSupportedResultItemCount"])
        self.assertNotIn("results.json", json.dumps(summary, sort_keys=True))
        self.assertNotIn("execute_sql_result", json.dumps(summary, sort_keys=True))

    def test_db_gap_report_summary_requires_fresh_generated_at(self):
        summary = {
            "present": True,
            "subsystemCount": 8,
            "actionRequiredCount": 0,
            "criticalCount": 0,
            "mediumCount": 0,
            "residualVolatileStateCount": 0,
            "externalSupabaseSnapshot": True,
            "externalSupabaseReadOnly": True,
            "externalSupabaseMutationBlocked": True,
            "externalSupabaseSqlBundle": True,
            "externalSupabaseCollectionPacket": True,
            "externalSupabaseCollectionPacketTool": "execute_sql",
            "externalSupabaseCollectionPacketReadOnly": True,
            "externalSupabaseCollectionPacketMutationBlocked": True,
            "externalSupabaseCollectionPacketQueryCount": 12,
            "externalSupabaseCollectionPacketDeclaredQueryCount": 12,
            "externalSupabaseCollectionPacketQueryCountMismatch": False,
            "externalSupabaseCollectionPacketNextActions": "set_SUPABASE_PROJECT_REF",
            "externalSupabaseCollectionPacketSecretHits": 0,
            "externalSupabaseAdvisors": True,
            "externalSupabaseAdvisorCollectionTool": "get_advisors",
            "externalSupabaseAdvisorFeatureGroup": "debugging",
            "externalSupabaseAdvisorRowCount": 0,
            "externalSupabaseRiskSummary": True,
            "externalSupabaseAuthPlan": True,
            "externalSupabaseDocsContracts": True,
            "externalSupabaseApiKeysDocs": True,
            "externalSupabaseSecretKeysBackendOnly": True,
            "externalSupabaseDefaultHostedAuthMode": "dynamic_client_registration_oauth",
            "externalSupabaseSupportedAuthModes": "dynamic_oauth,manual_ci_pat_auth",
            "externalSupabaseMcpOAuthRequired": True,
            "externalSupabaseManualAuthMode": "manual_pat_header_for_ci",
            "externalSupabaseManualAuthSensitiveEnvRefs": "SUPABASE_ACCESS_TOKEN",
            "externalSupabaseRiskFindingCount": 0,
            "externalSupabaseSnapshotImportStatus": "evidence_needed",
            "externalSupabaseSnapshotImportedCount": 0,
            "externalSupabaseMissingResultCount": 12,
            "externalSupabaseUnexpectedResultCount": 0,
            "externalSupabaseDuplicateResultCount": 0,
            "externalSupabaseInputShape": True,
            "externalSupabaseSupportedResultItemCount": 0,
            "rawSecretPatternHits": 0,
            "generatedAt": False,
            "fresh": False,
            "freshnessStatus": "missing_generated_at",
        }

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        fail_reason = completion_audit.db_gap_report_ready_fail_reason(summary)
        self.assertIn("generatedAt", fail_reason)
        self.assertIn("fresh", fail_reason)
        self.assertIn("freshnessStatus", fail_reason)

    def test_supabase_live_db_requirement_prefers_fresh_auth_probe_over_stale_unreachable_probe(self):
        summary = {
            "externalSupabaseProjectRefPresent": False,
            "externalSupabaseSnapshotImportStatus": "evidence_needed",
            "externalSupabaseSnapshotImportedCount": 0,
            "externalSupabaseSupportedResultItemCount": 0,
            "externalSupabaseMissingResultCount": 1,
            "externalSupabaseMissingResultNames": "schemas_and_tables",
            "externalSupabaseUnexpectedResultCount": 0,
            "externalSupabaseDuplicateResultCount": 0,
            "externalSupabaseRiskFindingCount": 0,
            "externalSupabaseAdvisorsStatus": "not_collected",
            "externalSupabaseAdvisorsAvailable": False,
            "externalSupabaseAdvisorCollectionTool": "get_advisors",
            "externalSupabaseAdvisorFeatureGroup": "debugging",
            "externalSupabaseAdvisorRowCount": 0,
            "externalSupabaseAdvisorErrorCount": 0,
            "externalSupabaseAdvisorWarningCount": 0,
            "externalSupabaseEvidenceNeeded": [
                "Supabase MCP endpoint not reachable from this host / verify network and URL",
                "supabase CLI missing / verify with `supabase --version` after installing CLI",
            ],
            "externalSupabaseAuthRequired": True,
        }
        fresh_auth_smoke = {
            "summaryFresh": True,
            "summaryFreshnessStatus": "current",
            "mcpReachable": True,
            "mcpDecision": "mcp_endpoint_auth_required",
        }

        requirement = completion_audit.supabase_live_db_structure_requirement(summary, fresh_auth_smoke)
        evidence_needed_text = " ".join(requirement["evidenceNeeded"])

        self.assertNotIn("endpoint not reachable", evidence_needed_text)
        self.assertIn("Supabase MCP endpoint auth required", evidence_needed_text)
        self.assertIn("supabase CLI missing", evidence_needed_text)

    def test_completion_audit_requires_db_gap_supabase_advisor_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "residual_volatile_state_count": 0,
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "collectionPacket": {
                                "status": "available",
                                "mcpTool": "execute_sql",
                                "readOnly": True,
                                "mutationAllowed": False,
                                "queryCount": 12,
                                "secretPatternHits": 0,
                            },
                            "riskSummary": {},
                            "authPlan": {
                                "manualAuthMode": "manual_pat_header_for_ci",
                                "manualAuthSensitiveEnvRefs": "SUPABASE_ACCESS_TOKEN",
                            },
                            "snapshotImport": {
                                "status": "evidence_needed",
                                "importedCount": 0,
                            },
                            "inputShape": {
                                "payloadKind": "not_imported",
                                "supportedResultItemCount": 0,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        self.assertIn(
            "externalSupabaseAdvisors",
            completion_audit.db_gap_report_ready_fail_reason(summary),
        )

    def test_completion_audit_detects_stale_supabase_snapshot_template_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            snapshot_path = report_dir / "supabase-schema-snapshot.json"
            sql_bundle_path = report_dir / "supabase-readonly-snapshot.sql"
            result_template_path = report_dir / "supabase-query-results.template.json"
            snapshot_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "sqlBundlePath": str(sql_bundle_path),
                        "resultTemplatePath": str(result_template_path),
                        "dbSnapshotPlan": {
                            "docsRefs": [{}, {}, {}],
                            "breakingChanges": [{}],
                            "securityContracts": ["a", "b", "c"],
                            "sqlQueries": [
                                {"name": "schemas_and_tables", "sql": "select 1"},
                                {"name": "rls_and_table_flags", "sql": "select 2"},
                            ],
                        },
                        "snapshots": [],
                        "evidence_needed": [],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            sql_bundle_path.write_text(
                "\n".join(
                    [
                        "START TRANSACTION READ ONLY;",
                        "-- Docs: supabase-mcp https://supabase.com/docs/guides/ai-tools/mcp",
                        "-- Breaking change: data-api-explicit-grants",
                        "-- Security contract: data_api_grants_control_reachability",
                        "-- schemas_and_tables",
                        "select 1;",
                        "COMMIT;",
                    ]
                ),
                encoding="utf-8",
            )
            result_template_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_query_results_template.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "importTool": "supabase_schema_snapshot_import",
                        "queries": [
                            {"name": "schemas_and_tables", "sqlHash": "abc"},
                        ],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            summary = completion_audit.supabase_schema_snapshot_artifact_summary(root)

        self.assertEqual(2, summary["planSqlQueryCount"])
        self.assertEqual(1, summary["resultTemplateQueryCount"])
        self.assertEqual("rls_and_table_flags", summary["resultTemplateMissingQueryNames"])
        self.assertEqual("rls_and_table_flags", summary["sqlBundleMissingQueryNames"])
        self.assertFalse(completion_audit.supabase_schema_snapshot_artifact_ready(summary))
        self.assertIn(
            "resultTemplateMissingQueryNames(rls_and_table_flags)",
            completion_audit.supabase_schema_snapshot_artifact_ready_fail_reason(summary),
        )

    def test_completion_audit_detects_duplicate_supabase_snapshot_query_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            snapshot_path = report_dir / "supabase-schema-snapshot.json"
            sql_bundle_path = report_dir / "supabase-readonly-snapshot.sql"
            result_template_path = report_dir / "supabase-query-results.template.json"
            snapshot_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "schemaSnapshotAvailable": False,
                        "sqlBundlePath": str(sql_bundle_path),
                        "resultTemplatePath": str(result_template_path),
                        "dbSnapshotPlan": {
                            "docsRefs": [{}, {}, {}],
                            "breakingChanges": [{}],
                            "securityContracts": ["a", "b", "c"],
                            "sqlQueries": [
                                {"name": "schemas_and_tables", "sql": "select 1"},
                                {"name": "rls_and_table_flags", "sql": "select 2"},
                            ],
                        },
                        "snapshots": [],
                        "evidence_needed": [],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            sql_bundle_path.write_text(
                "\n".join(
                    [
                        "START TRANSACTION READ ONLY;",
                        "-- Docs: supabase-mcp https://supabase.com/docs/guides/ai-tools/mcp",
                        "-- Breaking change: data-api-explicit-grants",
                        "-- Security contract: data_api_grants_control_reachability",
                        "-- schemas_and_tables",
                        "select 1;",
                        "-- rls_and_table_flags",
                        "select 2;",
                        "-- schemas_and_tables",
                        "select 1;",
                        "COMMIT;",
                    ]
                ),
                encoding="utf-8",
            )
            result_template_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_query_results_template.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "importTool": "supabase_schema_snapshot_import",
                        "queries": [
                            {"name": "schemas_and_tables", "sqlHash": "abc"},
                            {"name": "rls_and_table_flags", "sqlHash": "def"},
                            {"name": "schemas_and_tables", "sqlHash": "abc"},
                        ],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            summary = completion_audit.supabase_schema_snapshot_artifact_summary(root)

        self.assertEqual("", summary["planDuplicateQueryNames"])
        self.assertEqual("schemas_and_tables", summary["sqlBundleDuplicateQueryNames"])
        self.assertEqual("schemas_and_tables", summary["resultTemplateDuplicateQueryNames"])
        self.assertFalse(completion_audit.supabase_schema_snapshot_artifact_ready(summary))
        fail_reason = completion_audit.supabase_schema_snapshot_artifact_ready_fail_reason(summary)
        self.assertIn("sqlBundleDuplicateQueryNames(schemas_and_tables)", fail_reason)
        self.assertIn("resultTemplateDuplicateQueryNames(schemas_and_tables)", fail_reason)
        self.assertNotIn("select 1", json.dumps(summary, sort_keys=True))

    def test_completion_audit_rejects_mutating_supabase_collection_packet_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            snapshot_path = report_dir / "supabase-schema-snapshot.json"
            sql_bundle_path = report_dir / "supabase-readonly-snapshot.sql"
            result_template_path = report_dir / "supabase-query-results.template.json"
            collection_packet_path = report_dir / "supabase-execute-sql-collection.packet.json"
            names = [f"query_{idx}" for idx in range(10)]
            snapshot_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_schema_snapshot.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "schemaSnapshotAvailable": False,
                        "sqlBundlePath": str(sql_bundle_path),
                        "resultTemplatePath": str(result_template_path),
                        "collectionPacketPath": str(collection_packet_path),
                        "dbSnapshotPlan": {
                            "docsRefs": [{}, {}, {}],
                            "breakingChanges": [{}],
                            "securityContracts": ["a", "b", "c"],
                            "sqlQueries": [{"name": name, "sql": "select 1"} for name in names],
                        },
                        "snapshots": [],
                        "evidence_needed": [],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            sql_bundle_path.write_text(
                "\n".join(
                    ["START TRANSACTION READ ONLY;"]
                    + [
                        line
                        for name in names
                        for line in (f"-- {name}", "select 1;")
                    ]
                    + ["COMMIT;"]
                ),
                encoding="utf-8",
            )
            result_template_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_query_results_template.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "importTool": "supabase_schema_snapshot_import",
                        "collectionPlan": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "mcpTool": "execute_sql",
                            "projectRefEnv": "SUPABASE_PROJECT_REF",
                            "authEnv": "SUPABASE_ACCESS_TOKEN",
                            "importTool": "supabase_schema_snapshot_import",
                            "supportedResultShapes": ["a", "b", "c", "d"],
                        },
                        "queries": [{"name": name, "sqlHash": name} for name in names],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            collection_queries = [
                {
                    "name": name,
                    "sqlHash": name,
                    "sql": "delete from public.audit_log",
                    "executeSqlInput": {"query": "delete from public.audit_log"},
                }
                if name == "query_3"
                else {
                    "name": name,
                    "sqlHash": name,
                    "sql": "select 'UPDATE' as literal_only",
                    "executeSqlInput": {"query": "select 'UPDATE' as literal_only"},
                }
                for name in names
            ]
            collection_packet_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": "awx.mcp.supabase_execute_sql_collection_packet.v1",
                        "readOnly": True,
                        "mutationAllowed": False,
                        "mcpTool": "execute_sql",
                        "projectRefEnv": "SUPABASE_PROJECT_REF",
                        "authEnv": "SUPABASE_ACCESS_TOKEN",
                        "importTool": "supabase_schema_snapshot_import",
                        "executionMode": "one_query_per_execute_sql_call",
                        "executeSqlInputField": "query",
                        "queries": collection_queries,
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )

            summary = completion_audit.supabase_schema_snapshot_artifact_summary(root)

        self.assertEqual(1, summary["collectionPacketMutationQueryCount"])
        self.assertEqual("query_3", summary["collectionPacketMutationQueryNames"])
        self.assertFalse(completion_audit.supabase_schema_snapshot_artifact_ready(summary))
        fail_reason = completion_audit.supabase_schema_snapshot_artifact_ready_fail_reason(summary)
        self.assertIn("collectionPacketMutationQueryNames(query_3)", fail_reason)
        self.assertNotIn("delete from", json.dumps(summary, sort_keys=True))
        self.assertNotIn("literal_only", json.dumps(summary, sort_keys=True))

    def test_completion_audit_requires_fresh_supabase_schema_snapshot_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            snapshot_path = report_dir / "supabase-schema-snapshot.json"
            toolbox.supabase_schema_snapshot(
                {
                    "root": str(ROOT),
                    "output_path": str(snapshot_path),
                    "mcp_url": "http://127.0.0.1:1/mcp",
                    "timeout_sec": 1,
                }
            )

            artifact = json.loads(snapshot_path.read_text(encoding="utf-8"))
            artifact.pop("generatedAt", None)
            snapshot_path.write_text(json.dumps(artifact, sort_keys=True), encoding="utf-8")

            summary = completion_audit.supabase_schema_snapshot_artifact_summary(root)

        self.assertFalse(summary.get("generatedAt"))
        self.assertFalse(summary.get("fresh"))
        self.assertEqual("missing_generated_at", summary.get("freshnessStatus"))
        self.assertFalse(completion_audit.supabase_schema_snapshot_artifact_ready(summary))
        fail_reason = completion_audit.supabase_schema_snapshot_artifact_ready_fail_reason(summary)
        self.assertIn("generatedAt", fail_reason)
        self.assertIn("freshnessStatus", fail_reason)

    def test_completion_audit_rejects_db_gap_report_without_supabase_input_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {},
                            "snapshotImport": {
                                "status": "evidence_needed",
                                "importedCount": 0,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        self.assertIn("externalSupabaseInputShape", completion_audit.db_gap_report_ready_fail_reason(summary))

    def test_completion_audit_rejects_db_gap_report_without_supabase_import_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {},
                            "inputShape": {
                                "payloadKind": "object",
                                "supportedResultItemCount": 0,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        fail_reason = completion_audit.db_gap_report_ready_fail_reason(summary)
        self.assertIn("externalSupabaseSnapshotImportStatus", fail_reason)
        self.assertIn("externalSupabaseSnapshotImportedCount", fail_reason)

    def test_completion_audit_rejects_imported_supabase_snapshot_with_zero_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {},
                            "snapshotImport": {
                                "status": "imported",
                                "importedCount": 0,
                            },
                            "inputShape": {
                                "payloadKind": "object",
                                "supportedResultItemCount": 0,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        self.assertIn(
            "externalSupabaseSnapshotImportedCount",
            completion_audit.db_gap_report_ready_fail_reason(summary),
        )

    def test_completion_audit_rejects_imported_supabase_snapshot_with_risk_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {
                                "exposedTablesWithoutRls": 1,
                                "metadataPolicyRisk": 1,
                                "rawRows": [{"table_name": "private_table"}],
                            },
                            "snapshotImport": {
                                "status": "imported",
                                "importedCount": 2,
                            },
                            "inputShape": {
                                "payloadKind": "object",
                                "supportedResultItemCount": 2,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertEqual(2, summary["externalSupabaseRiskFindingCount"])
        self.assertEqual(
            "exposedTablesWithoutRls,metadataPolicyRisk",
            summary["externalSupabaseRiskFindingKeys"],
        )
        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        fail_reason = completion_audit.db_gap_report_ready_fail_reason(summary)
        self.assertIn("externalSupabaseRiskFindingCount", fail_reason)
        self.assertIn("exposedTablesWithoutRls,metadataPolicyRisk", fail_reason)
        self.assertNotIn("private_table", json.dumps(summary, sort_keys=True))

    def test_completion_audit_rejects_evidence_needed_supabase_snapshot_with_partial_duplicate_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report_dir = root / "data" / "db-gap-report"
            report_dir.mkdir(parents=True)
            (report_dir / "gap_matrix.json").write_text(
                json.dumps(
                    {
                        "gap_severity_counts": {"CRITICAL": 0, "MEDIUM": 0, "LOW": 8},
                        "persistence_status_counts": {"review_runtime_only": 3, "resolved_jpa": 2},
                        "action_required_count": 0,
                        "subsystems": [{} for _ in range(8)],
                        "external_supabase_snapshot": {
                            "readOnly": True,
                            "mutationAllowed": False,
                            "sqlBundlePath": "data/db-gap-report/supabase-readonly-snapshot.sql",
                            "riskSummary": {},
                            "snapshotImport": {
                                "status": "evidence_needed",
                                "importedCount": 11,
                                "missingResultCount": 1,
                                "missingResultNames": ["rls_and_table_flags"],
                                "unexpectedResultCount": 1,
                                "unexpectedResultNames": ["typo_extra_probe"],
                                "duplicateResultCount": 1,
                                "duplicateResultNames": ["schemas_and_tables"],
                            },
                            "inputShape": {
                                "payloadKind": "object",
                                "supportedResultItemCount": 12,
                                "duplicateResultItemCount": 1,
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            summary = completion_audit.db_gap_report_artifact_summary(root)

        self.assertFalse(completion_audit.db_gap_report_ready(summary))
        fail_reason = completion_audit.db_gap_report_ready_fail_reason(summary)
        self.assertIn("externalSupabaseMissingResultNames(rls_and_table_flags)", fail_reason)
        self.assertIn("externalSupabaseUnexpectedResultNames(typo_extra_probe)", fail_reason)
        self.assertIn("externalSupabaseDuplicateResultNames(schemas_and_tables)", fail_reason)
        self.assertNotIn("private_table", json.dumps(summary, sort_keys=True))

    def test_external_node_smoke_contract_requires_supabase_probe(self):
        self.assertIn("agent_db_snapshot", toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("trace_snapshot_probe", toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("supabase_context_probe", toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("supabase_schema_snapshot", toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("agent_db_snapshot", completion_audit.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("trace_snapshot_probe", completion_audit.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("supabase_context_probe", completion_audit.REQUIRED_NODE_SMOKE_TOOLS)
        self.assertIn("supabase_schema_snapshot", completion_audit.REQUIRED_NODE_SMOKE_TOOLS)

    def test_external_node_smoke_validator_rejects_missing_supabase_probe(self):
        steps = [
            {
                "toolName": tool,
                "decision": "restore_target_blocked" if tool == "archive_restore" else "ok",
                "failReason": "smb-conflict-risk" if tool == "archive_restore" else "",
            }
            for tool in sorted(toolbox.REQUIRED_NODE_SMOKE_TOOLS - {"supabase_context_probe", "supabase_schema_snapshot"})
        ]
        validation = toolbox.validate_node_smoke_evidence(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
        )

        self.assertFalse(validation["valid"])
        self.assertIn("supabase_context_probe", validation["failReason"])
        self.assertIn("supabase_schema_snapshot", validation["failReason"])

    def test_external_node_smoke_validator_rejects_missing_db_and_trace_probes(self):
        omitted = {"agent_db_snapshot", "trace_snapshot_probe"}
        steps = [
            {
                "toolName": tool,
                "decision": "restore_target_blocked" if tool == "archive_restore" else "ok",
                "failReason": "smb-conflict-risk" if tool == "archive_restore" else "",
            }
            for tool in sorted(toolbox.REQUIRED_NODE_SMOKE_TOOLS - omitted)
        ]
        validation = toolbox.validate_node_smoke_evidence(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
        )

        self.assertFalse(validation["valid"])
        self.assertIn("agent_db_snapshot", validation["failReason"])
        self.assertIn("trace_snapshot_probe", validation["failReason"])

    def test_external_node_smoke_validator_rejects_invalid_db_and_trace_decisions(self):
        steps = [
            {
                "toolName": tool,
                "decision": "ok",
                "failReason": "",
            }
            for tool in sorted(toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        ]
        validation = toolbox.validate_node_smoke_evidence(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
        )
        completion_validation = completion_audit.validate_external_node_smoke(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
            raw_secret_hits=0,
        )

        self.assertFalse(validation["valid"])
        self.assertIn("agent-db-snapshot-decision", validation["failReason"])
        self.assertIn("trace-snapshot-probe-decision", validation["failReason"])
        self.assertFalse(completion_validation["valid"])
        self.assertIn("agent-db-snapshot-decision", completion_validation["failReason"])
        self.assertIn("trace-snapshot-probe-decision", completion_validation["failReason"])

    def test_external_evidence_intake_reports_stale_desktop_evidence_clear(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            patchdrop_root = root / "__patch_drop__"
            source_evidence_dir = patchdrop_root / "external-node-proof"
            evidence_dir = root / "data" / "agent-handoff" / "mcp-control-tower"
            evidence_dir.mkdir(parents=True)
            source_evidence_dir.mkdir(parents=True)
            for role in ("macmini", "notebook"):
                (evidence_dir / f"{role}-node-smoke.json").write_text(
                    json.dumps({"stale": True}),
                    encoding="utf-8",
                )
                (evidence_dir / f"{role}-producer-handoff.json").write_text(
                    json.dumps({"stale": True}),
                    encoding="utf-8",
                )

            result = toolbox.external_evidence_intake(
                {
                    "patchdrop_root": str(patchdrop_root),
                    "source_evidence_dir": str(source_evidence_dir),
                    "evidence_dir": str(evidence_dir),
                    "required_roles": ["macmini", "notebook"],
                    "topic": "stale-clear-proof",
                }
            )

            summary = result["intakeSummary"]

        self.assertFalse(result["ok"])
        self.assertTrue(summary["staleDesktopEvidenceCleared"])
        self.assertEqual(2, summary["clearedNodeEvidenceCount"])
        self.assertEqual(2, summary["clearedHandoffEvidenceCount"])
        self.assertEqual(4, summary["clearedEvidenceCount"])
        self.assertIn(
            "stale Desktop external evidence cleared count=4",
            result["evidence_needed"],
        )
        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        intake_tool = next(tool for tool in manifest["tools"] if tool["name"] == "external_evidence_intake")
        intake_fields = intake_tool["output_schema"]["properties"]["intakeSummary"]["properties"]
        self.assertIn("staleDesktopEvidenceCleared", intake_fields)
        self.assertIn("clearedNodeEvidenceCount", intake_fields)
        self.assertIn("clearedHandoffEvidenceCount", intake_fields)
        self.assertIn("clearedEvidenceCount", intake_fields)
        for role in ("macmini", "notebook"):
            self.assertFalse((evidence_dir / f"{role}-node-smoke.json").exists())
            self.assertFalse((evidence_dir / f"{role}-producer-handoff.json").exists())

    def test_external_node_smoke_validator_rejects_invalid_supabase_decisions(self):
        valid_decisions = {
            "agent_db_snapshot": "agent_db_snapshot_unavailable_with_local_fallback",
            "trace_snapshot_probe": "trace_snapshot_unavailable_with_local_fallback",
        }
        steps = [
            {
                "toolName": tool,
                "decision": valid_decisions.get(tool, "ok"),
                "failReason": "",
            }
            for tool in sorted(toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        ]
        validation = toolbox.validate_node_smoke_evidence(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
        )
        completion_validation = completion_audit.validate_external_node_smoke(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
            raw_secret_hits=0,
        )

        self.assertFalse(validation["valid"])
        self.assertIn("supabase-context-probe-decision", validation["failReason"])
        self.assertIn("supabase-schema-snapshot-decision", validation["failReason"])
        self.assertFalse(completion_validation["valid"])
        self.assertIn("supabase-context-probe-decision", completion_validation["failReason"])
        self.assertIn("supabase-schema-snapshot-decision", completion_validation["failReason"])

    def test_external_node_smoke_validator_rejects_fallback_without_fallback_marker(self):
        valid_decisions = {
            "agent_db_snapshot": "agent_db_snapshot_unavailable_with_local_fallback",
            "trace_snapshot_probe": "trace_snapshot_unavailable_with_local_fallback",
            "supabase_context_probe": "supabase_context_probe",
            "supabase_schema_snapshot": "supabase_schema_snapshot_evidence_needed",
        }
        steps = [
            {
                "toolName": tool,
                "decision": valid_decisions.get(tool, "ok"),
                "failReason": "",
            }
            for tool in sorted(toolbox.REQUIRED_NODE_SMOKE_TOOLS)
        ]
        validation = toolbox.validate_node_smoke_evidence(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
        )
        completion_validation = completion_audit.validate_external_node_smoke(
            {
                "schemaVersion": "awx.mcp.node_smoke.v1",
                "ok": True,
                "nodeRole": "desktop",
                "rawSecretPatternHits": 0,
                "steps": steps,
            },
            "desktop",
            raw_secret_hits=0,
        )

        self.assertFalse(validation["valid"])
        self.assertIn("agent-db-snapshot-local-fallback", validation["failReason"])
        self.assertIn("trace-snapshot-probe-local-fallback", validation["failReason"])
        self.assertFalse(completion_validation["valid"])
        self.assertIn("agent-db-snapshot-local-fallback", completion_validation["failReason"])
        self.assertIn("trace-snapshot-probe-local-fallback", completion_validation["failReason"])

    def test_node_smoke_includes_supabase_context_probe(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_node_smoke.py"),
                "--root",
                str(ROOT),
                "--canonical-root",
                str(ROOT),
                "--node-role",
                "desktop",
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        smoke = json.loads(completed.stdout)
        steps = {row["toolName"]: row for row in smoke["steps"]}

        self.assertIn("agent_db_snapshot", steps)
        self.assertEqual("agent_db_snapshot_unavailable_with_local_fallback", steps["agent_db_snapshot"]["decision"])
        self.assertTrue(steps["agent_db_snapshot"]["localFallbackPresent"])
        self.assertIn("trace_snapshot_probe", steps)
        self.assertEqual("trace_snapshot_unavailable_with_local_fallback", steps["trace_snapshot_probe"]["decision"])
        self.assertTrue(steps["trace_snapshot_probe"]["localFallbackPresent"])
        self.assertIn("supabase_context_probe", steps)
        self.assertTrue(steps["supabase_context_probe"]["ok"])
        self.assertIn("supabase_schema_snapshot", steps)
        self.assertTrue(steps["supabase_schema_snapshot"]["ok"])
        completed_audit = subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "awx_mcp_completion_audit.py"),
                "--root",
                str(ROOT),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(0, completed_audit.returncode, completed_audit.stderr)
        audit = json.loads(completed_audit.stdout)
        checks = {row["id"]: row for row in audit["checked"]}
        self.assertIn("agentDbSnapshot=True", checks["mcp.node-smoke-runner"]["evidence"])
        self.assertIn("traceSnapshotProbe=True", checks["mcp.node-smoke-runner"]["evidence"])
        self.assertIn("supabaseContextProbe=True", checks["mcp.node-smoke-runner"]["evidence"])
        self.assertIn("localFallbackMarker=True", checks["mcp.node-smoke-runner"]["evidence"])
        self.assertIn("runtimeBaseUrlEnv=True", checks["mcp.node-smoke-runner"]["evidence"])
        self.assertIn(
            "supabase CLI missing / verify with `supabase --version` after installing CLI",
            smoke["evidence_needed"],
        )
        self.assertFalse(any(item.startswith("[") for item in smoke["evidence_needed"]))

    def test_node_smoke_tolerates_preexisting_forbidden_restore_probe_marker(self):
        with tempfile.TemporaryDirectory() as source_tmp, tempfile.TemporaryDirectory() as canonical_tmp:
            subprocess.run(["git", "-C", source_tmp, "init", "-q"], check=False)
            marker = Path(canonical_tmp) / "__awx_mcp_smoke_forbidden__" / "blocked.txt"
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text("preexisting marker", encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_node_smoke.py"),
                    "--root",
                    source_tmp,
                    "--canonical-root",
                    canonical_tmp,
                    "--node-role",
                    "macmini",
                ],
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            smoke = json.loads(completed.stdout)
            self.assertTrue(smoke["ok"])
            self.assertEqual("node_smoke", smoke["decision"])
            self.assertEqual("preexisting marker", marker.read_text(encoding="utf-8"))

    def test_node_smoke_desktop_role_does_not_leave_restore_probe_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["git", "-C", tmp, "init", "-q"], check=False)
            marker = Path(tmp) / "__awx_mcp_smoke_forbidden__" / "blocked.txt"

            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_node_smoke.py"),
                    "--root",
                    tmp,
                    "--canonical-root",
                    tmp,
                    "--node-role",
                    "desktop",
                ],
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            smoke = json.loads(completed.stdout)
            self.assertTrue(smoke["ok"])
            self.assertFalse(marker.exists())

    def test_node_smoke_uses_runtime_base_url_env_when_available(self):
        class RuntimeProbeHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path.startswith("/agent/db-context/snapshot"):
                    self.send_json({"memory": {}, "ledger": {}, "strategy": {}})
                    return
                if self.path.startswith("/api/diagnostics/trace/snapshots"):
                    self.send_json({"available": True, "snapshots": []})
                    return
                self.send_response(404)
                self.end_headers()

            def send_json(self, payload):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), RuntimeProbeHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base_url = f"http://127.0.0.1:{server.server_address[1]}"
            env = os.environ.copy()
            env["AWX_AGENT_DB_CONTEXT_BASE_URL"] = base_url
            env["AWX_TRACE_SNAPSHOT_BASE_URL"] = base_url
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "awx_mcp_node_smoke.py"),
                    "--root",
                    str(ROOT),
                    "--canonical-root",
                    str(ROOT),
                    "--node-role",
                    "desktop",
                ],
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
                env=env,
            )
        finally:
            server.shutdown()
            thread.join(timeout=5)
            server.server_close()

        self.assertEqual(0, completed.returncode, completed.stderr)
        smoke = json.loads(completed.stdout)
        steps = {row["toolName"]: row for row in smoke["steps"]}

        self.assertEqual("agent_db_snapshot_loaded", steps["agent_db_snapshot"]["decision"])
        self.assertFalse(steps["agent_db_snapshot"]["localFallbackPresent"])
        self.assertEqual("trace_snapshot_loaded", steps["trace_snapshot_probe"]["decision"])
        self.assertFalse(steps["trace_snapshot_probe"]["localFallbackPresent"])

    def test_trace_snapshot_probe_is_registered_and_redacts_token_on_connection_failure(self):
        old_token = os.environ.get("AWX_ADMIN_TOKEN")
        os.environ["AWX_ADMIN_TOKEN"] = "unit-test-trace-token"
        try:
            self.assertIn("trace_snapshot_probe", toolbox.TOOL_ALIASES.values())
            manifest = (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(
                encoding="utf-8"
            )
            self.assertIn('"name": "trace_snapshot_probe"', manifest)
            self.assertIn('"command": "python scripts/awx_mcp_toolbox.py trace_snapshot_probe"', manifest)
            result = toolbox.trace_snapshot_probe(
                {
                    "base_url": "http://127.0.0.1:1",
                    "timeout_sec": 1,
                    "limit": 3,
                }
            )
        finally:
            if old_token is None:
                os.environ.pop("AWX_ADMIN_TOKEN", None)
            else:
                os.environ["AWX_ADMIN_TOKEN"] = old_token

        rendered = str(result)
        self.assertFalse(result["ok"])
        self.assertTrue(result["tokenPresented"])
        self.assertEqual("trace_snapshot_unavailable_with_local_fallback", result["decision"])
        self.assertNotIn("unit-test-trace-token", rendered)
        self.assertNotIn("X-Admin-Token", rendered)

    def test_trace_snapshot_probe_returns_static_local_fallback_on_connection_failure(self):
        result = toolbox.trace_snapshot_probe(
            {
                "root": str(ROOT),
                "base_url": "http://127.0.0.1:1",
                "timeout_sec": 1,
                "limit": 3,
            }
        )

        self.assertFalse(result["ok"])
        self.assertEqual("trace_snapshot_unavailable_with_local_fallback", result["decision"])
        fallback = result["localFallback"]
        self.assertTrue(fallback["storePresent"])
        self.assertTrue(fallback["diagnosticsControllerPresent"])
        self.assertTrue(fallback["exporterPresent"])
        self.assertIn("/api/diagnostics/trace/snapshots", fallback["endpointPaths"])
        self.assertIn(
            "Spring runtime /api/diagnostics/trace/snapshots response for live TraceStore export",
            fallback["evidence_needed"],
        )
        self.assertEqual(0, fallback["rawSecretPatternHits"])
        self.assertNotIn("TraceSnapshotStore.java", json.dumps(fallback, sort_keys=True))

        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        trace_tool = next(tool for tool in manifest["tools"] if tool["name"] == "trace_snapshot_probe")
        self.assertIn("localFallback", trace_tool["output_schema"]["properties"])

    def test_agent_db_snapshot_returns_root_scoped_local_fallback_on_connection_failure(self):
        result = toolbox.agent_db_snapshot(
            {
                "nodeRole": "desktop",
                "root": str(ROOT),
                "base_url": "http://127.0.0.1:1",
                "timeout_sec": 1,
            }
        )

        self.assertFalse(result["ok"])
        self.assertEqual("agent_db_snapshot_unavailable_with_local_fallback", result["decision"])
        fallback = result["localFallback"]
        self.assertEqual("local_file_and_source_metadata", fallback["source"])
        self.assertGreater(fallback["jpaSurface"]["entityFileCount"], 0)
        self.assertGreater(fallback["jpaSurface"]["repositoryFileCount"], 0)
        self.assertIn("subsystemPersistence", fallback)
        self.assertIn(
            "Spring runtime /agent/db-context response for live database snapshot",
            fallback["evidence_needed"],
        )

        manifest = json.loads(
            (ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json").read_text(encoding="utf-8")
        )
        agent_tool = next(tool for tool in manifest["tools"] if tool["name"] == "agent_db_snapshot")
        self.assertIn("root", agent_tool["input_schema"]["properties"])
        self.assertIn("localFallback", agent_tool["output_schema"]["properties"])

    def test_agent_db_snapshot_jpa_surface_ignores_marker_strings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tool_dir = root / "main" / "java" / "com" / "example" / "tools"
            repo_dir = root / "main" / "java" / "com" / "example" / "lms" / "repository"
            domain_dir = root / "main" / "java" / "com" / "example" / "lms" / "domain"
            tool_dir.mkdir(parents=True)
            repo_dir.mkdir(parents=True)
            domain_dir.mkdir(parents=True)
            (tool_dir / "DbEvidenceScanTool.java").write_text(
                "package com.example.tools;\n"
                "class DbEvidenceScanTool {\n"
                "  private static final String ENTITY_MARKER = \"@\" + \"Entity\";\n"
                "  private static final String REPOSITORY_MARKER = \"Jpa\" + \"Repository\";\n"
                "}\n",
                encoding="utf-8",
            )
            (repo_dir / "ReadmeOnly.java").write_text(
                "package com.example.lms.repository;\n"
                "class ReadmeOnly {\n"
                "  String text = \"JpaRepository is mentioned here but not extended\";\n"
                "}\n",
                encoding="utf-8",
            )
            (domain_dir / "Course.java").write_text(
                "package com.example.lms.domain;\n"
                "import jakarta.persistence.Entity;\n"
                "@Entity\n"
                "class Course {}\n",
                encoding="utf-8",
            )
            (repo_dir / "CourseRepository.java").write_text(
                "package com.example.lms.repository;\n"
                "import com.example.lms.domain.Course;\n"
                "import org.springframework.data.jpa.repository.JpaRepository;\n"
                "interface CourseRepository extends JpaRepository<Course, Long> {}\n",
                encoding="utf-8",
            )

            surface = toolbox.jpa_surface_summary(root)

        self.assertEqual(1, surface["entityFileCount"])
        self.assertEqual(1, surface["repositoryFileCount"])
        sample_paths = {row["path"] for row in surface["samples"]}
        self.assertNotIn("main/java/com/example/tools/DbEvidenceScanTool.java", sample_paths)
        self.assertNotIn("main/java/com/example/lms/repository/ReadmeOnly.java", sample_paths)

    def test_hb09_done_when_raw_tile_builder_and_trace_contract_test_exist(self):
        all_text = "\n".join(
            [
                "class CfvmRawTileBuilder",
                "cfvm.rawTile.enabled",
                "cfvm.rawTile.condensed",
                "cfvm.rawTile.rawPayloadStored",
                "no_raw_matrix_entries",
            ]
        )
        test_text = "\n".join(
            [
                "class CfvmRawTileBuilderTest",
                "builderRecordsCondensedRawTileWithoutRawTracePayload",
                "builderDisablesOnlyWhenNoRawMatrixEntriesExist",
            ]
        )

        breaks = toolbox.harmony_break_statuses(
            all_text=all_text,
            test_text=test_text,
            trace_coverage={},
            catch_stats={"catchWithoutBreadcrumbCount": 0},
        )

        hb09 = next(item for item in breaks if item["code"] == "HB-09")
        self.assertEqual("DONE", hb09["status"])

    def test_harmony_scan_drops_runtime_evidence_needed_when_runtime_proof_is_present(self):
        report = toolbox.harmony_scan(
            {
                "root": str(ROOT),
                "max_files": 12000,
                "runtimeProof": {
                    "traceStoreExportOk": True,
                    "agentDbSnapshotOk": True,
                },
            }
        )

        self.assertEqual([], report["evidence_needed"])
        self.assertEqual(
            {
                "traceStoreExportOk": True,
                "agentDbSnapshotOk": True,
            },
            report["runtimeProof"],
        )

    def test_harmony_scan_reports_runtime_proof_next_actions_when_missing(self):
        old_agent_url = os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        old_trace_url = os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL")
        try:
            os.environ.pop("AWX_AGENT_DB_CONTEXT_BASE_URL", None)
            os.environ.pop("AWX_TRACE_SNAPSHOT_BASE_URL", None)
            report = toolbox.harmony_scan({"root": str(ROOT), "max_files": 12000})
        finally:
            if old_agent_url is not None:
                os.environ["AWX_AGENT_DB_CONTEXT_BASE_URL"] = old_agent_url
            if old_trace_url is not None:
                os.environ["AWX_TRACE_SNAPSHOT_BASE_URL"] = old_trace_url

        self.assertIn("boot/live TraceStore export for runtime-only coverage", report["evidence_needed"])
        self.assertIn("agent_db_snapshot after Spring server is running for DB-backed state proof", report["evidence_needed"])
        self.assertIn("nextActions", report)
        self.assertIn("start_spring_runtime_for_harmony_probe", report["nextActions"])
        self.assertIn("set_AGENT_DB_CONTEXT_ENABLED_true", report["nextActions"])
        self.assertIn("set_AWX_AGENT_DB_CONTEXT_BASE_URL", report["nextActions"])
        self.assertIn("set_AWX_TRACE_SNAPSHOT_BASE_URL", report["nextActions"])
        self.assertIn("rerun_harmony_scan_with_runtime_probe", report["nextActions"])
        self.assertEqual(len(report["harmonyBreaks"]), report["outputCount"])

    def test_harmony_scan_recognizes_harmony_stream_failure_helper(self):
        source = ROOT / "main" / "java" / "com" / "example" / "lms" / "harmony" / "HarmonyScoreController.java"
        stats = toolbox.harmony_catch_stats({source: source.read_text(encoding="utf-8")}, ROOT)

        self.assertEqual(0, stats["catchWithoutBreadcrumbCount"])

    def test_harmony_scan_uses_runtime_base_url_env_when_available(self):
        class RuntimeProbeHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path.startswith("/agent/db-context/snapshot"):
                    self.send_json({"memory": {}, "ledger": {}, "strategy": {}})
                    return
                if self.path.startswith("/api/diagnostics/trace/snapshots"):
                    self.send_json({"available": True, "snapshots": []})
                    return
                self.send_response(404)
                self.end_headers()

            def send_json(self, payload):
                body = json.dumps(payload).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), RuntimeProbeHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        old_agent_url = os.environ.get("AWX_AGENT_DB_CONTEXT_BASE_URL")
        old_trace_url = os.environ.get("AWX_TRACE_SNAPSHOT_BASE_URL")
        try:
            base_url = f"http://127.0.0.1:{server.server_address[1]}"
            os.environ["AWX_AGENT_DB_CONTEXT_BASE_URL"] = base_url
            os.environ["AWX_TRACE_SNAPSHOT_BASE_URL"] = base_url
            report = toolbox.harmony_scan({"root": str(ROOT), "max_files": 12000})
        finally:
            if old_agent_url is None:
                os.environ.pop("AWX_AGENT_DB_CONTEXT_BASE_URL", None)
            else:
                os.environ["AWX_AGENT_DB_CONTEXT_BASE_URL"] = old_agent_url
            if old_trace_url is None:
                os.environ.pop("AWX_TRACE_SNAPSHOT_BASE_URL", None)
            else:
                os.environ["AWX_TRACE_SNAPSHOT_BASE_URL"] = old_trace_url
            server.shutdown()
            thread.join(timeout=5)
            server.server_close()

        self.assertEqual([], report["evidence_needed"])
        self.assertEqual(
            {
                "traceStoreExportOk": True,
                "agentDbSnapshotOk": True,
            },
            report["runtimeProof"],
        )
        self.assertEqual("live-runtime", report["runtimeProofSource"])
        self.assertEqual("agent_db_snapshot_loaded", report["runtimeProofDetails"]["agentDbSnapshotDecision"])
        self.assertEqual("trace_snapshot_loaded", report["runtimeProofDetails"]["traceSnapshotDecision"])
        self.assertEqual(0, report["runtimeProofDetails"]["rawSecretPatternHits"])
        self.assertIn("runtimeProofArtifactPath", report)
        runtime_artifact = json.loads(Path(report["runtimeProofArtifactPath"]).read_text(encoding="utf-8"))
        self.assertEqual("awx.mcp.harmony_runtime_proof.v1", runtime_artifact["schemaVersion"])
        self.assertEqual(report["runtimeProof"], runtime_artifact["runtimeProof"])
        self.assertEqual("live-runtime", runtime_artifact["runtimeProofSource"])
        self.assertEqual(0, runtime_artifact["rawSecretPatternHits"])

    def test_harmony_scan_writes_output_path_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_path = Path(tmp) / "harmony-scan.json"
            report = toolbox.harmony_scan(
                {
                    "root": str(ROOT),
                    "max_files": 12000,
                    "output_path": str(output_path),
                    "runtimeProof": {
                        "traceStoreExportOk": True,
                        "agentDbSnapshotOk": True,
                    },
                }
            )

            artifact = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual("awx.mcp.harmony_scan.v1", artifact["schemaVersion"])
        self.assertTrue(artifact["ok"])
        self.assertEqual([], artifact["evidence_needed"])
        self.assertEqual(report["harmonyScore"], artifact["harmonyScore"])
        self.assertEqual(report["runtimeProof"], artifact["runtimeProof"])
        self.assertIn("outputPath", report)

    def test_harmony_scan_exposes_cross_subsystem_review_queue(self):
        report = toolbox.harmony_scan(
            {
                "root": str(ROOT),
                "max_files": 12000,
                "runtimeProof": {
                    "traceStoreExportOk": True,
                    "agentDbSnapshotOk": True,
                },
            }
        )

        queue = report["samples"]["crossSubsystemReviewQueue"]
        self.assertGreater(len(queue), 0)
        self.assertIn("priorityScore", queue[0])
        self.assertEqual("REVIEW", queue[0]["risk"])

    def test_cross_subsystem_files_separates_glue_from_runtime_review(self):
        autoconfig = (
            ROOT
            / "main"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "autoconfig"
            / "NovaOrchestrationAutoConfiguration.java"
        )
        runtime_aspect = (
            ROOT
            / "main"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "orch"
            / "aop"
            / "ExtremeZBurstAspect.java"
        )

        report = toolbox.harmony_cross_subsystem_files(
            {
                autoconfig: "class NovaOrchestrationAutoConfiguration { String x = \"cfvm extremeZ hypernova\"; }",
                runtime_aspect: "class ExtremeZBurstAspect { String x = \"cfvm extremeZ hypernova breadcrumb\"; }",
            },
            ROOT,
        )

        self.assertEqual(2, report["crossSubsystemFileCount"])
        self.assertEqual({"LOW": 1, "REVIEW": 1}, report["riskCounts"])
        samples = {item["path"]: item for item in report["samples"]}
        self.assertEqual(
            "autoconfiguration_glue",
            samples["main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java"]["classification"],
        )
        self.assertEqual(
            "runtime_cross_subsystem_review",
            samples["main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"]["classification"],
        )

    def test_cross_subsystem_review_queue_prioritizes_runtime_hotspots(self):
        autoconfig = (
            ROOT
            / "main"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "autoconfig"
            / "NovaOrchestrationAutoConfiguration.java"
        )
        runtime_aspect = (
            ROOT
            / "main"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "orch"
            / "aop"
            / "ExtremeZBurstAspect.java"
        )
        domain_enum = (
            ROOT
            / "main"
            / "java"
            / "com"
            / "example"
            / "lms"
            / "domain"
            / "enums"
            / "JamminiMode.java"
        )

        report = toolbox.harmony_cross_subsystem_files(
            {
                autoconfig: "class NovaOrchestrationAutoConfiguration { String x = \"cfvm extremeZ hypernova breadcrumb\"; }",
                runtime_aspect: "class ExtremeZBurstAspect { String x = \"cfvm extremeZ hypernova breadcrumb langchain4j\"; }",
                domain_enum: "enum JamminiMode { HYPERNOVA_OVERDRIVE }",
            },
            ROOT,
        )

        queue = report["reviewQueue"]
        self.assertEqual(
            [
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java",
                "main/java/com/example/lms/domain/enums/JamminiMode.java",
            ],
            [item["path"] for item in queue],
        )
        self.assertGreater(queue[0]["priorityScore"], queue[1]["priorityScore"])
        self.assertTrue(all(item["risk"] == "REVIEW" for item in queue))

    def test_cross_subsystem_review_queue_excludes_test_fixtures(self):
        runtime_aspect = (
            ROOT
            / "main"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "orch"
            / "aop"
            / "ExtremeZBurstAspect.java"
        )
        test_fixture = (
            ROOT
            / "src"
            / "test"
            / "java"
            / "ai"
            / "abandonware"
            / "nova"
            / "orch"
            / "aop"
            / "ExtremeZBurstAspectTest.java"
        )

        report = toolbox.harmony_cross_subsystem_files(
            {
                runtime_aspect: "class ExtremeZBurstAspect { String x = \"cfvm extremeZ hypernova breadcrumb langchain4j\"; }",
                test_fixture: "class ExtremeZBurstAspectTest { String x = \"cfvm extremeZ hypernova breadcrumb langchain4j\"; }",
            },
            ROOT,
        )

        self.assertEqual({"REVIEW": 1, "TEST": 1}, report["riskCounts"])
        self.assertEqual(
            ["main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java"],
            [item["path"] for item in report["reviewQueue"]],
        )
        self.assertEqual("test_or_fixture_coverage", report["samples"][1]["classification"])


if __name__ == "__main__":
    unittest.main()

import json
import tempfile
import unittest
from pathlib import Path

import test_tree_contamination_report as report


class TestTreeContaminationReportTest(unittest.TestCase):
    def test_reports_missing_test_imports_against_active_main_roots(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)
            (root / "app/src/main/java_clean/com/example/app").mkdir(parents=True)

            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "app/src/main/java_clean/com/example/app/AppBridge.java").write_text(
                "package com.example.app;\npublic class AppBridge {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/LegacyTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.AliveService;",
                        "import com.example.app.AppBridge;",
                        "import ai.abandonware.nova.missing.LegacyAspect;",
                        "import static com.example.missing.LegacyUtil.run;",
                        "class LegacyTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

            self.assertEqual(data["activeClassCount"], 2)
            self.assertEqual(data["testJavaFileCount"], 1)
            self.assertEqual(data["missingImportCount"], 2)
            self.assertEqual(data["affectedTestFileCount"], 1)
            self.assertEqual(
                data["topAffectedTestFiles"][0]["file"],
                "src/test/java/com/example/live/LegacyTest.java",
            )
            self.assertIn(
                "ai.abandonware.nova.missing.LegacyAspect",
                data["topAffectedTestFiles"][0]["missingImports"],
            )
            self.assertIn(
                "com.example.missing.LegacyUtil",
                data["topAffectedTestFiles"][0]["missingImports"],
            )
            self.assertEqual(data["missingByNamespace"]["ai.abandonware.nova"], 1)
            self.assertEqual(data["missingByNamespace"]["com.example"], 1)
            self.assertGreater(data["riskScore"], 0)
            json.dumps(data)

    def test_counts_nested_active_types_as_resolved_imports(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)

            (root / "main/java/com/example/live/OuterService.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "public class OuterService {",
                        "  public static class NestedRequest {}",
                        "  public enum NestedMode { SAFE }",
                        "}",
                    ]
                ),
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/NestedTypeTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.OuterService;",
                        "import com.example.live.OuterService.NestedRequest;",
                        "import com.example.live.OuterService.NestedMode;",
                        "class NestedTypeTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertEqual(data["activeClassCount"], 3)
        self.assertEqual(data["missingImportCount"], 0)
        self.assertEqual(data["affectedTestFileCount"], 0)

    def test_counts_test_support_imports_as_resolved_not_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "main/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live").mkdir(parents=True)
            (root / "src/test/java/com/example/live/support").mkdir(parents=True)

            (root / "main/java/com/example/live/AliveService.java").write_text(
                "package com.example.live;\npublic class AliveService {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/support/TestFixtures.java").write_text(
                "package com.example.live.support;\npublic final class TestFixtures {}\n",
                encoding="utf-8",
            )
            (root / "src/test/java/com/example/live/UsesFixtureTest.java").write_text(
                "\n".join(
                    [
                        "package com.example.live;",
                        "import com.example.live.AliveService;",
                        "import com.example.live.support.TestFixtures;",
                        "class UsesFixtureTest {}",
                    ]
                ),
                encoding="utf-8",
            )

            data = report.build_report(root)

        self.assertEqual(data["activeClassCount"], 1)
        self.assertEqual(data["testSupportClassCount"], 2)
        self.assertEqual(data["testSupportImportCount"], 1)
        self.assertEqual(data["missingImportCount"], 0)
        self.assertEqual(data["affectedTestFileCount"], 0)

    def test_gradle_task_is_registered_for_repo_owned_refresh(self):
        root = Path(__file__).resolve().parents[1]
        build = (root / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")

        self.assertIn('tasks.register<Exec>("testTreeContaminationReport")', build)
        self.assertIn("scripts/test_tree_contamination_report.py", build)
        self.assertIn("verification/test-tree-contamination-metrics.json", build)


if __name__ == "__main__":
    unittest.main()

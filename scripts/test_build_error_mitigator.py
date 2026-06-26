#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "build_error_mitigator.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_mitigator", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorMitigatorRedactionTest(unittest.TestCase):
    def test_exception_summary_is_hash_only(self):
        mitigator = load_module()
        secret = "sk-" + "B" * 24

        summary = mitigator.safe_error(RuntimeError(f"failed with token {secret}"))

        self.assertEqual("RuntimeError", summary["errorType"])
        self.assertTrue(summary["errorHash"].startswith("hash:"))
        self.assertGreater(summary["errorLength"], 0)
        self.assertNotIn(secret, str(summary))


if __name__ == "__main__":
    unittest.main()

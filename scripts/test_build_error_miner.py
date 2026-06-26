#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "build_error_miner.py"


def load_module():
    spec = importlib.util.spec_from_file_location("build_error_miner", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BuildErrorMinerRedactionTest(unittest.TestCase):
    def test_examples_redact_secret_shaped_log_fragments(self):
        miner = load_module()
        secret = "sk-" + "C" * 24
        hits = miner.scan_text(f"""
> Task :compileJava FAILED
error: cannot find symbol token={secret}
symbol: class MissingThing
""")

        rendered = str(hits)

        self.assertNotIn(secret, rendered)
        self.assertNotIn("token=sk-", rendered)
        self.assertIn("<secret>", rendered)


if __name__ == "__main__":
    unittest.main()

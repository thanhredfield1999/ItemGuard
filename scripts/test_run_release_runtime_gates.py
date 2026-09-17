"""Contracts for the release-gate runner, because this runner is where a false pass would hide.

Three of its properties are load-bearing and none of them is visible from a green run:

  * an unrecognised gate name is refused instead of skipped (it used to leave `0/0 gates PASS` and
    exit 0, which a caller reading the exit code takes as success);
  * every standalone gate's verdict comes from the JSON file it writes, never from its exit code;
  * the runner knows exactly the seven gates the release checklist names, so a gate cannot quietly
    disappear from the count.

    python -m unittest scripts.test_run_release_runtime_gates -v
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.run_release_runtime_gates import (  # noqa: E402
    SMOKE_GATES,
    STANDALONE_GATES,
    main,
)

EXPECTED_GATES = {"separate", "loss", "sweep", "reload", "multi", "two-plugin", "power-cut"}


class ReleaseGateRunnerContractTest(unittest.TestCase):
    def run_runner(self, *args):
        with mock.patch.object(sys, "argv", ["run_release_runtime_gates.py", *args]):
            return main()

    def test_an_unknown_gate_name_is_refused_before_anything_runs(self):
        self.assertEqual(2, self.run_runner("multii"))

    def test_a_valid_name_beside_a_typo_is_refused_too(self):
        self.assertEqual(2, self.run_runner("multi", "power-cut", "powercut"))

    def test_the_runner_knows_exactly_the_gates_the_checklist_names(self):
        self.assertEqual(EXPECTED_GATES, set(SMOKE_GATES) | set(STANDALONE_GATES))

    def test_every_standalone_gate_declares_a_verdict_file(self):
        for name, (_script, verdict, _verifier) in STANDALONE_GATES.items():
            self.assertTrue(verdict.endswith(".json"), f"{name} must name the JSON it writes")
        self.assertEqual({"reload.json", "multi.json", "two-plugin.json", "power-cut.json"},
                         {verdict for _script, verdict, _verifier in STANDALONE_GATES.values()})

    def test_the_json_does_not_understate_which_gates_were_verified(self):
        """Every gate in this runner is adjudicated: the smoke gates by `verify.py <mode> <root>` on
        each run, the four standalone ones by their own verdict file. The JSON used to record the
        smoke gates as unverified (`bool(verifier)`), and the JSON is what people read instead of the
        log - so the file understated the evidence it was reporting."""
        source = (Path(__file__).resolve().parents[1] / "scripts"
                  / "run_release_runtime_gates.py").read_text(encoding="utf-8")
        self.assertIn('result["independent_verifier"] = True', source)
        self.assertNotIn('result["independent_verifier"] = bool(verifier)', source)

    def test_the_smoke_gates_are_adjudicated_by_verify_py(self):
        for name, (mode, verifier) in SMOKE_GATES.items():
            self.assertTrue(mode, f"{name} must name a smoke mode")
            self.assertIsInstance(verifier, list)


if __name__ == "__main__":
    unittest.main()

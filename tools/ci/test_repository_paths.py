"""Keep tracked paths usable on case-insensitive Windows/macOS filesystems."""
import subprocess
import unittest
from pathlib import Path


def collisions(paths):
    groups = {}
    for path in paths:
        groups.setdefault(path.casefold(), []).append(path)
    return [group for group in groups.values() if len(group) > 1]


class RepositoryPathTests(unittest.TestCase):
    def test_case_only_names_are_rejected(self):
        self.assertEqual(collisions(["captures/CLOUD.png", "captures/cloud.png"]),
                         [["captures/CLOUD.png", "captures/cloud.png"]])

    def test_distinct_scenario_names_are_allowed(self):
        self.assertEqual(collisions(["captures/comparison-cloud.png", "captures/choice-cloud.png"]), [])

    def test_tracked_paths_have_no_case_collisions(self):
        root = Path(__file__).resolve().parents[2]
        paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
        self.assertEqual(collisions([path for path in paths if path]), [])

    def test_choice_capture_names_do_not_overlap_state_capture_names(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "shared-ui/src/test/kotlin/io/github/xyz1001/pdsync/android/SharedM3ReferenceRenderTest.kt").read_text(encoding="utf-8")
        self.assertIn('session.capture("legacy-comparison-$name",', source)
        self.assertIn('session.capture("legacy-choice-${choice.name.lowercase()}-selected")', source)
        self.assertNotIn('session.capture("legacy-comparison-$choice-selected")', source)

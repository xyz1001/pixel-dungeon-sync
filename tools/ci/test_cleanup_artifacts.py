import unittest
from urllib.parse import parse_qs, urlsplit

import cleanup_artifacts


class CleanupArtifactsTest(unittest.TestCase):
    def test_scopes_runs_and_snapshots_all_pages_before_deletion(self):
        runs = [{"id": n, "run_number": n, "status": "completed"} for n in range(1, 102)]
        runs += [{"id": 102, "run_number": 102, "status": "in_progress"},
                 {"id": 103, "run_number": 103, "status": "completed"}]
        calls, deleted = [], []

        def get(url):
            self.assertFalse(deleted, "pagination must finish before deletion")
            parts = urlsplit(url)
            calls.append(parts.path)
            page = int(parse_qs(parts.query)["page"][0])
            if parts.path.endswith("/workflows/packages.yml/runs"):
                return {"workflow_runs": runs[(page - 1) * 100:page * 100]}
            if parts.path.endswith("/runs/1/artifacts"):
                return {"artifacts": [{"id": n} for n in range((page - 1) * 100, min(page * 100, 101))]}
            return {"artifacts": []}

        cleanup_artifacts.cleanup("https://api.example", "owner/repo", 103, get, deleted.append)
        self.assertEqual(len(deleted), 101)
        self.assertTrue(all("/actions/artifacts/" in url for url in deleted))
        self.assertNotIn("/repos/owner/repo/actions/runs/102/artifacts", calls)
        self.assertNotIn("/repos/owner/repo/actions/runs/103/artifacts", calls)
        self.assertEqual(calls.count("/repos/owner/repo/actions/workflows/packages.yml/runs"), 2)
        self.assertEqual(calls.count("/repos/owner/repo/actions/runs/1/artifacts"), 2)

    def test_current_and_newer_runs_are_never_deleted(self):
        runs = [{"id": n, "run_number": n, "status": "completed"} for n in (9, 10, 11)]
        calls, deleted = [], []

        def get(url):
            calls.append(url)
            return {"workflow_runs": runs} if "/workflows/" in url else {"artifacts": [{"id": 88}]}

        cleanup_artifacts.cleanup("https://api.example", "o/r", 10, get, deleted.append)
        self.assertEqual(deleted, ["https://api.example/repos/o/r/actions/artifacts/88"])
        self.assertEqual(len(calls), 2)
        self.assertIn("/runs/9/artifacts", calls[1])


if __name__ == "__main__":
    unittest.main()

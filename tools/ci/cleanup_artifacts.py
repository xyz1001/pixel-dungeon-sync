"""Delete Actions artifacts from older completed runs of packages.yml only."""

import json
import os
import urllib.error
import urllib.request


def request(url, token, method="GET"):
    req = urllib.request.Request(url, method=method, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        # Concurrent cleanups may already have deleted this artifact.
        if method == "DELETE" and error.code == 404:
            return None
        raise


def pages(get, url, key):
    page = 1
    while True:
        items = get(f"{url}?per_page=100&page={page}")[key]
        yield from items
        if len(items) < 100:
            break
        page += 1


def cleanup(api, repository, run_number, get, delete):
    base = f"{api}/repos/{repository}/actions"
    # run_number increases per workflow and is unchanged by reruns. Only
    # completed runs are safe: active older runs may still need their artifacts.
    runs = list(pages(get, f"{base}/workflows/packages.yml/runs", "workflow_runs"))
    older = [run for run in runs if run["run_number"] < run_number and run["status"] == "completed"]
    artifacts = []
    for run in older:
        artifacts.extend(pages(get, f"{base}/runs/{run['id']}/artifacts", "artifacts"))
    # Snapshot pages before deletion so page offsets cannot shift underneath us.
    for artifact in artifacts:
        print(f"Deleting artifact {artifact['id']} from older workflow run")
        delete(f"{base}/artifacts/{artifact['id']}")


if __name__ == "__main__":
    token = os.environ["GH_TOKEN"]
    cleanup(os.environ["GITHUB_API_URL"], os.environ["GITHUB_REPOSITORY"],
            int(os.environ["GITHUB_RUN_NUMBER"]),
            lambda url: request(url, token),
            lambda url: request(url, token, "DELETE"))

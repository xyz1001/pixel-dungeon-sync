"""Copy only generated synthetic render fixtures into the checkout for artifacts."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]


def collect(root=ROOT):
    sources = [
        (root.parent / 'code76-evidence', 'bilingual/*.png', 'bilingual'),
        (root.parent / 'code76-evidence', 'bilingual-render-assertions.txt', 'bilingual'),
        (root.parent / 'code73-evidence/after', 'wizard-step*.png', 'wizard'),
        (root / 'desktop/build/reports/shared-comparison', 'choice-feedback-*.png', 'comparison'),
        (root / 'desktop/build/reports/shared-home-history-refresh', 'home-history-refresh-*.png', 'history'),
        (root / 'desktop/build/reports/shared-home', 'home-expired-*.png', 'home'),
        (root / 'desktop/build/reports/appreciation-viewer', '*.png', 'viewer'),
    ]
    count = 0
    for directory, pattern, group in sources:
        for source in directory.glob(pattern):
            if not source.is_file() or source.is_symlink():
                continue
            target = root / 'build/ci-render-evidence' / group / source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            count += 1
    print('Collected synthetic render evidence:', count)


if __name__ == '__main__':
    collect()

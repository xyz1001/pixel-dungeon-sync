#!/usr/bin/env python3
"""Offline final audit. Run next to evidence_bundle.py and audit_fork_evidence.py.

python3 audit_final_evidence.py --bundle validation-evidence.json
Only reads the bounded JSON bundle; no UI, live saves, config, JAR or network.
"""
import argparse
import json
from pathlib import Path
from evidence_bundle import read, unpack, sha
from audit_fork_evidence import review, filemap, slot, digest, INPUTS


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--bundle', type=Path, default=Path(__file__).with_name('validation-evidence.json'))
    args = ap.parse_args()
    raw = read(args.bundle)
    bundle = json.loads(raw)
    docs = unpack(bundle)
    numbered = {name.split('/')[-1][:2]: value for name, value in docs.items()
                if name.startswith('evidence/') and name.split('/')[-1][:2].isdigit()}
    fork = review(numbered, {name: bundle['sources']['evidence/' + name]['sha256'] for name in INPUTS})
    checks = dict(fork['checks'])
    def check(name, value):
        checks[name] = bool(value)
    def save(n):
        return numbered[n]['devices']['B']['save']
    a, b = save('36'), save('37')
    check('retained_game2_native_713_to_680', a['games']['game2']['hero']['pos'] == 713
          and b['games']['game2']['hero']['pos'] == 680)
    check('retained_game2_saved_game_and_floor_changes', all(
        filemap(a)['game2/' + f] != filemap(b)['game2/' + f] for f in ['game.dat', 'depth1.dat']))
    check('retained_game2_save_preserves_both_children', all(
        slot(filemap(a), s) == slot(filemap(b), s) for s in ['game1', 'game3']))
    check('retained_game2_seed_version_UUID_preserved', a['games']['game2']['seed'] == b['games']['game2']['seed']
          and a['games']['game2']['version'] == b['games']['game2']['version'] == 896
          and a['sidecar']['runs']['game2']['runUUID'] == b['sidecar']['runs']['game2']['runUUID'])
    for number in ['37', '38', '39', '40']:
        s = save(number)
        check(number + '_save_stable_and_canonical_hash_valid', s['gameFilesStableAcrossRead']
              and digest(filemap(s)) == s['contentSha256']
              and all(digest(slot(filemap(s), k)) == v for k, v in s['runContentSha256'].items()))
        check(number + '_all_three_progresses_unsynced_expected', not s['sidecar']['currentContentMatchesFiles']
              and all(not s['sidecar']['runs'][k]['contentMatchesFiles'] for k in ['game1', 'game2', 'game3']))
    check('all_native_continuation_remote_unchanged', numbered['36']['remote']['immutableFileSha256']
          == numbered['37']['remote']['immutableFileSha256'])
    check('history_alias_before_after_B_save_sidecar_baseline_unchanged', numbered['38']['devices']['B']
          == numbered['39']['devices']['B'])
    check('restart_B_save_sidecar_baseline_unchanged', numbered['39']['devices']['B']
          == numbered['40']['devices']['B'])
    check('alias_and_restart_remote_unchanged', numbered['38']['remote']['immutableFileSha256']
          == numbered['39']['remote']['immutableFileSha256'] == numbered['40']['remote']['immutableFileSha256'])
    check('alias_and_restart_A_untouched', numbered['38']['devices']['A']
          == numbered['39']['devices']['A'] == numbered['40']['devices']['A'])
    alias = numbered['41']
    check('recorded_local_alias_qa_history', list(alias['localAliases'].values()) == ['qa-history'])
    check('recorded_UI_alias_and_current_name', alias['restartUiObserved']['historyAlias'] == 'qa-history'
          and alias['restartUiObserved']['currentName'] == 'qa-base')
    check('recorded_UI_paused_and_no_onboarding', alias['restartUiObserved']['autoUploadPaused']
          and alias['restartUiObserved']['onboardingNotRepeated'])
    clean = numbered['43']
    check('recorded_cleanup_identity_checked_and_SIGTERM', clean['fixtureIdentityChecked']
          and clean['sentSignal'] == 'SIGTERM')
    check('recorded_cleanup_apps_closed_and_service_gone', all(clean[k] for k in
          ['testAppsClosedByNativeWindowManager', 'loopbackPortClosed', 'fixtureProcessGone', 'nativeDesktopPortClosed']))
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    xml = [v for k, v in docs.items() if k.endswith('.xml')]
    for suite in xml:
        cases = suite['cases']
        observed = {'tests': len(cases), 'failures': sum(c['failure'] for c in cases),
                    'errors': sum(c['error'] for c in cases), 'skipped': sum(c['skipped'] for c in cases)}
        check('junit_' + suite['suite']['name'], all(int(suite['suite'][k]) == v for k, v in observed.items()))
        for k, v in observed.items():
            totals[k] += v
    check('494_real_test_cases_zero_fail_error_skip', totals
          == {'tests': 494, 'failures': 0, 'errors': 0, 'skipped': 0})
    check('junit_summary_agrees', docs['build-evidence/test-summary.json']['totals'] == totals)
    check('frozen_runtime_entry_equivalence', all(v['allArchiveEntryContentsEqual']
          for v in docs['build-evidence/runtime-source-equivalence.json'].values()))
    cap = docs['evidence/capacity-run.json']
    gate = cap['capacityGate']
    check('capacity_status_and_exit', cap['status'] == 'PASSED_EXPECTED_CAPACITY_REJECTION'
          and cap['exitCode'] == 0 and docs['evidence/capacity-run.exit'].strip() == '0')
    check('capacity_exact_error_and_zero_publication_attempts', gate['failureMessage']
          == '合并失败：需要 7 个游戏槽位，容量只有 6 个。未修改存档。' and gate['remoteWriteAttempts'] == 0)
    for key in ['gameFiles', 'remoteFiles', 'baseline', 'pending']:
        check('capacity_' + key + '_unchanged', gate[key + 'Before'] == gate[key + 'After'])
    check('capacity_sidecar_bytes_unchanged', gate['sidecarBeforeSha256'] == gate['sidecarAfterSha256'])
    check('capacity_six_unique_initial_UUIDs', len({v['runUUID']
          for v in cap['sixSlotPublishedA']['sidecar']['runs'].values()}) == 6)
    check('capacity_six_clones_of_one_native_run', len({v['contentSha256']
          for v in cap['sixSlotPublishedA']['sidecar']['runs'].values()}) == 1)
    check('capacity_three_distinct_native_inputs_preserved', len({v['completeRunContentSha256']
          for v in cap['inputs'].values()}) == 3 and cap['inputFixturesUnchanged'])
    result = {'passed': all(checks.values()), 'checkCount': len(checks), 'checks': checks,
              'testTotals': totals, 'bundleSha256': sha(raw),
              'sourceManifestSha256': bundle['sourceManifestSha256'],
              'sourceManifestEntries': len(bundle['sources']),
              'scope': 'Read-only replay of recorded JSON/JUnit attributes; no live process/API/GUI actions.',
              'operatorObservationOnly': ['identity confirmation used native UI',
                  'Main Menu save workflow', 'alias display/pause/no onboarding after restart',
                  'native app closure and fixture process/port cleanup'],
              'finalState': 'Three native slots continued successfully; new local progress remains unsynced '
                            'and sidecar hashes intentionally await identity confirmation. No more scenarios started.'}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

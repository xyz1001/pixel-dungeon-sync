#!/usr/bin/env python3
"""Replay same-run fork consistency checks from bounded saved evidence, read-only.

No fixture/save bytes, credentials, config, network, game APIs, or GUI are needed.
Usage: python3 audit_fork_evidence.py [--evidence-dir ../evidence]
Prints JSON to stdout and exits nonzero on any failed assertion.
This checks recorded observations, not their capture authenticity or UI actions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import struct

INPUTS = (
    '25-fork-common-baseline.json', '26-fork-A-advanced.json',
    '29-before-fork-merge.json', '31-fork-merge-result.json',
    '33-fork-first-child-continued.json', '36-both-fork-children-continued.json',
)


def read_input(root, name):
    # Fixed basenames; do not follow a substituted input symlink.
    fd = os.open(root / name, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > 2 * 1024 * 1024:
            raise ValueError('Unexpected input type/size')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(2 * 1024 * 1024 + 1)
        after = os.fstat(fd)
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (
                after.st_ino, after.st_size, after.st_mtime_ns):
            raise ValueError('Input changed while reading')
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('Duplicate JSON key')
                result[key] = value
            return result
        return json.loads(data, object_pairs_hook=unique), hashlib.sha256(data).hexdigest()
    finally:
        os.close(fd)


def digest(files):
    h = hashlib.sha256(b'PDSYNC-CONTENT-V1')
    h.update(struct.pack('>I', len(files)))
    for name, value in sorted(files.items(), key=lambda item: item[0].encode('utf-8')):
        raw = name.encode('utf-8')
        h.update(struct.pack('>I', len(raw)))
        h.update(raw)
        h.update(bytes.fromhex(value))
    return h.hexdigest()


def filemap(save):
    return {name: value['sha256'] for name, value in save['files'].items()}


def slot(files, name):
    return {key[len(name) + 1:]: value for key, value in files.items()
            if key.startswith(name + '/')}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--evidence-dir', type=Path,
                    default=Path(__file__).resolve().parents[1] / 'evidence')
    args = ap.parse_args()
    docs, hashes = {}, {}
    for name in INPUTS:
        docs[name[:2]], hashes[name] = read_input(args.evidence_dir, name)
    report = review(docs, hashes)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['passed'] else 1


def review(docs, hashes):
    base, left, before = (docs[n]['devices'][dev]['save']
                          for n, dev in [('25', 'B'), ('26', 'A'), ('29', 'B')])
    one, both = (docs[n]['devices']['B']['save'] for n in ['33', '36'])
    summary = docs['31']
    merged_id = summary['sidecar']['lastSynced']['commitId']
    commit = docs['33']['remote']['commits']['default/' + merged_id]
    obj = docs['33']['remote']['objects']['default/' + commit['snapshot']['id']]
    merged, lineage = commit['files'], commit['directoryMetadata']['runs']
    parent = base['sidecar']['runs']['game1']['runUUID']
    checks = {}
    def check(name, value):
        checks[name] = bool(value)

    for number in ['25', '26', '29', '33', '36']:
        for device in ['A', 'B']:
            save = docs[number]['devices'][device]['save']
            files = filemap(save)
            check(number + '_' + device + '_stable', save['gameFilesStableAcrossRead'])
            check(number + '_' + device + '_content_hash', digest(files) == save['contentSha256'])
            check(number + '_' + device + '_run_hashes', all(
                digest(slot(files, key)) == value for key, value in save['runContentSha256'].items()))
    check('merge_exactly_three_slots', set(lineage) == {'game1', 'game2', 'game3'}
          and summary['slots'] == ['game1', 'game2', 'game3'])
    for dest, source, source_slot in [('game1', before, 'game1'),
                                      ('game3', left, 'game1'), ('game2', base, 'game2')]:
        check(dest + '_whole_directory_matches_recorded_native_source',
              slot(merged, dest) == slot(filemap(source), source_slot))
        check(dest + '_canonical_run_hash', digest(slot(merged, dest)) == lineage[dest]['contentSha256'])
        check(dest + '_summary_position_matches_object',
              summary[dest]['heroPosition'] == obj['games'][dest]['hero']['pos'])
    check('merged_canonical_hash', digest(merged) == commit['contentSha256']
          == summary['sidecar']['currentContentSha256'] == obj['contentSha256'])
    check('manifest_matches_captured_zip_filemap', merged == obj['files'])
    check('manifest_matches_captured_zip_hash_and_size', commit['snapshot']['sha256']
          == obj['sha256'] and commit['objectBytes'] == obj['bytes'])
    check('children_two_unique_new_UUIDs', len({parent, lineage['game1']['runUUID'],
                                               lineage['game3']['runUUID']}) == 3)
    check('children_parentRunUUIDs_original', all(lineage[s]['parentRunUUIDs'] == [parent]
                                                for s in ['game1', 'game3']))
    check('unaffected_game2_UUID_preserved', lineage['game2']['runUUID']
          == base['sidecar']['runs']['game2']['runUUID'])
    check('resolution_two_actual_parents', commit['kind'] == 'resolution'
          and len(commit['parents']) == 2 and all('default/' + p in docs['33']['remote']['commits']
                                                for p in commit['parents']))
    parent_maps = [docs['33']['remote']['commits']['default/' + p]['files'] for p in commit['parents']]
    check('resolution_parents_retain_both_game1_sources',
          {digest(slot(p, 'game1')) for p in parent_maps}
          == {digest(slot(filemap(before), 'game1')), digest(slot(filemap(left), 'game1'))})
    check('game1_native_462_to_426', obj['games']['game1']['hero']['pos'] == 462
          and one['games']['game1']['hero']['pos'] == both['games']['game1']['hero']['pos'] == 426)
    check('game3_native_425_to_424', obj['games']['game3']['hero']['pos']
          == one['games']['game3']['hero']['pos'] == 425
          and both['games']['game3']['hero']['pos'] == 424)
    check('first_child_save_does_not_change_other_slots',
          all(slot(filemap(one), s) == slot(merged, s) for s in ['game2', 'game3']))
    check('second_child_save_does_not_change_first_or_game2',
          all(slot(filemap(one), s) == slot(filemap(both), s) for s in ['game1', 'game2']))
    check('both_children_saved_game_and_floor_changes', all(
        filemap(both)[s + '/' + f] != merged[s + '/' + f]
        for s in ['game1', 'game3'] for f in ['game.dat', 'depth1.dat']))
    check('seed_and_version_preserved_per_child', all(
        both['games'][s]['seed'] == obj['games'][s]['seed'] == base['games']['game1']['seed']
        and both['games'][s]['version'] == obj['games'][s]['version'] == 896
        for s in ['game1', 'game3']))
    check('sidecar_lineage_still_present_after_native_play', all(
        both['sidecar']['runs'][s]['runUUID'] == lineage[s]['runUUID']
        and both['sidecar']['runs'][s]['parentRunUUIDs'] == lineage[s]['parentRunUUIDs']
        for s in ['game1', 'game2', 'game3']))
    check('post_native_sidecar_stale_expected_not_claimed_synced',
          not both['sidecar']['currentContentMatchesFiles']
          and all(not both['sidecar']['runs'][s]['contentMatchesFiles'] for s in ['game1', 'game3'])
          and both['sidecar']['runs']['game2']['contentMatchesFiles'])
    check('remote_unchanged_between_native_child_saves',
          docs['33']['remote']['immutableFileSha256'] == docs['36']['remote']['immutableFileSha256'])
    check('baseline_unchanged_between_native_child_saves', docs['33']['devices']['B']['repositoryBaselines']
          == docs['36']['devices']['B']['repositoryBaselines'])
    check('device_settings_unchanged_during_fork', before['excludedDeviceSettings']['sha256']
          == one['excludedDeviceSettings']['sha256'] == both['excludedDeviceSettings']['sha256'])
    return {'passed': all(checks.values()), 'checkCount': len(checks),
                      'checks': checks, 'inputSha256': hashes, 'mergedCommit': merged_id,
                      'scope': 'Recorded evidence consistency only; native UI actions observed separately.',
                      'caveat': 'After native play, both child sidecar content hashes remain at merged baseline. '
                                'New game bytes are intentionally unconfirmed/unsynced at this checkpoint.'}


if __name__ == '__main__':
    raise SystemExit(main())

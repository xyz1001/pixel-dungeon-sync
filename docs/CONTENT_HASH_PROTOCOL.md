# Content-addressed save records (schema 3)

This document describes the schema-3 implementation in `AppendOnly.kt`,
`AppendOnlyWebDav.kt`, and `DirectoryMetadata.kt`. It supersedes the schema-2
write/identity and restore-publication descriptions in `APPEND_ONLY_PROTOCOL.md`
for current clients. That older document remains historical documentation, not
an assertion that schema 3 works on a server which ignores conditional requests.

**Deployment gate: a compatible backend is required. No live service's atomic
`If-None-Match: *` behavior has been verified by this change.** Local mock-server
tests do not establish deployed-server compatibility. Backend selection or a
server-side enforcement change remains a separate, unresolved deployment task.

## Content identity and canonical encoding

New save identity is `contentSha256(snapshot.files)`, a lowercase 64-character
SHA-256 hexadecimal digest. New outer commit `id` and inner `snapshot.id` both
hold that same value. The archive manifest remains schema 1; the outer append
record is schema 3. `snapshot.parent` remains null. `snapshot.sha256` separately
checks the exact ZIP bytes and is **not** the save identity.

`PDSYNC-CONTENT-V1` encodes the complete included save-file set as follows:

1. Validate each relative path with `safePath`; exclude reserved root directory
   metadata and its recognized temporary sidecars. Reject case-insensitive path
   aliases using `Locale.ROOT`, and reject file digests other than 64 lowercase
   hexadecimal characters. Normal capture also applies the game's file selection
   rules and complete-run validation; identity is not a digest of selected sample
   files or a display summary.
2. Sort paths lexicographically by their **unsigned UTF-8 bytes**, with a shorter
   matching prefix ordered first. Do not use locale collation, JSON order,
   platform separators, or ZIP entry order. Preserve the validated path bytes;
   do not add Unicode normalization or lowercase the encoded path.
3. Write the UTF-8 bytes of `PDSYNC-CONTENT-V1`, with no length field, terminator,
   BOM, or newline.
4. Write the entry count as a four-byte big-endian integer (`DataOutputStream.writeInt`).
5. For each sorted path, write its UTF-8 byte length as a four-byte big-endian
   integer, then those path bytes, then the file SHA-256 as **32 raw bytes**
   decoded from its lowercase hex representation. There are no separators.
6. SHA-256 this byte stream and render the result as lowercase hexadecimal.

An encoding-only test vector (not a valid game save): the single path
`game1/game.dat` containing the UTF-8 bytes `example` has file digest
`50d858e0985ecc7f60418aaf0cc5ab587f42c2570a884095a9e8ccacd0f6545c`
and content identity
`ac5b98689ccbd4dd2e9bbb0d020007caface62f4b01824b15b14cf03a464f8de`.

Capture times, device labels/IDs, directory names, run-identity metadata, operation
metadata, proposed parents, compression choices and ZIP timestamps do not enter
this identity. Different paths or file bytes do. The hash identifies a content
set, not a publication event. Hashes provide integrity checks, not authorship,
authentication, or proof of the globally latest save.

## Physical records, names, and compatibility

Within the already selected `<game>/<profile>/` collection, new records are:

- `pdsync-v3-object-<content-hash>.zip`
- `pdsync-v3-commit-<content-hash>.json`

The v3 prefix is a filename namespace, not an automatically created new game or
profile directory. Readers list both v2 and v3 commit names in the same collection,
validate the version-specific identifier syntax, and follow bounded same-origin,
same-collection pagination. Object requests never inherit pagination cursors.
There is no shared head, server index, alias-index upload, MOVE, LOCK, automatic
remote migration, or remote garbage collection.

Schema-2 records retain their original UUIDv4 commit ID, independent UUIDv4 object
ID, original serialized bytes, and original UUID parents. Their physical names
remain `pdsync-v2-commit-<uuid>.json` and `pdsync-v2-object-<uuid>.zip`. They are
read-compatible sources; current WebDAV write methods reject UUID IDs before any
request. No legacy JSON or ZIP is rewritten merely to adopt hash identities.
A schema-1 `head.json` still triggers the separate legacy-protocol boundary; it is
not silently imported as schema 2 or 3.

`validCommitId` remains the strict lowercase UUIDv4 validator. `validContentHashId`
is the hash validator; `validRecordId` accepts either physical-record form.
Device identity, run UUIDs, parent-run UUIDs, previous-run UUIDs and UUID temporary
sidecar names keep their existing UUID rules. This is not a global UUID-to-hash
replacement.

### Mixed-history parent resolution

A schema-3 parent list contains content hashes only. Preparation converts an
explicit legacy parent reference using its already remembered valid manifest's
complete file map. Missing parent metadata blocks preparation. Parent hashes are
sorted and deduplicated; the new record's own content hash is removed. A proposed
resolution with fewer than two remaining parents becomes an ordinary snapshot.

Discovery retains physical nodes rather than rewriting legacy metadata into a
collapsed hash graph. A UUID parent resolves only its exact original physical
record. A hash parent resolves the full set of discovered physical records with
that complete-files digest, including legacy UUID aliases and an existing v3
record. A legacy source can therefore satisfy a hash parent without a v3 copy.

These are equivalent evidence sources for one content identity, not additional
stored parents or a newly invented merge record. For equal-content legacy roots
`A1` and `A2`, a new `C` with the single parent hash `A` consumes both physical
aliases in derived tips and ancestry. The same applies to an old raw chain
`A1 → A2 → A3` sharing content: all raw links and bytes remain unchanged, and none
is left as a false unconsumed tip after `C`. UUID sorting, timestamps, or a unique
deepest alias are not required to publish valid new content.

Every resolved physical parent source must have complete verified ancestry before
the child is admitted to the verified graph; a convenient valid alias cannot hide
an incomplete or cyclic equivalent source. History rendering retains all actual
legacy parent evidence while grouping equivalent content. A hash reference does
not identify a particular old publication or rewrite its parents.

Hash parents with no matching discovered content are fetched by their v3 name
after available candidate metadata has been considered. Schema-2 UUID parent
chains are fetched by their original names. A missing source, invalid object, incomplete ancestry or cycle
keeps the graph unsettled and blocks publication. Metadata-only discovery is not
object-verification proof. Publication must verify the relevant objects before
adopting a source. Known manifest bytes changing at the same physical key remain
an integrity failure.

## First-writer immutability and publication

If a verified record already contains exactly the complete requested file map,
publication reuses that record with **zero remote writes**. This includes a legacy
source: there is no eager v3 copy solely to rename its identity. Device/time/name
changes and a different proposed parent list do not create another save identity.
The existing record's ZIP, metadata and parents remain unchanged. Equal content
cannot retroactively absorb branches, establish new ancestry, or rewrite the
remote record to persist a local rename. Restoring old content and uploading it
again can therefore reuse history without advancing the cloud tips.

For a genuinely absent v3 content record:

1. Freeze the local content-addressed plan and retain its exact local archive and
   manifest as recovery evidence. Validate the current graph and parent sources.
2. Enter a fresh-read boundary. Read any existing commit and verify its object
   before using it as a winner.
3. Read the object key. If absent, issue one create-only PUT with
   `If-None-Match: *`. Read back and verify the actual archive against the complete
   file map. A valid same-content archive with different ZIP encoding may win;
   its actual ZIP digest and byte count are used in the proposed commit.
4. Recheck for a competing completed commit. If none exists, conditionally create
   the commit with the same header. Read back the winning commit and object and
   validate them. A competing valid writer's metadata and parents win unchanged.
5. After verifying the reused or newly published content, associate the local
   baseline with that actual existing record and finish the applicable pending
   receipt. Reusing historical content `A` records the local association to `A`
   without publishing a new cloud record, changing `A`'s parents, or advancing
   cloud tips. An interrupted frozen publication can finish its local receipt
   through the same verified existing record.

The transport does not retry PUTs automatically. HTTP 409 and 412 are returned as
status-bearing failures, not treated as proof of successful publication. Core
performs bounded fresh reads and accepts only a fully verified winner; a 409 due
to a missing collection does not become success. Retryable HTTP statuses such as
503 are intercepted before OkHttp can replay a mutation. Connection loss can
still mean the server accepted a write; bounded readback resolves only what can
actually be verified. No unbounded retry, sleep loop, unconditional fallback PUT,
or overwrite is used. Namespace creation remains a separate explicitly selected
operation, never an implicit side effect of reads or content reuse.

### Backend safety requirement and release limitation

The server must atomically enforce create-if-absent across concurrent writers
for **both** object and commit keys and preserve the existing bytes when the
precondition fails. Sending the header alone cannot make this true. A server
which ignores it can allow two same-hash writers with different ZIP or metadata
bytes to overwrite one another between client reads. Subsequent readback may
detect corruption but cannot undo overwritten bytes or prove that no overwrite
occurred. Ordinary read-before-write is not a substitute for atomic enforcement.

Historical source inspection associated SFTPGo `2.7.6-62ae9ba3` with a WebDAV
implementation lacking this conditional-header check. The public source references
are:

- [SFTPGo revision 62ae9ba3 dependency declaration](https://github.com/drakkan/sftpgo/blob/62ae9ba3/go.mod#L24):
  pins `github.com/drakkan/webdav` to revision `f3e211091328`.
- [That WebDAV revision's PUT handler](https://github.com/drakkan/webdav/blob/f3e211091328/webdav.go#L354-L396):
  conditional-header support is still a TODO, and the open flags include truncation.
- [SFTPGo v2.7.6 upload path](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/webdavd/handler.go#L176-L265):
  separately checks existing-file state and overwrite permission before creation;
  this is not an atomic create-if-absent guarantee. Its “atomic upload” setting is
  not proof of conditional PUT semantics.

These public files were reviewed for this document; no deployed endpoint was
queried. Treat the historical version association as a compatibility warning,
not proof of the current deployment's version, configuration or behavior. This
client change does not patch that server or certify any active endpoint. There is
no runtime capability probe that can prove these semantics or safely compensate
for an ignored header. This document states a release prerequisite, not an
implemented deployment-enforcement switch. A
compatible backend or audited server-side atomic create-only enforcement is
required before schema-3 write deployment. Choosing and validating that solution
is still outstanding; do not describe current service support as established.

Deployment validation must be separately authorized and include two concurrent
conditional PUTs to one disposable test key with different bytes, exactly one
winner, a precondition failure for the loser, and unchanged winner bytes on later
conditional attempts, for both JSON and ZIP. Do not run this against real saves,
modify current accounts, or infer support from OPTIONS, advertised WebDAV levels,
a successful first PUT, a product name, or mock-server results.

## Older-client visibility

Older v2-only clients generally ignore v3-prefixed filenames. Separate prefixes
avoid presenting hash names as malformed v2 UUID names, but do **not** make old
clients compatible with the new history. An old client may see only legacy roots,
miss newer tips, or consider a v3-only collection empty. Its displayed history
must not be treated as complete, and mixed old/new writer operation is unsupported.
Upgrade or stop older clients before deploying v3 writes. No sentinel, warning
control, new UI, or server-side old-client block is added by this change.

## Restore and merge boundaries

Selected remote restore, including a historical choice or explicit branch choice,
reads the existing record and applies verified save bytes locally. It does not
PUT a restore/resolution commit, upload overwritten local content, create remote
collections, or change remote objects. Read-only transport tests require only
GET/HEAD/PROPFIND and byte-for-byte unchanged remote maps. Local restore receipts,
metadata and recovery state may change; local installation and recovery checks
remain necessary.

Merge preparation is also remote-read-only. A separate upload publishes truly
new merged content or reuses an existing verified content record. A new-content
multi-parent resolution can record its selected ancestry; selecting content
which already exists cannot mutate that existing record's parents to pretend a
new resolution occurred. All distinct unresolved branches remain represented.

## Pending conversion and failure boundaries

Existing schema-2 pending plans are read and verified in their original form.
They are not sent through UUID write methods. When publication needs new content,
conversion derives the content hash and parent hashes, writes a separate hash-keyed
local ZIP and manifest, and switches the active-plan marker only after both files
have been written. Original UUID-keyed local evidence remains recoverable. If
verified equal content already exists, the caller may reuse it instead without
remote conversion or writes. Conversion is not remote migration.

Atomic local file replacement is not a claim of fsync-backed power-loss durability
or a distributed transaction. Interrupted writes, marker/state inconsistencies,
missing parent metadata and invalid frozen bytes must fail rather than silently
recapture different local saves or send an invalid plan. An object can exist
without a commit after interruption; it is reusable only after content verification.
A corrupt/truncated existing object or commit is not overwritten, deleted, or
repaired automatically. Preserve the frozen local plan and report the unresolved
integrity/visibility failure. A failed readback does not establish success.

Access-denial exceptions stop the operation. Read/size/path/namespace checks,
listing-work limits, strict manifest validation and remembered-byte integrity
checks remain in force. No cryptographic origin signature, malicious-server
resistance, globally complete listing proof, or guarantee of concurrent-write
safety on a backend that ignores preconditions is supplied by this protocol.

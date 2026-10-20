# code89 / 0.5.68-test: history record time

Based on code88 commit 24f7b2d99a67408a89bc4345fd4632a453af665b; retains the reviewed in-place conflict dialog and existing ordinary upload path.

The shared Android/Windows history chart displays and orders cloud history using each commit's own `operation.preparedAt`, newest first. This is the record preparation/generation time, not server receipt or upload completion time. A resolution may reuse an old snapshot while having its own new commit time. File modification time is no longer used as a cloud record's ordering key.

For legacy ordinary snapshot records without operation metadata, `snapshot.metadata.capturedAt` is the available approximation. A reused-snapshot resolution without its own operation timestamp remains unknown rather than claiming the old snapshot time as a new record time. Unknown times remain unknown and sort last. Equal-time child records precede their parents; unrelated ties use stable IDs. Paging is applied after global ordering.

A content-matched local/baseline card shares its corresponding record's displayed and sorting time. Genuinely unmatched local progress still shows its actual local save modification time. Content equality does not rewrite ancestry, authorize sync or create upload/restore events. Existing parent-to-child and local/cloud relationship edges retain their original endpoints and direction.

Only presentation and regression tests change. No protocol, remote record, upload, restore, expiry, login or authorization changes, no new control or prompt, and no unrelated translation changes. Validation uses synthetic history and isolated test data; it does not start the user's application or write real cloud saves.

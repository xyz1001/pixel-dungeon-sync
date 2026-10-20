# code67 / 0.5.46-test — Wizard header and credential requirements

## Requested UI changes
- Removed the Android native global “存档同步 / 导出日志” toolbar.
- The same existing local diagnostic export action appears once, at the right of the step row on both wizard pages. It uses SharedActionButton's existing secondary rounded style. Desktop receives no action and no extra export control; the existing settings export row remains.
- AndroidAppViewport now owns the top safe area as well as bottom/IME padding; it no longer consumes the top inset as though the removed toolbar still existed.
- Username/password supporting text appears directly below its field, wraps naturally, and does not replace the label or typed value. Focus relocation targets the label and field, excluding the supporting paragraph so a short keyboard viewport still reveals the input.

## Actual rules, unchanged
Sources: core/src/main/java/io/github/xyz1001/pdsync/android/OfficialServiceRegistrationClient.java validateCredentials; server/managed-service/sftpgo_client.py _validate_credentials; android-app/src/main/java/io/github/xyz1001/pdsync/android/ConnectionEndpointPolicy.java.

Official registration: username [a-z0-9]{3,32}; password 6–128 Unicode code points, no control characters or malformed surrogate sequences, no leading $ or {. No new required uppercase, punctuation, or letter-first restriction.
Official login: legacy username [A-Za-z0-9_][A-Za-z0-9_.-]{0,63}; nonempty original password, at most 512 UTF-8 bytes, no control characters or malformed surrogate sequences. Registration rules are labelled separately and are not applied to existing login accounts.
Custom WebDAV: service-provided username/password; username cannot contain colon, neither may contain control characters. No official registration character/length policy is claimed for external providers.

## Preserved behavior
code66 access-mode notice/warning removal and code65 canceled-install automatic-restoration removal remain. Export still invokes the same ACTION_OPEN_DOCUMENT_TREE temporary-grant flow, local bounded log snapshot, unique ZIP creation, readback verification and silent cancellation. No new network request, credential logging, installer action, Root request or restore operation is added.

## Verification
Real offscreen production Compose tests cover steps 1/2 and repeated navigation, 280/320/412/800 widths, 1.0/1.4 font scale, one export action, actual pointer clicks, disable/re-enable, desktop/non-wizard absence, official/custom format text, wrapping and typed value preservation. Existing reduced viewport focus/typing/rotation tests include the Android wizard export action. These are host layout tests, not Android system insets/IME or a device SAF picker test.

Release, signature, complete host test results and known baseline lint boundaries are listed in the delivered code67 validation file. User-device validation is separate.

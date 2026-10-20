# code62: installer recovery and ordinary APK installer handoff

Version: 0.5.41-test / 62. Baseline: code61 archive SHA-256
`a50cc03c2491c293a35aae9cfd5e39a5b855830383f4915cca795c6713829efc`.

## Evidence and scope

Device evidence supplied during this revision distinguished two failures:
- A missing confirmation token retained an active checkpoint. Business controls stayed disabled while the independent log export button remained responsive.
- After that checkpoint was canceled, the system-only CONFIRM_INSTALL entry was positively identified as disabled. Activity start returned failure (-91), with rapid return/resume/launch activity. The user's ordinary third-party APK installer remained available for ACTION_VIEW.

No system component is enabled by this revision. A missing resolver alone is not proof that a component is disabled: package visibility can hide results.

## Recovery and replay boundaries

- Persist token-unavailable and positively disabled-launch observations separately.
- Auto-release only an identified install checkpoint whose original token and known session are both absent, after allowing result reconciliation. Never automatically abandon a live session, infer an uninstall outcome, or commit another installation.
- Live, unknown and uninstall requests retain explicit recovery actions. Unavailable requests do not enter the automatic 500ms polling/settling loop.
- One automatic confirmation launch attempt per persisted request nonce. Activity return, a throwing launch and Activity recreation cannot reset the budget. Explicit retry still uses the current token and existing safety gates.
- Receiver publication and token/expired-checkpoint changes share a narrow lock. Cleanup rechecks identity and token availability after expensive validation.
- Recovery controls stay in the fixed footer; errors take precedence over generic working text.

## Ordinary third-party installation

New ordinary single-APK installations always use a plain ACTION_VIEW APK intent. Android honors the existing default installer, and shows its resolver only when needed. No system-component disabled check or forced chooser is used to choose this route. Split sets and special helper flows retain the session route with the bounded automatic confirmation launch budget.

For an existing request, switching requires an explicit in-app confirmation. It closes only a matching session listed among this application's sessions, refuses active progress, and requires its disappearance before opening another installer. Pending uninstall and unknown-session states cannot silently switch. Restore-recovery guards apply before copying or closing sessions.

The plain install intent uses application/vnd.android.package-archive, an exact private content URI, ClipData and temporary read permission. It does not target a hardcoded installer or forward CONFIRM_INSTALL/session tokens. A non-exported provider exposes only one immutable verified APK copy, never arbitrary paths or writable access. Split sets are rejected instead of exposing only their base APK. The original prepared artifact is retained.

External installation has its own checkpoint and explicit result checking. Activity result codes are not success or cancellation evidence. Package name, version, current signer, full installed APK-set hash and stable package/component metadata must match before readiness is published. A changed or unrelated existing component cannot satisfy the request. Definitive handler launch failure only cleans up the share. The explicit End waiting action remains available even if package verification fails.

No automatic uninstall occurs. Signature conflicts remain the chosen installer's normal user-visible decision. Revoking the URI cannot cancel another application's installation or retract bytes already copied; the End waiting confirmation explains this limitation.

## Verification boundary

Executable production coordinator, production Activity-method extraction (including deliberate regression mutations), provider/URI/copy tests, shared Compose rendered interaction tests and release build/lint are recorded in the delivered validation summary. Host tests do not prove OEM resolver/grant enforcement or a successful device installation. The final candidate requires device verification with the user's selected installer.

The existing code61 development signing identity is reused; no new key is generated. Source packaging excludes signing secrets and raw user-device evidence.

References:
- https://developer.android.com/training/secure-file-sharing/share-file
- https://developer.android.com/reference/android/content/Intent
- https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionInfo

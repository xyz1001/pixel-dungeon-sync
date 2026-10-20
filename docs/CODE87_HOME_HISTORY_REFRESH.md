# code87 / 0.5.66-test: HOME history refresh

Based on final code86 fee16722c89b3b6d72b82564d2ac2261a6560fc7. Its renewal-triggered HOME read remains intact.

The user-authorized Refresh / 刷新 button sits at the right of the HOME archive-history title in the shared Android/Windows UI. Other history-section contexts retain their previous header. The action reuses the code86 read-only local/cloud reload and existing loading overlay. It rebuilds the current observation and action availability without requiring an existing archive/session or uploadable content.

Host and shared callbacks check current page, identity and capabilities. Repeated clicks while loading cannot start overlapping work. Expired, unauthenticated or unknown official-account access cannot enable the action; the existing fresh remote-operation authorization still applies. Navigation, account/selection changes, closure and late results cannot publish into a different context. Failures reuse existing feedback.

No automatic upload/restore, cloud namespace preparation, new Root-authorization request, package preparation, server mutation, expiry change or protocol change is introduced. No other controls or prompts are added. Windows renewal/manual refresh suppresses automatic scheduling from the old observation while establishing the new read-only baseline.

Validation covers callback freshness, disabled and loading states, empty history, read-once behavior, failure/cancellation/account isolation, read-only call paths, and Chinese/English narrow layouts and large font scales. See the release validation report for final test counts, package checks and platform-runtime limitations.

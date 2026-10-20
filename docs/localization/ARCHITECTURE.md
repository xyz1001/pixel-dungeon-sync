# System-language localization

## Scope and resource contract

The review inventory is `CATALOG_INVENTORY.json` (2,125 paired entries); the Java catalogs are authoritative.

Existing Android, Desktop and shared Compose surfaces use paired resources in `core/src/main/java/pdsync/i18n`. Each `UiText` has one stable explicit key and both Chinese and English templates. No language selector, extra control, new notice or restored retired UI is added.

- `SharedMessages`: navigation, onboarding, game/connection/comparison/branch pages, home/history, settings, paths, confirmations, naming, appreciation, placeholders and accessibility.
- `AndroidHostMessages`: Activity status, action/confirmation copy, Android adapters, summaries and existing host notice visibility.
- `AndroidMessages`: access, Root, installation, notifications, cloud/payment/account handling and Android validation errors.
- `PrepareMessages`: the preparation service's safe user-facing failure boundary and cache/path errors. Internal technical cause codes remain unchanged.
- `DesktopMessages`: desktop host/adapters, game launch, native choosers, details/status, dates and platform actions.
- `CoreMessages`, `CoreSessionMessages`, `CoreValidationMessages`: shared presentation, account APIs, history/counts, core/Android facades, save compatibility, merge, storage and transport validation.

Templates use numbered format arguments. `UiText` checks paired placeholder indices/types at class initialization. Original game/save names, account values, paths, versions, hashes and server protocol identifiers remain arguments or raw data. No general replacement of final strings is used.

## Locale behavior

`zh` (including zh-CN, zh-TW and zh-Hant) selects the Chinese catalog. English and unsupported languages select English. Formatting uses Chinese or English consistently with this fallback. Dates retain the user's system timezone. Both supported interface languages use left-to-right layout; an RTL system language therefore falls back to English with LTR layout, without altering the OS setting.

Android initializes the locale from the current resource `Configuration` before Activity state construction. The Application updates it on configuration changes; the Activity uses normal Android locale recreation rather than retaining a stale composition. Active automatic-sync services refresh the existing notification and notification-channel name. Default Android app-label resources are English, with `values-zh` Chinese.

Desktop initializes from the JVM/operating-system default locale. Resource access is dynamic for tests and future use; the supported user workflow after changing the OS language is to restart the application. No claim is made that a running JVM receives every platform's OS-language change notification.

## Behavior and persistence

Display text no longer chooses home status colors, dangerous confirmation style, history head markers, settings toggle/setup appearance or action icons: these use explicit booleans/enums. Default-directory discovery exposes a stable reason code. Existing comparison/upload/restore eligibility is preserved, including empty-save/source guards and stale-action rejection.

Existing Android app-owned installation/automatic-sync status fields are stored as resource IDs where possible. The closed codec accepts the prior exact Chinese or English status value for backwards compatibility, then renders it in the current locale. It is never applied to user save names or arbitrary file contents. Unknown values are preserved. Previously visible/suppressed host notices retain their display scope via an exact closed presentation allowlist, not English character-length heuristics.

## Deliberately untranslated technical values

- Package names, URI/protocol keys, archive members, JSON field names, IDs, hashes, regular expressions, machine-readable diagnostic action/stage/failure codes and file names are not translations.
- The legacy `自动识别` snapshot capture version marker is protocol/history data, not UI copy; it remains byte-for-byte unchanged.
- `PreparationDiagnostics.causeCode` consumes internal English technical exceptions to assign stable sanitized diagnostic codes. GamePreparationService public failures are wrapped in `PreparationDiagnostics.Failure`; its safe user message is localized. The strict safe-message whitelist accepts exact shipped messages in either language and rejects appended or modified text.
- `InstallCompatibility.reason` is internal compatibility evidence consumed by `InstallUiCopy`, whose user-facing output is localized. It is not shown directly as interface copy.
- Closed WebDAV transport messages (`WebDAV network request failed [CATEGORY]`) and namespace exception reasons remain diagnostic classification inputs. `NoviceCopy` and connection setup render their existing friendly localized messages. `WebDAV HTTP <status>` is a language-neutral protocol/status notation.
- Historical/raw user data is never rewritten solely to change the UI language.

## Verification strategy

Catalog tests enumerate every resource, check key uniqueness and both languages, construct all templates under zh-CN/zh-TW/en-US/fr-FR/ar-EG, and verify raw argument preservation. Focused tests cover locale changes after class loading, stable status persistence and old-status decoding, exact safety whitelists and count singular/plural cases.

Production Compose rendering covers existing pages in Chinese, English and Arabic-system English fallback with RTL layout, at wide/narrow widths and increased font scale. The tests inspect real text semantics and text layout overflow, and save PNGs. They do not log in or write real saves. Android source/host fixtures verify configuration routing; these are not evidence of a physical phone OS-language change. Final validation records separately distinguish compilation, host tests, rendering, emulator/device checks and any existing limitations.

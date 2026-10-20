# Code 50: two-step configuration

## Scope

The setup screens are now **Choose game (1 / 2) → Connect service (2 / 2) → Home** on both Android and desktop. The path page is no longer part of setup. The existing desktop standalone path setting remains available; Android's trusted path setting stays read-only.

The native Android access-mode dialog explains:

> Android 不允许直接访问其他应用的存档，请选择使用 Root 授权或修改游戏以开放存档访问。

The same installed-app and selected-APK choices, cancellation, permission checks, and installation confirmation remain. Root authorization and package-component readiness enable manual Next. Next binds metadata only and never scans save contents. Root readiness remains `NOT_CHECKED` until a real read succeeds.

## Navigation and reads

- A restored Android `PATH` or old `COMPARISON` page maps to `CONNECTION`; an interrupted `CONFIRM` maps to `HOME`. A live confirmation is not normalized away.
- Setup service Back returns to game selection. Settings connection Back still returns to settings. The service controller retains its existing built-in/custom selection and validation behavior.
- Service completion persists configuration and enters HOME before any save/history inspection. It does not upload, restore, adopt, or fabricate a baseline.
- Android HOME uses the existing Root/bridge read boundary. A stopped bridge can use the existing one-shot component activation after that targeted inspection, not during normal foreground changes.
- Missing Android `files` remains the existing empty-save result after a successful access check. Permission and I/O failures remain failures.
- Desktop retains the configured/default save path. After an explicit service completion, HOME performs the same safe directory preparation that the removed path step previously did, then opens the existing profile. An already completed cold start does not replay setup or silently create a missing directory.
- Existing automatic-upload rules continue to require valid stable comparison/ancestry. Configuration completion is not synchronization proof.

## Verification boundaries

Host tests execute navigation normalization, controller/adapter flows, pure Root setup and installation policies. Source contracts cover Android Activity wiring and no-save-read boundaries. Shared Compose raster tests exercise both reference and narrow layouts using the real shared UI with fake transports; they do not authenticate against production.

Android release and desktop compilation are checked offline. No phone, Windows, system installer, SAF picker, or real Android lifecycle was operated for this release. APK signing remains the code46–49 development certificate; the public game compatibility key and bridge runtime are unchanged.

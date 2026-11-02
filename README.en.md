# Pixel Dungeon Save Sync · PDSync

[简体中文](README.md)

Upload Pixel Dungeon progress to WebDAV, view history, and restore a selected save. Android manages separate saves for supported game variants; desktop currently supports only recognized Shattered Pixel Dungeon game JARs. **Synchronizing saves does not convert their format** or make saves from different variants interchangeable.

This is test software. Restoring replaces local progress; keep an independent save copy first.

## Supported games and platforms

- **Android 8.0 or later:** Shattered Pixel Dungeon and variants that pass the app's identity, save-format, and access checks. Appearing in the app list does not establish sync support; there is no compatibility list guaranteeing every variant works.
- **Windows / Linux / macOS:** Currently only Shattered Pixel Dungeon game JARs. Restore is currently available for game versions **3.3.8 and 4.0.2**; other identifiable versions may not support restores. Android variant support does not extend to arbitrary desktop variants.
- Saves are not converted, and two sets of game progress cannot be merged. Use the same game and compatible versions across devices, and try an upload and restore with replaceable progress first.

## Download and install

**As of 2026-11-02, [Releases](https://github.com/xyz1001/pixel-dungeon-sync/releases) has no published versions.** To try `0.5.114-test`, download the existing [test package collection](https://github.com/xyz1001/pixel-dungeon-sync/actions/runs/41074899460/artifacts/12987715941):

1. Sign in to GitHub, open [this build](https://github.com/xyz1001/pixel-dungeon-sync/actions/runs/41074899460), and select **verified-release-packages** under **Artifacts** (about 319 MB). Retention ends at **2026-11-13 14:59 UTC**; the link will stop working if it expires or is deleted. Check Releases or subsequent test-package instructions then.
2. Extract the collection and choose only the package for your device below. It contains six packages, `SHA256SUMS.txt`, and version notes; you do not need to install all of them. The version in the filenames is `0.5.114-test`.
3. For Android, choose **`PDSync-0.5.114-test-android.apk`**, the signed PDSync installer. **Do not install `android-unsigned`; `android-test-reports` contains check reports, not an installer.**

| Device | Package | Installation or launch |
| --- | --- | --- |
| Windows x64 | `.exe` installer | Run the installer and follow its instructions. |
| Windows x64 | Portable `.zip` | Extract the entire directory and run its application launcher. Do not copy only the EXE. A Java runtime is included; no separate JDK is needed. |
| Debian / Ubuntu x64 | `.deb` | Run `sudo apt install ./downloaded-file.deb` in the download directory. |
| Arch Linux x64 | `.pkg.tar.zst` | Run `sudo pacman -U ./downloaded-file.pkg.tar.zst`. |
| macOS Apple Silicon | `.dmg` | Open the disk image and drag the app into Applications. Intel Macs are not a target of this build. |
| Android 8.0 or later | `.apk` | Open the APK on the device and complete Android's installation confirmation. Includes arm64-v8a, armeabi-v7a, x86, and x86_64. |

Desktop packages include the Java runtime needed by the app. Linux still requires a graphical desktop and distribution dependencies. Windows/macOS packages do not yet have publisher code signing or Apple notarization, so the OS may report an unverified source. Do not disable system protections to install them. Android release packages must pass the project's signature checks; unsigned CI artifacts are not installable release packages.

Use the included `SHA256SUMS.txt` to check the extracted packages. Run `Get-FileHash filename -Algorithm SHA256` in Windows PowerShell, `sha256sum filename` on Linux, or `shasum -a 256 filename` on macOS, then compare the result with that file's entry. A matching checksum does not establish OS trust in the publisher.

## First use

1. **Choose a game.** Install the target game separately first. If you already have progress, save and exit normally; on a new device without progress, choose a cloud restore during the later comparison. Select the corresponding game or variant in PDSync. Check that its name, version, and target location are what you expect.
2. **Confirm save access.** On Android, choose Root or Modify APK mode as described below. Desktop uses the current system user's game save directory; check the detected game JAR and progress. Choosing a game executable does not select a cloud save.
3. **Connect to the cloud.** Sign in with the app's built-in account option, or enter your own WebDAV URL, username, and password in connection settings. The URL should point to an existing root collection you can access. Complete the connection check before saving configuration. Sync access for the built-in service depends on the account's current status; your WebDAV server controls access for custom connections.
4. **Compare progress.** Upload to keep the current phone/computer progress in the cloud. To bring back cloud progress, select the appropriate history node and restore it locally. If the two sides differ and there is a conflict, decide which progress to keep; cancel if unsure.

Devices should use the same account and corresponding game configuration. On Android, verified game package names separate cloud spaces for different variants; local state is also bound to the account, connection, and game identity. Switching from variant A to B on the same account does not restore A's saves into B. Account isolation still depends on correct WebDAV server permissions: a shared directory is not a substitute for server-side access control.

## How Android accesses game saves

- **Root mode:** For an already installed game. The device must already have working Root access, and its Root manager must grant the app access. Selecting this mode does not root the device or install/uninstall games.
- **Modify APK / built-in save bridge:** Does not require Root. The app's preparation flow adds a save bridge to a supported game APK and signs it again, followed by Android's normal installation flow. The bridge is installed with that game; it does not grant arbitrary access to other apps. A newly signed APK may not update an installation signed with the original key. Do not uninstall a game holding your only save simply to resolve an installation conflict.

Installing a game package may require Android to allow the selected installation source and display a system confirmation. Follow the actual installation flow. If Root is denied, a game is unsupported, or an access check fails, do not change system directory permissions to bypass the check.

## Uploads, restores, and conflicts

For everyday use: **save and exit the game → open PDSync and upload → confirm success → refresh and restore on the other device → start the game there.** Clicking Upload alone does not mean the other device has the latest progress.

Desktop can upload automatically after save changes or game exit while PDSync is running and connected; pause this in Settings. Android automatic uploads depend on how the game was launched and the app's running state. Return to the home screen and confirm the result before switching devices. Automatic uploads do not decide conflicts or automatically restore cloud progress locally.

- **Upload** stores progress for the selected game. Identical content does not create another save; the upload button may also be unavailable when both sides match.
- **Restore** writes the selected cloud progress back to the current game, replacing the corresponding local progress. Exit the game normally first, then reopen it after completion to check the character, floor, and progress. Do not assume the app keeps an additional recoverable local backup.
- **Overwrite or cancel:** A conflict dialog asks whether to make local progress the current cloud progress. Cancel does not perform that overwrite. The app does not merge save contents.
- **The current history chain:** If the cloud is `A → B → C → D`, you restore B, play to E, and confirm overwrite, the current chain becomes `A → B → E`. The cloud files for C and D remain, but this does not promise that the old branch remains directly selectable for restore in the current interface.

Do not keep playing during synchronization or have another device change the same cloud progress at the same time. After a network failure, check the app's result and current history before retrying; do not repeatedly click restore or overwrite. The local/cloud markers help compare progress, but do not imply compatibility between game variants.

PDSync synchronizes game saves, not game installers or a whole-device backup. It includes `gameN` save directories and rankings, badges, and journal files; device settings and `bones.dat` are excluded. Restore replaces the corresponding files without automatic rollback. An interrupted write may leave a partial replacement, so keep an independent copy. Do not use older clients on the same cloud directory alongside this version.

## Common questions

**Why is the collection much larger than the Android APK?** It contains all six packages, and each desktop package includes Java. Windows has installer and portable editions, while Linux has two distribution formats. The collection size is not the installed size on any one platform.

**Why are there no local saves?** Start the selected game, save and exit normally, then refresh. Check the variant, access method, and system user. Missing saves and missing read permission are different problems.

**Is a disabled upload button a bug?** Both sides may already match, access/connection checks may be incomplete, or another operation may be running. Read the home status before refreshing; the button alone is not enough to diagnose it.

**Restore completed, but the game still shows old progress.** Check that you selected the right node for the current game. A running game may retain old state in memory; do not save it over the restored progress. Record the app's result and game screen for a report rather than clearing app data.

**What if connection or sync fails?** Check the service URL, network, account access, and WebDAV directory. Fix TLS/certificate issues on the server instead of disabling certificate verification. Settings can export diagnostics; review and remove personal information before sharing. Never send passwords, keys, or complete connection configuration.

**Does the portable package keep all configuration beside the EXE?** No. Desktop configuration defaults to `.pixel-dungeon-sync` in the current user's home directory. “Portable” means no installer or separately installed Java is needed, not that the app never writes user configuration.

**Where is the connection password stored?** The existing design saves connection configuration in plaintext: Android uses the app-private `noBackup/webdav.properties`; desktop uses `.local/config.json` in the application workspace, which defaults to `.pixel-dungeon-sync` in the user's home directory. This is not system-keychain encryption. Do not share these files: anyone with access to the relevant private directory, user account, or backups may be able to read the connection information.

## Feedback and development

Include the app version, OS version, game/variant version, reproduction steps, and screenshots without sensitive information when reporting a problem through [Issues](https://github.com/xyz1001/pixel-dungeon-sync/issues). Do not upload your only save or account secrets.

For builds, CI, signing, version tags, and releases, see the [build and release guide](docs/RELEASING.md).

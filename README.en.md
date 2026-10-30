# Pixel Dungeon Save Sync · PDSync

[简体中文](README.md)

Upload Pixel Dungeon progress to WebDAV, view history, and restore a selected save. Android manages separate saves for supported game variants; desktop currently supports only identity-verified Shattered Pixel Dungeon game JARs. **Synchronizing saves does not convert their format** or make saves from different variants interchangeable.

> This is still test software, and the first formal release is not complete. Use only assets actually listed on [Releases](https://github.com/xyz1001/pixel-dungeon-sync/releases), together with their release notes. The table below describes planned packages, not downloads that are already available. Keep an independent copy of your game saves before trying restore or overwrite operations.

## Download and install

| Device | Package | Installation or launch |
| --- | --- | --- |
| Windows x64 | `.exe` installer | Run the installer and follow its instructions. |
| Windows x64 | Portable `.zip` | Extract the entire directory and run its application launcher. Do not copy only the EXE. A Java runtime is included; no separate JDK is needed. |
| Debian / Ubuntu x64 | `.deb` | Run `sudo apt install ./downloaded-file.deb` in the download directory. |
| Arch Linux x64 | `.pkg.tar.zst` | Run `sudo pacman -U ./downloaded-file.pkg.tar.zst`. This is a pacman package, not a renamed generic archive. |
| macOS Apple Silicon | `.dmg` | Open the disk image and drag the app into Applications. Intel Macs are not a target of this build. |
| Android 8.0 or later | `.apk` | Open the APK on the device and complete Android's installation confirmation. Includes arm64-v8a, armeabi-v7a, x86, and x86_64. |

Desktop packages include the Java runtime needed by the app. Linux still requires a graphical desktop and distribution dependencies. Windows/macOS packages do not yet have publisher code signing or Apple notarization, so the OS may report an unverified source. Do not disable system protections to install them. Android release packages must pass the project's signature checks; unsigned CI artifacts are not installable release packages.

Releases will include SHA-256 checksums to check download integrity. Build and launch checks do not establish full compatibility with every OS or game version. See each release's notes for its architectures, signing status, and verification scope.

## First use

1. **Choose a game.** Create and save progress normally in the game, then exit it. Select the corresponding game or variant in PDSync. Check that its name, version, and target location are what you expect.
2. **Confirm save access.** On Android, choose Root or Modify APK mode as described below. Desktop uses the current system user's game save directory; check the detected game JAR and progress. Choosing a game executable does not select a cloud save.
3. **Connect to the cloud.** Sign in with the app's built-in account option, or enter your own WebDAV URL, username, and password in connection settings. The URL should point to an existing root collection you can access. Complete the connection check before saving configuration. Sync access for the built-in service depends on the account's current status; your WebDAV server controls access for custom connections.
4. **Compare progress.** Upload to keep the current phone/computer progress in the cloud. To bring back cloud progress, select the appropriate history node and restore it locally. If the two sides differ and there is a conflict, decide which progress to keep; cancel if unsure.

Devices should use the same account and corresponding game configuration. On Android, verified game package names separate cloud spaces for different variants; local state is also bound to the account, connection, and game identity. Switching from variant A to B on the same account does not restore A's saves into B. Account isolation still depends on correct WebDAV server permissions: a shared directory is not a substitute for server-side access control.

## How Android accesses game saves

- **Root mode:** For an already installed game. The device must already have working Root access, and its Root manager must grant the app access. Selecting this mode does not root the device or install/uninstall games.
- **Modify APK / built-in save bridge:** Does not require Root. The app's preparation flow adds a save bridge to a supported game APK and signs it again, followed by Android's normal installation flow. The bridge is installed with that game; it does not grant arbitrary access to other apps. A newly signed APK may not update an installation signed with the original key. Do not uninstall a game holding your only save simply to resolve an installation conflict.

Installing a game package may require Android to allow the selected installation source and display a system confirmation. Follow the actual installation flow. If Root is denied, a game is unsupported, or an access check fails, do not change system directory permissions to bypass the check.

## Uploads, restores, and conflicts

- **Upload** stores progress for the selected game. Identical content does not create another save; the upload button may also be unavailable when both sides match.
- **Restore** writes the selected cloud progress back to the current game, replacing the corresponding local progress. Exit the game normally first, then reopen it after completion to check the character, floor, and progress. Do not assume the app keeps an additional recoverable local backup.
- **Overwrite or cancel:** A conflict dialog asks whether to make local progress the current cloud progress. Cancel does not perform that overwrite. The app does not merge save contents.
- **The current history chain:** If the cloud is `A → B → C → D`, you restore B, play to E, and confirm overwrite, the current chain becomes `A → B → E`. The cloud files for C and D remain, but this does not promise that the old branch remains directly selectable for restore in the current interface.
- **Using multiple devices:** Android and desktop now use the same save protocol and share history, comparison, and restore parsing. Real desktop UI checks have covered uploads, restores followed by continuing in the official game, conflict cancellation/overwrite, account isolation, and history arrows. This is not an Android-device-to-desktop round-trip test or a guarantee for every platform and game version. Verify cross-device use with replaceable test progress first.

Do not keep playing during synchronization or have another device change the same cloud progress at the same time. After a network failure, check the app's result and current history before retrying; do not repeatedly click restore or overwrite. The local/cloud markers help compare progress, but do not imply compatibility between game variants.

## Common questions

**Why are there no local saves?** Start the selected game, save and exit normally, then refresh. Check the variant, access method, and system user. Missing saves and missing read permission are different problems.

**Is a disabled upload button a bug?** Both sides may already match, access/connection checks may be incomplete, or another operation may be running. Read the home status before refreshing; the button alone is not enough to diagnose it.

**Restore completed, but the game still shows old progress.** Check that you selected the right node for the current game. A running game may retain old state in memory; do not save it over the restored progress. Record the app's result and game screen for a report rather than clearing app data.

**What if connection or sync fails?** Check the service URL, network, account access, and WebDAV directory. Fix TLS/certificate issues on the server instead of disabling certificate verification. Settings can export diagnostics; review and remove personal information before sharing. Never send passwords, keys, or complete connection configuration.

**Does the portable package keep all configuration beside the EXE?** No. Desktop configuration defaults to `.pixel-dungeon-sync` in the current user's home directory. “Portable” means no installer or separately installed Java is needed, not that the app never writes user configuration.

**Where is the connection password stored?** The existing design saves connection configuration in plaintext: Android uses the app-private `noBackup/webdav.properties`; desktop uses `.local/config.json` in the application workspace, which defaults to `.pixel-dungeon-sync` in the user's home directory. This is not system-keychain encryption. Do not share these files: anyone with access to the relevant private directory, user account, or backups may be able to read the connection information.

## Feedback and development

Include the app version, OS version, game/variant version, reproduction steps, and screenshots without sensitive information when reporting a problem through [Issues](https://github.com/xyz1001/pixel-dungeon-sync/issues). Do not upload your only save or account secrets.

For builds, CI, signing, version tags, and releases, see the [build and release guide](docs/RELEASING.md).

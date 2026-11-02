# PDSync · Pixel Dungeon Cross-Platform Save Sync Tool

<p align="center">
  <b>Seamlessly continue your dungeon crawl anywhere, anytime across all your devices.</b>
</p>

<p align="center">
  <a href="README.md">简体中文</a> | <b>English</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-blue.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Protocol-WebDAV-green.svg" alt="WebDAV" />
  <img src="https://img.shields.io/badge/Target-Pixel%20Dungeon%20%26%20Forks-orange.svg" alt="Pixel Dungeon" />
</p>

---

**PDSync** is a cross-platform save synchronization utility crafted for *Pixel Dungeon* and its community forks (such as *Shattered Pixel Dungeon*).

Whether you are crawling depths on your Android phone during a commute or continuing the run on your PC at home, PDSync keeps your progress seamlessly connected across all your devices.

> [!IMPORTANT]
> **💡 Design Philosophy & Note on Save-Scumming (S/L)**:
> - The **sole core goal of this tool is cross-device progress synchronization and seamless handoff**, breaking platform barriers.
> - As a quintessential Roguelike, *Pixel Dungeon* derives its true charm and thrill from irreversible choices and **Permadeath**.
> - The underlying snapshot history exists **strictly as a safety net for disaster recovery, conflict resolution, and accidental save loss**. We **strongly discourage using this tool for save-scumming (S/L, Save & Load)**. Relying on rollback ruins the procedural unpredictability and tension, stripping away the authentic sense of achievement that makes Roguelikes fun.

---

## ✨ Key Features

- 🔄 **Seamless Cross-Platform Handoff**: Full support across Android, Windows, macOS, and Linux. Hop effortlessly between mobile and desktop.
- ☁️ **Flexible Dual Cloud Storage Options**:
  - **Built-in WebDAV (Zero Configuration)**: One-click sign-in without manual server setup. Supported via voluntary appreciation donations; **donations strictly cover backend server hosting, bandwidth, and storage maintenance costs**.
  - **Custom / Third-Party WebDAV (100% Free & Self-Hosted)**: Native support for Jianguoyun, Nextcloud, Alist, Synology, or any standard WebDAV server. Your data stays completely in your own hands.
- 🛡️ **Robust Anti-Loss & Disaster Recovery**:
  - **Automatic Local Backups**: Takes an isolated backup of your existing local save before applying cloud restores.
  - **Explicit Conflict Resolution**: Clear comparison dialogs when two devices diverge—never silently overwriting your hard-earned run.
  - **Append-Only History Tree**: Tracks sync checkpoints clearly for auditing and error recovery.
- 📱 **Flexible Android Integration**:
  - **Non-Root Mode (Embedded Save Bridge)**: No rooting required! Follow the simple wizard to patch the game APK.
  - **Root Mode**: Direct, native access for rooted devices without needing APK repacking.
  - **Multi-Fork Isolation**: Keeps saves organized independently for each Pixel Dungeon variant.
- 💻 **Out-of-the-Box Desktop Experience**: Bundled with a Java runtime (no need to manually configure a JDK). A clean 4-step wizard guides your initial setup.

---

## 🎮 Supported Platforms & Games

| Platform | Supported Games / Forks | Notes |
| :--- | :--- | :--- |
| **Android** (8.0+) | *Shattered Pixel Dungeon* and compatible forks | Supports both **Non-Root Save Bridge** and **Root Direct Access**. Different variants are isolated independently. |
| **Desktop** (Win / Mac / Linux) | *Shattered Pixel Dungeon* (Java Edition) | Connects to official runnable game JARs (including 3.3.8, 4.0.2, and compatible releases). |

> [!WARNING]
> **⚠️ Crucial Notice for Android Non-Root Mode (Read Before Installing)**:
> Non-Root mode integrates a save bridge into the game APK and **re-signs it with a new key**. Due to Android's strict signature verification, the newly signed package **cannot update the original game directly; you must uninstall the existing game first**.
> 
> **Please Note: Uninstalling the original game will permanently erase all existing local saves on your device!**
> - **Recommendation**: Only set this up after finishing your current run (either through victory or game over).
> - If your device already has Root access, use **Root Mode** instead—it reads saves in-place **without uninstalling or losing data**.

> [!NOTE]
> **Save Format Notice**: PDSync transfers and archives saves reliably, but **does not convert proprietary save formats**. For cross-device sync, please use identical or compatible game versions.

---

## 📦 Download & Installation

Download packages from the [Releases page](https://github.com/xyz1001/pixel-dungeon-sync/releases) or the latest verified build packages:

| Platform | Package | How to Run |
| :--- | :--- | :--- |
| **Windows** (x64) | `PDSync-Setup.exe` or Portable `.zip` | Run the installer or unzip and launch the executable directly (Java runtime included). |
| **Android** (8.0+) | `PDSync-*-android.apk` | Install the APK on your device (signed and ready to use). |
| **macOS** (Apple Silicon) | `PDSync-*.dmg` | Open the DMG and drag PDSync into your Applications folder. |
| **Debian / Ubuntu** | `pdsync_*_amd64.deb` | Run `sudo apt install ./pdsync_xxx.deb` |
| **Arch Linux** | `pdsync-*.pkg.tar.zst` | Run `sudo pacman -U ./pdsync-xxx.pkg.tar.zst` |

> [!TIP]
> **System Security Prompts**:
> If Windows SmartScreen or macOS Gatekeeper alerts you about an unrecognized publisher, this is normal for open-source tools without purchased enterprise certificates. Simply select "Run anyway" or allow it in System Settings.

---

## 🚀 3-Minute Quick Start

### Step 1: Prepare Your Cloud Storage (Choose One)

#### Option A: Use Built-in WebDAV (Zero Configuration, Recommended for Convenience)
- **Features**: No need to configure third-party storage or copy server URLs. Sign in to your built-in account to sync immediately.
- **About Appreciation Donations**: The built-in sync service can be activated via voluntary appreciation. **Donations solely go towards covering server rent, bandwidth, and maintenance overhead with zero commercial profit.**

#### Option B: Use Your Own WebDAV Service (100% Free & Self-Hosted)
- **Features**: Works with Nextcloud, Jianguoyun (Nutstore), Alist, Synology, or self-hosted WebDAV. Completely free and under your own control.
- **Example configuration parameters**:
  - **Server URL**: E.g., `https://dav.your-server.com/remote.php/webdav/`
  - **Username**: Your login account / email
  - **Password**: Your app-specific password / auth token

---

### Step 2: Configure PDSync

#### 📱 On Android:
1. Open PDSync and choose your Pixel Dungeon game/variant.
2. Select your save access mode:
   - **Non-Root Mode**: Follow the wizard to patch and install the game APK. *(Reminder: requires uninstalling the original game first, erasing current saves. Perform after ending your current run!)*
   - **Root Mode**: Grant Root permissions for in-place access. *(No uninstallation required, zero data loss!)*
3. Connect: Sign in with the built-in account or input your custom WebDAV details, tap **Test Connection**, and proceed once verified.

#### 💻 On Desktop:
1. Open PDSync; the 4-step wizard will auto-detect or let you locate your game JAR and save folder.
2. Sign in to your built-in account or enter custom WebDAV server details and test the connection.
3. Compare progress between your local folder and cloud storage, then choose to **Upload** or **Restore**.

---

### Step 3: Everyday Play & Sync Workflow

```mermaid
flowchart LR
    A["📱 Play on phone and 【Save & Exit】"] --> B["☁️ Open PDSync and tap 【Upload】"]
    B --> C["💻 Open PDSync on PC and click 【Restore】"]
    C --> D["🎮 Launch game and continue your run!"]
```

- **When finishing a run**: In game, choose *Save & Return to Menu*, open PDSync and click **Upload** (or let auto-sync handle it on game exit).
- **When switching devices**: Open PDSync on your other device, click **Restore to Device**, and then launch the game to pick up where you left off.

---

## 🛡️ Advanced Safety & Protection Mechanisms

### 1. Conflict Protection & Explicit Confirmation
- If you advance progress independently across devices without timely sync, PDSync detects and clearly highlights the divergence.
- An explicit decision dialog lets you choose whether to make your current local progress the new cloud baseline or cancel to inspect—**never silently overwriting any data**.

### 2. Automatic Local Backups
- Before writing any cloud save back to your local game, PDSync creates an isolated local backup first, ensuring you can always trace back your previous local progress.

### 3. Automatic Sync Awareness
- On desktop and supported environments, PDSync detects game exits and save file modifications in the background, automatically uploading during idle periods (can be toggled or paused in Settings at any time).

---

## ❓ Frequently Asked Questions (FAQ)

<details>
<summary><b>Q1: What is the difference between Built-in WebDAV and Custom WebDAV? Are donations mandatory?</b></summary>
<b>Donations are completely voluntary and never mandatory!</b><br>
PDSync provides full, unrestricted, and permanently free support for custom WebDAV (e.g. Nextcloud, Nutstore, or self-hosted services).<br>
Built-in WebDAV is provided as a zero-setup convenience for users who do not want to manage third-party storage. Because real server hosting and bandwidth incur ongoing costs, voluntary appreciation donations are used exclusively to maintain and balance server operational expenses.
</details>

<details>
<summary><b>Q2: Why does Android Non-Root mode require uninstalling the original game? Will I lose saves?</b></summary>
<b>Yes, current local saves will be deleted!</b> Android requires apps that update each other to share identical signing certificates. Because the save bridge must be injected and resigned with a new key, it cannot overwrite the original installation. Uninstalling is required, which removes the game's internal data. Please finish your active run before setting up Non-Root mode, or use Root mode if available.
</details>

<details>
<summary><b>Q3: Can I use this tool to save-scum (S/L)?</b></summary>
PDSync was built for <b>cross-device continuity</b> and <b>disaster prevention</b>. While snapshots exist technically, we strongly advise against using them for S/L. Permadeath is the core soul of Pixel Dungeon; save-scumming defeats the thrill and satisfaction of beating the dungeon.
</details>

<details>
<summary><b>Q4: Will clicking "Restore" wipe my current local save permanently?</b></summary>
No. PDSync automatically takes an isolated backup of your local save directory prior to any restore action.
</details>

<details>
<summary><b>Q5: Why is the Upload button disabled or grayed out?</b></summary>
Common reasons:
1. Local and cloud contents are already identical.
2. You haven't started a run yet and no local save files exist (launch the game and perform a normal save first).
</details>

<details>
<summary><b>Q6: I restored a save, but the game still shows my old progress?</b></summary>
Always exit the game completely before clicking "Restore". If the game is still running in memory, it will overwrite the restored disk files upon exit.
</details>

<details>
<summary><b>Q7: Where does the desktop portable edition store settings?</b></summary>
Configurations are stored in your user home folder (`~/.pixel-dungeon-sync`) so updating portable versions won't lose your connection credentials.
</details>

<details>
<summary><b>Q8: How to troubleshoot network errors or connection timeouts?</b></summary>
1. Verify your WebDAV URL is complete (e.g., Nutstore requires <code>/dav/</code> at the end).<br>
2. Check if local proxy/VPN software is interfering with WebDAV traffic.<br>
3. Check detailed diagnostic logs or export a diagnostic report in <b>Settings -> Diagnostics</b>.
</details>

---

## 🤝 Feedback & Contributions

If you encounter issues or have suggestions, feel free to open a ticket:
- [GitHub Issues](https://github.com/xyz1001/pixel-dungeon-sync/issues)
- For developers & CI releases, see [RELEASING.md](docs/RELEASING.md).

---

<p align="center">
  Happy dungeoneering, and may the Amulet of Yendor be yours! 🧙‍♂️🗡️
</p>

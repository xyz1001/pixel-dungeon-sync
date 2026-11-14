/**
 * PDSync 官方网站核心交互脚本
 * Pixel Dungeon Cross-Platform Save Sync Tool
 */

// 当前发布版本常量（由 GitHub Actions CI 自动注入更新）
const CURRENT_VERSION = "v0.5.114";
const VERSION_NAME = "0.5.114";
const GITHUB_REPO = "xyz1001/pixel-dungeon-sync";

// 全平台构建产物配置表
const DOWNLOAD_CONFIG = {
  android: {
    key: "android",
    name: "Android 安装包",
    platform: "Android 8.0+",
    format: "APK",
    filename: `PDSync-${VERSION_NAME}-android.apk`,
    desc: "支持 Android 8.0+。集成免 Root 存档桥与 Root 原生接入。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-android.apk`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-android.apk`
  },
  windowsExe: {
    key: "windowsExe",
    name: "Windows 安装版",
    platform: "Windows 10 / 11 (x64)",
    format: "EXE",
    filename: `PDSync-${VERSION_NAME}-windows-x64.exe`,
    desc: "Windows x64 向导安装包，内置轻量 Java 运行时，开箱即用。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-windows-x64.exe`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-windows-x64.exe`
  },
  windowsZip: {
    key: "windowsZip",
    name: "Windows 便携免安装版",
    platform: "Windows (x64 Portable)",
    format: "ZIP",
    filename: `PDSync-${VERSION_NAME}-windows-x64-portable.zip`,
    desc: "绿色解压即用，不写系统注册表，所有配置跟随用户目录。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-windows-x64-portable.zip`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-windows-x64-portable.zip`
  },
  macDmg: {
    key: "macDmg",
    name: "macOS 镜像",
    platform: "macOS (Apple Silicon M系列)",
    format: "DMG",
    filename: `PDSync-${VERSION_NAME}-darwin-arm64.dmg`,
    desc: "原生适配 Apple Silicon (arm64)，拖入应用程序目录即可运行。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-darwin-arm64.dmg`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-darwin-arm64.dmg`
  },
  linuxDeb: {
    key: "linuxDeb",
    name: "Debian / Ubuntu 软件包",
    platform: "Debian / Ubuntu (x64)",
    format: "DEB",
    filename: `PDSync-${VERSION_NAME}-linux-x64.deb`,
    desc: "适用于 Debian 11+、Ubuntu 20.04+ 及基于其衍生的发行版。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-linux-x64.deb`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-linux-x64.deb`
  },
  linuxPacman: {
    key: "linuxPacman",
    name: "Arch Linux 软件包",
    platform: "Arch Linux / Manjaro (x64)",
    format: "pkg.tar.zst",
    filename: `PDSync-${VERSION_NAME}-linux-x64.pkg.tar.zst`,
    desc: "Arch Linux 原生 pacman 格式归档包，执行 pacman -U 即可安装。",
    ghproxy: `https://ghproxy.net/https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-linux-x64.pkg.tar.zst`,
    raw: `https://github.com/${GITHUB_REPO}/releases/download/${CURRENT_VERSION}/PDSync-${VERSION_NAME}-linux-x64.pkg.tar.zst`
  }
};

/**
 * 访问者操作系统智能识别
 */
function detectVisitorOS() {
  const userAgent = window.navigator.userAgent || "";
  const platform = window.navigator.platform || "";

  if (/android/i.test(userAgent)) {
    return "android";
  }
  if (/win/i.test(platform) || /windows/i.test(userAgent)) {
    return "windows";
  }
  if (/mac/i.test(platform) || /macintosh/i.test(userAgent)) {
    return "mac";
  }
  if (/linux/i.test(platform) || /linux/i.test(userAgent)) {
    return "linux";
  }
  return "all";
}

/**
 * 默认触发当前系统的快速下载（优先境内加速，异常时自动转弹窗）
 */
function handleHeroDownload() {
  const os = detectVisitorOS();
  if (os === "android") {
    downloadPackage("android", true);
  } else if (os === "windows") {
    downloadPackage("windowsExe", true);
  } else if (os === "mac") {
    downloadPackage("macDmg", true);
  } else if (os === "linux") {
    downloadPackage("linuxDeb", true);
  } else {
    openDownloadModal();
  }
}

/**
 * 下载指定平台安装包
 * @param {string} key - 产物唯一 key
 * @param {boolean} preferAccelerated - 是否优先走高速免流节点
 */
function downloadPackage(key, preferAccelerated = true) {
  const item = DOWNLOAD_CONFIG[key];
  if (!item) {
    window.location.href = `https://github.com/${GITHUB_REPO}/releases`;
    return;
  }
  const url = preferAccelerated ? item.ghproxy : item.raw;
  window.location.href = url;
}

/**
 * 打开全平台下载对话框
 */
function openDownloadModal() {
  const modal = document.getElementById("download-modal");
  if (modal) {
    modal.style.display = "flex";
    document.body.style.overflow = "hidden";
  }
}

/**
 * 关闭全平台下载对话框
 */
function closeDownloadModal() {
  const modal = document.getElementById("download-modal");
  if (modal) {
    modal.style.display = "none";
    document.body.style.overflow = "";
  }
}

/**
 * 打开 SHA-256 校验哈希弹窗
 */
function openChecksumModal() {
  const modal = document.getElementById("checksum-modal");
  if (modal) {
    modal.style.display = "flex";
    document.body.style.overflow = "hidden";
  }
}

/**
 * 关闭 SHA-256 校验哈希弹窗
 */
function closeChecksumModal() {
  const modal = document.getElementById("checksum-modal");
  if (modal) {
    modal.style.display = "none";
    document.body.style.overflow = "";
  }
}

/**
 * 复制文本并给出轻提示
 */
function copyText(text, btnElement) {
  if (!navigator.clipboard) {
    const input = document.createElement("input");
    input.value = text;
    document.body.appendChild(input);
    input.select();
    document.execCommand("copy");
    document.body.removeChild(input);
  } else {
    navigator.clipboard.writeText(text);
  }

  if (btnElement) {
    const originalText = btnElement.innerText;
    btnElement.innerText = "已复制 ✓";
    btnElement.style.color = "#10b981";
    setTimeout(() => {
      btnElement.innerText = originalText;
      btnElement.style.color = "";
    }, 1800);
  }
}

// 移动端菜单切换
function toggleMobileMenu() {
  const navLinks = document.querySelector(".nav-links");
  if (navLinks) {
    const isVisible = navLinks.style.display === "flex";
    navLinks.style.display = isVisible ? "none" : "flex";
    navLinks.style.flexDirection = "column";
    navLinks.style.position = "absolute";
    navLinks.style.top = "72px";
    navLinks.style.left = "0";
    navLinks.style.width = "100%";
    navLinks.style.background = "#ffffff";
    navLinks.style.padding = "20px 24px";
    navLinks.style.boxShadow = "0 10px 15px -3px rgba(0,0,0,0.1)";
    navLinks.style.borderBottom = "1px solid #e2e8f0";
    navLinks.style.zIndex = "99";
  }
}

// 监听 ESC 键关闭模态弹窗
document.addEventListener("keydown", function(e) {
  if (e.key === "Escape") {
    closeDownloadModal();
    closeChecksumModal();
  }
});

// 点击外部阴影区域关闭弹窗
document.addEventListener("click", function(e) {
  const downloadModal = document.getElementById("download-modal");
  if (downloadModal && e.target === downloadModal) {
    closeDownloadModal();
  }
  const checksumModal = document.getElementById("checksum-modal");
  if (checksumModal && e.target === checksumModal) {
    closeChecksumModal();
  }
});

// 初始化：高亮识别到的访客操作系统平台卡片
document.addEventListener("DOMContentLoaded", function() {
  const os = detectVisitorOS();
  const heroBtnText = document.getElementById("hero-download-text");
  if (heroBtnText) {
    if (os === "android") heroBtnText.innerText = "立即下载 Android APK";
    else if (os === "windows") heroBtnText.innerText = "立即下载 Windows 版";
    else if (os === "mac") heroBtnText.innerText = "立即下载 macOS 镜像";
    else if (os === "linux") heroBtnText.innerText = "立即下载 Linux 安装包";
  }

  // 为对应平台卡片添加识别标记
  const targetCardId = `platform-${os}`;
  const targetCard = document.getElementById(targetCardId);
  if (targetCard) {
    targetCard.classList.add("highlight");
    const badge = targetCard.querySelector(".platform-badge-tag");
    if (badge) {
      badge.innerText = "当前系统推荐";
    }
  }
});

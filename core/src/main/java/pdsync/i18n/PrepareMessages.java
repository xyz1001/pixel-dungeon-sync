package pdsync.i18n;

/** User-visible preparation messages. Technical exception text remains locale-independent. */
public final class PrepareMessages {
    private PrepareMessages() {}
    public static final UiText RUNTIME_CHECK = new UiText("android.prepare.runtime_check", "Android 版本检查", "Android version check");
    public static final UiText EXTRACTION = new UiText("android.prepare.extraction", "读取／提取所选 APK", "Reading or extracting selected APKs");
    public static final UiText SOURCE_SIGNATURE_SET = new UiText("android.prepare.source_signature_set", "原始 APK 签名与分包集合检查", "Original APK signature and split-set check");
    public static final UiText MANIFEST_PATCH = new UiText("android.prepare.manifest_patch", "准备游戏组件", "Game component preparation");
    public static final UiText LOCAL_KEY = new UiText("android.prepare.local_key", "读取游戏兼容签名", "Reading the game compatibility signing key");
    public static final UiText GAME_SIGNING = new UiText("android.prepare.game_signing", "为游戏 APK 签名", "Game APK signing");
    public static final UiText HELPER_SIGNING = new UiText("android.prepare.helper_signing", "为助手 APK 签名", "Helper APK signing");
    public static final UiText OUTPUT_SIGNATURE = new UiText("android.prepare.output_signature", "生成 APK 的签名／对齐复核", "Generated APK signature and alignment check");
    public static final UiText PAYLOAD_VALIDATION = new UiText("android.prepare.payload_validation", "代码／资源完整性复核", "Code and resource integrity check");
    public static final UiText RECEIPT_VALIDATION = new UiText("android.prepare.receipt_validation", "准备记录与来源绑定复核", "Preparation receipt and source binding check");
    public static final UiText PREPARATION_FAILED = new UiText("android.prepare.preparation_failed", "准备未完成：%1$s失败（%2$s）。可从顶部导出日志。原游戏不会因此被卸载。", "Preparation did not finish: %1$s failed (%2$s). Export logs from the top of the screen. The original game has not been uninstalled.");
    public static final UiText FILE_PATH_CHANGED = new UiText("android.prepare.file_path_changed", "准备文件路径已变化。", "The preparation file path has changed.");
    public static final UiText FILE_UNAVAILABLE = new UiText("android.prepare.file_unavailable", "准备文件不可用。", "The preparation file is unavailable.");
    public static final UiText VERIFIED_PACKAGE_MISSING = new UiText("android.prepare.verified_package_missing", "缺少已验证安装包。", "The verified installation package is missing.");
    public static final UiText VERIFIED_FILES_MISSING = new UiText("android.prepare.verified_files_missing", "缺少已验证安装文件。", "The verified installation files are missing.");
    public static final UiText FILES_CHANGED = new UiText("android.prepare.files_changed", "准备文件已变化，请重新准备安装包。", "Preparation files have changed. Prepare the installation package again.");
    public static final UiText DIRECTORY_UNAVAILABLE = new UiText("android.prepare.directory_unavailable", "应用准备目录不可用。", "The app preparation directory is unavailable.");
}

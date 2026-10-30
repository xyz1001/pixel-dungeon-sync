import com.android.apksigner.ApkSignerTool;

/** Keep the configured alias out of process arguments as well as passwords. */
class SignApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected keystore, output APK, input APK");
        String alias = System.getenv("PDSYNC_KEY_ALIAS");
        if (alias == null || alias.isEmpty()) throw new IllegalArgumentException("Missing signing alias");
        ApkSignerTool.main(new String[] {
            "sign", "--ks", args[0], "--ks-key-alias", alias,
            "--ks-pass", "env:PDSYNC_STORE_PASSWORD", "--key-pass", "env:PDSYNC_KEY_PASSWORD",
            "--min-sdk-version", "26", "--v1-signing-enabled", "false",
            "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false", "--out", args[1], args[2]
        });
    }
}

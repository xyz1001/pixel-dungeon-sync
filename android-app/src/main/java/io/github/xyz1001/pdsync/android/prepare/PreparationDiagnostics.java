package io.github.xyz1001.pdsync.android.prepare;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.ProviderException;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PreparationDiagnostics {
    private static final int MAX_CAUSES = 8;
    private static final int MAX_SCAN = 4096;

    public enum Source {
        INSTALLED_BASE_AND_SPLITS,
        DOCUMENT_PICKER,
        RETAINED_PREPARATION,
        UNKNOWN
    }

    public enum Stage {
        RUNTIME_CHECK("PREP_RUNTIME", "Android 版本检查"),
        EXTRACTION("PREP_EXTRACTION", "读取／提取所选 APK"),
        SOURCE_SIGNATURE_SET("PREP_SOURCE_SIGNATURE_SET", "原始 APK 签名与分包集合检查"),
        MANIFEST_PATCH("PREP_MANIFEST_PATCH", "修改游戏／助手清单"),
        LOCAL_KEY("PREP_LOCAL_KEY", "读取／创建本机签名密钥"),
        GAME_SIGNING("PREP_GAME_SIGNING", "为游戏 APK 签名"),
        HELPER_SIGNING("PREP_HELPER_SIGNING", "为助手 APK 签名"),
        OUTPUT_SIGNATURE("PREP_OUTPUT_SIGNATURE", "生成 APK 的签名／对齐复核"),
        PAYLOAD_VALIDATION("PREP_PAYLOAD_VALIDATION", "代码／资源完整性复核"),
        RECEIPT_VALIDATION("PREP_RECEIPT_VALIDATION", "准备记录与来源绑定复核");

        public final String code;
        public final String label;

        Stage(String str, String str2) {
            this.code = str;
            this.label = str2;
        }
    }

    public static final class Metadata {
        public final int api;
        public final int apkCount;
        public final String appVersion;
        public final long appVersionCode;
        public final String gamePackage;
        public final long gameVersionCode;
        public final Source source;

        public Metadata(Source source, int i, String str, long j, String str2, long j2, int i2) {
            this.source = source == null ? Source.UNKNOWN : source;
            this.api = (i < 1 || i > 1000) ? -1 : i;
            this.apkCount = (i2 < 1 || i2 > 256) ? -1 : i2;
            this.appVersion = (str == null || str.length() > 40 || !str.matches("[0-9]+(?:\\.[0-9]+){0,3}(?:-(?:test|debug|release))?")) ? "unknown" : str;
            this.appVersionCode = j < 0 ? -1L : j;
            this.gamePackage = (str2 == null || str2.length() > 255 || !str2.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) ? "unknown" : str2;
            this.gameVersionCode = j2 < 0 ? -1L : j2;
        }
    }

    public static final class Failure extends IOException {
        private final Metadata metadata;
        public final Stage stage;

        private Failure(Stage stage, Throwable th, Metadata metadata) {
            super(stage.code + ": " + stage.label, th);
            this.stage = stage;
            this.metadata = metadata;
        }

        public String userMessage() {
            return "准备未完成：" + this.stage.label + "失败（" + this.stage.code + "）。请点击“复制脱敏诊断”或展开“详细信息”。原游戏不会因此被卸载。";
        }
    }

    public static final class VerificationFailure extends IOException {
        public final List<String> issueNames;
        public final boolean issuesTruncated;

        public VerificationFailure(Collection<? extends Enum<?>> collection) {
            this(collection, null);
        }

        public VerificationFailure(Collection<? extends Enum<?>> collection, Throwable th) {
            super("APK verification failed", th);
            boolean z = false;
            ArrayList arrayList = new ArrayList();
            if (collection != null) {
                for (Enum<?> r0 : collection) {
                    if (r0 != null && "com.android.apksig.ApkVerifier$Issue".equals(r0.getDeclaringClass().getName())) {
                        String strName = r0.name();
                        if (strName.length() <= 100 && strName.matches("[A-Z][A-Z0-9_]*") && !arrayList.contains(strName)) {
                            if (arrayList.size() >= 12) {
                                z = true;
                                break;
                            }
                            arrayList.add(strName);
                        }
                    }
                }
            }
            this.issueNames = Collections.unmodifiableList(arrayList);
            this.issuesTruncated = z;
        }
    }

    private PreparationDiagnostics() {
    }

    public static Failure failure(Stage stage, Throwable th) {
        return failure(stage, th, null);
    }

    public static Failure failure(Stage stage, Throwable th, Metadata metadata) {
        Failure failureFind = find(th);
        if (failureFind != null) {
            boolean z = true;
            boolean z2 = metadata != null && (metadata.source == Source.INSTALLED_BASE_AND_SPLITS || metadata.source == Source.DOCUMENT_PICKER);
            if (failureFind.metadata != null && failureFind.metadata.source != Source.UNKNOWN && failureFind.metadata.source != Source.RETAINED_PREPARATION) {
                z = false;
            }
            return (metadata == null || !(failureFind.metadata == null || (z2 && z))) ? failureFind : new Failure(failureFind.stage, failureFind.getCause(), metadata);
        }
        if (stage == null) {
            stage = Stage.RECEIPT_VALIDATION;
        }
        return new Failure(stage, th, metadata);
    }

    public static Failure find(Throwable th) {
        Set setNewSetFromMap = Collections.newSetFromMap(new IdentityHashMap());
        int i = 0;
        while (th != null && i < 8 && setNewSetFromMap.add(th)) {
            if (th instanceof Failure) {
                return (Failure) th;
            }
            i++;
            th = th.getCause();
        }
        return null;
    }

    public static String report(Failure failure) {
        StringBuilder sb = new StringBuilder("PD Sync preparation diagnostic v1\n");
        sb.append("stage=");
        sb.append(failure.stage.name());
        sb.append("\ncode=");
        sb.append(failure.stage.code);
        sb.append('\n');
        Metadata metadata = failure.metadata;
        if (metadata != null) {
            sb.append("source=");
            sb.append(metadata.source.name());
            sb.append("\napi=");
            sb.append(metadata.api);
            sb.append("\napp_version=");
            sb.append(metadata.appVersion);
            sb.append("\napp_version_code=");
            sb.append(metadata.appVersionCode);
            sb.append("\ngame_package=");
            sb.append(metadata.gamePackage);
            sb.append("\ngame_version_code=");
            sb.append(metadata.gameVersionCode);
            sb.append("\napk_count=");
            sb.append(metadata.apkCount);
            sb.append('\n');
        } else {
            sb.append("source=UNKNOWN\n");
        }
        sb.append("signing_configuration=AndroidKeyStore; RSA-3072; APK-v2/v3; Android-API-26..36; verification=device-API\n");
        Set setNewSetFromMap = Collections.newSetFromMap(new IdentityHashMap());
        Throwable cause = failure.getCause();
        int i = 0;
        while (cause != null && i < 8 && setNewSetFromMap.add(cause)) {
            sb.append("cause[");
            int i2 = i + 1;
            sb.append(i);
            sb.append("]=");
            sb.append(className(cause));
            sb.append("; reason=");
            sb.append(reason(cause));
            if (cause instanceof VerificationFailure) {
                VerificationFailure verificationFailure = (VerificationFailure) cause;
                sb.append("; verifier_issues=");
                sb.append(String.join(",", verificationFailure.issueNames));
                if (verificationFailure.issuesTruncated) {
                    sb.append("; verifier_issues_truncated=true");
                }
            }
            appendCryptoHints(sb, cause.getMessage());
            appendPlatformCode(sb, cause);
            sb.append('\n');
            cause = cause.getCause();
            i = i2;
        }
        if (cause != null) {
            sb.append("causes=truncated_or_cyclic\n");
        }
        sb.append("privacy=raw_messages,stack_traces,paths,URIs,credentials,keys,certificates_omitted");
        return sb.toString();
    }

    private static String className(Throwable th) {
        String name = th.getClass().getName();
        return (name.length() > 180 || !name.matches("[A-Za-z_$][A-Za-z0-9_.$]*")) ? "Throwable" : name;
    }

    private static String boundedMessage(Throwable th) {
        return bounded(th.getMessage()).toLowerCase(Locale.ROOT);
    }

    private static String bounded(String str) {
        return str == null ? "" : str.substring(0, Math.min(str.length(), 4096));
    }

    private static String reason(Throwable th) {
        String strBoundedMessage = boundedMessage(th);
        if (th instanceof VerificationFailure) {
            return "APK_VERIFIER_REJECTED";
        }
        if (th instanceof NoSuchAlgorithmException) {
            return "CRYPTO_ALGORITHM_UNAVAILABLE";
        }
        if (th instanceof NoSuchProviderException) {
            return "CRYPTO_PROVIDER_UNAVAILABLE";
        }
        if (th instanceof InvalidKeyException) {
            return "CRYPTO_KEY_REJECTED";
        }
        if (th instanceof InvalidAlgorithmParameterException) {
            return "CRYPTO_PARAMETERS_REJECTED";
        }
        if (th instanceof UnrecoverableKeyException) {
            return "KEY_UNRECOVERABLE";
        }
        if (th instanceof SignatureException) {
            return "CRYPTO_SIGNATURE_OPERATION_FAILED";
        }
        if (th instanceof CertificateException) {
            return "CERTIFICATE_PROCESSING_FAILED";
        }
        if (th instanceof KeyStoreException) {
            return "KEYSTORE_OPERATION_FAILED";
        }
        if (th instanceof ProviderException) {
            return "CRYPTO_PROVIDER_OPERATION_FAILED";
        }
        if (th instanceof FileNotFoundException) {
            return "FILE_OR_ACCESS_UNAVAILABLE";
        }
        if (th instanceof SecurityException) {
            return "ACCESS_DENIED";
        }
        if (strBoundedMessage.contains("unverified signing block") || strBoundedMessage.contains("unverified signing artifacts") || strBoundedMessage.contains("unexplained zip") || strBoundedMessage.contains("ambiguous zip signing") || strBoundedMessage.contains("signing container could not")) {
            return "INSTALLED_SOURCE_CONTAINER_AMBIGUOUS";
        }
        if (strBoundedMessage.contains("read-only system file")) {
            return "INSTALLED_SOURCE_NOT_PROTECTED";
        }
        if (strBoundedMessage.contains("extracted apk bytes differ") || strBoundedMessage.contains("source changed while reading")) {
            return "INSTALLED_SOURCE_COPY_MISMATCH";
        }
        if (strBoundedMessage.contains("source apk has no verified signer")) {
            return "SOURCE_SIGNER_MISSING";
        }
        if (strBoundedMessage.contains("generated apk has no verified v2/v3")) {
            return "OUTPUT_SIGNATURE_SCHEME_MISSING";
        }
        if (strBoundedMessage.contains("generated apk must have exactly one signer")) {
            return "OUTPUT_SIGNER_COUNT_INVALID";
        }
        if (strBoundedMessage.contains("source apk signature")) {
            return "SOURCE_SIGNATURE_INVALID";
        }
        if (strBoundedMessage.contains("alignment") || strBoundedMessage.contains("aligned")) {
            return "ZIP_ALIGNMENT_INVALID";
        }
        if (strBoundedMessage.contains("signature verification failed")) {
            return "OUTPUT_SIGNATURE_INVALID";
        }
        if (strBoundedMessage.contains("certificate differs")) {
            return "SIGNING_CERTIFICATE_MISMATCH";
        }
        if (strBoundedMessage.contains("key is missing") || strBoundedMessage.contains("key is missing or different")) {
            return "LOCAL_KEY_MISSING_OR_CHANGED";
        }
        if (strBoundedMessage.contains("signing identity differs") || strBoundedMessage.contains("another signing key")) {
            return "LOCAL_IDENTITY_MISMATCH";
        }
        if (strBoundedMessage.contains("consent") || strBoundedMessage.contains("confirm creation")) {
            return "KEY_CREATION_CONSENT_REQUIRED";
        }
        if (strBoundedMessage.contains("split")) {
            return "APK_SET_STRUCTURE_OR_SIGNER_MISMATCH";
        }
        if (strBoundedMessage.contains("changed during extraction")) {
            return "INSTALLED_SOURCE_CHANGED";
        }
        if (strBoundedMessage.contains("no longer installed")) {
            return "SELECTED_PACKAGE_UNAVAILABLE";
        }
        if (strBoundedMessage.contains("digest mismatch") || strBoundedMessage.contains("payload") || strBoundedMessage.contains("protected-entry")) {
            return "PAYLOAD_OR_DIGEST_MISMATCH";
        }
        if (strBoundedMessage.contains("receipt") || strBoundedMessage.contains("binding")) {
            return "RECEIPT_OR_SOURCE_BINDING_INVALID";
        }
        if (strBoundedMessage.contains("manifest") || strBoundedMessage.contains("xml") || strBoundedMessage.contains("versionname")) {
            return "MANIFEST_INVALID_OR_UNSUPPORTED";
        }
        if (strBoundedMessage.contains("limit") || strBoundedMessage.contains("too large")) {
            return "TECHNICAL_SIZE_LIMIT";
        }
        return strBoundedMessage.contains("apk preparation supports android api 26 through 36") ? "RUNTIME_API_UNSUPPORTED" : "MESSAGE_OMITTED";
    }

    private static void appendCryptoHints(StringBuilder sb, String str) {
        String lowerCase = bounded(str).toLowerCase(Locale.ROOT);
        String[] strArr = {"AndroidKeyStoreBCWorkaround", "AndroidKeyStore", "AndroidOpenSSL", "Conscrypt", "SHA256withRSA", "SHA512withRSA", "RSASSA-PSS", "PKCS1", "SHA-256", "SHA-512", "RSA"};
        for (int i = 0; i < 11; i++) {
            String str2 = strArr[i];
            if (lowerCase.contains(str2.toLowerCase(Locale.ROOT))) {
                sb.append("; hint=");
                sb.append(str2);
            }
        }
    }

    private static void appendPlatformCode(StringBuilder sb, Throwable th) {
        if ("android.security.KeyStoreException".equals(th.getClass().getName())) {
            try {
                Object objInvoke = th.getClass().getMethod("getNumericErrorCode").invoke(th);
                if (objInvoke instanceof Integer) {
                    sb.append("; keystore_error_code=");
                    sb.append((Integer) objInvoke);
                }
            } catch (ReflectiveOperationException | RuntimeException unused) {
            }
        }
    }
}

package io.github.xyz1001.pdsync.android.prepare;

import pdsync.i18n.PrepareMessages;
import pdsync.i18n.UiText;

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
import java.util.Map;
import java.util.LinkedHashMap;

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
        RUNTIME_CHECK("PREP_RUNTIME", PrepareMessages.RUNTIME_CHECK),
        EXTRACTION("PREP_EXTRACTION", PrepareMessages.EXTRACTION),
        SOURCE_SIGNATURE_SET("PREP_SOURCE_SIGNATURE_SET", PrepareMessages.SOURCE_SIGNATURE_SET),
        MANIFEST_PATCH("PREP_MANIFEST_PATCH", PrepareMessages.MANIFEST_PATCH),
        LOCAL_KEY("PREP_LOCAL_KEY", PrepareMessages.LOCAL_KEY),
        GAME_SIGNING("PREP_GAME_SIGNING", PrepareMessages.GAME_SIGNING),
        HELPER_SIGNING("PREP_HELPER_SIGNING", PrepareMessages.HELPER_SIGNING),
        OUTPUT_SIGNATURE("PREP_OUTPUT_SIGNATURE", PrepareMessages.OUTPUT_SIGNATURE),
        PAYLOAD_VALIDATION("PREP_PAYLOAD_VALIDATION", PrepareMessages.PAYLOAD_VALIDATION),
        RECEIPT_VALIDATION("PREP_RECEIPT_VALIDATION", PrepareMessages.RECEIPT_VALIDATION);

        public final String code;
        public final String label;
        private final UiText displayLabel;

        Stage(String str, UiText text) {
            this.code = str;
            this.label = text.chinese(); // Stable diagnostic label, independent of UI locale.
            this.displayLabel = text;
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
        public final List<PreparationInputEvidence> inputs = new ArrayList<>();

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
            return safeUserMessage(this.stage);
        }
    }

    private static String safeUserMessage(Stage stage) {
        return PrepareMessages.PREPARATION_FAILED.text(stage.displayLabel.text(), stage.code);
    }

    /** Exact allowlist of fixed stage messages; never accepts cause text, paths, or appended details. */
    public static boolean isSafeUserMessage(String message) {
        for (Stage stage : Stage.values()) {
            // Stored notices can outlive a locale change. Accept only either complete fixed translation.
            String chinese = String.format(Locale.ROOT, PrepareMessages.PREPARATION_FAILED.chinese(),
                    stage.displayLabel.chinese(), stage.code);
            String english = String.format(Locale.ROOT, PrepareMessages.PREPARATION_FAILED.english(),
                    stage.displayLabel.english(), stage.code);
            if (chinese.equals(message) || english.equals(message)) return true;
        }
        return false;
    }

    public static final class VerificationFailure extends IOException {
        public final List<String> issueNames;
        public final boolean issuesTruncated;
        public int verificationApi = -1;
        public long elapsedMillis = -1;
        public String verifiedSchemes = "unknown";
        public List<String> issueParameters = Collections.emptyList();

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
        sb.append("signing_configuration=public-game-compatibility; RSA-3072; APK-v2/v3; Android-API-26..36; verification=API-26-and-36\n");
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
        sb.append("privacy=raw_messages,paths,URIs,credentials,keys,certificates_omitted");
        return sb.toString();
    }

    /** Safe structured evidence for persistent logs; caller can apply an additional schema allowlist. */
    public static Map<String, String> details(Throwable throwable) {
        Map<String, String> values = new LinkedHashMap<>(); Failure failure = find(throwable);
        if (failure != null) {
            values.put("stage", failure.stage.name()); Metadata m = failure.metadata;
            if (m != null) {
                values.put("source", m.source.name()); values.put("api", Integer.toString(m.api));
                values.put("app_version_code", Long.toString(m.appVersionCode)); values.put("game_package", m.gamePackage);
                values.put("game_version_code", Long.toString(m.gameVersionCode)); values.put("apk_count", Integer.toString(m.apkCount));
                for (PreparationInputEvidence input : m.inputs) if (input.index < 16) values.putAll(input.details());
                if (m.inputs.size() > 16) values.put("inputs_truncated", "true");
            }
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cause = failure == null ? throwable : failure.getCause(); int index = 0;
        while (cause != null && index < MAX_CAUSES && seen.add(cause)) {
            String prefix = "cause." + index + ".";
            values.put(prefix + "class", className(cause)); values.put(prefix + "reason", reason(cause));
            StackTraceElement[] frames = cause.getStackTrace();
            for (int f = 0; f < Math.min(6, frames.length); f++) {
                String frame = frames[f].getClassName() + "." + frames[f].getMethodName() + ":" + frames[f].getLineNumber();
                if (frame.length() <= 240 && frame.matches("[A-Za-z0-9_.$<>:-]+")) values.put(prefix + "frame." + f, frame);
            }
            if (frames.length > 6) values.put(prefix + "frames_truncated", "true");
            if (cause instanceof VerificationFailure) {
                VerificationFailure v = (VerificationFailure) cause;
                values.put("verifier.api", Integer.toString(v.verificationApi)); values.put("verifier.elapsed_ms", Long.toString(v.elapsedMillis));
                values.put("verifier.schemes", v.verifiedSchemes);
                for (int i = 0; i < v.issueNames.size(); i++) values.put("verifier.issue." + i, v.issueNames.get(i));
                for (int i = 0; i < Math.min(32, v.issueParameters.size()); i++) values.put("verifier.parameter." + i, v.issueParameters.get(i));
                if (v.issuesTruncated) values.put("verifier.issues_truncated", "true");
            }
            index++; cause = cause.getCause();
        }
        if (cause != null) values.put("causes_truncated", "true");
        return Collections.unmodifiableMap(values);
    }

    public static String safeIssueParameter(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            String number = String.valueOf(value); return number.length() <= 40 && number.matches("(?:-?[0-9]+(?:\\.[0-9]+)?|true|false)") ? number : "OMITTED";
        }
        if (value instanceof Enum<?>) {
            String name = ((Enum<?>) value).name(); return name.length() <= 100 && name.matches("[A-Z][A-Z0-9_]*") ? name : "OMITTED";
        }
        if (!(value instanceof String)) return "OMITTED";
        String text = (String) value;
        if (text.matches("(?:SHA-?(?:1|256|384|512)|RSA|DSA|EC|PKCS1|RSASSA-PSS|SHA(?:256|512)withRSA)")) return text;
        return safeApkEntry(text);
    }

    public static String safeApkEntry(String value) {
        if (value == null || value.length() > 180 || value.contains("..") || value.contains(":") || value.contains("@")) return "OMITTED";
        return value.matches("(?:AndroidManifest\\.xml|classes(?:[2-9]|[1-9][0-9]+)?\\.dex|META-INF/[A-Za-z0-9_.-]{1,80}|lib/(?:arm64-v8a|armeabi-v7a|x86|x86_64)/lib[A-Za-z0-9_.-]{1,80}\\.so)") ? value : "OMITTED";
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
        if (strBoundedMessage.startsWith("preparation receipt missing ")) return "RECEIPT_FIELD_MISSING";
        if (strBoundedMessage.startsWith("invalid receipt digest ") || strBoundedMessage.startsWith("invalid embedded receipt digest ")) return "RECEIPT_DIGEST_INVALID";
        if (strBoundedMessage.startsWith("invalid receipt number ")) return "RECEIPT_NUMBER_INVALID";
        if (strBoundedMessage.equals("invalid preparation identity") || strBoundedMessage.equals("mismatched preparation receipt")) return "RECEIPT_ID_MISMATCH";
        if (strBoundedMessage.contains("unsupported preparation receipt format") || strBoundedMessage.contains("requires format 3")) return "RECEIPT_FORMAT_UNSUPPORTED";
        if (strBoundedMessage.contains("unsupported preparation bridge mode")) return "RECEIPT_BRIDGE_MODE_UNSUPPORTED";
        if (strBoundedMessage.contains("obsolete helper field") || strBoundedMessage.contains("legacy helper flags")) return "RECEIPT_OBSOLETE_HELPER_FIELDS";
        if (strBoundedMessage.contains("source identity/version receipt")) return "RECEIPT_SOURCE_VERSION_MISMATCH";
        if (strBoundedMessage.contains("embedded provider identity receipt")) return "RECEIPT_PROVIDER_IDENTITY_MISMATCH";
        if (strBoundedMessage.contains("protected-entry receipt")) return "RECEIPT_PAYLOAD_COUNT_MISMATCH";
        if (strBoundedMessage.contains("outside app-owned staging")) return "STAGING_PATH_OUTSIDE_PRIVATE_ROOT";
        if (strBoundedMessage.contains("staged game apk digest")) return "STAGED_GAME_DIGEST_MISMATCH";
        if (strBoundedMessage.contains("staged bridge runtime digest")) return "STAGED_RUNTIME_DIGEST_MISMATCH";
        if (strBoundedMessage.contains("invalid embedded runtime dex entry")) return "RUNTIME_DEX_NAME_INVALID";
        if (strBoundedMessage.contains("unsupported retained bridge runtime version") || strBoundedMessage.contains("unsupported embedded bridge runtime version")) return "RUNTIME_VERSION_UNSUPPORTED";
        if (strBoundedMessage.contains("inherited runtime receipt")) return "INHERITED_RUNTIME_DIGEST_MISMATCH";
        if (strBoundedMessage.contains("components must occur only in the base")) return "EMBEDDED_COMPONENT_IN_SPLIT";
        if (strBoundedMessage.contains("not the isolated appended dex boundary") || strBoundedMessage.contains("unowned original game classes")) return "EMBEDDED_RUNTIME_BOUNDARY_AMBIGUOUS";
        if (strBoundedMessage.contains("embedded binding has no recognized runtime") || strBoundedMessage.contains("embedded runtime provider is missing")) return "EMBEDDED_RUNTIME_MISSING";
        if (strBoundedMessage.contains("embedded upgrade changed original game payload")) return "EMBEDDED_UPGRADE_PAYLOAD_CHANGED";
        if (strBoundedMessage.contains("embedded upgrade changed unapproved manifest") || strBoundedMessage.contains("embedded upgrade changed split manifest")) return "EMBEDDED_UPGRADE_MANIFEST_CHANGED";
        if (strBoundedMessage.contains("public compatibility signing alone")) return "PUBLIC_SIGNER_WITHOUT_SOURCE_BINDING";
        if (strBoundedMessage.contains("apk signing block size") || strBoundedMessage.contains("apk signing block header")
                || strBoundedMessage.contains("apk signing block pair") || strBoundedMessage.contains("apk signing block has duplicate")) return "APK_SIGNING_BLOCK_STRUCTURE_INVALID";
        if (strBoundedMessage.contains("noncanonical gap before its signing block") || strBoundedMessage.contains("nonzero pre-block alignment")) return "SIGNED_CONTAINER_PADDING_INVALID";
        if (strBoundedMessage.contains("source retains unverified v1 signing artifacts")) return "SOURCE_V1_MATERIAL_UNVERIFIED";
        if (strBoundedMessage.contains("source retains a signing block")) return "SOURCE_SIGNING_BLOCK_OR_GAP";
        if (strBoundedMessage.contains("end-of-directory")) return "ZIP_EOCD_INVALID";
        if (strBoundedMessage.contains("central-directory") || strBoundedMessage.contains("extra central-directory")) return "ZIP_CENTRAL_DIRECTORY_INVALID";
        if (strBoundedMessage.contains("local record differs") || strBoundedMessage.contains("local zip records")) return "ZIP_LOCAL_RECORD_INVALID";
        if (strBoundedMessage.contains("local and central zip")) return "ZIP_LOCAL_CENTRAL_MISMATCH";
        if (strBoundedMessage.contains("data descriptor") || strBoundedMessage.contains("data-descriptor")) return "ZIP_DATA_DESCRIPTOR_INVALID";
        if (strBoundedMessage.contains("zip64")) return "ZIP64_UNSUPPORTED";
        if (strBoundedMessage.contains("duplicate zip entry") || strBoundedMessage.contains("duplicate, excessive, or unsafe zip")) return "ZIP_ENTRY_DUPLICATE_OR_UNSAFE";
        if (strBoundedMessage.contains("zip entry name")) return "ZIP_ENTRY_NAME_INVALID";
        if (strBoundedMessage.contains("zip extra field")) return "ZIP_EXTRA_FIELD_INVALID";
        if (strBoundedMessage.contains("leading gap or preamble")) return "ZIP_PREAMBLE_UNSUPPORTED";
        if (strBoundedMessage.contains("split apk signer differs")) return "SPLIT_SIGNER_OR_PROVENANCE_MISMATCH";
        if (strBoundedMessage.contains("split apk version code differs")) return "SPLIT_VERSION_MISMATCH";
        if (strBoundedMessage.contains("mixed apk packages")) return "SPLIT_PACKAGE_MISMATCH";
        if (strBoundedMessage.contains("more than one base apk")) return "APK_SET_MULTIPLE_BASES";
        if (strBoundedMessage.contains("missing its base apk")) return "APK_SET_BASE_MISSING";
        if (strBoundedMessage.contains("missing feature split") || strBoundedMessage.contains("dependency is missing") || strBoundedMessage.contains("missing required split type")) return "SPLIT_DEPENDENCY_MISSING";
        if (strBoundedMessage.contains("cyclic split")) return "SPLIT_DEPENDENCY_CYCLE";
        if (strBoundedMessage.contains("source input cache digest mismatch")) return "INPUT_CACHE_MISMATCH";
        if (strBoundedMessage.contains("source is not a proven unsigned apk")) return "SOURCE_SIGNING_CONTAINER_PRESENT_OR_AMBIGUOUS";
        if (strBoundedMessage.contains("unknown historical embedded runtime")) return "EMBEDDED_RUNTIME_NOT_RECOGNIZED";
        if (strBoundedMessage.contains("embedded runtime") && strBoundedMessage.contains("ownership")) return "EMBEDDED_RUNTIME_BOUNDARY_AMBIGUOUS";
        if (strBoundedMessage.contains("inherited source binding")) return "INHERITED_BINDING_MISMATCH";
        if (strBoundedMessage.contains("source namespace receipt")) return "SOURCE_NAMESPACE_MISMATCH";
        if (strBoundedMessage.contains("provenance")) return "SOURCE_PROVENANCE_MISMATCH";
        if (strBoundedMessage.contains("crc mismatch")) return "ZIP_CRC_MISMATCH";
        if (strBoundedMessage.contains("dex checksum") || strBoundedMessage.contains("dex signature")) return "DEX_CHECKSUM_MISMATCH";
        if (strBoundedMessage.contains("dex magic") || strBoundedMessage.contains("dex header") || strBoundedMessage.contains("dex table")
                || strBoundedMessage.contains("dex data bounds") || strBoundedMessage.contains("dex class") || strBoundedMessage.contains("dex string")
                || strBoundedMessage.contains("dex version") || strBoundedMessage.contains("dex missing") || strBoundedMessage.contains("dex entry sequence")) return "DEX_STRUCTURE_INVALID";
        if (strBoundedMessage.contains("apk file missing, empty, or oversized")) return "INPUT_FILE_INVALID";
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

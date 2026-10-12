package io.github.xyz1001.pdsync.android.prepare;

import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

public final class BinaryManifestPatch {
    private static final Map<String, Long> ANDROID_IDS;
    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final int BRIDGE_MIN_SDK = 26;
    public static final String MAIN_APP_PACKAGE = "io.github.xyz1001.pdsync.android";
    public static final String BRIDGE_PROVIDER = "io.github.xyz1001.pdsync.bridge.SaveBridgeProvider";
    public static final String BRIDGE_ACTIVATION = "io.github.xyz1001.pdsync.bridge.ActivationActivity";
    public static final String BRIDGE_PROCESS = ":pdsync_bridge";
    public static final String BRIDGE_MODE = "embedded-v1";
    public static final String METADATA_PREFIX = "io.github.xyz1001.pdsync.";
    private static final String BINDING_PREFIX = "io.github.xyz1001.pdsync.BOUND_";
    public static final String GAME_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon";
    public static final String HELPER_PACKAGE = "io.github.xyz1001.pdsync.bridge";
    private static final int MAX_BYTES = 16777216;
    private static final int MAX_STRINGS = 262144;
    private static final long NONE = 4294967295L;
    public static final String SHARED_UID = "io.github.xyz1001.pdsync.shared";
    public static final int VERSION_CODE = 896;
    public static final String VERSION_NAME = "3.3.8";

    static {
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        ANDROID_IDS = linkedHashMap;
        linkedHashMap.put("label", 16842753L);
        linkedHashMap.put("name", 16842755L);
        linkedHashMap.put("sharedUserId", 16842763L);
        linkedHashMap.put("sharedUserLabel", 16843361L);
        linkedHashMap.put("permission", 16842758L);
        linkedHashMap.put("readPermission", 16842759L);
        linkedHashMap.put("writePermission", 16842760L);
        linkedHashMap.put("hasCode", 16842764L);
        linkedHashMap.put("exported", 16842768L);
        linkedHashMap.put("process", 16842769L);
        linkedHashMap.put("grantUriPermissions", 16842779L);
        linkedHashMap.put("authorities", 16842776L);
        linkedHashMap.put("value", 16842788L);
        linkedHashMap.put("resource", 16842789L);
        linkedHashMap.put("minSdkVersion", 16843276L);
        linkedHashMap.put("targetSdkVersion", 16843376L);
        linkedHashMap.put("versionCode", 16843291L);
        linkedHashMap.put("versionName", 16843292L);
        linkedHashMap.put("targetSandboxVersion", 16844108L);
        linkedHashMap.put("isFeatureSplit", 16844123L);
        linkedHashMap.put("versionCodeMajor", 16844150L);
        linkedHashMap.put("isSplitRequired", 16844177L);
        linkedHashMap.put("sharedUserMaxSdkVersion", 16844365L);
        linkedHashMap.put("requiredSplitTypes", 16844366L);
        linkedHashMap.put("splitTypes", 16844367L);
    }

    private BinaryManifestPatch() {
    }

    public static final class ManifestInfo {
        public final String applicationLabel;
        public final String configForSplit;
        public final boolean hasSharedUserMaxSdkVersion;
        public final boolean isFeatureSplit;
        public final boolean isSplitRequired;
        public final int minSdkVersion;
        public final String packageName;
        public final boolean playSplitsRequired;
        public final List<String> requiredSplitTypes;
        public final String sharedUserId;
        public final String splitName;
        public final List<String> splitTypes;
        public final int targetSandboxVersion;
        public final List<String> usesSplits;
        public final long versionCode;
        public final String versionName;
        public final boolean versionNameResource;
        public final int versionNameResourceId;

        private ManifestInfo(Document document) throws IOException {
            Node node = document.root;
            List<String> list = document.pool.strings;
            String strStringValue = BinaryManifestPatch.stringValue(node.find(null, "package"), list, null);
            this.packageName = strStringValue;
            BinaryManifestPatch.require((strStringValue == null || strStringValue.isEmpty()) ? false : true, "Missing manifest package");
            this.sharedUserId = BinaryManifestPatch.stringValue(node.find("http://schemas.android.com/apk/res/android", "sharedUserId"), list, null);
            Attribute attributeFind = node.find("http://schemas.android.com/apk/res/android", "versionName");
            int i = attributeFind == null ? 3 : attributeFind.bytes[15] & 255;
            boolean z = i == 1 || i == 2 || i == 7 || i == 8;
            this.versionNameResource = z;
            this.versionNameResourceId = z ? (int) BinaryManifestPatch.u32(attributeFind.bytes, 16) : 0;
            BinaryManifestPatch.require(i == 3 || z, "Expected string/reference versionName");
            this.versionName = BinaryManifestPatch.displayValue(attributeFind, list, "");
            this.versionCode = BinaryManifestPatch.intValue(node.find("http://schemas.android.com/apk/res/android", "versionCode"), 0L) | (BinaryManifestPatch.intValue(node.find("http://schemas.android.com/apk/res/android", "versionCodeMajor"), 0L) << 32);
            this.targetSandboxVersion = BinaryManifestPatch.boundedInt(node.find("http://schemas.android.com/apk/res/android", "targetSandboxVersion"), 1);
            this.hasSharedUserMaxSdkVersion = node.find("http://schemas.android.com/apk/res/android", "sharedUserMaxSdkVersion") != null;
            this.splitName = BinaryManifestPatch.stringValue(node.find(null, "split"), list, "");
            this.configForSplit = BinaryManifestPatch.stringValue(node.find(null, "configForSplit"), list, null);
            this.isFeatureSplit = BinaryManifestPatch.boolValue(node.find("http://schemas.android.com/apk/res/android", "isFeatureSplit"), false);
            Node nodeSingleChild = document.singleChild(node, "application", false);
            Attribute attributeFind2 = node.find("http://schemas.android.com/apk/res/android", "isSplitRequired");
            Attribute attributeFind3 = nodeSingleChild == null ? null : nodeSingleChild.find("http://schemas.android.com/apk/res/android", "isSplitRequired");
            BinaryManifestPatch.require(attributeFind2 == null || attributeFind3 == null || BinaryManifestPatch.boolValue(attributeFind2, false) == BinaryManifestPatch.boolValue(attributeFind3, false), "Conflicting manifest/application isSplitRequired");
            boolean zPlaySplitsRequired = BinaryManifestPatch.playSplitsRequired(document, nodeSingleChild);
            this.playSplitsRequired = zPlaySplitsRequired;
            this.isSplitRequired = BinaryManifestPatch.boolValue(attributeFind2 == null ? attributeFind3 : attributeFind2, false) || zPlaySplitsRequired;
            this.splitTypes = BinaryManifestPatch.stringList(node.find("http://schemas.android.com/apk/res/android", "splitTypes"), list);
            this.requiredSplitTypes = BinaryManifestPatch.stringList(node.find("http://schemas.android.com/apk/res/android", "requiredSplitTypes"), list);
            this.applicationLabel = nodeSingleChild != null ? BinaryManifestPatch.displayValue(nodeSingleChild.find("http://schemas.android.com/apk/res/android", "label"), list, strStringValue) : strStringValue;
            Node nodeSingleChild2 = document.singleChild(node, "uses-sdk", false);
            this.minSdkVersion = nodeSingleChild2 == null ? 1 : BinaryManifestPatch.boundedInt(nodeSingleChild2.find("http://schemas.android.com/apk/res/android", "minSdkVersion"), 1);
            ArrayList arrayList = new ArrayList();
            HashSet hashSet = new HashSet();
            for (Node node2 : document.nodes) {
                if (node2.parent == node && node2.namespace == null && "uses-split".equals(node2.name)) {
                    String strStringValue2 = BinaryManifestPatch.stringValue(node2.find("http://schemas.android.com/apk/res/android", "name"), list, null);
                    BinaryManifestPatch.require((strStringValue2 == null || strStringValue2.isEmpty()) ? false : true, "uses-split has no name");
                    BinaryManifestPatch.require(hashSet.add(strStringValue2), "Duplicate uses-split: " + strStringValue2);
                    arrayList.add(strStringValue2);
                }
            }
            this.usesSplits = Collections.unmodifiableList(arrayList);
        }
    }

    public static ManifestInfo inspect(byte[] bArr) throws IOException {
        return new ManifestInfo(new Document(bArr));
    }

    /** Immutable receipt inputs for one embedded runtime. No signing key or user secret belongs here. */
    public static final class BridgeBinding {
        public final String authority, gamePackage, originIdentity, namespaceId, sourceApkSetSha256;
        public final long gameVersionCode;
        public final String gameVersionName, mainAppCertSha256, bridgeRuntimeVersion;
        public final boolean includeActivationActivity;

        public BridgeBinding(String authority, String gamePackage, String originIdentity, String namespaceId,
                String sourceApkSetSha256, long gameVersionCode, String gameVersionName,
                String mainAppCertSha256, String bridgeRuntimeVersion) throws IOException {
            this(authority, gamePackage, originIdentity, namespaceId, sourceApkSetSha256, gameVersionCode,
                    gameVersionName, mainAppCertSha256, bridgeRuntimeVersion, true);
        }

        public BridgeBinding(String authority, String gamePackage, String originIdentity, String namespaceId,
                String sourceApkSetSha256, long gameVersionCode, String gameVersionName,
                String mainAppCertSha256, String bridgeRuntimeVersion, boolean includeActivationActivity) throws IOException {
            require(authority != null && authority.length() <= 255 && authority.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"), "Invalid bridge authority");
            require(gamePackage != null && gamePackage.length() <= 255 && gamePackage.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"), "Invalid bound game package");
            requireHexDigest(originIdentity, "origin identity");
            require(("android-" + originIdentity).equals(namespaceId), "Namespace does not match bound origin");
            requireHexDigest(sourceApkSetSha256, "source APK-set hash");
            requireHexDigest(mainAppCertSha256, "main app certificate pin");
            require(gameVersionCode >= 0, "Invalid bound game version code");
            require(gameVersionName != null && gameVersionName.indexOf(0) < 0, "Invalid bound game version name");
            requireText(bridgeRuntimeVersion, "bridge runtime version");
            require(bridgeRuntimeVersion.length() <= 128, "Bridge runtime version too long");
            this.authority = authority; this.gamePackage = gamePackage; this.originIdentity = originIdentity;
            this.namespaceId = namespaceId; this.sourceApkSetSha256 = sourceApkSetSha256;
            this.gameVersionCode = gameVersionCode; this.gameVersionName = gameVersionName;
            this.mainAppCertSha256 = mainAppCertSha256; this.bridgeRuntimeVersion = bridgeRuntimeVersion;
            this.includeActivationActivity = includeActivationActivity;
        }

        public Map<String, String> metadata() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(METADATA_PREFIX + "BRIDGE_MODE", BRIDGE_MODE);
            values.put(BINDING_PREFIX + "GAME_PACKAGE", gamePackage);
            values.put(BINDING_PREFIX + "ORIGIN_IDENTITY", originIdentity);
            values.put(BINDING_PREFIX + "NAMESPACE_ID", namespaceId);
            values.put(BINDING_PREFIX + "SOURCE_APK_SHA256", sourceApkSetSha256);
            values.put(BINDING_PREFIX + "GAME_VERSION_CODE", Long.toString(gameVersionCode));
            values.put(BINDING_PREFIX + "GAME_VERSION_NAME", gameVersionName);
            values.put(METADATA_PREFIX + "MAIN_APP_CERT_SHA256", mainAppCertSha256);
            values.put(METADATA_PREFIX + "BRIDGE_RUNTIME_VERSION", bridgeRuntimeVersion);
            return Collections.unmodifiableMap(values);
        }
    }

    /** Add only audited bridge components, bindings and one visibility query; original game components survive. */
    public static byte[] patchProvider(byte[] bytes, BridgeBinding binding) throws IOException {
        require(binding != null, "Missing bridge binding");
        Document document = new Document(bytes);
        ManifestInfo before = new ManifestInfo(document);
        require(before.splitName.isEmpty(), "Bridge runtime belongs only in the base APK");
        require(binding.gamePackage.equals(before.packageName) && binding.gameVersionCode == before.versionCode,
                "Bridge binding does not match game identity/version");
        require(before.versionNameResource || binding.gameVersionName.equals(before.versionName), "Bridge version name does not match game");
        Node application = document.singleChild(document.root, "application", true);
        rejectBridgeConflicts(document, application, before.packageName, binding);
        removeSharedIdentity(document);
        ensureBridgeMinSdk(document);
        addMainAppQuery(document);
        if (!boolValue(application.find(ANDROID_NS, "hasCode"), true)) document.setBool(application, "hasCode", true);
        Node provider = document.appendElement(application, "provider");
        document.setString(provider, ANDROID_NS, "name", BRIDGE_PROVIDER);
        document.setString(provider, ANDROID_NS, "authorities", binding.authority);
        document.setString(provider, ANDROID_NS, "process", BRIDGE_PROCESS);
        document.setBool(provider, "exported", true);
        document.setBool(provider, "grantUriPermissions", false);
        // Empty values prevent an original application-level permission from being inherited.
        document.setString(provider, ANDROID_NS, "permission", "");
        document.setString(provider, ANDROID_NS, "readPermission", "");
        document.setString(provider, ANDROID_NS, "writePermission", "");
        if (binding.includeActivationActivity) {
            Node activity = document.appendElement(application, "activity");
            document.setString(activity, ANDROID_NS, "name", BRIDGE_ACTIVATION);
            document.setString(activity, ANDROID_NS, "process", BRIDGE_PROCESS);
            document.setBool(activity, "exported", true);
            document.setString(activity, ANDROID_NS, "permission", "");
        }
        for (Map.Entry<String, String> entry : binding.metadata().entrySet()) {
            Node metadata = document.appendElement(application, "meta-data");
            document.setString(metadata, ANDROID_NS, "name", entry.getKey());
            document.setString(metadata, ANDROID_NS, "value", entry.getValue());
        }
        byte[] result = document.encode();
        requireManifestIdentityUnchanged(before, inspect(result));
        verifyProviderManifest(result, binding);
        return result;
    }

    /** Splits receive only shared-identity/sandbox and minimum-SDK migration, never runtime code or components. */
    public static byte[] patchProviderSplit(byte[] bytes) throws IOException {
        Document document = new Document(bytes);
        ManifestInfo before = new ManifestInfo(document);
        require(!before.splitName.isEmpty(), "Expected a split APK manifest");
        removeSharedIdentity(document);
        ensureBridgeMinSdk(document);
        byte[] result = document.encode();
        requireManifestIdentityUnchanged(before, inspect(result));
        return result;
    }

    private static void ensureBridgeMinSdk(Document document) throws IOException {
        Node sdk = document.singleChild(document.root, "uses-sdk", false);
        int originalMin = sdk == null ? 1 : boundedInt(sdk.find(ANDROID_NS, "minSdkVersion"), 1);
        if (originalMin >= BRIDGE_MIN_SDK) return;
        if (sdk == null) sdk = document.prependElement(document.root, "uses-sdk");
        // Android defaults an omitted target to minSdk. Keep its prior effective value when raising min.
        if (sdk.find(ANDROID_NS, "targetSdkVersion") == null) document.setInt(sdk, "targetSdkVersion", originalMin);
        document.setInt(sdk, "minSdkVersion", BRIDGE_MIN_SDK);
    }

    private static void addMainAppQuery(Document document) throws IOException {
        Node queries = document.singleChild(document.root, "queries", false);
        int matches = 0;
        for (Node node : document.nodes) if (queries != null && node.parent == queries && node.namespace == null
                && "package".equals(node.name) && MAIN_APP_PACKAGE.equals(stringValue(node.find(ANDROID_NS, "name"), document.pool.strings, null))) matches++;
        require(matches <= 1, "Duplicate main app visibility query");
        if (matches == 1) return;
        if (queries == null) queries = document.appendElement(document.root, "queries");
        Node query = document.appendElement(queries, "package");
        document.setString(query, ANDROID_NS, "name", MAIN_APP_PACKAGE);
    }

    private static void removeSharedIdentity(Document document) throws IOException {
        document.root.remove(ANDROID_NS, "sharedUserId");
        document.root.remove(ANDROID_NS, "sharedUserMaxSdkVersion");
        document.root.remove(ANDROID_NS, "sharedUserLabel");
        if (boundedInt(document.root.find(ANDROID_NS, "targetSandboxVersion"), 1) != 1)
            document.setInt(document.root, "targetSandboxVersion", 1);
    }

    private static void rejectBridgeConflicts(Document document, Node application, String gamePackage,
            BridgeBinding binding) throws IOException {
        for (Node node : document.nodes) {
            Attribute name = node.find(ANDROID_NS, "name");
            if (node == application || node.parent == application && ("activity".equals(node.name)
                    || "activity-alias".equals(node.name) || "service".equals(node.name)
                    || "receiver".equals(node.name) || "provider".equals(node.name))) {
                String component = stringValue(name, document.pool.strings, "");
                if (!component.isEmpty()) {
                    if (component.charAt(0) == '.') component = gamePackage + component;
                    else if (component.indexOf('.') < 0) component = gamePackage + "." + component;
                    require(!BRIDGE_PROVIDER.equals(component) && !BRIDGE_ACTIVATION.equals(component), "Existing injected bridge component");
                }
            }
            if ("provider".equals(node.name) && node.parent == application) {
                String authorities = stringValue(node.find(ANDROID_NS, "authorities"), document.pool.strings, "");
                for (String authority : authorities.split(";", -1))
                    require(!binding.authority.equals(authority.trim()), "Existing conflicting bridge authority");
            }
            if ("meta-data".equals(node.name)) {
                String key = stringValue(name, document.pool.strings, "");
                require(!key.startsWith(BINDING_PREFIX) && !binding.metadata().containsKey(key), "Existing conflicting bridge metadata");
            }
        }
    }

    /** Check a base or feature/config split for declarations that would collide with the injected bridge. */
    public static void validateProviderSource(byte[] bytes, BridgeBinding binding) throws IOException {
        require(binding != null, "Missing bridge binding");
        Document document = new Document(bytes);
        ManifestInfo info = new ManifestInfo(document);
        require(binding.gamePackage.equals(info.packageName) && binding.gameVersionCode == info.versionCode,
                "Bridge source package/version mismatch");
        Node application = document.singleChild(document.root, "application", false);
        if (application != null) rejectBridgeConflicts(document, application, info.packageName, binding);
    }

    /** Structural self-check also usable by receipt validation; it does not establish source trust. */
    public static void verifyProviderManifest(byte[] bytes, BridgeBinding binding) throws IOException {
        require(binding != null, "Missing bridge binding");
        Document document = new Document(bytes);
        ManifestInfo info = new ManifestInfo(document);
        require(binding.gamePackage.equals(info.packageName) && binding.gameVersionCode == info.versionCode
                && (info.versionNameResource || binding.gameVersionName.equals(info.versionName)) && info.splitName.isEmpty() && info.sharedUserId == null && !info.hasSharedUserMaxSdkVersion
                && info.minSdkVersion >= BRIDGE_MIN_SDK && info.targetSandboxVersion == 1 && document.root.find(ANDROID_NS, "sharedUserLabel") == null,
                "Embedded bridge manifest identity failed");
        Node application = document.singleChild(document.root, "application", true);
        require(boolValue(application.find(ANDROID_NS, "hasCode"), true), "Bridge application has no code");
        int providers = 0, activities = 0;
        Set<String> seen = new HashSet<>();
        for (Node node : document.nodes) {
            if (node.parent != application || node.namespace != null) continue;
            String name = stringValue(node.find(ANDROID_NS, "name"), document.pool.strings, "");
            if ("provider".equals(node.name) && BRIDGE_PROVIDER.equals(name)) {
                providers++;
                require(binding.authority.equals(stringValue(node.find(ANDROID_NS, "authorities"), document.pool.strings, null)), "Bridge authority mismatch");
                require(BRIDGE_PROCESS.equals(stringValue(node.find(ANDROID_NS, "process"), document.pool.strings, null))
                        && boolValue(node.find(ANDROID_NS, "exported"), false)
                        && !boolValue(node.find(ANDROID_NS, "grantUriPermissions"), true), "Bridge provider isolation mismatch");
                for (String permission : new String[]{"permission", "readPermission", "writePermission"})
                    require("".equals(stringValue(node.find(ANDROID_NS, permission), document.pool.strings, null)), "Bridge permission mismatch");
                for (Node child : document.nodes) require(child.parent != node, "Bridge provider must have no child grants");
            } else if ("activity".equals(node.name) && BRIDGE_ACTIVATION.equals(name)) {
                activities++;
                require(BRIDGE_PROCESS.equals(stringValue(node.find(ANDROID_NS, "process"), document.pool.strings, null))
                        && boolValue(node.find(ANDROID_NS, "exported"), false)
                        && "".equals(stringValue(node.find(ANDROID_NS, "permission"), document.pool.strings, null)), "Bridge activation mismatch");
                for (Node child : document.nodes) require(child.parent != node, "Bridge activation must not add a launcher");
            } else if ("meta-data".equals(node.name) && binding.metadata().containsKey(name)) {
                require(seen.add(name) && node.find(ANDROID_NS, "resource") == null
                        && binding.metadata().get(name).equals(stringValue(node.find(ANDROID_NS, "value"), document.pool.strings, null)), "Bridge metadata mismatch");
            }
        }
        require(providers == 1 && activities == (binding.includeActivationActivity ? 1 : 0)
                && seen.size() == binding.metadata().size(), "Missing/duplicate bridge components or metadata");
        Node queries = document.singleChild(document.root, "queries", true);
        int mainQueries = 0;
        for (Node node : document.nodes) if (node.parent == queries && node.namespace == null && "package".equals(node.name)
                && MAIN_APP_PACKAGE.equals(stringValue(node.find(ANDROID_NS, "name"), document.pool.strings, null))) mainQueries++;
        require(mainQueries == 1, "Main app visibility query missing or duplicated");
    }

    private static void requireManifestIdentityUnchanged(ManifestInfo before, ManifestInfo after) throws IOException {
        require(before.packageName.equals(after.packageName) && before.versionCode == after.versionCode
                && before.versionName.equals(after.versionName) && before.versionNameResource == after.versionNameResource
                && before.versionNameResourceId == after.versionNameResourceId && before.splitName.equals(after.splitName)
                && Objects.equals(before.configForSplit, after.configForSplit) && before.isFeatureSplit == after.isFeatureSplit
                && before.isSplitRequired == after.isSplitRequired && before.playSplitsRequired == after.playSplitsRequired
                && before.usesSplits.equals(after.usesSplits) && before.splitTypes.equals(after.splitTypes)
                && before.requiredSplitTypes.equals(after.requiredSplitTypes) && before.applicationLabel.equals(after.applicationLabel)
                && Math.max(before.minSdkVersion, BRIDGE_MIN_SDK) == after.minSdkVersion, "Game manifest identity changed during injection");
    }

    private static void requireHexDigest(String value, String label) throws IOException {
        require(value != null && value.matches("[0-9a-f]{64}"), "Invalid " + label);
    }

    public static byte[] patch(byte[] bArr) throws IOException {
        return patchGame(bArr, "io.github.xyz1001.pdsync.shared");
    }

    public static byte[] patchGame(byte[] bArr, String str) throws IOException {
        requireText(str, "shared UID");
        Document document = new Document(bArr);
        ManifestInfo manifestInfo = new ManifestInfo(document);
        document.setString(document.root, "http://schemas.android.com/apk/res/android", "sharedUserId", str);
        document.setInt(document.root, "targetSandboxVersion", 1);
        document.root.remove("http://schemas.android.com/apk/res/android", "sharedUserMaxSdkVersion");
        byte[] bArrEncode = document.encode();
        ManifestInfo manifestInfoInspect = inspect(bArrEncode);
        require(str.equals(manifestInfoInspect.sharedUserId) && manifestInfoInspect.targetSandboxVersion == 1 && !manifestInfoInspect.hasSharedUserMaxSdkVersion, "Game manifest patch self-check failed");
        require(manifestInfo.packageName.equals(manifestInfoInspect.packageName) && manifestInfo.versionCode == manifestInfoInspect.versionCode && manifestInfo.versionName.equals(manifestInfoInspect.versionName) && manifestInfo.versionNameResource == manifestInfoInspect.versionNameResource && manifestInfo.versionNameResourceId == manifestInfoInspect.versionNameResourceId && manifestInfo.splitName.equals(manifestInfoInspect.splitName) && Objects.equals(manifestInfo.configForSplit, manifestInfoInspect.configForSplit) && manifestInfo.isFeatureSplit == manifestInfoInspect.isFeatureSplit && manifestInfo.isSplitRequired == manifestInfoInspect.isSplitRequired && manifestInfo.playSplitsRequired == manifestInfoInspect.playSplitsRequired && manifestInfo.usesSplits.equals(manifestInfoInspect.usesSplits) && manifestInfo.splitTypes.equals(manifestInfoInspect.splitTypes) && manifestInfo.requiredSplitTypes.equals(manifestInfoInspect.requiredSplitTypes) && manifestInfo.applicationLabel.equals(manifestInfoInspect.applicationLabel) && manifestInfo.minSdkVersion == manifestInfoInspect.minSdkVersion, "Game manifest identity changed during patch");
        return bArrEncode;
    }

    public static byte[] patchHelper(byte[] bArr, String str, String str2, String str3, String str4, String str5, String str6) throws IOException {
        return patchHelperInternal(bArr, str, str2, str3, str4, str5, str6, null);
    }

    public static byte[] patchHelper(byte[] bArr, String str, String str2, String str3, String str4, String str5, String str6, String str7, long j, String str8) throws IOException {
        requireText(str7, "source APK SHA-256");
        require(str8 != null, "Missing game version name");
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        linkedHashMap.put("SOURCE_APK_SHA256", str7);
        linkedHashMap.put("GAME_VERSION_CODE", Long.toString(j));
        linkedHashMap.put("GAME_VERSION_NAME", str8);
        return patchHelperInternal(bArr, str, str2, str3, str4, str5, str6, linkedHashMap);
    }

    private static byte[] patchHelperInternal(byte[] bArr, String str, String str2, String str3, String str4, String str5, String str6, Map<String, String> map) throws IOException {
        String str7;
        String strStringValue;
        Node node;
        Node node2;
        requireText(str, "helper package");
        requireText(str2, "shared UID");
        requireText(str3, "provider authority");
        requireText(str4, "game package");
        requireText(str5, "origin identity");
        requireText(str6, "namespace ID");
        Document document = new Document(bArr);
        ManifestInfo manifestInfo = new ManifestInfo(document);
        require("io.github.xyz1001.pdsync.bridge".equals(manifestInfo.packageName) || str.equals(manifestInfo.packageName), "Helper template package does not match");
        String str8 = "application";
        Node nodeSingleChild = document.singleChild(document.root, "application", true);
        Node nodeSingleChild2 = document.singleChild(nodeSingleChild, "provider", true);
        require("io.github.xyz1001.pdsync.bridge.SaveBridgeProvider".equals(stringValue(nodeSingleChild2.find("http://schemas.android.com/apk/res/android", "name"), document.pool.strings, null)), "Helper provider must use its full template class name");
        LinkedHashMap linkedHashMap = new LinkedHashMap();
        linkedHashMap.put("GAME_PACKAGE", str4);
        linkedHashMap.put("SHARED_USER_ID", str2);
        linkedHashMap.put("ORIGIN_IDENTITY", str5);
        linkedHashMap.put("NAMESPACE_ID", str6);
        if (map != null) {
            linkedHashMap.putAll(map);
        }
        HashSet hashSet = new HashSet();
        Iterator<Node> it = document.nodes.iterator();
        String strStringValue2 = null;
        while (true) {
            Iterator<Node> it2 = it;
            str7 = str8;
            if (!it.hasNext()) {
                break;
            }
            Node next = it2.next();
            Node node3 = nodeSingleChild2;
            if (next.parent == nodeSingleChild && next.namespace == null && "meta-data".equals(next.name)) {
                node2 = nodeSingleChild;
                String strStringValue3 = stringValue(next.find("http://schemas.android.com/apk/res/android", "name"), document.pool.strings, null);
                if (strStringValue3 != null && strStringValue3.startsWith("io.github.xyz1001.pdsync.BOUND_")) {
                    String strSubstring = strStringValue3.substring(31);
                    if (linkedHashMap.containsKey(strSubstring)) {
                        require(hashSet.add(strSubstring), "Duplicate helper binding: " + strStringValue3);
                        require(next.find("http://schemas.android.com/apk/res/android", "resource") == null, "Helper binding must use android:value: " + strStringValue3);
                        if ("GAME_PACKAGE".equals(strSubstring)) {
                            strStringValue2 = stringValue(next.find("http://schemas.android.com/apk/res/android", "value"), document.pool.strings, null);
                        }
                        document.setString(next, "http://schemas.android.com/apk/res/android", "value", (String) linkedHashMap.get(strSubstring));
                    }
                }
            } else {
                node2 = nodeSingleChild;
            }
            it = it2;
            str8 = str7;
            nodeSingleChild2 = node3;
            nodeSingleChild = node2;
        }
        Node node4 = nodeSingleChild2;
        require(hashSet.size() == linkedHashMap.size(), "Missing helper binding metadata: " + missing(linkedHashMap.keySet(), hashSet));
        Node nodeSingleChild3 = document.singleChild(document.root, "queries", true);
        int i = 0;
        for (Node node5 : document.nodes) {
            if (node5.parent == nodeSingleChild3 && node5.namespace == null && "package".equals(node5.name)) {
                node = nodeSingleChild3;
                String strStringValue4 = stringValue(node5.find("http://schemas.android.com/apk/res/android", "name"), document.pool.strings, null);
                if (Objects.equals(strStringValue4, strStringValue2) || "com.shatteredpixel.shatteredpixeldungeon".equals(strStringValue4) || str4.equals(strStringValue4)) {
                    document.setString(node5, "http://schemas.android.com/apk/res/android", "name", str4);
                    i++;
                }
            } else {
                node = nodeSingleChild3;
            }
            nodeSingleChild3 = node;
        }
        require(i == 1, "Helper template must have one bound game visibility query");
        document.setString(document.root, null, "package", str);
        document.setString(document.root, "http://schemas.android.com/apk/res/android", "sharedUserId", str2);
        document.setInt(document.root, "targetSandboxVersion", 1);
        document.root.remove("http://schemas.android.com/apk/res/android", "sharedUserMaxSdkVersion");
        document.setString(node4, "http://schemas.android.com/apk/res/android", "authorities", str3);
        byte[] bArrEncode = document.encode();
        Document document2 = new Document(bArrEncode);
        ManifestInfo manifestInfo2 = new ManifestInfo(document2);
        require(str.equals(manifestInfo2.packageName) && str2.equals(manifestInfo2.sharedUserId) && manifestInfo2.targetSandboxVersion == 1 && !manifestInfo2.hasSharedUserMaxSdkVersion, "Helper patch self-check failed");
        Node nodeSingleChild4 = document2.singleChild(document2.root, str7, true);
        require(str3.equals(stringValue(document2.singleChild(nodeSingleChild4, "provider", true).find("http://schemas.android.com/apk/res/android", "authorities"), document2.pool.strings, null)), "Helper authority self-check failed");
        for (Node node6 : document2.nodes) {
            if (node6.parent == nodeSingleChild4 && node6.namespace == null && "meta-data".equals(node6.name) && (strStringValue = stringValue(node6.find("http://schemas.android.com/apk/res/android", "name"), document2.pool.strings, null)) != null && strStringValue.startsWith("io.github.xyz1001.pdsync.BOUND_")) {
                if (linkedHashMap.containsKey(strStringValue.substring(31))) {
                    require(((String) linkedHashMap.get(strStringValue.substring(31))).equals(stringValue(node6.find("http://schemas.android.com/apk/res/android", "value"), document2.pool.strings, null)), "Helper binding self-check failed: " + strStringValue);
                }
            }
        }
        return bArrEncode;
    }

    private static Set<String> missing(Set<String> set, Set<String> set2) {
        HashSet hashSet = new HashSet(set);
        hashSet.removeAll(set2);
        return hashSet;
    }

    private static final class Document {
        boolean androidDeclaredAtRoot;
        final byte[] header;
        boolean mapChanged;
        int mapPart;
        final StringPool pool;
        int poolPart;
        byte[] resourceHeader;
        long[] resourceIds;
        final Node root;
        final List<byte[]> parts = new ArrayList();
        final List<Node> nodes = new ArrayList();
        final Map<Integer, List<Node>> appendedElements = new LinkedHashMap<>();
        final Set<String> namespacePrefixes = new HashSet();

        Document(byte[] bytes) throws IOException {
            poolPart = -1;
            mapPart = -1;
            require(bytes != null && bytes.length >= 8 && bytes.length <= 16777216, "Invalid binary XML length (limit 16 MiB)");
            int headerSize = u16(bytes, 2);
            require(u16(bytes, 0) == 3 && headerSize >= 8 && headerSize <= bytes.length && u32(bytes, 4) == bytes.length, "Not complete Android binary XML");
            header = Arrays.copyOf(bytes, headerSize);
            int firstNode = -1;
            for (int offset = headerSize; offset < bytes.length;) {
                require(parts.size() < 262144, "Too many binary XML chunks");
                bounds(bytes, offset, 8);
                int type = u16(bytes, offset), sizeHeader = u16(bytes, offset + 2);
                long size = u32(bytes, offset + 4);
                require(sizeHeader >= 8 && size >= sizeHeader && size <= bytes.length - offset && (size & 3) == 0, "Invalid binary XML chunk bounds/alignment");
                if (type == 1) { require(poolPart < 0, "Duplicate string pool"); poolPart = parts.size(); }
                if (type == 384) { require(mapPart < 0, "Duplicate resource map"); mapPart = parts.size(); }
                if (type >= 256 && type <= 260 && firstNode < 0) firstNode = parts.size();
                int end = offset + (int)size;
                parts.add(Arrays.copyOfRange(bytes, offset, end));
                offset = end;
            }
            require(poolPart >= 0 && firstNode > poolPart && (mapPart < 0 || (mapPart > poolPart && mapPart < firstNode)), "String pool/resource map must precede XML nodes");
            pool = new StringPool(parts.get(poolPart));
            if (mapPart >= 0) {
                byte[] map = parts.get(mapPart);
                int sizeHeader = u16(map, 2);
                require((map.length - sizeHeader) % 4 == 0, "Malformed resource map");
                int count = (map.length - sizeHeader) / 4;
                require(count <= pool.strings.size(), "Resource map exceeds string count");
                resourceHeader = Arrays.copyOf(map, sizeHeader);
                resourceIds = new long[count];
                for (int i = 0; i < count; i++) resourceIds[i] = u32(map, sizeHeader + i * 4);
            } else {
                resourceHeader = new byte[8]; put16(resourceHeader, 0, 384); put16(resourceHeader, 2, 8);
                resourceIds = new long[0];
            }
            List<Node> stack = new ArrayList<>();
            List<long[]> namespaces = new ArrayList<>();
            Node rootNode = null;
            for (int part = 0; part < parts.size(); part++) {
                byte[] chunk = parts.get(part);
                int type = u16(chunk, 0), sizeHeader = u16(chunk, 2);
                if (type < 256 || type > 260) continue;
                require(sizeHeader >= 16, "Invalid XML node header");
                if (u32(chunk, 12) != 0xffffffffL) pool.at(u32(chunk, 12));
                if (type == 256 || type == 257) {
                    bounds(chunk, sizeHeader, 8);
                    long prefix = u32(chunk, sizeHeader), uri = u32(chunk, sizeHeader + 4);
                    if (prefix != 0xffffffffL) namespacePrefixes.add(pool.at(prefix));
                    pool.at(uri);
                    if (type == 256) namespaces.add(new long[]{prefix, uri, stack.size()});
                    else {
                        require(!namespaces.isEmpty(), "Unbalanced namespace end");
                        long[] opened = namespaces.remove(namespaces.size() - 1);
                        require(opened[0] == prefix && opened[1] == uri && opened[2] == stack.size(), "Mismatched namespace end/scope");
                    }
                } else if (type == 258) {
                    require(stack.size() < 256, "Manifest nesting exceeds 256 elements");
                    Node node = new Node(this, part, stack.isEmpty() ? null : stack.get(stack.size() - 1));
                    if (node.parent == null) {
                        require(rootNode == null && node.namespace == null && "manifest".equals(node.name), "Expected one manifest root");
                        for (long[] namespace : namespaces) if ("http://schemas.android.com/apk/res/android".equals(pool.at(namespace[1]))) androidDeclaredAtRoot = true;
                        rootNode = node;
                    }
                    nodes.add(node); stack.add(node);
                } else if (type == 259) {
                    bounds(chunk, sizeHeader, 8);
                    require(!stack.isEmpty(), "Unbalanced element end");
                    Node node = stack.remove(stack.size() - 1);
                    String ns = u32(chunk, sizeHeader) == 0xffffffffL ? null : pool.at(u32(chunk, sizeHeader));
                    require(Objects.equals(node.namespace, ns) && node.name.equals(pool.at(u32(chunk, sizeHeader + 4))), "Mismatched element end");
                    node.endPart = part;
                } else {
                    require(!stack.isEmpty(), "CDATA outside manifest root");
                    bounds(chunk, sizeHeader, 12); pool.at(u32(chunk, sizeHeader)); validateValue(chunk, sizeHeader + 4, pool.strings);
                }
            }
            require(rootNode != null && stack.isEmpty() && namespaces.isEmpty(), "Incomplete manifest XML tree");
            root = rootNode;
        }

        long resourceId(int i) {
            long[] jArr = this.resourceIds;
            if (i < jArr.length) {
                return jArr[i];
            }
            return 0L;
        }

        Node singleChild(Node node, String str, boolean z) throws IOException {
            Iterator<Node> it = this.nodes.iterator();
            Node node2 = null;
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                Node next = it.next();
                if (next.parent == node && next.namespace == null && next.name.equals(str)) {
                    BinaryManifestPatch.require(node2 == null, "Duplicate " + str + " element");
                    node2 = next;
                }
            }
            BinaryManifestPatch.require((z && node2 == null) ? false : true, "Missing " + str + " element");
            return node2;
        }

        int nameIndex(String str, String str2) throws IOException {
            int i = 0;
            if (str != null) {
                Long l = (Long) BinaryManifestPatch.ANDROID_IDS.get(str2);
                BinaryManifestPatch.require("http://schemas.android.com/apk/res/android".equals(str) && l != null, "Unknown attribute edit: " + str2);
                while (i < this.pool.strings.size()) {
                    if (this.pool.strings.get(i).equals(str2) && resourceId(i) == l.longValue()) {
                        return i;
                    }
                    i++;
                }
                int iAdd = this.pool.add(str2, true);
                long[] jArr = this.resourceIds;
                if (jArr.length <= iAdd) {
                    this.resourceIds = Arrays.copyOf(jArr, iAdd + 1);
                }
                this.resourceIds[iAdd] = l.longValue();
                this.mapChanged = true;
                return iAdd;
            }
            while (i < this.pool.strings.size()) {
                if (this.pool.strings.get(i).equals(str2) && resourceId(i) == 0) {
                    return i;
                }
                i++;
            }
            return this.pool.add(str2, true);
        }

        void setString(Node node, String str, String str2, String str3) throws IOException {
            int iAdd = this.pool.add(str3, false);
            Attribute attributeFind = node.find(str, str2);
            if (attributeFind == null) {
                attributeFind = node.add(str, str2, nameIndex(str, str2), str == null ? -1 : this.pool.add(str, false));
            }
            long j = iAdd;
            BinaryManifestPatch.put32(attributeFind.bytes, 8, j);
            BinaryManifestPatch.put16(attributeFind.bytes, 12, 8);
            attributeFind.bytes[14] = 0;
            attributeFind.bytes[15] = 3;
            BinaryManifestPatch.put32(attributeFind.bytes, 16, j);
            node.changed = true;
        }

        void setInt(Node node, String str, int i) throws IOException {
            Attribute attributeFind = node.find("http://schemas.android.com/apk/res/android", str);
            if (attributeFind == null) {
                attributeFind = node.add("http://schemas.android.com/apk/res/android", str, nameIndex("http://schemas.android.com/apk/res/android", str), this.pool.add("http://schemas.android.com/apk/res/android", false));
            }
            BinaryManifestPatch.put32(attributeFind.bytes, 8, 4294967295L);
            BinaryManifestPatch.put16(attributeFind.bytes, 12, 8);
            attributeFind.bytes[14] = 0;
            attributeFind.bytes[15] = 16;
            BinaryManifestPatch.put32(attributeFind.bytes, 16, i);
            node.changed = true;
        }

        void setBool(Node node, String name, boolean value) throws IOException {
            setInt(node, name, value ? -1 : 0);
            node.find(ANDROID_NS, name).bytes[15] = 18;
        }

        Node prependElement(Node parent, String name) throws IOException {
            require(parent.part >= 0, "Cannot prepend to a newly added element");
            Node node = new Node(this, parent, name);
            int before = parent.part + 1;
            List<Node> elements = appendedElements.get(before);
            if (elements == null) { elements = new ArrayList<>(); appendedElements.put(before, elements); }
            elements.add(0, node);
            return node;
        }

        Node appendElement(Node parent, String name) throws IOException {
            Node node = new Node(this, parent, name);
            if (parent.part < 0) { parent.appendedChildren.add(node); return node; }
            List<Node> leaves = appendedElements.get(parent.endPart);
            if (leaves == null) { leaves = new ArrayList<>(); appendedElements.put(parent.endPart, leaves); }
            leaves.add(node);
            return node;
        }

        private void writeAddedNode(ByteArrayOutputStream output, Node node) throws IOException {
            output.write(node.encode());
            for (Node child : node.appendedChildren) writeAddedNode(output, child);
            byte[] end = new byte[24];
            put16(end, 0, 259); put16(end, 2, 16); put32(end, 4, end.length);
            put32(end, 12, NONE); put32(end, 16, NONE);
            put32(end, 20, u32(node.original, node.headerSize + 4));
            output.write(end);
        }

        byte[] encode() throws IOException {
            int iAdd;
            byte[] bArrCopyOf;
            int iAdd2 = this.pool.add("http://schemas.android.com/apk/res/android", false);
            if (this.androidDeclaredAtRoot) {
                iAdd = -1;
            } else {
                String str = "android";
                int i = 1;
                while (this.namespacePrefixes.contains(str)) {
                    str = "pdsync_android" + i;
                    i++;
                }
                iAdd = this.pool.add(str, false);
            }
            this.parts.set(this.poolPart, this.pool.encode());
            if (this.mapChanged || this.mapPart < 0) {
                byte[] bArr = this.resourceHeader;
                bArrCopyOf = Arrays.copyOf(bArr, bArr.length + (this.resourceIds.length * 4));
                BinaryManifestPatch.put32(bArrCopyOf, 4, bArrCopyOf.length);
                int i2 = 0;
                while (true) {
                    long[] jArr = this.resourceIds;
                    if (i2 >= jArr.length) {
                        break;
                    }
                    BinaryManifestPatch.put32(bArrCopyOf, this.resourceHeader.length + (i2 * 4), jArr[i2]);
                    i2++;
                }
                int i3 = this.mapPart;
                if (i3 >= 0) {
                    this.parts.set(i3, bArrCopyOf);
                }
            } else {
                bArrCopyOf = null;
            }
            for (Node node : this.nodes) {
                if (node.changed) {
                    this.parts.set(node.part, node.encode());
                }
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byteArrayOutputStream.write(this.header);
            for (int i4 = 0; i4 < this.parts.size(); i4++) {
                if (!this.androidDeclaredAtRoot && i4 == this.root.part) {
                    byteArrayOutputStream.write(BinaryManifestPatch.namespaceChunk(256, iAdd, iAdd2));
                }
                List<Node> leaves = appendedElements.get(i4);
                if (leaves != null) for (Node leaf : leaves) writeAddedNode(byteArrayOutputStream, leaf);
                byteArrayOutputStream.write(this.parts.get(i4));
                if (this.mapPart < 0 && i4 == this.poolPart) {
                    byteArrayOutputStream.write(bArrCopyOf);
                }
                if (!this.androidDeclaredAtRoot && i4 == this.root.endPart) {
                    byteArrayOutputStream.write(BinaryManifestPatch.namespaceChunk(257, iAdd, iAdd2));
                }
                BinaryManifestPatch.require(byteArrayOutputStream.size() <= 16777216, "Patched manifest exceeds 16 MiB");
            }
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            BinaryManifestPatch.put32(byteArray, 4, byteArray.length);
            return byteArray;
        }
    }

    static final class Attribute {
        final byte[] bytes;
        final String name;
        final String namespace;
        final int originalIndex;

        Attribute(String str, String str2, byte[] bArr, int i) {
            this.namespace = str;
            this.name = str2;
            this.bytes = bArr;
            this.originalIndex = i;
        }
    }

    static final class Node {
        final int attributeSize;
        final int attributeStart;
        boolean changed;
        final Document doc;
        final int headerSize;
        final String name;
        final String namespace;
        final int oldCount;
        final byte[] original;
        final Node parent;
        final int part;
        final List<Attribute> attrs = new ArrayList();
        final List<Node> appendedChildren = new ArrayList<>();
        int endPart = -1;

        Node(Document document, int part, Node parent) throws IOException {
            this.doc = document; this.part = part; this.parent = parent;
            original = document.parts.get(part); headerSize = u16(original, 2);
            bounds(original, headerSize, 20);
            namespace = u32(original, headerSize) == 0xffffffffL ? null : document.pool.at(u32(original, headerSize));
            name = document.pool.at(u32(original, headerSize + 4));
            attributeStart = u16(original, headerSize + 8) + headerSize;
            attributeSize = u16(original, headerSize + 10); oldCount = u16(original, headerSize + 12);
            require(attributeStart >= headerSize + 20 && attributeSize >= 20 && (attributeStart & 3) == 0 && (attributeSize & 3) == 0 && (long)attributeStart + (long)oldCount * attributeSize <= original.length, "Invalid " + name + " attribute layout");
            for (int offset = headerSize + 14; offset <= headerSize + 18; offset += 2) require(u16(original, offset) <= oldCount, "Invalid special attribute index on " + name);
            Set<String> names = new HashSet<>(); Set<Long> ids = new HashSet<>();
            for (int i = 0; i < oldCount; i++) {
                int offset = attributeStart + i * attributeSize;
                byte[] bytes = Arrays.copyOfRange(original, offset, offset + attributeSize);
                String ns = u32(bytes, 0) == 0xffffffffL ? null : document.pool.at(u32(bytes, 0));
                int nameIndex = checkedIndex(u32(bytes, 4), document.pool.strings.size());
                String attrName = document.pool.strings.get(nameIndex);
                require(names.add((ns == null ? "-1:" : ns.length() + ":" + ns) + '\0' + attrName), "Duplicate " + name + " attribute: " + attrName);
                long id = document.resourceId(nameIndex);
                require(id == 0 || ids.add(id), "Duplicate attribute resource ID on " + name);
                Long expected = ANDROID_IDS.get(attrName);
                if ("http://schemas.android.com/apk/res/android".equals(ns)) {
                    require(id != 0, "Android attribute has no resource ID: " + attrName);
                    require(expected == null || id == expected, "Conflicting Android resource ID: " + attrName);
                }
                for (Map.Entry<String, Long> entry : ANDROID_IDS.entrySet()) if (id == entry.getValue()) require("http://schemas.android.com/apk/res/android".equals(ns) && attrName.equals(entry.getKey()), "Ambiguous Android resource ID for " + attrName);
                if (ns == null) require(id == 0, "Unqualified attribute has a resource ID: " + attrName);
                if (u32(bytes, 8) != 0xffffffffL) document.pool.at(u32(bytes, 8));
                validateValue(bytes, 12, document.pool.strings);
                if ((bytes[15] & 255) == 3 && u32(bytes, 8) != 0xffffffffL) require(document.pool.at(u32(bytes, 8)).equals(document.pool.at(u32(bytes, 16))), "Conflicting raw/typed string value: " + attrName);
                attrs.add(new Attribute(ns, attrName, bytes, i + 1));
            }
        }

        Node(Document document, Node parent, String name) throws IOException {
            this.doc = document; this.parent = parent; this.name = name; this.namespace = null; this.part = -1;
            this.headerSize = 16; this.attributeStart = 36; this.attributeSize = 20; this.oldCount = 0;
            this.original = new byte[36];
            put16(original, 0, 258); put16(original, 2, headerSize); put32(original, 4, original.length);
            put32(original, 12, NONE); put32(original, 16, NONE); put32(original, 20, document.pool.add(name, false));
            put16(original, 24, 20); put16(original, 26, attributeSize);
        }

        Attribute find(String str, String str2) {
            for (Attribute attribute : this.attrs) {
                if (Objects.equals(attribute.namespace, str) && attribute.name.equals(str2)) {
                    return attribute;
                }
            }
            return null;
        }

        Attribute add(String str, String str2, int i, int i2) throws IOException {
            BinaryManifestPatch.require(this.attrs.size() < 65535, "Too many attributes on " + this.name);
            byte[] bArr = new byte[this.attributeSize];
            BinaryManifestPatch.put32(bArr, 0, i2 < 0 ? 4294967295L : i2);
            BinaryManifestPatch.put32(bArr, 4, i);
            Attribute attribute = new Attribute(str, str2, bArr, 0);
            this.attrs.add(attribute);
            this.changed = true;
            return attribute;
        }

        void remove(String str, String str2) {
            Attribute attributeFind = find(str, str2);
            if (attributeFind != null) {
                this.attrs.remove(attributeFind);
                this.changed = true;
            }
        }

        byte[] encode() throws IOException {
            Collections.sort(this.attrs, Comparator.comparingLong(new ToLongFunction() { // from class: io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch$Node$$ExternalSyntheticLambda0
                @Override // java.util.function.ToLongFunction
                public final long applyAsLong(Object obj) {
                    return Node.this.m154x3a33bcf4((BinaryManifestPatch.Attribute) obj);
                }
            }));
            int i = this.attributeStart;
            int i2 = (this.oldCount * this.attributeSize) + i;
            int size = i + (this.attrs.size() * this.attributeSize);
            byte[] bArr = this.original;
            byte[] bArrCopyOf = Arrays.copyOf(bArr, (bArr.length + size) - i2);
            byte[] bArr2 = this.original;
            System.arraycopy(bArr2, i2, bArrCopyOf, size, bArr2.length - i2);
            BinaryManifestPatch.put32(bArrCopyOf, 4, bArrCopyOf.length);
            BinaryManifestPatch.put16(bArrCopyOf, this.headerSize + 12, this.attrs.size());
            int i3 = this.headerSize + 14;
            while (true) {
                if (i3 > this.headerSize + 18) {
                    break;
                }
                int iU16 = BinaryManifestPatch.u16(this.original, i3);
                int i4 = 0;
                for (int i5 = 0; iU16 != 0 && i5 < this.attrs.size(); i5++) {
                    if (this.attrs.get(i5).originalIndex == iU16) {
                        i4 = i5 + 1;
                    }
                }
                BinaryManifestPatch.put16(bArrCopyOf, i3, i4);
                i3 += 2;
            }
            for (int i6 = 0; i6 < this.attrs.size(); i6++) {
                byte[] bArr3 = this.attrs.get(i6).bytes;
                int i7 = this.attributeStart;
                int i8 = this.attributeSize;
                System.arraycopy(bArr3, 0, bArrCopyOf, i7 + (i6 * i8), i8);
            }
            return bArrCopyOf;
        }

         long m154x3a33bcf4(Attribute attribute) {
            long jResourceId = this.doc.resourceId((int) BinaryManifestPatch.unchecked32(attribute.bytes, 4));
            if (jResourceId == 0) {
                return 4294967295L;
            }
            return jResourceId;
        }
    }

    private static final class StringPool {
        int additionBytes;
        final int headerSize;
        final int oldCount;
        final byte[] original;
        final int stringEnd;
        final int stringsStart;
        final int styleCount;
        final int stylesStart;
        final boolean utf8;
        final List<String> strings = new ArrayList();
        final List<byte[]> additions = new ArrayList();
        final Map<String, Integer> firstIndex = new HashMap();

        StringPool(byte[] bArr) throws IOException {
            long j;
            boolean z;
            this.original = bArr;
            int iU16 = BinaryManifestPatch.u16(bArr, 2);
            this.headerSize = iU16;
            BinaryManifestPatch.require(iU16 >= 28, "Invalid string-pool header");
            long jU32 = BinaryManifestPatch.u32(bArr, 8);
            long jU322 = BinaryManifestPatch.u32(bArr, 12);
            long jU323 = BinaryManifestPatch.u32(bArr, 20);
            long jU324 = BinaryManifestPatch.u32(bArr, 24);
            BinaryManifestPatch.require(jU32 <= 262144 && jU322 <= jU32 && jU323 >= ((long) iU16) + ((jU32 + jU322) * 4) && jU323 <= ((long) bArr.length), "Invalid string-pool offsets/counts");
            j = 0;
            z = jU324 == 0 ? jU322 == 0 : jU324 >= jU323 && jU324 <= bArr.length && (jU324 & 3) == 0;
            BinaryManifestPatch.require(z, "Invalid style-pool bounds");
            this.oldCount = (int) jU32;
            this.styleCount = (int) jU322;
            this.stringsStart = (int) jU323;
            int i = (int) jU324;
            this.stylesStart = i;
            this.stringEnd = i == 0 ? bArr.length : i;
            this.utf8 = (BinaryManifestPatch.u32(bArr, 16) & 256) != j;
            HashMap map = new HashMap();
            long j2 = j;
            for (int i2 = 0; i2 < this.oldCount; i2++) {
                long jU325 = BinaryManifestPatch.u32(bArr, this.headerSize + (i2 * 4));
                BinaryManifestPatch.require(jU325 < ((long) (this.stringEnd - this.stringsStart)), "Invalid string offset");
                int i3 = this.stringsStart + ((int) jU325);
                String string = (String) map.get(Integer.valueOf(i3));
                if (string == null) {
                    int[] iArr = {i3};
                    long length = BinaryManifestPatch.readLength(bArr, iArr, this.utf8, this.stringEnd);
                    long length2 = this.utf8 ? BinaryManifestPatch.readLength(bArr, iArr, true, this.stringEnd) : 2 * length;
                    BinaryManifestPatch.require(length2 <= ((long) ((this.stringEnd - iArr[0]) - (this.utf8 ? 1 : 2))), "Truncated string-pool value");
                    int i4 = (int) length2;
                    int i5 = iArr[0] + i4;
                    BinaryManifestPatch.require(bArr[i5] == 0 && (this.utf8 || bArr[i5 + 1] == 0), "Unterminated string-pool value");
                    j2 += length;
                    BinaryManifestPatch.require(j2 <= 33554432, "Excessive overlapping string-pool values");
                    try {
                        string = (this.utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bArr, iArr[0], i4)).toString();
                        BinaryManifestPatch.require(((long) string.length()) == length, "String character length mismatch");
                        map.put(Integer.valueOf(i3), string);
                    } catch (CharacterCodingException e) {
                        throw new IOException("Invalid string-pool encoding", e);
                    }
                }
                this.strings.add(string);
                if (!this.firstIndex.containsKey(string)) {
                    this.firstIndex.put(string, Integer.valueOf(i2));
                }
            }
            int i6 = 0;
            for (int i7 = 0; i7 < this.styleCount; i7++) {
                long jU326 = BinaryManifestPatch.u32(bArr, this.headerSize + ((this.oldCount + i7) * 4));
                if (jU326 != 4294967295L) {
                    int i8 = this.stylesStart;
                    BinaryManifestPatch.require(i8 != 0 && jU326 <= ((long) ((bArr.length - i8) + (-4))) && (jU326 & 3) == j, "Invalid style offset");
                    int i9 = this.stylesStart + ((int) jU326);
                    while (true) {
                        long jU327 = BinaryManifestPatch.u32(bArr, i9);
                        if (jU327 == 4294967295L) {
                            break;
                        }
                        i6++;
                        BinaryManifestPatch.require(i6 <= 1000000, "Excessive string-pool style spans");
                        at(jU327);
                        long jU328 = BinaryManifestPatch.u32(bArr, i9 + 4);
                        long jU329 = BinaryManifestPatch.u32(bArr, i9 + 8);
                        BinaryManifestPatch.require(jU328 <= jU329 && jU329 < ((long) this.strings.get(i7).length()), "Invalid styled string span");
                        i9 += 12;
                    }
                }
            }
        }

        String at(long j) throws IOException {
            List<String> list = this.strings;
            return list.get(BinaryManifestPatch.checkedIndex(j, list.size()));
        }

        int add(String str, boolean z) throws IOException {
            BinaryManifestPatch.require(str != null && str.indexOf(0) < 0, "Invalid added manifest string");
            if (!z && this.firstIndex.containsKey(str)) {
                return this.firstIndex.get(str).intValue();
            }
            BinaryManifestPatch.require(this.strings.size() < 262144, "String count exceeds 262144");
            try {
                ByteBuffer byteBufferEncode = (this.utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(str));
                int iRemaining = byteBufferEncode.remaining();
                byte[] bArr = new byte[iRemaining];
                byteBufferEncode.get(bArr);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                BinaryManifestPatch.writeLength(byteArrayOutputStream, str.length(), this.utf8);
                if (this.utf8) {
                    BinaryManifestPatch.writeLength(byteArrayOutputStream, iRemaining, true);
                }
                byteArrayOutputStream.write(bArr);
                byteArrayOutputStream.write(0);
                if (!this.utf8) {
                    byteArrayOutputStream.write(0);
                }
                byte[] byteArray = byteArrayOutputStream.toByteArray();
                int length = this.additionBytes + byteArray.length;
                this.additionBytes = length;
                BinaryManifestPatch.require((((long) this.original.length) + ((long) length)) + (((long) (this.additions.size() + 1)) * 4) <= 16777216, "String pool exceeds 16 MiB");
                int size = this.strings.size();
                this.additions.add(byteArray);
                this.strings.add(str);
                if (!this.firstIndex.containsKey(str)) {
                    this.firstIndex.put(str, Integer.valueOf(size));
                }
                return size;
            } catch (CharacterCodingException e) {
                throw new IOException("Invalid added Unicode string", e);
            }
        }

        byte[] encode() throws IOException {
            if (this.additions.isEmpty()) {
                return this.original;
            }
            int size = this.additions.size();
            int i = this.headerSize + ((this.oldCount + this.styleCount) * 4);
            int i2 = this.stringsStart;
            int i3 = size * 4;
            int i4 = i2 + i3;
            int i5 = this.stringEnd;
            int i6 = (i5 - i2) + this.additionBytes;
            int i7 = (4 - (i6 & 3)) & 3;
            int i8 = this.stylesStart == 0 ? 0 : i4 + i6 + i7;
            int i9 = i6 + i4;
            int i10 = i7 + i9;
            int length = (this.original.length + i10) - i5;
            BinaryManifestPatch.require(length <= 16777216, "String pool exceeds 16 MiB");
            byte[] bArrCopyOf = Arrays.copyOf(this.original, length);
            BinaryManifestPatch.put32(bArrCopyOf, 4, length);
            BinaryManifestPatch.put32(bArrCopyOf, 8, this.strings.size());
            BinaryManifestPatch.put32(bArrCopyOf, 16, BinaryManifestPatch.u32(this.original, 16) & (-2));
            BinaryManifestPatch.put32(bArrCopyOf, 20, i4);
            BinaryManifestPatch.put32(bArrCopyOf, 24, i8);
            byte[] bArr = this.original;
            int i11 = this.headerSize;
            System.arraycopy(bArr, (this.oldCount * 4) + i11, bArrCopyOf, i11 + (this.strings.size() * 4), this.styleCount * 4);
            System.arraycopy(this.original, i, bArrCopyOf, i3 + i, this.stringsStart - i);
            byte[] bArr2 = this.original;
            int i12 = this.stringsStart;
            System.arraycopy(bArr2, i12, bArrCopyOf, i4, this.stringEnd - i12);
            int length2 = this.stringEnd - this.stringsStart;
            for (int i13 = 0; i13 < this.additions.size(); i13++) {
                BinaryManifestPatch.put32(bArrCopyOf, this.headerSize + ((this.oldCount + i13) * 4), length2);
                byte[] bArr3 = this.additions.get(i13);
                System.arraycopy(bArr3, 0, bArrCopyOf, i4 + length2, bArr3.length);
                length2 += bArr3.length;
            }
            Arrays.fill(bArrCopyOf, i9, i10, (byte) 0);
            int i14 = this.stylesStart;
            if (i14 != 0) {
                byte[] bArr4 = this.original;
                System.arraycopy(bArr4, i14, bArrCopyOf, i8, bArr4.length - i14);
            }
            return bArrCopyOf;
        }
    }

    public static void validateValue(byte[] bArr, int i, List<String> list) throws IOException {
        require(u16(bArr, i) == 8 && bArr[i + 2] == 0, "Invalid typed XML value");
        if ((bArr[i + 3] & 255) == 3) {
            checkedIndex(u32(bArr, i + 4), list.size());
        }
    }

    public static String stringValue(Attribute attribute, List<String> list, String str) throws IOException {
        if (attribute == null) {
            return str;
        }
        require((attribute.bytes[15] & 255) == 3, "Expected literal string attribute: " + attribute.name);
        return list.get(checkedIndex(u32(attribute.bytes, 16), list.size()));
    }

    public static String displayValue(Attribute attribute, List<String> list, String str) throws IOException {
        if (attribute != null) {
            if ((attribute.bytes[15] & 255) == 3) {
                return stringValue(attribute, list, str);
            }
            long jU32 = u32(attribute.bytes, 8);
            if (jU32 != 4294967295L) {
                return list.get(checkedIndex(jU32, list.size()));
            }
        }
        return str;
    }

    public static long intValue(Attribute attribute, long j) throws IOException {
        if (attribute == null) {
            return j;
        }
        int i = attribute.bytes[15] & 255;
        require(i == 16 || i == 17, "Expected literal integer attribute: " + attribute.name);
        return u32(attribute.bytes, 16);
    }

    public static int boundedInt(Attribute attribute, int i) throws IOException {
        long jIntValue = intValue(attribute, i);
        boolean z = jIntValue <= 2147483647L;
        StringBuilder sb = new StringBuilder("Integer attribute exceeds supported range: ");
        sb.append(attribute == null ? "default" : attribute.name);
        require(z, sb.toString());
        return (int) jIntValue;
    }

    public static boolean boolValue(Attribute attribute, boolean z) throws IOException {
        if (attribute == null) {
            return z;
        }
        require((attribute.bytes[15] & 255) == 18, "Expected boolean attribute: " + attribute.name);
        return u32(attribute.bytes, 16) != 0;
    }

    public static boolean playSplitsRequired(Document document, Node node) throws IOException {
        if (node == null) {
            return false;
        }
        boolean z = false;
        boolean z2 = false;
        for (Node node2 : document.nodes) {
            if (node2.parent == node && node2.namespace == null && "meta-data".equals(node2.name) && "com.android.vending.splits.required".equals(displayValue(node2.find("http://schemas.android.com/apk/res/android", "name"), document.pool.strings, null))) {
                require(!z2, "Duplicate Play required-split metadata");
                Attribute attributeFind = node2.find("http://schemas.android.com/apk/res/android", "value");
                z = (node2.find("http://schemas.android.com/apk/res/android", "resource") == null && attributeFind != null && (attributeFind.bytes[15] & 255) == 18 && u32(attributeFind.bytes, 16) == 0) ? false : true;
                z2 = true;
            }
        }
        return z;
    }

    public static List<String> stringList(Attribute attribute, List<String> list) throws IOException {
        String strStringValue = stringValue(attribute, list, "");
        ArrayList arrayList = new ArrayList();
        HashSet hashSet = new HashSet();
        if (!strStringValue.isEmpty()) {
            for (String str : strStringValue.split(",", -1)) {
                String strTrim = str.trim();
                require(!strTrim.isEmpty() && hashSet.add(strTrim), "Malformed split type list: " + attribute.name);
                arrayList.add(strTrim);
            }
        }
        return Collections.unmodifiableList(arrayList);
    }

    public static byte[] namespaceChunk(int i, int i2, int i3) {
        byte[] bArr = new byte[24];
        put16(bArr, 0, i);
        put16(bArr, 2, 16);
        put32(bArr, 4, 24L);
        put32(bArr, 12, 4294967295L);
        put32(bArr, 16, i2);
        put32(bArr, 20, i3);
        return bArr;
    }

    public static long readLength(byte[] bArr, int[] iArr, boolean z, int i) throws IOException {
        int i2;
        long jU16;
        int i3 = iArr[0];
        require(i3 < i, "Truncated string length");
        if (z) {
            i2 = i3 + 1;
            jU16 = bArr[i3] & 255;
            if ((128 & jU16) != 0) {
                require(i2 < i, "Truncated UTF-8 length");
                jU16 = ((127 & jU16) << 8) | (bArr[i2] & 255);
                i2 = i3 + 2;
            }
        } else {
            int i4 = i - 2;
            require(i3 <= i4, "Truncated UTF-16 length");
            long jU162 = u16(bArr, i3);
            i2 = i3 + 2;
            if ((32768 & jU162) != 0) {
                require(i2 <= i4, "Truncated UTF-16 length");
                jU16 = ((32767 & jU162) << 16) | u16(bArr, i2);
                i2 = i3 + 4;
            } else {
                jU16 = jU162;
            }
        }
        iArr[0] = i2;
        return jU16;
    }

    public static void writeLength(ByteArrayOutputStream byteArrayOutputStream, int i, boolean z) throws IOException {
        require(i >= 0 && (!z || i <= 32767), "UTF-8 AXML string exceeds 32767-byte/character encoding limit");
        if (z) {
            if (i > 127) {
                byteArrayOutputStream.write((i >>> 8) | 128);
            }
            byteArrayOutputStream.write(i & 255);
        } else {
            if (i > 32767) {
                int i2 = (i >>> 16) | 32768;
                byteArrayOutputStream.write(i2 & 255);
                byteArrayOutputStream.write(i2 >>> 8);
            }
            byteArrayOutputStream.write(i & 255);
            byteArrayOutputStream.write((i >>> 8) & 255);
        }
    }

    public static int checkedIndex(long j, int i) throws IOException {
        require(j >= 0 && j < ((long) i), "Invalid string index");
        return (int) j;
    }

    private static void requireText(String str, String str2) throws IOException {
        boolean z = false;
        if (str != null && !str.isEmpty() && str.indexOf(0) < 0) {
            z = true;
        }
        require(z, "Missing/invalid " + str2);
    }

    public static int u16(byte[] bArr, int i) throws IOException {
        bounds(bArr, i, 2);
        return ((bArr[i + 1] & 255) << 8) | (bArr[i] & 255);
    }

    public static long u32(byte[] bArr, int i) throws IOException {
        bounds(bArr, i, 4);
        return unchecked32(bArr, i);
    }

    public static long unchecked32(byte[] bArr, int i) {
        return ((bArr[i + 3] & 255L) << 24) | (bArr[i] & 255) | ((bArr[i + 1] & 255) << 8) | ((bArr[i + 2] & 255) << 16);
    }

    public static void put16(byte[] bArr, int i, int i2) {
        bArr[i] = (byte) i2;
        bArr[i + 1] = (byte) (i2 >>> 8);
    }

    public static void put32(byte[] bArr, int i, long j) {
        for (int i2 = 0; i2 < 4; i2++) {
            bArr[i + i2] = (byte) (j >>> (i2 * 8));
        }
    }

    public static void bounds(byte[] bArr, int i, int i2) throws IOException {
        require(i2 >= 0 && i >= 0 && i <= bArr.length - i2, "Truncated binary XML");
    }

    public static void require(boolean z, String str) throws IOException {
        if (!z) {
            throw new IOException(str);
        }
    }
}

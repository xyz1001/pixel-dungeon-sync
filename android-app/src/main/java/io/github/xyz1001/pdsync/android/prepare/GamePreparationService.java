package io.github.xyz1001.pdsync.android.prepare;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import io.github.xyz1001.pdsync.android.InstallCoordinator;
import java.util.UUID;
import io.github.xyz1001.pdsync.android.prepare.ApkSetValidator;
import io.github.xyz1001.pdsync.android.prepare.BinaryManifestPatch;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.InstallCompatibility;
import io.github.xyz1001.pdsync.android.prepare.InstalledExtractionGuard;
import io.github.xyz1001.pdsync.android.prepare.InstalledPreparationReuse;
import io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

public final class GamePreparationService {
    public static final String GAME_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon";
    public static final String HELPER_PACKAGE = "io.github.xyz1001.pdsync.bridge";
    public static final int MAX_VERIFIED_SDK = 36;
    public static final int MIN_SUPPORTED_SDK = 36;
    public static final String SHARED_UID = "io.github.xyz1001.pdsync.shared";
    private final Context context;
    private final PackageManager packages;
    private final File root;

    public enum InstallStrategy {
        FRESH_INSTALL,
        COMPATIBLE_UPDATE,
        REQUIRES_UNINSTALL
    }

    interface SourceOpener {
        InputStream open() throws Exception;
    }

    public static final class InstalledGame {
        public final File baseApk;
        public final String label;
        public final String packageName;
        public final String sharedUserId;
        public final List<String> signerCertificateSha256;
        private final InstalledExtractionGuard.Snapshot snapshot;
        public final List<File> splitApks;
        public final long versionCode;
        public final String versionName;

        private InstalledGame(PackageInfo packageInfo, PackageManager packageManager) throws IOException {
            String str = packageInfo.packageName;
            this.packageName = str;
            this.versionName = packageInfo.versionName == null ? "" : packageInfo.versionName;
            this.sharedUserId = packageInfo.sharedUserId;
            this.versionCode = packageInfo.getLongVersionCode();
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (applicationInfo == null || applicationInfo.sourceDir == null) {
                throw new IOException("Installed package does not expose a base APK");
            }
            CharSequence charSequenceLoadLabel = applicationInfo.loadLabel(packageManager);
            this.label = charSequenceLoadLabel != null ? charSequenceLoadLabel.toString() : str;
            this.baseApk = new File(applicationInfo.sourceDir);
            ArrayList arrayList = new ArrayList();
            if (applicationInfo.splitSourceDirs != null) {
                for (String str2 : applicationInfo.splitSourceDirs) {
                    if (str2 == null) {
                        throw new IOException("Installed split APK path is missing");
                    }
                    arrayList.add(new File(str2));
                }
            }
            List<File> listUnmodifiableList = Collections.unmodifiableList(arrayList);
            this.splitApks = listUnmodifiableList;
            try {
                List<String> listUnmodifiableList2 = Collections.unmodifiableList(GamePreparationService.certificates(packageInfo));
                this.signerCertificateSha256 = listUnmodifiableList2;
                ArrayList arrayList2 = new ArrayList();
                arrayList2.add(this.baseApk);
                arrayList2.addAll(listUnmodifiableList);
                this.snapshot = new InstalledExtractionGuard.Snapshot(this.packageName, this.versionCode, packageInfo.firstInstallTime, packageInfo.lastUpdateTime, applicationInfo.uid, arrayList2, listUnmodifiableList2);
            } catch (Exception e) {
                throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET, e);
            }
        }
    }

    public static final class PreparedPair {
        public final String authority;
        private final boolean completeInstalledSet;
        public final File gameApk;
        public final List<File> gameApks;
        private final List<String> gameHashes;
        public final String gameLabel;
        public final String gamePackage;
        public final InstallStrategy gameStrategy;
        public final String gameStrategyReason;
        public final long gameVersionCode;
        public final String gameVersionName;
        public final File helperApk;
        public final boolean helperOnlyUpgrade;
        public final String helperPackage;
        private final String helperSha256;
        public final InstallStrategy helperStrategy;
        public final String helperStrategyReason;
        private final File helperTemplate;
        public final long helperVersionCode;
        private final String id;
        private final InstalledSourceProof installedSourceProof;
        public final boolean legacyIdentity;
        public final boolean legacyUnboundHelper;
        public final String namespaceId;
        public final String originIdentity;
        private final List<String> originalHashes;
        public final String originalSetSha256;
        public final String originalSha256;
        private final List<File> originals;
        public final int protectedEntryCount;
        private final Properties receiptProperties;
        public final String sharedUid;
        public final String signingCertificateSha256;
        public final String sourceApkSetSha256;
        public final List<String> sourceSignerCertificateSha256;
        private final String templateSha256;
        private final File unsignedHelperApk;
        private final String unsignedHelperSha256;

        private PreparedPair(File file, Properties properties, InstallCompatibility.Decision decision, InstallCompatibility.Decision decision2) throws IOException, NumberFormatException {
            this.id = GamePreparationService.required(properties, "id");
            String strRequired = GamePreparationService.required(properties, "gamePackage");
            this.gamePackage = strRequired;
            this.gameLabel = GamePreparationService.required(properties, "gameLabel");
            this.gameVersionName = properties.getProperty("gameVersionName", "");
            this.gameVersionCode = GamePreparationService.number(properties, "gameVersionCode", 0L, Long.MAX_VALUE);
            List<String> listUnmodifiableList = Collections.unmodifiableList(Arrays.asList(GamePreparationService.required(properties, "sourceCertificates").split(",", -1)));
            this.sourceSignerCertificateSha256 = listUnmodifiableList;
            GameSourceIdentity gameSourceIdentity = new GameSourceIdentity(strRequired, listUnmodifiableList);
            String strRequiredHash = GamePreparationService.requiredHash(properties, "originIdentity");
            this.originIdentity = strRequiredHash;
            String strRequired2 = GamePreparationService.required(properties, "namespaceId");
            this.namespaceId = strRequired2;
            if (!gameSourceIdentity.originIdentity.equals(strRequiredHash) || !gameSourceIdentity.namespaceId.equals(strRequired2)) {
                throw new IOException("Source namespace receipt mismatch");
            }
            boolean zEquals = "true".equals(properties.getProperty("legacyIdentity"));
            this.legacyIdentity = zEquals;
            boolean zEquals2 = "true".equals(properties.getProperty("legacyUnboundHelper"));
            this.legacyUnboundHelper = zEquals2;
            boolean zHelperOnlyUpgrade = HelperUpdatePolicy.helperOnlyUpgrade(properties);
            this.helperOnlyUpgrade = zHelperOnlyUpgrade;
            if (zHelperOnlyUpgrade && zEquals2) {
                throw new IOException("An unbound helper cannot be a helper-only upgrade");
            }
            if (!zEquals2 || zEquals) {
                String strRequired3 = GamePreparationService.required(properties, "helperPackage");
                this.helperPackage = strRequired3;
                String strRequired4 = GamePreparationService.required(properties, "authority");
                this.authority = strRequired4;
                String strRequired5 = GamePreparationService.required(properties, "sharedUid");
                this.sharedUid = strRequired5;
                if (zEquals) {
                    if (!"32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89".equals(strRequiredHash) || !"com.shatteredpixel.shatteredpixeldungeon".equals(strRequired) || !"io.github.xyz1001.pdsync.bridge".equals(strRequired3) || !"io.github.xyz1001.pdsync.shared".equals(strRequired5) || !strRequired4.equals("io.github.xyz1001.pdsync.bridge.saves")) {
                        throw new IOException("Legacy shared-UID group must not expand beyond its original game/helper");
                    }
                } else if (!gameSourceIdentity.helperPackage.equals(strRequired3) || !gameSourceIdentity.authority.equals(strRequired4) || !gameSourceIdentity.sharedUid.equals(strRequired5)) {
                    throw new IOException("Per-game helper identity receipt mismatch");
                }
                int iNumber = (int) GamePreparationService.number(properties, "apkCount", 1L, 256L);
                ArrayList arrayList = new ArrayList();
                ArrayList arrayList2 = new ArrayList();
                ArrayList arrayList3 = new ArrayList();
                ArrayList arrayList4 = new ArrayList();
                for (int i = 0; i < iNumber; i++) {
                    arrayList2.add(new File(file, GamePreparationService.originalName(i)));
                    arrayList.add(new File(file, GamePreparationService.gameName(i)));
                    arrayList3.add(GamePreparationService.requiredHash(properties, "originalSha256." + i));
                    arrayList4.add(GamePreparationService.requiredHash(properties, "gameSha256." + i));
                }
                List<File> listUnmodifiableList2 = Collections.unmodifiableList(arrayList);
                this.gameApks = listUnmodifiableList2;
                this.originals = Collections.unmodifiableList(arrayList2);
                List<String> listUnmodifiableList3 = Collections.unmodifiableList(arrayList3);
                this.originalHashes = listUnmodifiableList3;
                this.gameHashes = Collections.unmodifiableList(arrayList4);
                this.gameApk = listUnmodifiableList2.get(0);
                this.originalSha256 = listUnmodifiableList3.get(0);
                String strRequiredHash2 = GamePreparationService.requiredHash(properties, "sourceApkSetSha256");
                this.sourceApkSetSha256 = strRequiredHash2;
                this.originalSetSha256 = strRequiredHash2;
                this.helperApk = new File(file, "helper-local.apk");
                this.unsignedHelperApk = new File(file, "helper-unsigned.apk");
                this.helperTemplate = new File(file, this.legacyUnboundHelper ? "helper-unsigned.apk" : "helper-template.apk");
                this.helperSha256 = GamePreparationService.requiredHash(properties, "helperSha256");
                this.unsignedHelperSha256 = GamePreparationService.requiredHash(properties, "unsignedHelperSha256");
                this.templateSha256 = GamePreparationService.requiredHash(properties, "templateSha256");
                this.signingCertificateSha256 = GamePreparationService.requiredHash(properties, "certificateSha256");
                this.protectedEntryCount = (int) GamePreparationService.number(properties, "protectedEntryCount", 0L, 2147483647L);
                this.helperVersionCode = GamePreparationService.number(properties, "helperVersionCode", 1L, Long.MAX_VALUE);
                boolean zEquals3 = "true".equals(properties.getProperty("completeInstalledSet"));
                this.completeInstalledSet = zEquals3;
                InstalledSourceProof from = InstalledSourceProof.readFrom(properties);
                this.installedSourceProof = from;
                if (from != null && !zEquals3) {
                    throw new IOException("Installed source proof requires a complete installation");
                }
                this.gameStrategy = InstallStrategy.valueOf(decision.strategy.name());
                this.helperStrategy = InstallStrategy.valueOf(decision2.strategy.name());
                this.gameStrategyReason = decision.reason;
                this.helperStrategyReason = decision2.reason;
                Properties properties2 = new Properties();
                this.receiptProperties = properties2;
                properties2.putAll(properties);
                return;
            }
            throw new IOException("Unbound legacy helper requires the historical group");
        }
    }

    public GamePreparationService(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.packages = applicationContext.getPackageManager();
        this.root = new File(applicationContext.getNoBackupFilesDir(), "local-apk-preparation-v1");
    }

    public String currentLocalCertificateSha256() throws Exception {
        try {
            KeyStore.PrivateKeyEntry privateKeyEntryExisting = LocalApkSigner.existing();
            if (privateKeyEntryExisting == null) {
                return null;
            }
            String strFingerprint = LocalApkSigner.fingerprint(privateKeyEntryExisting);
            validateIdentityMarker(strFingerprint);
            return strFingerprint;
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.LOCAL_KEY, e);
        }
    }

    public PreparationDiagnostics.Metadata diagnosticMetadata(PreparationDiagnostics.Source source, String str, long j, int i) throws PackageManager.NameNotFoundException {
        long longVersionCode;
        String str2 = null;
        try {
            PackageInfo packageInfo = this.packages.getPackageInfo(this.context.getPackageName(), 0);
            str2 = packageInfo.versionName;
            longVersionCode = packageInfo.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException unused) {
            longVersionCode = -1;
        }
        return new PreparationDiagnostics.Metadata(source, Build.VERSION.SDK_INT, str2, longVersionCode, str, j, i);
    }

    public InstalledGame inspectInstalledGame(String packageName) throws Exception {
        PackageInfo info = null;
        try {
            info = installed(packageName);
            return info == null ? null : new InstalledGame(info, packages);
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.EXTRACTION, e, diagnosticMetadata(PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS, packageName, info == null ? -1 : info.getLongVersionCode(), -1));
        }
    }

    public List<InstalledGame> listInstalledGames() throws Exception {
        InstalledGame installedGameInspectInstalledGame;
        try {
            ArrayList arrayList = new ArrayList();
            HashSet hashSet = new HashSet();
            for (ResolveInfo resolveInfo : this.packages.queryIntentActivities(new Intent("android.intent.action.MAIN").addCategory("android.intent.category.LAUNCHER"), 0)) {
                if (resolveInfo.activityInfo != null) {
                    String str = resolveInfo.activityInfo.packageName;
                    if (hashSet.add(str) && !str.equals(this.context.getPackageName()) && !str.startsWith("io.github.xyz1001.pdsync.bridge.") && (installedGameInspectInstalledGame = inspectInstalledGame(str)) != null) {
                        arrayList.add(installedGameInspectInstalledGame);
                    }
                }
            }
            arrayList.sort(Comparator.comparing(new Function() { // from class: io.github.xyz1001.pdsync.android.prepare.GamePreparationService$$ExternalSyntheticLambda3
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    return ((GamePreparationService.InstalledGame) obj).label;
                }
            }, String.CASE_INSENSITIVE_ORDER).thenComparing(new Function() { // from class: io.github.xyz1001.pdsync.android.prepare.GamePreparationService$$ExternalSyntheticLambda4
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    return ((GamePreparationService.InstalledGame) obj).packageName;
                }
            }));
            return Collections.unmodifiableList(arrayList);
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.EXTRACTION, e, this.diagnosticMetadata(PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS, null, -1L, -1));
        }
    }

    public synchronized PreparedPair prepareInstalledGame(String packageName, boolean consent, InputStream helper) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.RUNTIME_CHECK;
        PreparationDiagnostics.Metadata metadata = diagnosticMetadata(PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS, packageName, -1, -1);
        try (InputStream ownedHelper = helper) {
            requireSupportedRuntime();
            stage = PreparationDiagnostics.Stage.EXTRACTION;
            InstalledGame game = inspectInstalledGame(packageName);
            if (game == null) throw new IOException("Selected game is no longer installed");
            metadata = diagnosticMetadata(PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS, packageName, game.versionCode, game.splitApks.size() + 1);
            PreparedPair retained = reuseInstalledPreparation(game);
            if (retained != null) return retained;
            List<SourceOpener> sources = new ArrayList<>();
            sources.add(() -> new FileInputStream(game.baseApk));
            for (File split : game.splitApks) sources.add(() -> new FileInputStream(split));
            return prepare(sources, ownedHelper, consent, game);
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(stage, e, metadata);
        }
    }

    static  InputStream lambda$prepareInstalledGame$2(InstalledGame installedGame) throws Exception {
        return new FileInputStream(installedGame.baseApk);
    }

    static  InputStream lambda$prepareInstalledGame$3(File file) throws Exception {
        return new FileInputStream(file);
    }

    private PreparedPair reuseInstalledPreparation(InstalledGame installedGame) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.LOCAL_KEY;
        try {
            String strCurrentLocalCertificateSha256 = currentLocalCertificateSha256();
            String currentReceiptId = null;
            if (!InstalledPreparationReuse.requiresReceipt(installedGame.signerCertificateSha256, installedGame.sharedUserId, strCurrentLocalCertificateSha256)) {
                return null;
            }
            stage = PreparationDiagnostics.Stage.EXTRACTION;
            List<String> listCapture = InstalledExtractionGuard.capture(installedGame.snapshot.files);
            requireUnchangedInstallation(installedGame);
            InstalledPreparationReuse.Installed installed = new InstalledPreparationReuse.Installed(installedGame.packageName, installedGame.versionCode, installedGame.sharedUserId, installedGame.signerCertificateSha256, listCapture);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            try {
                currentReceiptId = readCurrentReceiptId();
            } catch (IOException unused) {
            }
            for (File file : InstalledPreparationReuse.prioritizeCurrentReceipt(this.root.listFiles(), currentReceiptId)) {
                if (file.isDirectory() && new File(file, "receipt.properties").isFile()) {
                    try {
                        PreparedPair preparedPairLoadReceipt = loadReceipt(file.getName());
                        if (InstalledPreparationReuse.sameApkSet(listCapture, preparedPairLoadReceipt.gameHashes)) {
                            validatePrepared(preparedPairLoadReceipt);
                            GameSourceIdentity gameSourceIdentity = new GameSourceIdentity(preparedPairLoadReceipt.gamePackage, preparedPairLoadReceipt.sourceSignerCertificateSha256);
                            if (preparedPairLoadReceipt.legacyIdentity) {
                                gameSourceIdentity = GameSourceIdentity.legacyTransport(gameSourceIdentity);
                            }
                            InstalledPreparationReuse.requireMatch(installed, new InstalledPreparationReuse.Receipt(gameSourceIdentity, preparedPairLoadReceipt.gameVersionCode, preparedPairLoadReceipt.signingCertificateSha256, preparedPairLoadReceipt.gameHashes), strCurrentLocalCertificateSha256);
                            stage = PreparationDiagnostics.Stage.EXTRACTION;
                            if (!listCapture.equals(InstalledExtractionGuard.capture(installedGame.snapshot.files))) {
                                throw new IOException("Installed game bytes changed during receipt validation; select it again");
                            }
                            requireUnchangedInstallation(installedGame);
                            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
                            writeBytes(new File(this.root, "current"), preparedPairLoadReceipt.id.getBytes(StandardCharsets.US_ASCII));
                            return preparedPairLoadReceipt;
                        }
                    } catch (Exception unused2) {
                        continue;
                    }
                }
            }
            throw new IOException("This installed game was locally re-signed, but its exact original publisher receipt is unavailable. Preserve the installed game and saves; select the original source APK set to recover its cloud identity.");
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(stage, e);
        }
    }

    private void requireUnchangedInstallation(InstalledGame installedGame) throws Exception {
        InstalledGame installedGameInspectInstalledGame = inspectInstalledGame(installedGame.packageName);
        installedGame.snapshot.requireSame(installedGameInspectInstalledGame == null ? null : installedGameInspectInstalledGame.snapshot);
        if (!Objects.equals(installedGame.sharedUserId, installedGameInspectInstalledGame.sharedUserId)) {
            throw new IOException("Installed game shared UID changed during receipt validation; select it again");
        }
    }

    public synchronized PreparedPair prepareSelectedApk(Uri uri, boolean z, InputStream inputStream) throws Exception {
        return prepareSelectedApks(Collections.singletonList(uri), z, inputStream);
    }

    public synchronized PreparedPair prepareSelectedApks(List<Uri> uris, boolean consent, InputStream helper) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.RUNTIME_CHECK;
        PreparationDiagnostics.Metadata metadata = diagnosticMetadata(PreparationDiagnostics.Source.DOCUMENT_PICKER, null, -1, uris == null ? -1 : uris.size());
        try (InputStream ownedHelper = helper) {
            requireSupportedRuntime();
            stage = PreparationDiagnostics.Stage.EXTRACTION;
            if (uris == null || uris.isEmpty() || uris.size() > 256) throw new IOException("Select one base APK and any required split APK files");
            List<SourceOpener> sources = new ArrayList<>(); Set<Uri> distinct = new HashSet<>();
            for (Uri uri : uris) {
                if (uri == null || !"content".equals(uri.getScheme()) || !distinct.add(uri)) throw new IOException("Select distinct APK files through the system document picker");
                sources.add(() -> context.getContentResolver().openInputStream(uri));
            }
            return prepare(sources, ownedHelper, consent, null);
        } catch (Exception e) { throw PreparationDiagnostics.failure(stage, e, metadata); }
    }

     InputStream m155xa3035543(Uri uri) throws Exception {
        return this.context.getContentResolver().openInputStream(uri);
    }

    private PreparedPair prepare(List<SourceOpener> sources, InputStream helper, boolean consent, InstalledGame installedGame) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.EXTRACTION;
        PreparationDiagnostics.Source source = installedGame == null ? PreparationDiagnostics.Source.DOCUMENT_PICKER : PreparationDiagnostics.Source.INSTALLED_BASE_AND_SPLITS;
        PreparationDiagnostics.Metadata metadata = diagnosticMetadata(source, installedGame == null ? null : installedGame.packageName, installedGame == null ? -1 : installedGame.versionCode, sources.size());
        String id = UUID.randomUUID().toString();
        File directory = new File(root, id);
        boolean committed = false;
        try {
            ensureDirectory(root); ensureDirectory(directory);
            List<File> intake = new ArrayList<>();
            List<String> beforeHashes = installedGame == null ? null : InstalledExtractionGuard.capture(installedGame.snapshot.files);
            InstalledSourceProof[] proof = new InstalledSourceProof[]{null};
            long bytes = 0;
            for (int i = 0; i < sources.size(); i++) {
                File file = new File(directory, "intake-" + i + ".apk");
                try (InputStream input = sources.get(i).open()) { ApkArchive.copyToNewFile(input, file, 2147483647L); }
                bytes += file.length();
                if (bytes > 8589934592L) throw new IOException("Selected APK set exceeds the 8 GiB technical staging limit");
                intake.add(file);
            }
            if (installedGame != null) {
                InstalledGame current = inspectInstalledGame(installedGame.packageName);
                installedGame.snapshot.requireSame(current == null ? null : current.snapshot);
                InstalledExtractionGuard.verifyCopies(installedGame.snapshot.files, intake, beforeHashes);
                current = inspectInstalledGame(installedGame.packageName);
                installedGame.snapshot.requireSame(current == null ? null : current.snapshot);
                proof[0] = InstalledSourceProof.record(installedGame.packageName, installedGame.versionCode, installedGame.signerCertificateSha256, intake);
            }
            stage = PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET;
            boolean[] complete = new boolean[]{installedGame != null};
            ApkSetValidator.ValidatedSet set = ApkSetValidator.inspect(recoverOriginalSourcesIfLocal(intake, directory, complete, proof), complete[0], proof[0]);
            metadata = diagnosticMetadata(source, set.identity.gamePackage, set.base.manifest.versionCode, set.apks.size());
            if (installedGame != null && (!set.identity.gamePackage.equals(installedGame.packageName) || set.base.manifest.versionCode != installedGame.versionCode)) throw new IOException("Extracted APK identity differs from the selected installation");
            String sourceHash = ApkSetValidator.sourceSetSha256(set);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            GameSourceIdentity identity = preserveVerifiedLegacyTransport(set.identity);
            String versionName = resolvedVersionName(set.base.file, set.base.manifest);
            if (identity.gamePackage.equals(context.getPackageName()) || identity.gamePackage.equals(HELPER_PACKAGE) || identity.gamePackage.startsWith(HELPER_PACKAGE + ".")) throw new IOException("The sync app and its helper cannot be selected as the game");
            stage = PreparationDiagnostics.Stage.MANIFEST_PATCH;
            List<File> originals = new ArrayList<>(), unsignedGames = new ArrayList<>(), games = new ArrayList<>();
            for (int i = 0; i < set.apks.size(); i++) {
                File original = new File(directory, originalName(i));
                if (!set.apks.get(i).file.renameTo(original)) throw new IOException("Could not persist original APK set");
                originals.add(original);
                File unsigned = new File(directory, "unsigned-" + i + ".apk");
                unsignedGames.add(unsigned); ApkArchive.patchGame(original, unsigned, identity.sharedUid);
                games.add(new File(directory, gameName(i)));
            }
            File template = new File(directory, "helper-template.apk"), unsignedHelper = new File(directory, "helper-unsigned.apk"), signedHelper = new File(directory, "helper-local.apk");
            ApkArchive.copyToNewFile(helper, template, 2147483647L);
            BinaryManifestPatch.ManifestInfo helperInfo = ApkArchive.validateHelperTemplate(template);
            ApkArchive.patchHelper(template, unsignedHelper, identity, sourceHash, set.base.manifest.versionCode, versionName);
            stage = PreparationDiagnostics.Stage.LOCAL_KEY;
            KeyStore.PrivateKeyEntry key = obtainIdentity(consent, identity);
            String certificate = LocalApkSigner.fingerprint(key);
            int protectedCount = 0;
            for (int i = 0; i < originals.size(); i++) {
                stage = PreparationDiagnostics.Stage.GAME_SIGNING;
                LocalApkSigner.sign(unsignedGames.get(i), games.get(i), key);
                stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
                protectedCount += ApkArchive.verifyGamePayload(originals.get(i), games.get(i), identity.sharedUid);
            }
            stage = PreparationDiagnostics.Stage.HELPER_SIGNING;
            LocalApkSigner.sign(unsignedHelper, signedHelper, key);
            stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
            ApkArchive.verifyHelperPayload(unsignedHelper, signedHelper);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            Properties receipt = new Properties();
            receipt.setProperty("format", "2"); receipt.setProperty("id", id);
            receipt.setProperty("legacyIdentity", Boolean.toString(identity.legacyTransport)); receipt.setProperty("legacyUnboundHelper", "false");
            receipt.setProperty("gamePackage", identity.gamePackage);
            receipt.setProperty("gameLabel", installedGame == null ? gameLabel(originals.get(0), set.base.manifest) : installedGame.label);
            receipt.setProperty("gameVersionName", versionName); receipt.setProperty("gameVersionCode", Long.toString(set.base.manifest.versionCode));
            receipt.setProperty("sourceCertificates", String.join(",", identity.signerCertificateSha256)); receipt.setProperty("sourceApkSetSha256", sourceHash);
            receipt.setProperty("originIdentity", identity.originIdentity); receipt.setProperty("namespaceId", identity.namespaceId);
            receipt.setProperty("helperPackage", identity.helperPackage); receipt.setProperty("sharedUid", identity.sharedUid); receipt.setProperty("authority", identity.authority);
            receipt.setProperty("completeInstalledSet", Boolean.toString(complete[0])); receipt.setProperty("apkCount", Integer.toString(originals.size()));
            for (int i = 0; i < originals.size(); i++) {
                receipt.setProperty("originalSha256." + i, ApkArchive.sha256(originals.get(i)));
                receipt.setProperty("gameSha256." + i, ApkArchive.sha256(games.get(i)));
            }
            receipt.setProperty("helperSha256", ApkArchive.sha256(signedHelper)); receipt.setProperty("unsignedHelperSha256", ApkArchive.sha256(unsignedHelper));
            receipt.setProperty("templateSha256", ApkArchive.sha256(template)); receipt.setProperty("certificateSha256", certificate);
            receipt.setProperty("protectedEntryCount", Integer.toString(protectedCount)); receipt.setProperty("helperVersionCode", Long.toString(helperInfo.versionCode));
            if (proof[0] != null) proof[0].writeTo(receipt);
            writeProperties(new File(directory, "receipt.properties"), receipt);
            PreparedPair result = loadReceipt(id); validatePrepared(result);
            writeBytes(new File(root, "current"), id.getBytes(StandardCharsets.US_ASCII));
            committed = true;
            for (File file : unsignedGames) file.delete();
            return result;
        } catch (Exception e) { throw PreparationDiagnostics.failure(stage, e, metadata); }
        finally { if (!committed) discardIncompleteStage(directory); }
    }

    public synchronized PreparedPair prepareHelperUpdate(PreparedPair retained, InputStream helper) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.RUNTIME_CHECK;
        PreparationDiagnostics.Metadata metadata = diagnosticMetadata(PreparationDiagnostics.Source.RETAINED_PREPARATION, retained == null ? null : retained.gamePackage, retained == null ? -1 : retained.gameVersionCode, retained == null ? -1 : retained.gameApks.size());
        File directory = null; boolean created = false, committed = false;
        try {
            PreparedPair result;
            try (InputStream ownedHelper = helper) {
                requireSupportedRuntime(); new InstallCoordinator(context).assertCanPrepare();
                stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
                if (retained == null) throw new IOException("No retained preparation is available for a helper-only update");
                if (helper == null) throw new IOException("The bundled helper template is unavailable");
                File oldReceipt = new File(new File(root, checkedId(retained.id)), "receipt.properties");
                Properties properties = readProperties(oldReceipt);
                if (!"2".equals(properties.getProperty("format")) || retained.legacyUnboundHelper) throw new IOException("A historical unbound helper cannot be updated through this path. Resolve any pending restore and prepare a source-bound pair first.");
                if (!retained.receiptProperties.equals(properties)) throw new IOException("The retained preparation receipt changed; reload it before updating the helper");
                validatePrepared(retained);
                stage = PreparationDiagnostics.Stage.LOCAL_KEY;
                KeyStore.PrivateKeyEntry key = LocalApkSigner.existing();
                if (key == null) throw new IOException("The original phone signing key is missing. Preserve the installed game and helper; this update never creates a new key.");
                String certificate = LocalApkSigner.fingerprint(key); validateIdentityMarker(certificate);
                if (!retained.signingCertificateSha256.equals(certificate)) throw new IOException("The phone signing key differs from the retained preparation; a helper-only update is not safe");
                stage = PreparationDiagnostics.Stage.EXTRACTION;
                InstalledGame game = inspectInstalledGame(retained.gamePackage), oldHelper = inspectInstalledGame(retained.helperPackage);
                List<String> gameHashes = game == null ? null : InstalledExtractionGuard.capture(game.snapshot.files);
                List<String> helperHashes = oldHelper == null ? null : InstalledExtractionGuard.capture(oldHelper.snapshot.files);
                GameSourceIdentity identity = new GameSourceIdentity(retained.gamePackage, retained.sourceSignerCertificateSha256);
                if (retained.legacyIdentity) identity = GameSourceIdentity.legacyTransport(identity);
                HelperUpdatePolicy.Retained prior = new HelperUpdatePolicy.Retained(new InstalledPreparationReuse.Receipt(identity, retained.gameVersionCode, retained.signingCertificateSha256, retained.gameHashes), retained.helperVersionCode, retained.helperSha256, retained.legacyUnboundHelper);
                HelperUpdatePolicy.requireCompatible(prior, upgradeSnapshot(game, gameHashes), upgradeSnapshot(oldHelper, helperHashes), certificate, retained.helperVersionCode);
                requireUnchangedInstallation(game); requireUnchangedInstallation(oldHelper);
                String id = UUID.randomUUID().toString(); directory = new File(root, id);
                ensureDirectory(root);
                if (!directory.mkdir()) throw new IOException("Could not create a new helper-update staging directory");
                created = true;
                for (int i = 0; i < retained.gameApks.size(); i++) {
                    copyUnchanged(retained.originals.get(i), new File(directory, originalName(i)), retained.originalHashes.get(i));
                    copyUnchanged(retained.gameApks.get(i), new File(directory, gameName(i)), retained.gameHashes.get(i));
                }
                File template = new File(directory, "helper-template.apk"), unsigned = new File(directory, "helper-unsigned.apk"), signed = new File(directory, "helper-local.apk");
                ApkArchive.copyToNewFile(ownedHelper, template, 2147483647L);
                stage = PreparationDiagnostics.Stage.MANIFEST_PATCH;
                BinaryManifestPatch.ManifestInfo info = ApkArchive.validateHelperTemplate(template);
                HelperUpdatePolicy.requireCompatible(prior, upgradeSnapshot(game, gameHashes), upgradeSnapshot(oldHelper, helperHashes), certificate, info.versionCode);
                ApkArchive.patchHelper(template, unsigned, identity, retained.sourceApkSetSha256, retained.gameVersionCode, retained.gameVersionName);
                stage = PreparationDiagnostics.Stage.HELPER_SIGNING; LocalApkSigner.sign(unsigned, signed, key);
                stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION; ApkArchive.verifyHelperPayload(unsigned, signed);
                stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
                writeProperties(new File(directory, "receipt.properties"), HelperUpdatePolicy.updatedReceipt(properties, id, info.versionCode, ApkArchive.sha256(signed), ApkArchive.sha256(unsigned), ApkArchive.sha256(template)));
                result = loadReceipt(id); validatePrepared(result); validatePrepared(retained);
                if (!properties.equals(readProperties(oldReceipt))) throw new IOException("The retained receipt changed during the helper update; its previous selection is preserved");
                stage = PreparationDiagnostics.Stage.EXTRACTION;
                requireUnchangedInstallation(game); requireUnchangedInstallation(oldHelper);
                List<String> finalGame = InstalledExtractionGuard.capture(game.snapshot.files), finalHelper = InstalledExtractionGuard.capture(oldHelper.snapshot.files);
                if (!gameHashes.equals(finalGame) || !helperHashes.equals(finalHelper)) throw new IOException("The installed game or helper bytes changed during the update; reload the preparation");
                requireUnchangedInstallation(game); requireUnchangedInstallation(oldHelper);
                HelperUpdatePolicy.requireCompatible(prior, upgradeSnapshot(game, finalGame), upgradeSnapshot(oldHelper, finalHelper), currentLocalCertificateSha256(), info.versionCode);
                stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION; new InstallCoordinator(context).assertCanPrepare();
            }
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION; new InstallCoordinator(context).assertCanPrepare();
            writeBytes(new File(root, "current"), result.id.getBytes(StandardCharsets.US_ASCII));
            committed = true; return result;
        } catch (Exception e) { throw PreparationDiagnostics.failure(stage, e, metadata); }
        finally { if (created && !committed) discardIncompleteStage(directory); }
    }

    public synchronized void validateHelperUpdateGame(PreparedPair preparedPair) throws Exception {
        validatePrepared(preparedPair);
        if (!preparedPair.helperOnlyUpgrade) {
            throw new IOException("This preparation is not a helper-only update");
        }
        GameSourceIdentity gameSourceIdentity = new GameSourceIdentity(preparedPair.gamePackage, preparedPair.sourceSignerCertificateSha256);
        if (preparedPair.legacyIdentity) {
            gameSourceIdentity = GameSourceIdentity.legacyTransport(gameSourceIdentity);
        }
        GameSourceIdentity gameSourceIdentity2 = gameSourceIdentity;
        InstalledGame installedGameInspectInstalledGame = inspectInstalledGame(preparedPair.gamePackage);
        if (installedGameInspectInstalledGame != null) {
            List<String> listCapture = InstalledExtractionGuard.capture(installedGameInspectInstalledGame.snapshot.files);
            InstalledPreparationReuse.requireMatch(upgradeSnapshot(installedGameInspectInstalledGame, listCapture), new InstalledPreparationReuse.Receipt(gameSourceIdentity2, preparedPair.gameVersionCode, preparedPair.signingCertificateSha256, preparedPair.gameHashes), currentLocalCertificateSha256());
            requireUnchangedInstallation(installedGameInspectInstalledGame);
            if (!listCapture.equals(InstalledExtractionGuard.capture(installedGameInspectInstalledGame.snapshot.files))) {
                throw new IOException("The installed game changed during helper-update verification; no game reinstall will be offered");
            }
            requireUnchangedInstallation(installedGameInspectInstalledGame);
        } else {
            throw new IOException("The retained game is no longer installed. The helper-only update is stopped and will not reinstall it.");
        }
    }

    private static InstalledPreparationReuse.Installed upgradeSnapshot(InstalledGame installedGame, List<String> list) {
        if (installedGame == null) {
            return null;
        }
        return new InstalledPreparationReuse.Installed(installedGame.packageName, installedGame.versionCode, installedGame.sharedUserId, installedGame.signerCertificateSha256, list);
    }

    private static void copyUnchanged(File file, File file2, String str) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            ApkArchive.copyToNewFile(fileInputStream, file2, 2147483647L);
            fileInputStream.close();
            if (!str.equals(ApkArchive.sha256(file2)) || !str.equals(ApkArchive.sha256(file))) {
                throw new IOException("Retained source or signed game APK bytes changed while copying; the helper update was stopped");
            }
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    public synchronized PreparedPair reloadPrepared() throws Exception {
        try {
            String id = readCurrentReceiptId();
            if (id == null) return null;
            PreparedPair pair = loadReceipt(id); validatePrepared(pair); return pair;
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.RECEIPT_VALIDATION, e, diagnosticMetadata(PreparationDiagnostics.Source.RETAINED_PREPARATION, null, -1, -1));
        }
    }

    private String readCurrentReceiptId() throws IOException {
        AtomicFile atomicFile = new AtomicFile(new File(this.root, "current"));
        if (!atomicFile.getBaseFile().exists()) {
            if (!new File(atomicFile.getBaseFile().getPath() + ".bak").exists()) {
                return null;
            }
        }
        FileInputStream fileInputStreamOpenRead = atomicFile.openRead();
        try {
            String strCheckedId = checkedId(new String(ApkArchive.readLimited(fileInputStreamOpenRead, 100), StandardCharsets.US_ASCII));
            if (fileInputStreamOpenRead != null) {
                fileInputStreamOpenRead.close();
            }
            return strCheckedId;
        } catch (Throwable th) {
            if (fileInputStreamOpenRead != null) {
                try {
                    fileInputStreamOpenRead.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public synchronized PreparedPair refreshInstallStrategies(PreparedPair preparedPair) throws Exception {
        validatePrepared(preparedPair);
        return loadReceipt(preparedPair.id);
    }

    public synchronized void validatePrepared(PreparedPair preparedPair) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.RUNTIME_CHECK;
        try {
            requireSupportedRuntime();
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            if (preparedPair == null) {
                throw new IOException("No prepared APK pair");
            }
            File canonicalFile = new File(this.root, checkedId(preparedPair.id)).getCanonicalFile();
            for (int i = 0; i < preparedPair.gameApks.size(); i++) {
                if (!preparedPair.gameApks.get(i).getCanonicalFile().equals(new File(canonicalFile, gameName(i)))) {
                    throw new IOException("Prepared APK path is outside app-owned staging");
                }
            }
            if (!preparedPair.helperApk.getCanonicalFile().equals(new File(canonicalFile, "helper-local.apk"))) {
                throw new IOException("Prepared helper path is outside app-owned staging");
            }
            stage = PreparationDiagnostics.Stage.LOCAL_KEY;
            String strCurrentLocalCertificateSha256 = currentLocalCertificateSha256();
            if (strCurrentLocalCertificateSha256 == null || !strCurrentLocalCertificateSha256.equals(preparedPair.signingCertificateSha256)) {
                throw new IOException("The original local signing key is missing or different. Do not uninstall the game or helper.");
            }
            rejectMismatchedInstalledIdentity(strCurrentLocalCertificateSha256, preparedPair.gamePackage, preparedPair.helperPackage, preparedPair.sharedUid);
            stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
            for (int i2 = 0; i2 < preparedPair.originals.size(); i2++) {
                if (!((String) preparedPair.originalHashes.get(i2)).equals(ApkArchive.sha256((File) preparedPair.originals.get(i2))) || !((String) preparedPair.gameHashes.get(i2)).equals(ApkArchive.sha256(preparedPair.gameApks.get(i2)))) {
                    throw new IOException("Staged game APK digest mismatch");
                }
            }
            if (!preparedPair.helperSha256.equals(ApkArchive.sha256(preparedPair.helperApk)) || !preparedPair.unsignedHelperSha256.equals(ApkArchive.sha256(preparedPair.unsignedHelperApk)) || !preparedPair.templateSha256.equals(ApkArchive.sha256(preparedPair.helperTemplate))) {
                throw new IOException("Staged helper APK digest mismatch");
            }
            stage = PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET;
            ApkSetValidator.ValidatedSet validatedSetInspect = ApkSetValidator.inspect(preparedPair.originals, preparedPair.completeInstalledSet, preparedPair.installedSourceProof);
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            if (!preparedPair.sourceApkSetSha256.equals(ApkSetValidator.sourceSetSha256(validatedSetInspect)) || !preparedPair.originIdentity.equals(validatedSetInspect.identity.originIdentity) || !preparedPair.gamePackage.equals(validatedSetInspect.base.manifest.packageName) || preparedPair.gameVersionCode != validatedSetInspect.base.manifest.versionCode || !preparedPair.gameVersionName.equals(resolvedVersionName(validatedSetInspect.base.file, validatedSetInspect.base.manifest))) {
                throw new IOException("Source identity/version receipt mismatch");
            }
            int iVerifyLegacyGame = 0;
            for (int i3 = 0; i3 < preparedPair.gameApks.size(); i3++) {
                stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
                iVerifyLegacyGame += preparedPair.legacyUnboundHelper ? verifyLegacyGame((File) preparedPair.originals.get(i3), preparedPair.gameApks.get(i3)) : ApkArchive.verifyGamePayload((File) preparedPair.originals.get(i3), preparedPair.gameApks.get(i3), preparedPair.sharedUid);
                stage = PreparationDiagnostics.Stage.OUTPUT_SIGNATURE;
                LocalApkSigner.verify(preparedPair.gameApks.get(i3), strCurrentLocalCertificateSha256);
            }
            stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
            if (iVerifyLegacyGame == preparedPair.protectedEntryCount) {
                BinaryManifestPatch.ManifestInfo manifestInfoValidateSource = ApkArchive.validateSource(preparedPair.unsignedHelperApk);
                if (!preparedPair.helperPackage.equals(manifestInfoValidateSource.packageName) || !preparedPair.sharedUid.equals(manifestInfoValidateSource.sharedUserId) || manifestInfoValidateSource.targetSandboxVersion != 1 || manifestInfoValidateSource.hasSharedUserMaxSdkVersion || manifestInfoValidateSource.versionCode != preparedPair.helperVersionCode) {
                    throw new IOException("Prepared helper manifest identity mismatch");
                }
                if (!preparedPair.legacyUnboundHelper) {
                    stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
                    ApkArchive.verifyProtectedPayload(preparedPair.helperTemplate, preparedPair.unsignedHelperApk);
                    stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
                    if (!Arrays.equals(BinaryManifestPatch.patchHelper(ApkArchive.manifest(preparedPair.helperTemplate), preparedPair.helperPackage, preparedPair.sharedUid, preparedPair.authority, preparedPair.gamePackage, preparedPair.originIdentity, preparedPair.namespaceId, preparedPair.sourceApkSetSha256, preparedPair.gameVersionCode, preparedPair.gameVersionName), ApkArchive.manifest(preparedPair.unsignedHelperApk))) {
                        throw new IOException("Helper binding differs from source identity receipt");
                    }
                }
                stage = PreparationDiagnostics.Stage.PAYLOAD_VALIDATION;
                ApkArchive.verifyHelperPayload(preparedPair.unsignedHelperApk, preparedPair.helperApk);
                stage = PreparationDiagnostics.Stage.OUTPUT_SIGNATURE;
                LocalApkSigner.verify(preparedPair.helperApk, strCurrentLocalCertificateSha256);
            } else {
                throw new IOException("Protected-entry receipt mismatch");
            }
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(stage, e, diagnosticMetadata(PreparationDiagnostics.Source.RETAINED_PREPARATION, preparedPair == null ? null : preparedPair.gamePackage, preparedPair == null ? -1L : preparedPair.gameVersionCode, preparedPair == null ? -1 : preparedPair.gameApks.size()));
        }
    }

    private PreparedPair loadReceipt(String str) throws Exception {
        try {
            File file = new File(this.root, checkedId(str));
            Properties properties = readProperties(new File(file, "receipt.properties"));
            if (!str.equals(properties.getProperty("id"))) {
                throw new IOException("Mismatched preparation receipt");
            }
            if ("1".equals(properties.getProperty("format"))) {
                properties = legacyReceipt(file, properties);
            }
            Properties properties2 = properties;
            if (!"2".equals(properties2.getProperty("format"))) {
                throw new IOException("Unsupported preparation receipt format");
            }
            String strRequiredHash = requiredHash(properties2, "certificateSha256");
            String strRequired = required(properties2, "sharedUid");
            return new PreparedPair(file, properties2, decision(required(properties2, "gamePackage"), strRequiredHash, strRequired, number(properties2, "gameVersionCode", 0L, Long.MAX_VALUE)), decision(required(properties2, "helperPackage"), strRequiredHash, strRequired, number(properties2, "helperVersionCode", 1L, Long.MAX_VALUE)));
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.RECEIPT_VALIDATION, e);
        }
    }

    private Properties legacyReceipt(File file, Properties properties) throws Exception {
        File file2 = new File(file, "original.apk");
        BinaryManifestPatch.ManifestInfo manifestInfoValidateSource = ApkArchive.validateSource(file2);
        if (!"com.shatteredpixel.shatteredpixeldungeon".equals(manifestInfoValidateSource.packageName)) {
            throw new IOException("Legacy receipt does not describe its original game");
        }
        ApkSetValidator.ValidatedSet validatedSetInspect = ApkSetValidator.inspect(Collections.singletonList(file2), false);
        GameSourceIdentity gameSourceIdentity = validatedSetInspect.identity;
        Properties properties2 = new Properties();
        properties2.putAll(properties);
        properties2.setProperty("format", "2");
        properties2.setProperty("legacyIdentity", "true");
        properties2.setProperty("legacyUnboundHelper", "true");
        properties2.setProperty("gamePackage", manifestInfoValidateSource.packageName);
        properties2.setProperty("gameLabel", gameLabel(file2, manifestInfoValidateSource));
        properties2.setProperty("gameVersionCode", Long.toString(manifestInfoValidateSource.versionCode));
        properties2.setProperty("gameVersionName", resolvedVersionName(file2, manifestInfoValidateSource));
        properties2.setProperty("sourceCertificates", String.join(",", gameSourceIdentity.signerCertificateSha256));
        properties2.setProperty("originIdentity", gameSourceIdentity.originIdentity);
        properties2.setProperty("namespaceId", gameSourceIdentity.namespaceId);
        properties2.setProperty("helperPackage", "io.github.xyz1001.pdsync.bridge");
        properties2.setProperty("sharedUid", "io.github.xyz1001.pdsync.shared");
        properties2.setProperty("authority", "io.github.xyz1001.pdsync.bridge.saves");
        properties2.setProperty("sourceApkSetSha256", ApkSetValidator.sourceSetSha256(validatedSetInspect));
        properties2.setProperty("apkCount", "1");
        properties2.setProperty("originalSha256.0", requiredHash(properties, "originalSha256"));
        properties2.setProperty("gameSha256.0", requiredHash(properties, "gameSha256"));
        properties2.setProperty("templateSha256", requiredHash(properties, "unsignedHelperSha256"));
        properties2.setProperty("completeInstalledSet", "false");
        return properties2;
    }

    private List<File> recoverOriginalSourcesIfLocal(List<File> list, File file, boolean[] zArr, InstalledSourceProof[] installedSourceProofArr) throws Exception {
        PreparationDiagnostics.Stage stage = PreparationDiagnostics.Stage.LOCAL_KEY;
        try {
            String strCurrentLocalCertificateSha256 = currentLocalCertificateSha256();
            if (strCurrentLocalCertificateSha256 != null) {
                stage = PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET;
                if (containsLocalSigner(list, strCurrentLocalCertificateSha256, installedSourceProofArr[0])) {
                    stage = PreparationDiagnostics.Stage.RECEIPT_VALIDATION;
                    ArrayList arrayList = new ArrayList();
                    Iterator<File> it = list.iterator();
                    while (it.hasNext()) {
                        arrayList.add(ApkArchive.sha256(it.next()));
                    }
                    Collections.sort(arrayList);
                    File[] fileArrListFiles = this.root.listFiles();
                    if (fileArrListFiles != null) {
                        for (File file2 : fileArrListFiles) {
                            if (file2.isDirectory() && !file2.equals(file) && new File(file2, "receipt.properties").isFile()) {
                                try {
                                    PreparedPair preparedPairLoadReceipt = loadReceipt(file2.getName());
                                    ArrayList arrayList2 = new ArrayList(preparedPairLoadReceipt.gameHashes);
                                    Collections.sort(arrayList2);
                                    if (arrayList2.equals(arrayList)) {
                                        validatePrepared(preparedPairLoadReceipt);
                                        stage = PreparationDiagnostics.Stage.EXTRACTION;
                                        ArrayList arrayList3 = new ArrayList();
                                        for (int i = 0; i < preparedPairLoadReceipt.originals.size(); i++) {
                                            File file3 = new File(file, "recovered-" + i + ".apk");
                                            ApkArchive.copyToNewFile(new FileInputStream((File) preparedPairLoadReceipt.originals.get(i)), file3, 2147483647L);
                                            arrayList3.add(file3);
                                        }
                                        Iterator<File> it2 = list.iterator();
                                        while (it2.hasNext()) {
                                            it2.next().delete();
                                        }
                                        zArr[0] = preparedPairLoadReceipt.completeInstalledSet;
                                        installedSourceProofArr[0] = preparedPairLoadReceipt.installedSourceProof;
                                        return arrayList3;
                                    }
                                } catch (Exception unused) {
                                    continue;
                                }
                            }
                        }
                    }
                    throw new IOException("These APKs were re-signed by this sync app, but their original publisher receipt is unavailable. Select the original source APK set so its cloud identity can be preserved.");
                }
            }
            return list;
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(stage, e);
        }
    }

    static boolean containsLocalSigner(List<File> list, String str, InstalledSourceProof installedSourceProof) throws Exception {
        if (installedSourceProof != null) {
            installedSourceProof.validate(list);
        }
        Iterator<File> it = list.iterator();
        boolean z = false;
        while (it.hasNext()) {
            if (LocalApkSigner.sourceCertificates(it.next(), installedSourceProof).contains(str)) {
                z = true;
            }
        }
        return z;
    }

    private GameSourceIdentity preserveVerifiedLegacyTransport(GameSourceIdentity gameSourceIdentity) throws Exception {
        InstalledGame installedGameInspectInstalledGame;
        if (!"32cd68da7bd2cdb467330cf33ca7c0e9b18eaae94ecf0b4b944313def1732f89".equals(gameSourceIdentity.originIdentity) || (installedGameInspectInstalledGame = inspectInstalledGame(gameSourceIdentity.gamePackage)) == null || !"io.github.xyz1001.pdsync.shared".equals(installedGameInspectInstalledGame.sharedUserId)) {
            return gameSourceIdentity;
        }
        ArrayList arrayList = new ArrayList();
        arrayList.add(ApkArchive.sha256(installedGameInspectInstalledGame.baseApk));
        Iterator<File> it = installedGameInspectInstalledGame.splitApks.iterator();
        while (it.hasNext()) {
            arrayList.add(ApkArchive.sha256(it.next()));
        }
        Collections.sort(arrayList);
        File[] fileArrListFiles = this.root.listFiles();
        if (fileArrListFiles != null) {
            for (File file : fileArrListFiles) {
                if (file.isDirectory() && new File(file, "receipt.properties").isFile()) {
                    try {
                        PreparedPair preparedPairLoadReceipt = loadReceipt(file.getName());
                        if (preparedPairLoadReceipt.legacyIdentity && gameSourceIdentity.originIdentity.equals(preparedPairLoadReceipt.originIdentity)) {
                            ArrayList arrayList2 = new ArrayList(preparedPairLoadReceipt.gameHashes);
                            Collections.sort(arrayList2);
                            if (arrayList2.equals(arrayList)) {
                                validatePrepared(preparedPairLoadReceipt);
                                return GameSourceIdentity.legacyTransport(gameSourceIdentity);
                            }
                        }
                    } catch (Exception unused) {
                        continue;
                    }
                }
            }
        }
        throw new IOException("The installed historical shared-UID game has no matching retained source receipt. Preserve its saves before selecting an original APK for a fresh per-game group.");
    }

    private static int verifyLegacyGame(File file, File file2) throws IOException {
        int iVerifyProtectedPayload = ApkArchive.verifyProtectedPayload(file, file2);
        if (Arrays.equals(LegacyManifestPatch.patch(ApkArchive.manifest(file)), ApkArchive.manifest(file2))) {
            return iVerifyProtectedPayload;
        }
        throw new IOException("Historical game manifest differs from its exact original preparation");
    }

    private String resolvedVersionName(File file, BinaryManifestPatch.ManifestInfo manifestInfo) throws IOException {
        try {
            PackageInfo packageArchiveInfo = this.packages.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (packageArchiveInfo != null && packageArchiveInfo.versionName != null) {
                return packageArchiveInfo.versionName;
            }
        } catch (RuntimeException unused) {
        }
        if (manifestInfo.versionNameResource) {
            throw new IOException("The APK's versionName references a resource that Android cannot resolve from the base APK; this resource layout cannot be bound safely.");
        }
        return manifestInfo.versionName;
    }

    private String gameLabel(File file, BinaryManifestPatch.ManifestInfo manifestInfo) {
        try {
            PackageInfo packageArchiveInfo = this.packages.getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (packageArchiveInfo != null && packageArchiveInfo.applicationInfo != null) {
                packageArchiveInfo.applicationInfo.sourceDir = file.getAbsolutePath();
                packageArchiveInfo.applicationInfo.publicSourceDir = file.getAbsolutePath();
                CharSequence charSequenceLoadLabel = packageArchiveInfo.applicationInfo.loadLabel(this.packages);
                if (charSequenceLoadLabel != null && charSequenceLoadLabel.length() > 0) {
                    return charSequenceLoadLabel.toString();
                }
            }
        } catch (RuntimeException unused) {
        }
        return (manifestInfo.applicationLabel == null || manifestInfo.applicationLabel.isEmpty()) ? manifestInfo.packageName : manifestInfo.applicationLabel;
    }

    private KeyStore.PrivateKeyEntry obtainIdentity(boolean z, GameSourceIdentity gameSourceIdentity) throws Exception {
        KeyStore.PrivateKeyEntry privateKeyEntryExisting = LocalApkSigner.existing();
        if (privateKeyEntryExisting == null) {
            PackageInfo packageInfoInstalled = installed("io.github.xyz1001.pdsync.bridge");
            PackageInfo packageInfoInstalled2 = installed("com.shatteredpixel.shatteredpixeldungeon");
            PackageInfo packageInfoInstalled3 = installed(gameSourceIdentity.helperPackage);
            PackageInfo packageInfoInstalled4 = installed(gameSourceIdentity.gamePackage);
            if (identityMarkerExists() || packageInfoInstalled != null || packageInfoInstalled3 != null || ((packageInfoInstalled2 != null && "io.github.xyz1001.pdsync.shared".equals(packageInfoInstalled2.sharedUserId)) || (packageInfoInstalled4 != null && gameSourceIdentity.sharedUid.equals(packageInfoInstalled4.sharedUserId)))) {
                throw new IOException("The prior local signing key is missing while a saved identity/package remains. Recover the original key; a replacement cannot safely update this installation.");
            }
            if (!z) {
                throw new IOException("Confirm creation of this phone's non-exportable signing key. Clearing or uninstalling the sync app loses the key and can prevent game/helper updates.");
            }
            privateKeyEntryExisting = LocalApkSigner.createAfterExplicitConsent(true);
        }
        String strFingerprint = LocalApkSigner.fingerprint(privateKeyEntryExisting);
        validateIdentityMarker(strFingerprint);
        rejectMismatchedInstalledIdentity(strFingerprint, gameSourceIdentity.gamePackage, gameSourceIdentity.helperPackage, gameSourceIdentity.sharedUid);
        if (!identityMarkerExists()) {
            writeBytes(new File(this.root, "identity.sha256"), strFingerprint.getBytes(StandardCharsets.US_ASCII));
        }
        return privateKeyEntryExisting;
    }

    private void rejectMismatchedInstalledIdentity(String str, String str2, String str3, String str4) throws Exception {
        PackageInfo packageInfoInstalled = installed(str3);
        PackageInfo packageInfoInstalled2 = installed(str2);
        if (packageInfoInstalled != null && !matches(packageInfoInstalled, str)) {
            throw new IOException("The installed per-game helper belongs to another signing key; recover that original local identity before changing it.");
        }
        if (packageInfoInstalled2 != null && str4.equals(packageInfoInstalled2.sharedUserId) && !matches(packageInfoInstalled2, str)) {
            throw new IOException("The installed shared-UID game belongs to another signing key; preserve its original installation.");
        }
    }

    private InstallCompatibility.Decision decision(String str, String str2, String str3, long j) throws Exception {
        PackageInfo packageInfoInstalled = installed(str);
        return InstallCompatibility.decide(packageInfoInstalled != null, packageInfoInstalled != null && matches(packageInfoInstalled, str2), packageInfoInstalled != null && str3.equals(packageInfoInstalled.sharedUserId), packageInfoInstalled == null ? 0L : packageInfoInstalled.getLongVersionCode(), j);
    }

    private PackageInfo installed(String str) {
        try {
            return this.packages.getPackageInfo(str, 134217728);
        } catch (PackageManager.NameNotFoundException unused) {
            return null;
        }
    }

    private static boolean matches(PackageInfo packageInfo, String str) throws IOException {
        List<String> listCertificates = certificates(packageInfo);
        return listCertificates.size() == 1 && str.equals(listCertificates.get(0));
    }

    public static List<String> certificates(PackageInfo packageInfo) throws IOException {
        if (packageInfo.signingInfo == null || packageInfo.signingInfo.getApkContentsSigners() == null) {
            throw new IOException("Installed signer information unavailable; check package visibility");
        }
        ArrayList arrayList = new ArrayList();
        for (Signature signature : packageInfo.signingInfo.getApkContentsSigners()) {
            arrayList.add(ApkArchive.sha256(signature.toByteArray()));
        }
        if (arrayList.isEmpty()) {
            throw new IOException("Installed package has no readable signing certificate");
        }
        Collections.sort(arrayList);
        return arrayList;
    }

    private boolean identityMarkerExists() {
        return new File(this.root, "identity.sha256").exists() || new File(this.root, "identity.sha256.bak").exists();
    }

    private void validateIdentityMarker(String str) throws IOException {
        if (identityMarkerExists()) {
            FileInputStream fileInputStreamOpenRead = new AtomicFile(new File(this.root, "identity.sha256")).openRead();
            try {
                String str2 = new String(ApkArchive.readLimited(fileInputStreamOpenRead, 128), StandardCharsets.US_ASCII);
                if (fileInputStreamOpenRead != null) {
                    fileInputStreamOpenRead.close();
                }
                if (!str2.equals(str)) {
                    throw new IOException("Local signing identity differs from its saved receipt");
                }
            } catch (Throwable th) {
                if (fileInputStreamOpenRead != null) {
                    try {
                        fileInputStreamOpenRead.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
    }

    private static void requireSupportedRuntime() throws IOException {
        if (Build.VERSION.SDK_INT != 36) {
            throw new IOException("This test build supports Android 16/API 36 only");
        }
    }

    public static String originalName(int i) {
        if (i == 0) {
            return "original.apk";
        }
        return "original-split-" + i + ".apk";
    }

    public static String gameName(int i) {
        if (i == 0) {
            return "game-local.apk";
        }
        return "game-split-" + i + ".apk";
    }

    private static String checkedId(String str) throws IOException {
        if (str == null || !str.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid preparation identity");
        }
        return str;
    }

    private static void discardIncompleteStage(File file) {
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles != null) {
            for (File file2 : fileArrListFiles) {
                if (file2.isFile()) {
                    file2.delete();
                }
            }
        }
        file.delete();
    }

    private static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) {
            throw new IOException("Could not create app-private staging directory");
        }
    }

    public static String required(Properties properties, String str) throws IOException {
        String property = properties.getProperty(str);
        if (property != null && !property.isEmpty()) {
            return property;
        }
        throw new IOException("Preparation receipt missing " + str);
    }

    public static String requiredHash(Properties properties, String str) throws IOException {
        String strRequired = required(properties, str);
        if (strRequired.matches("[0-9a-f]{64}")) {
            return strRequired;
        }
        throw new IOException("Invalid receipt digest " + str);
    }

    public static long number(Properties properties, String str, long j, long j2) throws NumberFormatException, IOException {
        try {
            long j3 = Long.parseLong(required(properties, str));
            if (j3 < j || j3 > j2) {
                throw new NumberFormatException();
            }
            return j3;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid receipt number " + str, e);
        }
    }

    private static Properties readProperties(File file) throws IOException {
        Properties properties = new Properties();
        FileInputStream fileInputStreamOpenRead = new AtomicFile(file).openRead();
        try {
            properties.load(new ByteArrayInputStream(ApkArchive.readLimited(fileInputStreamOpenRead, 262144)));
            if (fileInputStreamOpenRead != null) {
                fileInputStreamOpenRead.close();
            }
            return properties;
        } catch (Throwable th) {
            if (fileInputStreamOpenRead != null) {
                try {
                    fileInputStreamOpenRead.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static void writeProperties(File file, Properties properties) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        properties.store(byteArrayOutputStream, "PD Sync source-bound preparation receipt");
        writeBytes(file, byteArrayOutputStream.toByteArray());
    }

    private static void writeBytes(File file, byte[] bArr) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream fileOutputStreamStartWrite = null;
        try {
            fileOutputStreamStartWrite = atomicFile.startWrite();
            fileOutputStreamStartWrite.write(bArr);
            atomicFile.finishWrite(fileOutputStreamStartWrite);
        } catch (IOException | RuntimeException e) {
            if (fileOutputStreamStartWrite != null) {
                atomicFile.failWrite(fileOutputStreamStartWrite);
            }
            throw e;
        }
    }
}

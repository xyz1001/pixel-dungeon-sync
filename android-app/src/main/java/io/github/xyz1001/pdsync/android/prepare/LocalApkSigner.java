package io.github.xyz1001.pdsync.android.prepare;

import android.security.keystore.KeyGenParameterSpec;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import javax.security.auth.x500.X500Principal;

final class LocalApkSigner {
    private static final String ALIAS = "pdsync.local.game-and-bridge.v1";

    private LocalApkSigner() {
    }

    static KeyStore.PrivateKeyEntry existing() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias("pdsync.local.game-and-bridge.v1")) {
            return null;
        }
        KeyStore.Entry entry = keyStore.getEntry("pdsync.local.game-and-bridge.v1", null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new IOException("Local signing identity is not a usable private key");
        }
        return (KeyStore.PrivateKeyEntry) entry;
    }

    static synchronized KeyStore.PrivateKeyEntry createAfterExplicitConsent(boolean z) throws Exception {
        KeyStore.PrivateKeyEntry privateKeyEntryExisting;
        try {
            if (!z) {
                throw new IOException("Explicit consent is required to create this device's signing key");
            }
            if (existing() != null) {
                throw new IOException("Refusing to overwrite an existing signing identity");
            }
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            long jCurrentTimeMillis = System.currentTimeMillis();
            keyPairGenerator.initialize(new KeyGenParameterSpec.Builder("pdsync.local.game-and-bridge.v1", 12).setKeySize(3072).setDigests("SHA-256", "SHA-512").setSignaturePaddings("PKCS1", "PSS").setCertificateSubject(new X500Principal("CN=PD Sync Device Local APK Signing")).setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(new Date(Math.max(0L, jCurrentTimeMillis - 86400000))).setCertificateNotAfter(new Date(jCurrentTimeMillis + 948672000000L)).setUserAuthenticationRequired(false).build());
            keyPairGenerator.generateKeyPair();
            privateKeyEntryExisting = existing();
            if (privateKeyEntryExisting == null) {
                throw new IOException("Android Keystore did not retain the generated identity");
            }
        } catch (Throwable th) {
            throw th;
        }
        return privateKeyEntryExisting;
    }

    static String fingerprint(KeyStore.PrivateKeyEntry privateKeyEntry) throws Exception {
        return ApkArchive.sha256(privateKeyEntry.getCertificate().getEncoded());
    }

    static void sign(File file, File file2, KeyStore.PrivateKeyEntry privateKeyEntry) throws Exception {
        if (file2.exists()) {
            throw new IOException("Signed output already exists");
        }
        try {
            new ApkSigner.Builder((List<ApkSigner.SignerConfig>) Collections.singletonList(new ApkSigner.SignerConfig.Builder("device-local", privateKeyEntry.getPrivateKey(), (List<X509Certificate>) Collections.singletonList((X509Certificate) privateKeyEntry.getCertificate())).build())).setInputApk(file).setOutputApk(file2).setMinSdkVersion(36).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false).setOtherSignersSignaturesPreserved(false).setDebuggableApkPermitted(true).setAlignmentPreserved(false).setLibraryPageAlignmentBytes(16384).setCreatedBy("PD Sync device-local preparation").build().sign();
            verify(file2, fingerprint(privateKeyEntry));
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file2, "rw")) { randomAccessFile.getFD().sync(); }
        } catch (Throwable th) {
            file2.delete();
            throw th;
        }
    }

    static List<String> sourceCertificates(File file) throws Exception {
        return sourceCertificates(file, null);
    }

    static List<String> sourceCertificates(File file, InstalledSourceProof installedSourceProof) throws Exception {
        if (installedSourceProof != null) {
            try {
                installedSourceProof.validateFile(file);
            } catch (Exception e) {
                throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET, e);
            }
        }
        ApkVerifier.Result resultVerify = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(36).setMaxCheckedPlatformVersion(36).build().verify();
        if (!resultVerify.isVerified()) {
            if (installedSourceProof != null && missingInstalledSignaturesOnly(resultVerify.getAllErrors())) {
                InstalledUnsignedContainer.requireAbsentSigningBlock(file);
                return installedSourceProof.certificates;
            }
            throw verificationFailure(resultVerify);
        }
        if (resultVerify.getSignerCertificates().isEmpty()) {
            throw new IOException("Source APK has no verified signer certificate");
        }
        ArrayList arrayList = new ArrayList();
        Iterator<X509Certificate> it = resultVerify.getSignerCertificates().iterator();
        while (it.hasNext()) {
            arrayList.add(ApkArchive.sha256(it.next().getEncoded()));
        }
        Collections.sort(arrayList);
        if (installedSourceProof != null && !installedSourceProof.certificates.equals(arrayList)) {
            throw new IOException("Source APK signing certificate differs from selected installed package");
        }
        return Collections.unmodifiableList(arrayList);
    }

    static boolean missingInstalledSignaturesOnly(List<ApkVerifier.IssueWithParams> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean z = false;
        for (ApkVerifier.IssueWithParams issueWithParams : list) {
            if (issueWithParams == null) {
                return false;
            }
            ApkVerifier.Issue issue = issueWithParams.getIssue();
            if (issue == ApkVerifier.Issue.JAR_SIG_NO_MANIFEST) {
                z = true;
            } else if (issue != ApkVerifier.Issue.NO_SIG_FOR_TARGET_SANDBOX_VERSION) {
                return false;
            }
        }
        return z;
    }

    static void verify(File file, String str) throws Exception {
        try {
            ApkZipAlignment.verify(file);
            ApkVerifier.Result resultVerify = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(36).setMaxCheckedPlatformVersion(36).build().verify();
            if (!resultVerify.isVerified()) {
                throw verificationFailure(resultVerify);
            }
            if (!resultVerify.isVerifiedUsingV2Scheme() && !resultVerify.isVerifiedUsingV3Scheme()) {
                throw new IOException("Generated APK has no verified v2/v3 signature");
            }
            if (resultVerify.getSignerCertificates().size() != 1) {
                throw new IOException("Generated APK must have exactly one signer");
            }
            if (!str.equals(ApkArchive.sha256(resultVerify.getSignerCertificates().get(0).getEncoded()))) {
                throw new IOException("APK signing certificate differs from this device's local signing identity");
            }
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.OUTPUT_SIGNATURE, e);
        }
    }

    private static PreparationDiagnostics.VerificationFailure verificationFailure(ApkVerifier.Result result) {
        return verificationFailure(result.getAllErrors());
    }

    static PreparationDiagnostics.VerificationFailure verificationFailure(List<ApkVerifier.IssueWithParams> list) {
        ArrayList arrayList = new ArrayList();
        Throwable th = null;
        for (int i = 0; i < list.size() && i < 32; i++) {
            ApkVerifier.IssueWithParams issueWithParams = list.get(i);
            arrayList.add(issueWithParams.getIssue());
            if (th == null) {
                Object[] params = issueWithParams.getParams();
                int i2 = 0;
                while (true) {
                    if (i2 >= params.length || i2 >= 16) {
                        break;
                    }
                    Object obj = params[i2];
                    if (obj instanceof Throwable) {
                        th = (Throwable) obj;
                        break;
                    }
                    i2++;
                }
            }
        }
        return new PreparationDiagnostics.VerificationFailure(arrayList, th);
    }
}

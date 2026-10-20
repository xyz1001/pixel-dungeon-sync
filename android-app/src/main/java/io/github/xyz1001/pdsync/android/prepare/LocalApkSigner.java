package io.github.xyz1001.pdsync.android.prepare;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import io.github.xyz1001.pdsync.android.prepare.PreparationDiagnostics;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.KeyStore;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocalApkSigner {
    private LocalApkSigner() {
    }

    static KeyStore.PrivateKeyEntry existing() throws Exception {
        return PublicGameSigningIdentity.load();
    }

    /** Compatibility entry point. No key is created or written to AndroidKeyStore. */
    static synchronized KeyStore.PrivateKeyEntry createAfterExplicitConsent(boolean consent) throws Exception {
        if (!consent) {
            throw new IOException("Explicit consent is required to use the public game compatibility identity");
        }
        return existing();
    }

    static String fingerprint(KeyStore.PrivateKeyEntry privateKeyEntry) throws Exception {
        return ApkArchive.sha256(signingCertificate(privateKeyEntry).getEncoded());
    }

    private static X509Certificate signingCertificate(KeyStore.PrivateKeyEntry identity) throws IOException {
        // RSAKey also accepts opaque provider keys without exporting private material.
        if (identity == null || !(identity.getPrivateKey() instanceof RSAKey)
                || !"RSA".equals(identity.getPrivateKey().getAlgorithm())
                || !(identity.getCertificate() instanceof X509Certificate)
                || !(identity.getCertificate().getPublicKey() instanceof RSAPublicKey)) {
            throw new IOException("Local signing identity is not a usable RSA/X.509 key pair");
        }
        RSAKey privateKey = (RSAKey) identity.getPrivateKey();
        RSAPublicKey publicKey = (RSAPublicKey) identity.getCertificate().getPublicKey();
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IOException("Local signing certificate differs from its private key");
        }
        return (X509Certificate) identity.getCertificate();
    }

    static void sign(File file, File file2, KeyStore.PrivateKeyEntry privateKeyEntry) throws Exception {
        if (file2.exists()) {
            throw new IOException("Signed output already exists");
        }
        try {
            new ApkSigner.Builder((List<ApkSigner.SignerConfig>) Collections.singletonList(new ApkSigner.SignerConfig.Builder("public-game-compatibility", privateKeyEntry.getPrivateKey(), (List<X509Certificate>) Collections.singletonList(signingCertificate(privateKeyEntry))).build())).setInputApk(file).setOutputApk(file2).setMinSdkVersion(PreparationPlatform.MIN_SUPPORTED_SDK).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false).setOtherSignersSignaturesPreserved(false).setDebuggableApkPermitted(true).setAlignmentPreserved(false).setLibraryPageAlignmentBytes(16384).setCreatedBy("PD Sync public game compatibility preparation").build().sign();
            verify(file2, fingerprint(privateKeyEntry));
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file2, "rw")) { randomAccessFile.getFD().sync(); }
        } catch (Throwable th) {
            file2.delete();
            throw th;
        }
    }

    static final class SourceSignatures {
        enum Kind { VERIFIED_SIGNER, INSTALLED_SIGNER, UNSIGNED }
        final Kind kind;
        final List<String> certificates;

        private SourceSignatures(Kind kind, List<String> certificates) {
            this.kind = kind;
            this.certificates = Collections.unmodifiableList(new ArrayList<>(certificates));
        }
    }

    static SourceSignatures sourceSignatures(File file) throws Exception {
        return sourceSignatures(file, null);
    }

    static SourceSignatures sourceSignatures(File file, InstalledSourceProof proof) throws Exception {
        return sourceSignatures(file, proof, PreparationPlatform.currentApi());
    }

    static SourceSignatures sourceSignatures(File file, InstalledSourceProof proof, int api) throws Exception {
        PreparationPlatform.requireSupportedApi(api);
        ApkArchive.validateSource(file);
        if (proof != null) {
            try {
                proof.validateFile(file);
            } catch (Exception e) {
                throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.SOURCE_SIGNATURE_SET, e);
            }
        }
        ApkVerifier.Result verification;
        long verificationStarted = System.nanoTime();
        try {
            verification = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(api)
                    .setMaxCheckedPlatformVersion(api).build().verify();
        } catch (Exception verificationError) {
            // Verifier exception categories vary by target SDK and apksig version.
            // Only the independent, strict container proof can establish unsigned.
            return unsignedSource(file, proof, verificationError);
        }
        if (!verification.isVerified()) {
            return unsignedSource(file, proof, verificationFailure(verification, api, elapsedMillis(verificationStarted)));
        }
        InstalledUnsignedContainer.requireSignedContainer(file);
        if (verification.getSignerCertificates().isEmpty()) {
            throw new IOException("Source APK has no verified signer certificate");
        }
        ArrayList<String> certificates = new ArrayList<>();
        for (X509Certificate certificate : verification.getSignerCertificates()) {
            certificates.add(ApkArchive.sha256(certificate.getEncoded()));
        }
        Collections.sort(certificates);
        if (proof != null && !proof.certificates.equals(certificates)) {
            throw new IOException("Source APK signing certificate differs from selected installed package");
        }
        return new SourceSignatures(SourceSignatures.Kind.VERIFIED_SIGNER, certificates);
    }

    private static SourceSignatures unsignedSource(File file, InstalledSourceProof proof,
                                                    Exception verificationError) throws IOException {
        try {
            InstalledUnsignedContainer.requireAbsentSigningBlock(file);
        } catch (IOException unsafeContainer) {
            if (unsafeContainer.getCause() == null) unsafeContainer.initCause(verificationError);
            else unsafeContainer.addSuppressed(verificationError);
            throw unsafeContainer;
        }
        // PM provenance remains authenticated and distinguishable from an ordinary
        // unsigned document. It never turns malformed/present signatures into unsigned.
        return proof == null
                ? new SourceSignatures(SourceSignatures.Kind.UNSIGNED, Collections.emptyList())
                : new SourceSignatures(SourceSignatures.Kind.INSTALLED_SIGNER, proof.certificates);
    }

    static List<String> sourceCertificates(File file) throws Exception {
        return sourceSignatures(file).certificates;
    }

    static List<String> sourceCertificates(File file, InstalledSourceProof proof) throws Exception {
        return sourceSignatures(file, proof).certificates;
    }

    static List<String> sourceCertificates(File file, InstalledSourceProof proof, int api) throws Exception {
        return sourceSignatures(file, proof, api).certificates;
    }

    /** Legacy diagnostic helper only; never used to establish unsigned provenance. */
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
        verify(file, str, PreparationPlatform.currentApi());
    }

    static void verify(File file, String expectedCertificate, int api) throws Exception {
        try {
            PreparationPlatform.requireSupportedApi(api);
            ApkZipAlignment.verify(file);
            // Every generated output must work at both supported endpoints, even
            // when preparation itself runs on an intermediate Android version.
            verifyForApi(file, expectedCertificate, PreparationPlatform.MIN_SUPPORTED_SDK);
            verifyForApi(file, expectedCertificate, PreparationPlatform.MAX_VERIFIED_SDK);
            if (api != PreparationPlatform.MIN_SUPPORTED_SDK && api != PreparationPlatform.MAX_VERIFIED_SDK) {
                verifyForApi(file, expectedCertificate, api);
            }
        } catch (Exception e) {
            throw PreparationDiagnostics.failure(PreparationDiagnostics.Stage.OUTPUT_SIGNATURE, e);
        }
    }

    private static void verifyForApi(File file, String expectedCertificate, int api) throws Exception {
        long started = System.nanoTime();
        ApkVerifier.Result verification = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(api)
                .setMaxCheckedPlatformVersion(api).build().verify();
        if (!verification.isVerified()) throw verificationFailure(verification, api, elapsedMillis(started));
        if (!verification.isVerifiedUsingV2Scheme() && !verification.isVerifiedUsingV3Scheme()) {
            throw new IOException("Generated APK has no verified v2/v3 signature at API " + api);
        }
        if (verification.getSignerCertificates().size() != 1) {
            throw new IOException("Generated APK must have exactly one signer");
        }
        if (!expectedCertificate.equals(ApkArchive.sha256(verification.getSignerCertificates().get(0).getEncoded()))) {
            throw new IOException("APK signing certificate differs from the expected game compatibility identity");
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static PreparationDiagnostics.VerificationFailure verificationFailure(
            ApkVerifier.Result result, int api, long elapsed) {
        PreparationDiagnostics.VerificationFailure failure = verificationFailure(result.getAllErrors());
        failure.verificationApi = api;
        failure.elapsedMillis = elapsed;
        failure.verifiedSchemes = "v1=" + result.isVerifiedUsingV1Scheme()
                + ",v2=" + result.isVerifiedUsingV2Scheme()
                + ",v3=" + result.isVerifiedUsingV3Scheme()
                + ",v31=" + result.isVerifiedUsingV31Scheme()
                + ",v4=" + result.isVerifiedUsingV4Scheme();
        ArrayList<String> parameters = new ArrayList<>();
        for (ApkVerifier.IssueWithParams issue : result.getAllErrors()) {
            for (Object parameter : issue.getParams()) {
                if (parameters.size() == 32) break;
                String safe = PreparationDiagnostics.safeIssueParameter(parameter);
                if (safe != null && !safe.isEmpty() && !"OMITTED".equals(safe)) {
                    parameters.add(safe);
                }
            }
            if (parameters.size() == 32) break;
        }
        failure.issueParameters = Collections.unmodifiableList(parameters);
        return failure;
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

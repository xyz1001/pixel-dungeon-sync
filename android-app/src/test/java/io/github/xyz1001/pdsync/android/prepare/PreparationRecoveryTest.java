package io.github.xyz1001.pdsync.android.prepare;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.android.apksig.ApkVerifier;

/** Host-only regression coverage; reads APK bytes, never installs or executes APK code. */
public final class PreparationRecoveryTest {
    private static int checks;
    private static final String CERT = "a".repeat(64), LOCAL = "b".repeat(64);
    interface Checked { void run() throws Exception; }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static void rejects(Checked action, String message) throws Exception {
        checks++; try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted unsafe case: " + message);
    }
    static File apk(Path directory, String name, byte[] manifest, byte[] payload) throws Exception {
        File file = directory.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("AndroidManifest.xml")); out.write(manifest); out.closeEntry();
            out.putNextEntry(new ZipEntry("classes.dex")); out.write(payload); out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/example.bin")); out.write(new byte[]{0, -1, 3, 4}); out.closeEntry();
        }
        return file;
    }
    static final class OpaqueRsaKey implements java.security.PrivateKey, java.security.interfaces.RSAKey {
        private final java.math.BigInteger modulus;
        OpaqueRsaKey(java.math.BigInteger modulus) { this.modulus = modulus; }
        public java.math.BigInteger getModulus() { return modulus; }
        public String getAlgorithm() { return "RSA"; }
        public String getFormat() { return null; }
        public byte[] getEncoded() { throw new AssertionError("Private key must never be exported"); }
    }
    static File tamperSignedPayload(Path directory, File signed) throws Exception {
        byte[] bytes = Files.readAllBytes(signed.toPath());
        byte[] name = "classes.dex".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int at = 0; at <= bytes.length - 30 - name.length; at++) {
            if (BinaryManifestPatch.unchecked32(bytes, at) != 0x04034b50L) continue;
            int nameLength = (bytes[at + 26] & 255) | ((bytes[at + 27] & 255) << 8);
            int extraLength = (bytes[at + 28] & 255) | ((bytes[at + 29] & 255) << 8);
            if (nameLength != name.length || !Arrays.equals(name, Arrays.copyOfRange(bytes, at + 30, at + 30 + nameLength))) continue;
            int payload = at + 30 + nameLength + extraLength;
            if (payload + 20 >= bytes.length) throw new IOException("Signed test APK payload is truncated");
            bytes[payload + 20] ^= 1;
            Path output = directory.resolve("tampered-" + signed.getName()); Files.write(output, bytes); return output.toFile();
        }
        throw new IOException("Signed test APK has no dex payload to tamper");
    }
    static byte[] minimalManifest(boolean utf8, boolean styled, boolean duplicate) throws Exception {
        String[] strings = {"manifest", "package", "com.example.game"};
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            byte[] encoded = strings[i].getBytes(utf8 ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.StandardCharsets.UTF_16LE);
            BinaryManifestPatch.writeLength(data, strings[i].length(), utf8);
            if (utf8) BinaryManifestPatch.writeLength(data, encoded.length, true);
            data.write(encoded); data.write(0); if (!utf8) data.write(0);
        }
        while ((data.size() & 3) != 0) data.write(0);
        int start = 28 + strings.length * 4 + (styled ? 4 : 0);
        byte[] pool = new byte[start + data.size() + (styled ? 16 : 0)];
        BinaryManifestPatch.put16(pool, 0, 1); BinaryManifestPatch.put16(pool,2,28); BinaryManifestPatch.put32(pool,4,pool.length);
        BinaryManifestPatch.put32(pool,8,strings.length); BinaryManifestPatch.put32(pool,12,styled ? 1 : 0);
        BinaryManifestPatch.put32(pool,16,utf8 ? 256 : 0); BinaryManifestPatch.put32(pool,20,start);
        BinaryManifestPatch.put32(pool,24,styled ? start + data.size() : 0);
        for (int i=0;i<offsets.length;i++) BinaryManifestPatch.put32(pool,28+i*4,offsets[i]);
        System.arraycopy(data.toByteArray(),0,pool,start,data.size());
        if (styled) { int at=start+data.size(); BinaryManifestPatch.put32(pool,at,0); BinaryManifestPatch.put32(pool,at+4,0); BinaryManifestPatch.put32(pool,at+8,0); BinaryManifestPatch.put32(pool,at+12,0xffffffffL); }
        byte[] root = new byte[duplicate ? 76 : 56];
        BinaryManifestPatch.put16(root,0,258); BinaryManifestPatch.put16(root,2,16); BinaryManifestPatch.put32(root,4,root.length);
        BinaryManifestPatch.put32(root,12,0xffffffffL); BinaryManifestPatch.put32(root,16,0xffffffffL); BinaryManifestPatch.put32(root,20,0);
        BinaryManifestPatch.put16(root,24,20); BinaryManifestPatch.put16(root,26,20); BinaryManifestPatch.put16(root,28,duplicate ? 2 : 1);
        BinaryManifestPatch.put32(root,36,0xffffffffL); BinaryManifestPatch.put32(root,40,1); BinaryManifestPatch.put32(root,44,2);
        BinaryManifestPatch.put16(root,48,8); root[51]=3; BinaryManifestPatch.put32(root,52,2);
        if (duplicate) System.arraycopy(root,36,root,56,20);
        byte[] end=new byte[24]; BinaryManifestPatch.put16(end,0,259); BinaryManifestPatch.put16(end,2,16); BinaryManifestPatch.put32(end,4,24);
        BinaryManifestPatch.put32(end,12,0xffffffffL); BinaryManifestPatch.put32(end,16,0xffffffffL); BinaryManifestPatch.put32(end,20,0);
        ByteArrayOutputStream xml=new ByteArrayOutputStream(); byte[] header=new byte[8]; BinaryManifestPatch.put16(header,0,3); BinaryManifestPatch.put16(header,2,8);
        BinaryManifestPatch.put32(header,4,8+pool.length+root.length+end.length); xml.write(header); xml.write(pool); xml.write(root); xml.write(end); return xml.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        File helper = new File(args[0]); File signedRelease = args.length > 1 && !args[1].isEmpty() ? new File(args[1]) : null;
        Path temporary = Files.createTempDirectory("pdsync-preparation-host-");
        try {
            for (boolean utf8 : new boolean[]{false,true}) for (boolean styled : new boolean[]{false,true}) {
                byte[] minimal = minimalManifest(utf8,styled,false);
                check(BinaryManifestPatch.inspect(minimal).packageName.equals("com.example.game"), "minimal UTF/style parser");
                byte[] patch = BinaryManifestPatch.patchGame(minimal,"com.example.dynamic.shared");
                check(BinaryManifestPatch.inspect(patch).sharedUserId.equals("com.example.dynamic.shared"),"inject missing resource map and namespace");
                rejects(() -> BinaryManifestPatch.inspect(minimalManifest(utf8,styled,true)), "duplicate attribute");
            }
            check(LocalApkSigner.missingInstalledSignaturesOnly(List.of(new ApkVerifier.IssueWithParams(ApkVerifier.Issue.JAR_SIG_NO_MANIFEST,new Object[0]))), "narrow stripped signature classification");
            check(!LocalApkSigner.missingInstalledSignaturesOnly(List.of(new ApkVerifier.IssueWithParams(ApkVerifier.Issue.NO_SIG_FOR_TARGET_SANDBOX_VERSION,new Object[0]))), "sandbox issue alone insufficient");
            check(!LocalApkSigner.missingInstalledSignaturesOnly(List.of()), "no verifier issue insufficient");
            check(!LocalApkSigner.missingInstalledSignaturesOnly(List.of(new ApkVerifier.IssueWithParams(ApkVerifier.Issue.JAR_SIG_NO_MANIFEST,new Object[0]),new ApkVerifier.IssueWithParams(ApkVerifier.Issue.JAR_SIG_NO_SIGNATURES,new Object[0]))), "additional signature errors fail closed");
            String secret="https://secret.example/path?password=credential";
            var diagnostic=PreparationDiagnostics.failure(PreparationDiagnostics.Stage.GAME_SIGNING,new IOException(secret),new PreparationDiagnostics.Metadata(PreparationDiagnostics.Source.DOCUMENT_PICKER,36,"0.2.11-test",14,"com.example.game",123,1));
            check(!PreparationDiagnostics.report(diagnostic).contains(secret) && !PreparationDiagnostics.report(diagnostic).contains("credential"+"="),"diagnostics omit raw messages");
            var verification=new PreparationDiagnostics.VerificationFailure(Arrays.asList(ApkVerifier.Issue.values()));
            check(verification.issueNames.size()==12 && verification.issuesTruncated,"verifier diagnostic limit");
            boolean[] closed={false};
            InputStream failing=new InputStream(){ public int read() throws IOException { throw new IOException("read failure"); } public void close(){ closed[0]=true; } };
            File partial=temporary.resolve("partial.apk").toFile();
            rejects(() -> ApkArchive.copyToNewFile(failing,partial,10),"failed intake");
            check(closed[0] && !partial.exists(),"failure closes stream and removes incomplete output");
            byte[] sourceManifest = ApkArchive.manifest(helper);
            BinaryManifestPatch.ManifestInfo original = BinaryManifestPatch.inspect(sourceManifest);
            check(original.packageName.equals("io.github.xyz1001.pdsync.bridge"), "template package");
            check(BinaryManifestPatch.unchecked32(new byte[]{-1,-1,-1,-1}, 0) == 0xffffffffL, "unsigned 32-bit sentinel");
            String shared = "io.github.xyz1001.pdsync.shared.g" + "3".repeat(32);
            byte[] patchedManifest = BinaryManifestPatch.patchGame(sourceManifest, shared);
            BinaryManifestPatch.ManifestInfo patched = BinaryManifestPatch.inspect(patchedManifest);
            check(shared.equals(patched.sharedUserId), "dynamic shared UID");
            check(patched.targetSandboxVersion == 1 && !patched.hasSharedUserMaxSdkVersion, "sandbox settings");
            check(patched.packageName.equals(original.packageName) && patched.versionCode == original.versionCode && patched.versionName.equals(original.versionName), "source identity preserved");
            check(Arrays.equals(patchedManifest, BinaryManifestPatch.patchGame(sourceManifest, shared)), "deterministic patch");
            GameSourceIdentity identity = new GameSourceIdentity("com.example.game", List.of(CERT));
            check(identity.helperPackage.endsWith(identity.originIdentity.substring(0,32)) && identity.authority.equals(identity.helperPackage + ".files"), "dynamic helper identity");
            check(!identity.sharedUid.equals("io.github.xyz1001.pdsync.shared"), "per-game isolation");
            byte[] bound = BinaryManifestPatch.patchHelper(sourceManifest, identity.helperPackage, identity.sharedUid, identity.authority, identity.gamePackage, identity.originIdentity, identity.namespaceId, CERT, 123L, "test 🐉");
            BinaryManifestPatch.ManifestInfo boundInfo = BinaryManifestPatch.inspect(bound);
            check(boundInfo.packageName.equals(identity.helperPackage) && boundInfo.sharedUserId.equals(identity.sharedUid), "helper manifest bound");
            File source = apk(temporary, "source.apk", sourceManifest, new byte[]{1,2,3});
            File patchedApk = temporary.resolve("patched.apk").toFile();
            ApkArchive.patchGame(source, patchedApk, shared);
            check(ApkArchive.verifyGamePayload(source, patchedApk, shared) == 2, "game exact protected payload count");
            check(ApkArchive.entryHashes(source).get("classes.dex").equals(ApkArchive.entryHashes(patchedApk).get("classes.dex")), "dex unchanged");
            File tampered = apk(temporary, "tampered.apk", patchedManifest, new byte[]{1,2,4});
            rejects(() -> ApkArchive.verifyGamePayload(source, tampered, shared), "game payload tampering");
            rejects(() -> ApkArchive.verifyGamePayload(source, patchedApk, "different.shared.uid"), "manifest deviation");
            rejects(() -> ApkArchive.patchGame(source, patchedApk, shared), "output overwrite");
            File boundApk = temporary.resolve("bound.apk").toFile();
            ApkArchive.patchHelper(helper, boundApk, identity, CERT, 123L, "version");
            check(ApkArchive.verifyProtectedPayload(helper, boundApk) > 0, "helper payload unchanged");
            byte[] truncated = Arrays.copyOf(sourceManifest, sourceManifest.length - 4);
            rejects(() -> BinaryManifestPatch.inspect(truncated), "truncated AXML");
            byte[] wrongLength = sourceManifest.clone(); BinaryManifestPatch.put32(wrongLength,4,8);
            rejects(() -> BinaryManifestPatch.inspect(wrongLength), "AXML declared length");
            byte[] badChunk = sourceManifest.clone(); BinaryManifestPatch.put32(badChunk,12,7);
            rejects(() -> BinaryManifestPatch.inspect(badChunk), "AXML chunk bounds");
            byte[] badPool = sourceManifest.clone(); BinaryManifestPatch.put32(badPool,16,0x40001);
            rejects(() -> BinaryManifestPatch.inspect(badPool), "AXML string count limit");
            InstalledUnsignedContainer.requireAbsentSigningBlock(helper);
            InstalledSourceProof proof = InstalledSourceProof.record(original.packageName, original.versionCode, List.of(CERT), List.of(helper));
            proof.validate(List.of(helper));
            check(LocalApkSigner.sourceCertificates(helper, proof, 36).equals(List.of(CERT)), "PM-attested stripped unsigned source accepted");
            rejects(() -> LocalApkSigner.sourceCertificates(helper, null, 36), "SAF unsigned APK rejected");
            rejects(() -> proof.validate(List.of(boundApk)), "installed proof cannot transfer to different bytes");
            Properties properties = new Properties(); proof.writeTo(properties);
            InstalledSourceProof.readFrom(properties).validate(List.of(helper)); checks++;
            properties.remove("installedSourceProof.format");
            rejects(() -> InstalledSourceProof.readFrom(properties), "partial PM proof receipt");
            List<String> captured = InstalledExtractionGuard.capture(List.of(source), file -> true);
            File exactCopy = temporary.resolve("exact.apk").toFile(); Files.copy(source.toPath(), exactCopy.toPath());
            InstalledExtractionGuard.verifyCopies(List.of(source), List.of(exactCopy), captured, file -> true); checks++;
            rejects(() -> InstalledExtractionGuard.capture(List.of(source, source), file -> true), "duplicate installed paths");
            rejects(() -> InstalledExtractionGuard.capture(List.of(source), file -> false), "writable installed paths");
            rejects(() -> InstalledExtractionGuard.verifyCopies(List.of(source), List.of(tampered), captured, file -> true), "changed extracted copy");
            InstalledExtractionGuard.Snapshot snapshot = new InstalledExtractionGuard.Snapshot("com.example.game",1,2,3,10001,List.of(source),List.of(CERT));
            rejects(() -> snapshot.requireSame(new InstalledExtractionGuard.Snapshot("com.example.game",1,2,4,10001,List.of(source),List.of(CERT))), "package mutation during extract");
            InstalledPreparationReuse.Installed localGame = new InstalledPreparationReuse.Installed(identity.gamePackage,123,identity.sharedUid,List.of(LOCAL),List.of(CERT));
            InstalledPreparationReuse.Receipt receipt = new InstalledPreparationReuse.Receipt(identity,123,LOCAL,List.of(CERT));
            check(InstalledPreparationReuse.requireMatch(localGame,receipt,LOCAL).originIdentity.equals(identity.originIdentity), "publisher identity receipt preserved");
            rejects(() -> InstalledPreparationReuse.requireMatch(localGame,receipt,CERT), "wrong local signer");
            rejects(() -> InstalledPreparationReuse.requiresReceipt(List.of(LOCAL),identity.sharedUid,null), "missing key with installed group");
            HelperUpdatePolicy.Retained retained = new HelperUpdatePolicy.Retained(receipt,5,CERT,false);
            InstalledPreparationReuse.Installed oldHelper = new InstalledPreparationReuse.Installed(identity.helperPackage,5,identity.sharedUid,List.of(LOCAL),List.of(CERT));
            check(HelperUpdatePolicy.requireCompatible(retained,localGame,oldHelper,LOCAL,6) == identity, "compatible helper-only update");
            rejects(() -> HelperUpdatePolicy.requireCompatible(retained,localGame,oldHelper,LOCAL,4), "helper downgrade");
            rejects(() -> HelperUpdatePolicy.requireInstallAction(true,identity.gamePackage,identity.helperPackage,false), "helper update installing game");
            rejects(() -> HelperUpdatePolicy.requireInstallAction(true,identity.helperPackage,identity.helperPackage,true), "helper update uninstall");
            for (int api : new int[]{26,27,28,36}) {
                check(PreparationPlatform.requireSupportedApi(api) == api, "supported runtime API " + api);
                check(LocalApkSigner.sourceCertificates(helper, proof, api).equals(List.of(CERT)), "bound PM-attested unsigned source at API " + api);
                rejects(() -> LocalApkSigner.sourceCertificates(helper, null, api), "unsigned document source rejected at API " + api);
                rejects(() -> LocalApkSigner.sourceCertificates(boundApk, proof, api), "PM attestation cannot transfer at API " + api);
            }
            rejects(() -> PreparationPlatform.requireSupportedApi(25), "runtime below minimum");
            rejects(() -> PreparationPlatform.requireSupportedApi(37), "unverified future runtime");
            if (signedRelease != null) {
                List<String> releasedCertificates = LocalApkSigner.sourceCertificates(signedRelease, null, 36);
                check(!releasedCertificates.isEmpty(), "strong verification of released APK");
                var publicCert = new ApkVerifier.Builder(signedRelease).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(36).build().verify().getSignerCertificates().get(0);
                var modulus = ((java.security.interfaces.RSAPublicKey) publicCert.getPublicKey()).getModulus();
                var opaqueIdentity = new java.security.KeyStore.PrivateKeyEntry(new OpaqueRsaKey(modulus), new java.security.cert.Certificate[]{publicCert});
                check(LocalApkSigner.fingerprint(opaqueIdentity).equals(releasedCertificates.get(0)), "certificate identity needs only public RSA modulus; no private key export");
                var mismatchedIdentity = new java.security.KeyStore.PrivateKeyEntry(new OpaqueRsaKey(modulus.add(java.math.BigInteger.TWO)), new java.security.cert.Certificate[]{publicCert});
                rejects(() -> LocalApkSigner.fingerprint(mismatchedIdentity), "RSA key and certificate mismatch");
                File badSignature = tamperSignedPayload(temporary, signedRelease);
                var signedManifest = ApkArchive.validateSource(signedRelease);
                InstalledSourceProof badSignatureProof = InstalledSourceProof.record(signedManifest.packageName, signedManifest.versionCode, releasedCertificates, List.of(badSignature));
                for (int api : new int[]{26,27,28,36}) {
                    rejects(() -> LocalApkSigner.sourceCertificates(badSignature, null, api), "tampered signature rejected at API " + api);
                    rejects(() -> LocalApkSigner.sourceCertificates(badSignature, badSignatureProof, api), "PM proof cannot bypass invalid signature at API " + api);
                    check(releasedCertificates.equals(LocalApkSigner.sourceCertificates(signedRelease, null, api)), "effective platform source verification at API " + api);
                    LocalApkSigner.verify(signedRelease, releasedCertificates.get(0), api); checks++;
                    rejects(() -> LocalApkSigner.verify(signedRelease, CERT, api), "wrong output certificate at API " + api);
                }
                check(ApkZipAlignment.verify(signedRelease) > 0, "released APK alignment");
                rejects(() -> InstalledUnsignedContainer.requireAbsentSigningBlock(signedRelease), "signed container cannot use stripped exception");
            }
            if (args.length > 3 && !args[3].isEmpty()) {
                File v1 = new File(args[3]);
                var v1Info = ApkArchive.validateSource(v1);
                List<String> v1Certificates = LocalApkSigner.sourceCertificates(v1, null, 26);
                File v1Tampered = tamperSignedPayload(temporary, v1);
                InstalledSourceProof v1BadProof = InstalledSourceProof.record(v1Info.packageName, v1Info.versionCode, v1Certificates, List.of(v1Tampered));
                for (int api : new int[]{26,27,28,36}) {
                    check(new ApkVerifier.Builder(v1).setMinCheckedPlatformVersion(api).setMaxCheckedPlatformVersion(api).build().verify().isVerifiedUsingV1Scheme(), "official fixture uses V1 at API " + api);
                    check(v1Certificates.equals(LocalApkSigner.sourceCertificates(v1, null, api)), "official V1 source verifies at API " + api);
                    rejects(() -> LocalApkSigner.sourceCertificates(v1Tampered, null, api), "tampered V1 source at API " + api);
                    rejects(() -> LocalApkSigner.sourceCertificates(v1Tampered, v1BadProof, api), "PM proof cannot bypass V1 tampering at API " + api);
                }
            }
            if (args.length > 2) fixtureTests(new File(args[2]));
            System.out.println("Preparation host checks passed: " + checks);
        } finally {
            try (var files = Files.walk(temporary)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    static void fixtureTests(File fixtures) throws Exception {
        File base = new File(fixtures,"base.apk"), feature = new File(fixtures,"feature.apk"), config = new File(fixtures,"config.apk");
        var b = new ApkSetValidator.SourceApk(base,ApkArchive.validateSource(base),List.of(CERT));
        var f = new ApkSetValidator.SourceApk(feature,ApkArchive.validateSource(feature),List.of(CERT));
        var c = new ApkSetValidator.SourceApk(config,ApkArchive.validateSource(config),List.of(CERT));
        check(ApkSetValidator.validate(List.of(c,b,f),false,36).apks.get(0) == b,"base before splits");
        rejects(() -> ApkSetValidator.validate(List.of(c,b,f),true,26), "API36 game cannot run on API26, including installed source");
        File low = new File(fixtures,"base-api26.apk");
        var lowApk = new ApkSetValidator.SourceApk(low,ApkArchive.validateSource(low),List.of(CERT));
        for (int api : new int[]{26,27,28,36}) {
            check(ApkSetValidator.validate(List.of(lowApk),false,api).base == lowApk, "API26 source valid on API " + api);
        }
        rejects(() -> ApkSetValidator.validate(List.of(lowApk),false,25), "unsupported validator platform");
        rejects(() -> ApkSetValidator.validate(List.of(b),false,36),"missing required config");
        rejects(() -> ApkSetValidator.validate(List.of(b,b,c),false,36),"duplicate base");
        rejects(() -> ApkSetValidator.validate(List.of(f,c),false,36),"missing base");
        rejects(() -> ApkSetValidator.validate(List.of(b,c,new ApkSetValidator.SourceApk(feature,f.manifest,List.of(LOCAL))),false,36),"mixed signer set");
        File legacy = new File(fixtures,"legacy.apk"), legacyConfig = new File(fixtures,"legacy-config.apk");
        var l = new ApkSetValidator.SourceApk(legacy,ApkArchive.validateSource(legacy),List.of(CERT));
        var lc = new ApkSetValidator.SourceApk(legacyConfig,ApkArchive.validateSource(legacyConfig),List.of(CERT));
        rejects(() -> ApkSetValidator.validate(List.of(l,lc),false,36),"SAF cannot attest legacy required configuration");
        check(ApkSetValidator.validate(List.of(l,lc),true,36).apks.size()==2,"PM complete legacy set accepted");
    }
}

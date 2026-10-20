package io.github.xyz1001.pdsync.android.prepare;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.math.BigInteger;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Host-only public-identity and signature-classification regressions. No APK code executes. */
public final class PublicSigningIdentityTest {
    private static int checks;
    private static final String EXPECTED = "cf56b878de677aa7b453a8bde1fbc78c282212b2126ae7aca28297eb25649e76";
    private static final String PUBLISHER = "a".repeat(64);
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Checked action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted unsafe case: " + message);
    }

    /** Provider-style RSA private key that intentionally exposes no CRT parameters. */
    private static final class NonCrtPrivateKey implements RSAPrivateKey {
        private static final long serialVersionUID = 1L;
        private final BigInteger modulus, exponent;
        NonCrtPrivateKey(BigInteger modulus, BigInteger exponent) { this.modulus = modulus; this.exponent = exponent; }
        public BigInteger getModulus() { return modulus; }
        public BigInteger getPrivateExponent() { return exponent; }
        public String getAlgorithm() { return "RSA"; }
        public String getFormat() { return null; }
        public byte[] getEncoded() { return null; }
    }

    private static void nonCrtProviderRegression(KeyStore.PrivateKeyEntry identity) throws Exception {
        RSAPrivateKey original = (RSAPrivateKey) identity.getPrivateKey();
        X509Certificate certificate = (X509Certificate) identity.getCertificate();
        NonCrtPrivateKey providerKey = new NonCrtPrivateKey(original.getModulus(), original.getPrivateExponent());
        check(!(((Object) providerKey) instanceof RSAPrivateCrtKey), "fixture genuinely lacks CRT interface");
        KeyStore.PrivateKeyEntry validated = PublicGameSigningIdentity.validate(providerKey, certificate);
        check(validated.getPrivateKey() == providerKey, "non-CRT provider key accepted after real sign/verify proof");
        check(EXPECTED.equals(LocalApkSigner.fingerprint(validated)), "non-CRT provider preserves exact compatibility certificate");
        rejects(() -> PublicGameSigningIdentity.validate(new NonCrtPrivateKey(
                original.getModulus().add(BigInteger.TWO), original.getPrivateExponent()), certificate),
                "mismatched public/private modulus");
        rejects(() -> PublicGameSigningIdentity.validate(new NonCrtPrivateKey(
                original.getModulus(), original.getPrivateExponent().add(BigInteger.TWO)), certificate),
                "same modulus with wrong private exponent fails cryptographic proof");
    }

    public static void main(String[] args) throws Exception {
        android.os.Build.VERSION.SDK_INT = 26;
        Path temporary = Files.createTempDirectory("pdsync-public-signing-");
        try {
            KeyStore.PrivateKeyEntry identity = LocalApkSigner.existing();
            check(EXPECTED.equals(LocalApkSigner.fingerprint(identity)), "exact recovered compatibility certificate");
            nonCrtProviderRegression(identity);
            check(identity == LocalApkSigner.existing(), "identity stable across repeated reads");
            check(identity == LocalApkSigner.createAfterExplicitConsent(true), "compatibility creation does not generate a replacement");
            rejects(() -> LocalApkSigner.createAfterExplicitConsent(false), "legacy consent boundary");
            check(((java.security.interfaces.RSAKey) identity.getPrivateKey()).getModulus().bitLength() == 3072, "original RSA-3072 identity");
            for (int target : new int[]{26, 36}) {
                File fixture = new File(args[0], "signing-api" + target + ".apk");
                InstalledUnsignedContainer.requireAbsentSigningBlock(fixture); checks++; // aapt2 zero-padded extras
                byte[] badPadding = Files.readAllBytes(fixture.toPath());
                boolean padded = false;
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(fixture)) {
                    int central = (int) u32(badPadding, eocd(badPadding) + 16);
                    int end = eocd(badPadding);
                    for (int cursor = central; cursor < end;) {
                        int local = (int) u32(badPadding, cursor + 42);
                        int extra = u16(badPadding, local + 28);
                        if (extra > 0 && extra < 4) {
                            badPadding[local + 30 + u16(badPadding, local + 26)] = 1;
                            padded = true;
                            break;
                        }
                        cursor += 46 + u16(badPadding, cursor + 28) + u16(badPadding, cursor + 30) + u16(badPadding, cursor + 32);
                    }
                }
                check(padded, "aapt2 fixture includes real zero alignment padding");
                badContainer(temporary, "nonzero-truncated-extra-" + target, badPadding);
                byte[] manifest = ApkArchive.manifest(fixture);
                File unsigned = makeZip(temporary, "unsigned-" + target, manifest, false, null);
                File descriptor = makeZip(temporary, "descriptor-" + target, manifest, true, null);
                InstalledUnsignedContainer.requireAbsentSigningBlock(unsigned); checks++;
                InstalledUnsignedContainer.requireAbsentSigningBlock(descriptor); checks++;
                BinaryManifestPatch.ManifestInfo info = ApkArchive.validateSource(unsigned);
                InstalledSourceProof proof = InstalledSourceProof.record(info.packageName, info.versionCode,
                        List.of(PUBLISHER), List.of(unsigned));
                for (int api : new int[]{26, 27, 28, 36}) {
                    LocalApkSigner.SourceSignatures result = LocalApkSigner.sourceSignatures(unsigned, null, api);
                    check(result.kind == LocalApkSigner.SourceSignatures.Kind.UNSIGNED && result.certificates.isEmpty(),
                            "genuine unsigned document at target/API " + target + "/" + api);
                    check(LocalApkSigner.sourceCertificates(unsigned, null, api).isEmpty(), "unsigned compatibility accessor");
                    LocalApkSigner.SourceSignatures installed = LocalApkSigner.sourceSignatures(unsigned, proof, api);
                    check(installed.kind == LocalApkSigner.SourceSignatures.Kind.INSTALLED_SIGNER
                            && installed.certificates.equals(List.of(PUBLISHER)), "installed provenance stays distinct");
                    rejects(() -> LocalApkSigner.sourceSignatures(descriptor, proof, api), "proof cannot transfer between APK bytes");
                }
                boolean immutable = false;
                try { LocalApkSigner.sourceSignatures(unsigned, proof, 26).certificates.add(EXPECTED); }
                catch (UnsupportedOperationException expected) { immutable = true; }
                check(immutable, "source certificate list immutable");
                File signed = temporary.resolve("signed-" + target + ".apk").toFile();
                LocalApkSigner.sign(unsigned, signed, identity);
                for (int api : new int[]{26, 36}) {
                    LocalApkSigner.verify(signed, EXPECTED, api); checks++;
                    ApkVerifier.Result verified = new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(api)
                            .setMaxCheckedPlatformVersion(api).build().verify();
                    check(verified.isVerified() && (verified.isVerifiedUsingV2Scheme() || verified.isVerifiedUsingV3Scheme()),
                            "output strong signature at API " + api);
                    LocalApkSigner.SourceSignatures source = LocalApkSigner.sourceSignatures(signed, null, api);
                    check(source.kind == LocalApkSigner.SourceSignatures.Kind.VERIFIED_SIGNER
                            && source.certificates.equals(List.of(EXPECTED)), "signed source typed provenance");
                    rejects(() -> LocalApkSigner.verify(signed, PUBLISHER, api), "wrong expected output signer");
                }
                rejects(() -> LocalApkSigner.sign(unsigned, signed, identity), "signed output overwrite");
                rejects(() -> InstalledUnsignedContainer.requireAbsentSigningBlock(signed), "present APK signing block");
                File corrupt = write(temporary, "tampered-signed-" + target, mutateStoredPayload(Files.readAllBytes(signed.toPath())));
                InstalledSourceProof badProof = InstalledSourceProof.record(info.packageName, info.versionCode,
                        List.of(EXPECTED), List.of(corrupt));
                for (int api : new int[]{26, 36}) {
                    rejects(() -> LocalApkSigner.sourceSignatures(corrupt, null, api), "tampered v2/v3 cannot become unsigned");
                    rejects(() -> LocalApkSigner.sourceSignatures(corrupt, badProof, api), "installed proof cannot bypass tampered v2/v3");
                }
                testSignedStructure(temporary, unsigned, identity, target);
                if (target == 26) testV1(temporary, unsigned, identity, info);
                testContainer(temporary, unsigned, descriptor, manifest, target);
            }
            rejects(() -> LocalApkSigner.sourceSignatures(new File(args[0], "signing-api26.apk"), null, 25), "unsupported source API");
            System.out.println("Public compatibility identity and strict source-signature checks passed: " + checks);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testSignedStructure(Path dir, File unsigned, KeyStore.PrivateKeyEntry identity, int target) throws Exception {
        byte[] raw = Files.readAllBytes(unsigned.toPath());
        int end = eocd(raw), central = (int) u32(raw, end + 16);
        int second = central + 46 + u16(raw, central + 28) + u16(raw, central + 30) + u16(raw, central + 32);
        int local = (int) u32(raw, second + 42);
        File canonical = signRawZip(dir, "raw-canonical-" + target, raw, identity, new byte[0], true);
        for (int api : new int[]{26, 36}) {
            check(new ApkVerifier.Builder(canonical).setMinCheckedPlatformVersion(api).setMaxCheckedPlatformVersion(api)
                    .build().verify().isVerified(), "raw canonical signing fixture verifies at API " + api);
            InstalledUnsignedContainer.requireSignedContainer(canonical); checks++;
            check(LocalApkSigner.sourceSignatures(canonical, null, api).kind == LocalApkSigner.SourceSignatures.Kind.VERIFIED_SIGNER,
                    "canonical signed padding remains accepted");
        }
        byte[] wrongName = raw.clone(); wrongName[local + 30] ^= 1;
        rejectSignedStructure(signRawZip(dir, "raw-local-name-" + target, wrongName, identity, new byte[0], true),
                "cryptographically signed local/central name mismatch");
        byte[] overlap = raw.clone(); put32(overlap, second + 42, 0);
        rejectSignedStructure(signRawZip(dir, "raw-overlap-" + target, overlap, identity, new byte[0], true),
                "cryptographically signed overlapping local records");
        rejectSignedStructure(signRawZip(dir, "raw-nonzero-padding-" + target, raw, identity, new byte[]{1}, true),
                "cryptographically signed nonzero alignment padding");
        rejectSignedStructure(signRawZip(dir, "raw-unbounded-padding-" + target, raw, identity, new byte[4096], true),
                "cryptographically signed overlong alignment padding");
        rejectSignedStructure(signRawZip(dir, "raw-wrong-padding-" + target, raw, identity, new byte[1], false),
                "cryptographically signed wrong-boundary padding");
    }

    private static void rejectSignedStructure(File signed, String label) throws Exception {
        for (int api : new int[]{26, 36}) {
            check(new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(api).setMaxCheckedPlatformVersion(api)
                    .build().verify().isVerified(), label + " fixture must first pass cryptographic verification");
            rejects(() -> InstalledUnsignedContainer.requireSignedContainer(signed), label);
            rejects(() -> LocalApkSigner.sourceSignatures(signed, null, api), label + " cannot enter preparation");
        }
    }

    /** Low-level signer intentionally preserves hostile ZIP metadata instead of normalizing it. */
    private static File signRawZip(Path dir, String name, byte[] raw, KeyStore.PrivateKeyEntry identity,
                                   byte[] extraGap, boolean canonicalPadding) throws Exception {
        int end = eocd(raw), central = (int) u32(raw, end + 16);
        byte[] before = Arrays.copyOf(raw, central + extraGap.length);
        System.arraycopy(extraGap, 0, before, central, extraGap.length);
        byte[] directory = Arrays.copyOfRange(raw, central, end), ending = Arrays.copyOfRange(raw, end, raw.length);
        put32(ending, 16, before.length);
        var signer = new com.android.apksig.DefaultApkSignerEngine.SignerConfig.Builder("public-game-compatibility",
                identity.getPrivateKey(), List.of((X509Certificate) identity.getCertificate())).build();
        var engine = new com.android.apksig.DefaultApkSignerEngine.Builder(List.of(signer), 26)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setDebuggableApkPermitted(true).build();
        engine.outputJarEntries();
        var beforeSource = com.android.apksig.util.DataSources.asDataSource(java.nio.ByteBuffer.wrap(before));
        var directorySource = com.android.apksig.util.DataSources.asDataSource(java.nio.ByteBuffer.wrap(directory));
        var endingSource = com.android.apksig.util.DataSources.asDataSource(java.nio.ByteBuffer.wrap(ending));
        byte[] block; int padding;
        if (canonicalPadding) {
            var request = engine.outputZipSections2(beforeSource, directorySource, endingSource);
            block = request.getApkSigningBlock(); padding = request.getPaddingSizeBeforeApkSigningBlock(); request.done();
        } else {
            var request = engine.outputZipSections(beforeSource, directorySource, endingSource);
            block = request.getApkSigningBlock(); padding = 0; request.done();
        }
        engine.outputDone(); engine.close();
        put32(ending, 16, before.length + padding + block.length);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.write(before); output.write(new byte[padding]); output.write(block); output.write(directory); output.write(ending);
        return write(dir, name, output.toByteArray());
    }

    private static void testV1(Path dir, File unsigned, KeyStore.PrivateKeyEntry identity,
                               BinaryManifestPatch.ManifestInfo info) throws Exception {
        File v1 = dir.resolve("v1-only.apk").toFile();
        new ApkSigner.Builder(Collections.singletonList(new ApkSigner.SignerConfig.Builder("public-game-compatibility",
                identity.getPrivateKey(), List.of((X509Certificate) identity.getCertificate())).build()))
                .setInputApk(unsigned).setOutputApk(v1).setMinSdkVersion(26).setV1SigningEnabled(true)
                .setV2SigningEnabled(false).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
        File bad = write(dir, "tampered-v1", mutateStoredPayload(Files.readAllBytes(v1.toPath())));
        InstalledSourceProof proof = InstalledSourceProof.record(info.packageName, info.versionCode,
                List.of(EXPECTED), List.of(bad));
        for (int api : new int[]{26, 36}) {
            check(LocalApkSigner.sourceSignatures(v1, null, api).kind == LocalApkSigner.SourceSignatures.Kind.VERIFIED_SIGNER,
                    "valid v1 sources remain supported");
            rejects(() -> LocalApkSigner.sourceSignatures(bad, null, api), "tampered v1 document cannot become unsigned");
            rejects(() -> LocalApkSigner.sourceSignatures(bad, proof, api), "PM proof cannot bypass tampered v1");
            rejects(() -> LocalApkSigner.verify(v1, EXPECTED, api), "v1-only output rejected");
        }
    }

    private static void testContainer(Path dir, File unsigned, File descriptor, byte[] manifest, int target) throws Exception {
        for (String artifact : new String[]{"META-INF/MANIFEST.MF", "META-INF/X.SF", "META-INF/X.RSA",
                "META-INF/X.DSA", "META-INF/X.EC", "meta-inf/sig-bad", "meta-inf/broken.sf"}) {
            File v1 = makeZip(dir, "artifact-" + target + "-" + checks, manifest, false, artifact);
            rejects(() -> InstalledUnsignedContainer.requireAbsentSigningBlock(v1), "present malformed v1 material " + artifact);
            rejects(() -> LocalApkSigner.sourceSignatures(v1, null, 36), "malformed v1 source cannot become unsigned");
        }
        File harmless = makeZip(dir, "notice-" + target, manifest, false, "META-INF/NOTICE");
        InstalledUnsignedContainer.requireAbsentSigningBlock(harmless); checks++;
        byte[] source = Files.readAllBytes(unsigned.toPath());
        int end = eocd(source), central = (int) u32(source, end + 16);
        badContainer(dir, "trailing-" + target, Arrays.copyOf(source, source.length + 1));
        byte[] count = source.clone(); put16(count, end + 8, 1); badContainer(dir, "count-" + target, count);
        byte[] disk = source.clone(); put16(disk, end + 4, 1); badContainer(dir, "disk-" + target, disk);
        byte[] zip64 = source.clone(); put32(zip64, end + 16, 0xffffffffL); badContainer(dir, "zip64-" + target, zip64);
        byte[] localName = source.clone(); localName[30] ^= 1; badContainer(dir, "local-name-" + target, localName);
        byte[] localCrc = source.clone(); localCrc[14] ^= 1; badContainer(dir, "local-crc-" + target, localCrc);
        byte[] localFlags = source.clone(); localFlags[6] ^= 1; badContainer(dir, "local-flags-" + target, localFlags);
        byte[] gap = new byte[source.length + 32];
        System.arraycopy(source, 0, gap, 0, central);
        System.arraycopy("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII), 0, gap, central + 16, 16);
        System.arraycopy(source, central, gap, central + 32, source.length - central);
        put32(gap, end + 32 + 16, central + 32);
        badContainer(dir, "malformed-block-" + target, gap);
        byte[] stream = Files.readAllBytes(descriptor.toPath());
        for (int i = 0; i < stream.length - 16; i++) if (u32(stream, i) == 0x08074b50L) {
            stream[i + 4] ^= 1; break;
        }
        badContainer(dir, "descriptor-crc-" + target, stream);
        byte[] duplicate = source.clone();
        int second = central + 46 + u16(duplicate, central + 28) + u16(duplicate, central + 30) + u16(duplicate, central + 32);
        put32(duplicate, second + 42, 0); badContainer(dir, "overlap-" + target, duplicate);
    }

    private static void badContainer(Path dir, String name, byte[] bytes) throws Exception {
        File file = write(dir, name, bytes);
        rejects(() -> InstalledUnsignedContainer.requireAbsentSigningBlock(file), name);
        rejects(() -> LocalApkSigner.sourceSignatures(file, null, 36), "source classification: " + name);
    }

    private static File makeZip(Path dir, String name, byte[] manifest, boolean descriptor, String artifact) throws Exception {
        File file = dir.resolve(name + ".apk").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip, "AndroidManifest.xml", manifest, descriptor);
            entry(zip, "assets/payload.bin", new byte[]{10, 20, 30, 40, 50, 60, 70, 80}, descriptor);
            if (artifact != null) entry(zip, artifact, new byte[]{1, 2, 3}, descriptor);
        }
        return file;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes, boolean descriptor) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (!descriptor) {
            entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
            CRC32 crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }

    private static byte[] mutateStoredPayload(byte[] bytes) throws IOException {
        byte[] result = bytes.clone(); int end = eocd(result), cursor = (int) u32(result, end + 16);
        while (cursor < end) {
            int nameLength = u16(result, cursor + 28);
            String name = new String(result, cursor + 46, nameLength, StandardCharsets.UTF_8);
            if (name.equals("assets/payload.bin")) {
                int local = (int) u32(result, cursor + 42), size = (int) u32(result, cursor + 20);
                if (u16(result, local + 8) != 0) throw new IOException("Fixture payload must be stored");
                int data = local + 30 + u16(result, local + 26) + u16(result, local + 28);
                result[data] ^= 1;
                CRC32 crc = new CRC32(); crc.update(result, data, size);
                put32(result, cursor + 16, crc.getValue()); put32(result, local + 14, crc.getValue());
                return result;
            }
            cursor += 46 + nameLength + u16(result, cursor + 30) + u16(result, cursor + 32);
        }
        throw new IOException("Fixture payload is absent");
    }

    private static File write(Path dir, String name, byte[] bytes) throws IOException {
        return Files.write(dir.resolve(name + ".apk"), bytes).toFile();
    }
    private static int eocd(byte[] bytes) throws IOException {
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--)
            if (u32(bytes, i) == 0x06054b50L && i + 22 + u16(bytes, i + 20) == bytes.length) return i;
        throw new IOException("No fixture EOCD");
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static long u32(byte[] b, int p) { return u16(b, p) | (long) u16(b, p + 2) << 16; }
    private static void put16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8); }
    private static void put32(byte[] b, int p, long v) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >>> (i * 8)); }
}

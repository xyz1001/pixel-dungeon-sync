package io.github.xyz1001.pdsync.android.prepare;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Intentionally PUBLIC, dedicated game-compatibility identity. This published
 * key is not a security boundary, a publisher identity, or the main app's key.
 * Exact recovery provenance: code45 signed APK SHA-256
 * aa839df6d2e89dfa3be062c73fb219c894ee1a08ec0556ad362d2998bdd311e5;
 * classes2.dex string indexes 24422 (certificate) and 24423 (PKCS#8 key).
 * Never substitute a generated/device-local key: prepared game updates depend
 * on retaining this exact certificate across devices and reinstalls.
 */
final class PublicGameSigningIdentity {
    static final String CERTIFICATE_SHA256 =
            "cf56b878de677aa7b453a8bde1fbc78c282212b2126ae7aca28297eb25649e76";
    private static final String CERTIFICATE_BASE64 =
            "MIIEkjCCAvqgAwIBAgIUGI5yNMb8jy0YZGnWUrwmjYwOlOQwDQYJKoZIhvcNAQELBQAwcTEtMCsGA1UEAwwkUEQgU3luYyBQ" +
            "VUJMSUMgR2FtZSBDb21wYXRpYmlsaXR5IHYxMUAwPgYDVQQKDDdQdWJsaWMgY29tcGF0aWJpbGl0eSBrZXkgLSBub3QgcHVi" +
            "bGlzaGVyIGF1dGhlbnRpY2F0aW9uMCAXDTIwMDEwMTAwMDAwMFoYDzIxMjAwMTAxMDAwMDAwWjBxMS0wKwYDVQQDDCRQRCBT" +
            "eW5jIFBVQkxJQyBHYW1lIENvbXBhdGliaWxpdHkgdjExQDA+BgNVBAoMN1B1YmxpYyBjb21wYXRpYmlsaXR5IGtleSAtIG5v" +
            "dCBwdWJsaXNoZXIgYXV0aGVudGljYXRpb24wggGiMA0GCSqGSIb3DQEBAQUAA4IBjwAwggGKAoIBgQDcwk6+7GW7NGXhJqqG" +
            "IiQ5iLDLFa93K7j0dINd/VKqh5wCy5S3G/HWDaeuC8LeFuuUD87dWOhBKXf2ZjUb0E7XIZxO2AaAvY+09g/I5RZRU128cMog" +
            "pIQ5WwyPdzjUXLI+O5uX+TRwGXyBeec6tIYoubMyd8xgPmEl4iggizKYN3bL4ial02clZC1n+4uBt8gOdVpvmA9DaLIXNLGs" +
            "YK1jOlcUB9AWpwVWxuqAtjMINl4LpjEqC2nj8ri4NODR1DFTbVyMNL+YF3W36C5DQDUAwPr8U2gM61zwzo0Ey40W306LtwVh" +
            "HK6EfildJqrNn7SD4jhaAm/04STUBRa4uWxbAwUWOuGlrBmDa/CGHAfSP3Yn9cyByOebE5dTRRZsL7Ml19LkOoTu7ppaiXYd" +
            "bZU18oJdiyc7A+fk4aGFL/yZnBhF3wbFvKW9SGOBg/Z8/DLKM+vmwMTkWuUYWZJAZni04cAc3ymP5/tdYaE7GuEr+lb4Nvgt" +
            "3El2kRcc093WFMMCAwEAAaMgMB4wDAYDVR0TAQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwDQYJKoZIhvcNAQELBQADggGBABim" +
            "i02yBJOnG6rCaZ6fkdl9xYb88CJXTlZQvIlwe4FVPm03On9J2y4b7IUkrhDLvFteQC3SfSCxWhVh0x5yG125QaT/pP9AkW0T" +
            "qKvfaLjo8Vm1aeRnyX4lMGLHXpYHvLrENj1QBE/D6tsYHP36TWD65kCJgALJfTpj+aBrVhbTp0qt+6uQVo6gW5A8pvv+UgzU" +
            "3/RVviP9tO0NhnrZYltT98vm1VE11IlEo+SxsvreUHy4MsjASxuU14QYjZS7rufhc12kPCHj7BgZmuY/nLtOZKGFrQiDdtTm" +
            "Eb0eEhY2XCAreNh0HVuZUQ+26Dr5SFG+LCgZ0tOscOtvV2q6m1jk9DQKRxm2A7AkLB80YR9q2ocAaGMz3GZUrdGbpt5wqJJu" +
            "jWztH0AFePo1A8pLBF/dh9tFfItENg0cMO9Jd7+4fK7soVcKYybAub0Y0L0lG723QesgpyxbmW0SHI6N6bBz/zjWajGs+XJb" +
            "j0LR8l+FWOiLG51Ksi7CDWXR+oBMzA==";
    private static final String PUBLIC_COMPATIBILITY_KEY_PKCS8_BASE64 =
            "MIIG/AIBADANBgkqhkiG9w0BAQEFAASCBuYwggbiAgEAAoIBgQDcwk6+7GW7NGXhJqqGIiQ5iLDLFa93K7j0dINd/VKqh5wC" +
            "y5S3G/HWDaeuC8LeFuuUD87dWOhBKXf2ZjUb0E7XIZxO2AaAvY+09g/I5RZRU128cMogpIQ5WwyPdzjUXLI+O5uX+TRwGXyB" +
            "eec6tIYoubMyd8xgPmEl4iggizKYN3bL4ial02clZC1n+4uBt8gOdVpvmA9DaLIXNLGsYK1jOlcUB9AWpwVWxuqAtjMINl4L" +
            "pjEqC2nj8ri4NODR1DFTbVyMNL+YF3W36C5DQDUAwPr8U2gM61zwzo0Ey40W306LtwVhHK6EfildJqrNn7SD4jhaAm/04STU" +
            "BRa4uWxbAwUWOuGlrBmDa/CGHAfSP3Yn9cyByOebE5dTRRZsL7Ml19LkOoTu7ppaiXYdbZU18oJdiyc7A+fk4aGFL/yZnBhF" +
            "3wbFvKW9SGOBg/Z8/DLKM+vmwMTkWuUYWZJAZni04cAc3ymP5/tdYaE7GuEr+lb4Nvgt3El2kRcc093WFMMCAwEAAQKCAYAU" +
            "8fKatr8oNd58sDh5pX+eXdO4UQRm4CUaPHvCTSisuVUaBsrDXMljL8R7BO6mN7J+R8muwqK2nAKfxC7Bxs/UJYI5ENH9IP8j" +
            "gj0ZKHMnoCtCl8j1s+Y/st3MNtmTfIAE2471qlVJVYG0nyZLMZmQ/5166lbfwstg1sZL4CkavBhvW7OjBB3DNHACb6Fa218F" +
            "0CiWcezwzlwnS36YBkwtH/nnEp6xp4MgKptRPvg0jUP7AvejL5YKZJsWDx5Nij4+s1jegBlvcrJ7SGmHJZ/K6GRNQrLfgPqG" +
            "HNFbKt9m+CN0p7wSu7llkzakPUFOBwfEqmCv1kXLUc1BPafnVVcRuJFjqMktkg8ahHDazkEdhFs5BaZuwAy3DqI4pE1gMX+6" +
            "jhjhjOWWsY2WFTf8doXCer76Mcs7azkbpAG781a/RzdDS/z1+LQwcR56KMsQtcn6Cpx4NRcv9wGdaWahh+/MU3SgR501YPwW" +
            "NMCYE52325B+JreDeUIayrPO/CokExECgcEA9cqN0p3Wdu88AWIv8znFzGNx1jik2sku0zYCzvm4z9PpZKls3k4+C/xhuBbH" +
            "+IGmP3AsBBdUP7Dy/9wv8NVw+4eSwpzewwuY68gE4Pny64HGPvZOL6Je/Il9o6LaGGDioAHF3oW27OsreAmft5iYwX37DUvx" +
            "XG8MhqClzFunKR6jx2o5NgGg27cU2CAfukUW4PawIyS82edEJnzQZlWAP87oWrxr9baO4FM6fvW6PcR/VpiKSOldmACM2dLO" +
            "43gvAoHBAOXtl2XO59SoBcGaTGP8mNj1Z/rAbaJ0G9+Jk1nLItaAoc49Z/4UgbV/N+YdvPwiLmSikvSNFt3MnrlHZzFmuUqV" +
            "F2mDTFG8t7zLjXowACkhSj5uwmaiO5WXTgC3+R3Benk+uvE2XQ1UXQgYqR99xvlVbQH9m5f++Tj9UwF+LPv6bWQ4WogrPnka" +
            "x6az6Ce2ljIBRIp7iAneCATse5N55r45QdNAu+ojh7HNe7KqzQkAOTfCl03nFN+aESZuxfuzrQKBwDy88C0CyhOb7MUewWCm" +
            "fyWR0XixiLN0Ebkp2KnS9gONh1hTsc6BoT2PAYrfPk0/pJh6nLXqGeyfSn1FqScnZ4MQroifAUFLpV9mjyYVPh1KdXXrociN" +
            "pFFdnImKXcC2eGo35fCmNFz5hEVmg2x4Lg9qlVZk1OEVpA/EtRP59iXoJ2rI6hhZcKm65VC/J3f9SLkQ3NfiZTSnvKSSJWGU" +
            "cVvTQoHhnAAdO9NdXdAv4vUOkLUSfuGjoXhFcokFWhDGCwKBwEPZL4LYYmrLRh+SM5aRtFzZVdyVqgR1NMBq7XNi0R3xCU49" +
            "qpE8ZeYz6z/ZaKoSQBRsMOc/Nw8uUWwlGNtoD6dlhWoaV4wLH0Dz5AS5Y2K/sxPnRTBaETEcW+U0ZKuWK1jZ3PpO63iCe41n" +
            "EYj8aZmrzqX2u1JH6C0b+8GGBSo6BMu9IWHrON0tvNqBXjFR3NJWs24V89fNkBo72l9zalaZuMXo5Uq7P1CFbUoUwro0Oa4t" +
            "ms5fMTamVQc9SujyZQKBwD9WWAPd/C4zcwlK7Flz+24GXzWSsXjkQvRmzNJsJyNH7HcGBE8EmMSjY/QiqQtKjA5D88zgWUKL" +
            "VwjUgKHUcRFqyqUj7MUNpqmeANs40vpCaoneQAyqaQzcL52Y8bckcpU3jFf8eR9VkhBrxjccp3jjQj+Ycu0y0qCmk33/23Mp" +
            "BC84vPAUXjvYHmvRNOwIsRpz05dK7Pr39yTILrXqPTZMxH1U6MBIdZ4Cx99SfMnmecgUN9Tt/OZEFzeKYrSb2w==";
    private static KeyStore.PrivateKeyEntry cached;

    private PublicGameSigningIdentity() {}

    static synchronized KeyStore.PrivateKeyEntry load() throws Exception {
        if (cached != null) return cached;
        byte[] certificateBytes = Base64.getDecoder().decode(CERTIFICATE_BASE64);
        if (!CERTIFICATE_SHA256.equals(ApkArchive.sha256(certificateBytes))) {
            throw new IOException("Public game compatibility certificate fingerprint mismatch");
        }
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateBytes));
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                Base64.getDecoder().decode(PUBLIC_COMPATIBILITY_KEY_PKCS8_BASE64)));
        cached = validate(privateKey, certificate);
        return cached;
    }

    /** Android providers may expose a valid RSA private key without the CRT interface. */
    static KeyStore.PrivateKeyEntry validate(PrivateKey privateKey, X509Certificate certificate) throws Exception {
        if (!(privateKey instanceof RSAKey) || !"RSA".equals(privateKey.getAlgorithm())
                || certificate == null
                || !(certificate.getPublicKey() instanceof RSAPublicKey)) {
            throw new IOException("Public game compatibility identity is not an RSA key pair");
        }
        RSAKey rsaPrivate = (RSAKey) privateKey;
        RSAPublicKey rsaPublic = (RSAPublicKey) certificate.getPublicKey();
        if (!rsaPrivate.getModulus().equals(rsaPublic.getModulus())
                || (privateKey instanceof RSAPrivateCrtKey
                    && !((RSAPrivateCrtKey) privateKey).getPublicExponent().equals(rsaPublic.getPublicExponent()))) {
            throw new IOException("Public game compatibility key and certificate differ");
        }
        // A real signature proves possession of the matching private exponent.
        byte[] challenge = "PD Sync public game compatibility identity".getBytes(StandardCharsets.UTF_8);
        Signature proof = Signature.getInstance("SHA256withRSA");
        proof.initSign(privateKey);
        proof.update(challenge);
        byte[] signed = proof.sign();
        proof.initVerify(certificate);
        proof.update(challenge);
        if (!proof.verify(signed)) {
            throw new IOException("Public game compatibility key-pair verification failed");
        }
        return new KeyStore.PrivateKeyEntry(privateKey, new Certificate[]{certificate});
    }
}

package io.github.xyz1001.pdsync.android;

import android.content.Context;
import io.github.xyz1001.pdsync.android.LegacyWebDavConnectionFile;
import io.github.xyz1001.pdsync.android.WebDavConnectionFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/* loaded from: classes.dex */
public final class SecureConnectionStore {
    private static final String ALIAS = "pdsync-main-webdav-v1";
    private final WebDavConnectionFile file;
    private final File legacyFile;

    public SecureConnectionStore(Context context) {
        File noBackupFilesDir = context.getNoBackupFilesDir();
        this.file = new WebDavConnectionFile(noBackupFilesDir);
        this.legacyFile = new File(noBackupFilesDir, "webdav.enc");
    }

    public static final class Connection extends WebDavConnectionFile.Value {
        @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.Value
        public /* bridge */ /* synthetic */ boolean isTemporary() {
            return super.isTemporary();
        }

        @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.Value
        public /* bridge */ /* synthetic */ String toString() {
            return super.toString();
        }

        @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.Value
        public /* bridge */ /* synthetic */ void validate() throws IOException {
            super.validate();
        }

        public Connection(String str, String str2, String str3, String str4) {
            super(str, str2, str3, str4);
        }

        public Connection(String endpoint, String username, String password, String profile, String provider) {
            super(endpoint, username, password, profile, provider);
        }
    }

    public boolean exists() {
        return this.file.exists() || legacyExists();
    }

    private boolean legacyExists() {
        return LegacyWebDavConnectionFile.exists(this.legacyFile);
    }

    public void save(Connection connection) throws IOException {
        this.file.save(connection);
    }

    public Connection load() throws Exception {
        WebDavConnectionFile.Value valueLoadOrMigrate = this.file.loadOrMigrate(new WebDavConnectionFile.LegacyReader() { // from class: io.github.xyz1001.pdsync.android.SecureConnectionStore$$ExternalSyntheticLambda0
            @Override // io.github.xyz1001.pdsync.android.WebDavConnectionFile.LegacyReader
            public final WebDavConnectionFile.Value load() throws Exception {
                return SecureConnectionStore.this.loadLegacy();
            }
        });
        if (valueLoadOrMigrate == null) {
            return null;
        }
        return new Connection(valueLoadOrMigrate.endpoint, valueLoadOrMigrate.username, valueLoadOrMigrate.password,
                valueLoadOrMigrate.profile, valueLoadOrMigrate.provider);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Connection loadLegacy() throws Exception {
        byte[] bArr = LegacyWebDavConnectionFile.read(this.legacyFile, new LegacyWebDavConnectionFile.KeyReader() { // from class: io.github.xyz1001.pdsync.android.SecureConnectionStore$$ExternalSyntheticLambda1
            @Override // io.github.xyz1001.pdsync.android.LegacyWebDavConnectionFile.KeyReader
            public final SecretKey read() throws Exception {
                return SecureConnectionStore.lambda$loadLegacy$0();
            }
        });
        if (bArr == null) {
            return null;
        }
        try {
            JSONObject jSONObject = new JSONObject(new String(bArr, StandardCharsets.UTF_8));
            Connection connection = new Connection(jSONObject.getString("endpoint"), jSONObject.getString("username"), jSONObject.getString("password"), jSONObject.getString("profile"));
            connection.validate();
            return connection;
        } finally {
            if (bArr != null) {
                Arrays.fill(bArr, (byte) 0);
            }
        }
    }

    static /* synthetic */ SecretKey lambda$loadLegacy$0() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias("pdsync-main-webdav-v1")) {
            throw new IOException("旧版连接密钥不可用。");
        }
        return (SecretKey) keyStore.getKey("pdsync-main-webdav-v1", null);
    }

    public void forget() throws IOException {
        Files.deleteIfExists(new File(this.legacyFile.getPath() + ".bak").toPath());
        Files.deleteIfExists(new File(this.legacyFile.getPath() + ".new").toPath());
        Files.deleteIfExists(this.legacyFile.toPath());
        this.file.forget();
    }
}

package io.github.xyz1001.pdsync.android;

import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import io.github.xyz1001.pdsync.android.prepare.GamePreparationService;
import io.github.xyz1001.pdsync.android.prepare.ValidatedPreparationCache;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Real handoff/storage/provider code; Android/preparation boundaries are explicitly host doubles. */
public final class ExternalInstallerHandoffChecks {
    private static int assertions, cases;
    private static Path root;
    private static final byte[] BYTES = "verified-selected-game-apk-bytes".getBytes(StandardCharsets.UTF_8);
    private interface Check { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]); Files.createDirectories(root);
        run("immutable exact copy and cold reload", ExternalInstallerHandoffChecks::copyAndReload);
        run("default installer plain VIEW read-only grants", ExternalInstallerHandoffChecks::defaultInstallerIntent);
        run("provider exact URI and read-only descriptor", ExternalInstallerHandoffChecks::providerReadOnly);
        run("malformed and traversal URIs fail closed", ExternalInstallerHandoffChecks::uriRejection);
        run("revocation removes only snapshot", ExternalInstallerHandoffChecks::revoke);
        run("split sets rejected before publishing", ExternalInstallerHandoffChecks::splitRejected);
        run("stale or legacy receipt rejected", ExternalInstallerHandoffChecks::staleRejected);
        run("source and private-copy tampering rejected", ExternalInstallerHandoffChecks::tamperRejected);
        run("symlinks cannot expose or delete outside files", ExternalInstallerHandoffChecks::symlinks);
        run("signature-conflict handoff performs no uninstall", ExternalInstallerHandoffChecks::conflictAllowed);
        run("separate tickets cannot overwrite each other", ExternalInstallerHandoffChecks::independentTickets);
        run("corrupt/oversized metadata fails bounded", ExternalInstallerHandoffChecks::corruptMetadata);
        System.out.println("PASS ExternalInstallerHandoffChecks: " + cases + " scenarios, " + assertions + " assertions");
        System.out.println("Boundary: production copy/hash, provider and intent code; Android and preparation validation are host doubles. No Android URI-grant enforcement, default-handler resolution, device install or save-access evidence.");
    }
    private static void run(String name, Check test) throws Exception { test.run(); cases++; System.out.println("PASS " + name); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void rejects(Check test, String message) throws Exception {
        try { test.run(); } catch (IOException | IllegalArgumentException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static Fixture fixture() throws Exception { return new Fixture(Files.createTempDirectory(root, "case-")); }
    private static final class Fixture {
        final Context context; final File source; final GamePreparationService.PreparedPair pair;
        Fixture(Path path) throws Exception {
            context = new Context(path.toFile()); source = path.resolve("retained-original.apk").toFile(); Files.write(source.toPath(), BYTES);
            pair = new GamePreparationService.PreparedPair(); pair.id = UUID.randomUUID().toString(); pair.gamePackage = "com.example.game";
            pair.signingCertificateSha256 = "a".repeat(64); pair.gameVersionCode = 7; pair.gameApks = List.of(source);
            GamePreparationService.current = pair; GamePreparationService.currentId = pair.id; GamePreparationService.reloads = 0;
            ValidatedPreparationCache.unchanged = true; ValidatedPreparationCache.checks = 0;
        }
        ExternalInstallerHandoff.Ticket prepare() throws Exception { return ExternalInstallerHandoff.prepare(context, pair); }
        ExternalApkProvider provider() { ExternalApkProvider provider = new ExternalApkProvider(); provider.testAttach(context); provider.onCreate(); return provider; }
        File snapshot(ExternalInstallerHandoff.Ticket ticket) throws Exception { return ExternalInstallerFiles.load(context.getNoBackupFilesDir(), ticket.nonce).apk; }
    }
    private static void copyAndReload() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); File copy = f.snapshot(ticket);
        check(!copy.equals(f.source) && Arrays.equals(BYTES, Files.readAllBytes(copy.toPath())), "exact independent copy");
        check(GamePreparationService.reloads == 1 && ValidatedPreparationCache.checks >= 3, "prepare passes receipt and source guards");
        check(ticket.packageName.equals(f.pair.gamePackage) && ticket.versionCode == 7 && ticket.signerSha256.equals(f.pair.signingCertificateSha256)
                && ticket.receiptId.equals(f.pair.id), "ticket retains verified expected identity");
        ExternalInstallerHandoff.Ticket restored = ExternalInstallerHandoff.load(new Context(f.context.getNoBackupFilesDir()), ticket.nonce);
        check(restored.uri.equals(ticket.uri) && restored.apkSetSha256.equals(ticket.apkSetSha256), "cold reload retains URI/hash");
        check(ticket.apkSetSha256.equals(ApkSetIdentity.digest(List.of(ticket.apkSha256))), "single-file set hash consistent");
    }
    private static void defaultInstallerIntent() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); Intent intent = ExternalInstallerHandoff.installIntent(ticket);
        check(Intent.ACTION_VIEW.equals(intent.getAction()) && ticket.uri.equals(intent.getData()), "bare ordinary VIEW with exact URI");
        check(!Intent.ACTION_CHOOSER.equals(intent.getAction()) && intent.target == null, "never force chooser wrapper");
        check(intent.getPackage() == null && intent.getComponent() == null, "no explicit app or component overrides default installer");
        check(ExternalInstallerHandoff.MIME_TYPE.equals(intent.getType()), "APK MIME");
        check(intent.getFlags() == Intent.FLAG_GRANT_READ_URI_PERMISSION, "read grant only");
        check(intent.getClipData().getItemCount() == 1 && ticket.uri.equals(intent.getClipData().getItemAt(0).getUri()), "same exact URI in ClipData");
    }
    private static void providerReadOnly() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); ExternalApkProvider provider = f.provider();
        check(ExternalInstallerHandoff.MIME_TYPE.equals(provider.getType(ticket.uri)), "provider APK MIME");
        MatrixCursor result = (MatrixCursor)provider.query(ticket.uri, null, null, null, null);
        check(result.values[0].equals("prepared.apk") && result.values[1].equals((long)BYTES.length), "openable name and size only");
        MatrixCursor paths = (MatrixCursor)provider.query(ticket.uri, new String[] {"_data", "receiptId"}, null, null, null);
        check(paths.values[0] == null && paths.values[1] == null, "no local paths or receipt disclosure");
        try (ParcelFileDescriptor fd = provider.openFile(ticket.uri, "r")) {
            byte[] content = new byte[BYTES.length]; fd.file.readFully(content); check(Arrays.equals(BYTES, content), "descriptor exact bytes");
            rejects(() -> fd.file.write(1), "descriptor must reject writes");
        }
        for (String mode : new String[] {"w", "wt", "wa", "rw", "rwt", "", "R"}) rejects(() -> provider.openFile(ticket.uri, mode), "mode rejected: " + mode);
        rejects(() -> provider.insert(ticket.uri, new ContentValues()), "insert rejected");
        rejects(() -> provider.update(ticket.uri, new ContentValues(), null, null), "update rejected");
        rejects(() -> provider.delete(ticket.uri, null, null), "delete rejected");
        rejects(() -> provider.query(ticket.uri, null, "1", null, null), "query conditions rejected");
    }
    private static void uriRejection() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); ExternalApkProvider provider = f.provider();
        String original = ticket.uri.toString();
        for (String uri : new String[] {original.replace("content:", "file:"), original.replace(".external-apk", ".other"),
                original + "?path=/etc/passwd", original + "#fragment", original.replace("prepared.apk", "handoff.properties"),
                original.replace("/apk/", "/apk/../"), original.replace("/apk/", "/apk/%2e%2e/"),
                original.replace("prepared.apk", "%70repared.apk"), original.replace(ticket.nonce, UUID.randomUUID().toString()),
                original.replace("/prepared.apk", "/prepared.apk/child"), original.replace("/apk/", "/apk//")}) {
            rejects(() -> provider.openFile(Uri.parse(uri), "r"), "invalid URI refused: " + uri);
        }
        rejects(() -> ExternalInstallerHandoff.load(f.context, "../../retained-original.apk"), "load takes nonce not path");
        rejects(() -> ExternalInstallerHandoff.revoke(f.context, "../"), "revoke takes nonce not path");
    }
    private static void revoke() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); ExternalInstallerHandoff.revoke(f.context, ticket.nonce);
        check(ticket.uri.equals(f.context.revoked) && f.context.revokedFlags == 1, "exact read URI revoked");
        check(Arrays.equals(BYTES, Files.readAllBytes(f.source.toPath())), "retained APK untouched");
        rejects(() -> ExternalInstallerHandoff.load(f.context, ticket.nonce), "revoked snapshot unavailable");
        rejects(() -> f.provider().openFile(ticket.uri, "r"), "revoked URI cannot open new descriptor");
        ExternalInstallerHandoff.revoke(f.context, ticket.nonce); check(true, "revoke idempotent");
    }
    private static void splitRejected() throws Exception {
        Fixture f = fixture(); f.pair.gameApks = List.of(f.source, f.source);
        rejects(f::prepare, "never hand out only split base");
        check(GamePreparationService.reloads == 0 && !new File(f.context.getNoBackupFilesDir(), ExternalInstallerFiles.DIRECTORY).exists(), "splits rejected before snapshot");
    }
    private static void staleRejected() throws Exception {
        Fixture f = fixture(); GamePreparationService.currentId = UUID.randomUUID().toString(); rejects(f::prepare, "stale receipt");
        f = fixture(); f.pair.embedded = false; rejects(f::prepare, "legacy helper excluded");
        f = fixture(); f.pair.helperOnlyUpgrade = true; rejects(f::prepare, "helper-only excluded");
        f = fixture(); ValidatedPreparationCache.unchanged = false; rejects(f::prepare, "source stamps changed");
    }
    private static void tamperRejected() throws Exception {
        Fixture initial = fixture(); InstallSessionWriter.Entry source = InstallSessionWriter.inspect(List.of(initial.source)).entries.get(0);
        Files.write(initial.source.toPath(), "malicious".getBytes(StandardCharsets.UTF_8));
        rejects(() -> ExternalInstallerFiles.create(initial.context.getNoBackupFilesDir(), initial.source, source.length, source.sha256,
                initial.pair.gamePackage, 7, initial.pair.signingCertificateSha256, initial.pair.id), "changed source cannot publish");
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); File copy = f.snapshot(ticket); copy.setWritable(true);
        byte[] changed = BYTES.clone(); changed[0] ^= 1; Files.write(copy.toPath(), changed);
        Fixture selected = f; rejects(() -> ExternalInstallerHandoff.load(selected.context, ticket.nonce), "same-length private copy corruption detected at cold load");
        Files.write(copy.toPath(), new byte[1]); rejects(() -> selected.provider().openFile(ticket.uri, "r"), "length corruption rejected at open");
    }
    private static void symlinks() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare(); File copy = f.snapshot(ticket); Files.delete(copy.toPath());
        Files.createSymbolicLink(copy.toPath(), f.source.toPath());
        rejects(() -> f.provider().openFile(ticket.uri, "r"), "symlink APK refused");
        rejects(() -> ExternalInstallerHandoff.revoke(f.context, ticket.nonce), "cleanup cannot follow symlink");
        check(Arrays.equals(BYTES, Files.readAllBytes(f.source.toPath())), "symlink target retained");
        Fixture g = fixture(); Path target = Files.createTempDirectory(root, "outside-");
        Files.createSymbolicLink(new File(g.context.getNoBackupFilesDir(), ExternalInstallerFiles.DIRECTORY).toPath(), target);
        rejects(g::prepare, "symlink staging root refused");
        try (java.util.stream.Stream<Path> files = Files.list(target)) { check(files.findAny().isEmpty(), "no outside write"); }
    }
    private static void conflictAllowed() throws Exception {
        Fixture f = fixture(); f.pair.gameStrategy = GamePreparationService.InstallStrategy.REQUIRES_UNINSTALL;
        ExternalInstallerHandoff.Ticket ticket = f.prepare();
        check(ticket != null && f.source.isFile(), "handoff only; selected installer owns its UI and conflict handling");
    }
    private static void independentTickets() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket first = f.prepare(), second = f.prepare();
        check(!first.nonce.equals(second.nonce) && !first.uri.equals(second.uri), "unique tickets");
        ExternalInstallerHandoff.revoke(f.context, first.nonce);
        check(ExternalInstallerHandoff.load(f.context, second.nonce).apkSha256.equals(second.apkSha256), "one revoke cannot affect another snapshot");
    }
    private static void corruptMetadata() throws Exception {
        Fixture f = fixture(); ExternalInstallerHandoff.Ticket ticket = f.prepare();
        File metadata = new File(f.snapshot(ticket).getParentFile(), "handoff.properties"); metadata.setWritable(true);
        Files.writeString(metadata.toPath(), "format=1\nnonce=" + ticket.nonce + "\nversionCode=bogus\n");
        rejects(() -> ExternalInstallerHandoff.load(f.context, ticket.nonce), "invalid metadata refused");
        Files.write(metadata.toPath(), new byte[8192]);
        rejects(() -> f.provider().openFile(ticket.uri, "r"), "bounded metadata refused");
    }
}

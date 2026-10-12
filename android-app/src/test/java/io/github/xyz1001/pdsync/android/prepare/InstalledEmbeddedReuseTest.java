package io.github.xyz1001.pdsync.android.prepare;

import java.io.IOException;
import java.util.List;

/** Proves embedded source reuse cannot rebind origins or accidentally demand a shared UID. */
public final class InstalledEmbeddedReuseTest {
    static final String PUBLISHER = "a".repeat(64), LOCAL = "b".repeat(64), BASE = "c".repeat(64), SPLIT = "d".repeat(64);
    static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean good, String message) { checks++; if (!good) throw new AssertionError(message); }
    static void rejects(Checked action, String message) throws Exception { checks++; try { action.run(); } catch (IOException expected) { return; } throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        GameSourceIdentity source = new GameSourceIdentity("com.example.fork", List.of(PUBLISHER));
        InstalledPreparationReuse.Receipt receipt = new InstalledPreparationReuse.Receipt(source,42,LOCAL,List.of(BASE,SPLIT),true);
        InstalledPreparationReuse.Installed installed = new InstalledPreparationReuse.Installed(source.gamePackage,42,null,List.of(LOCAL),List.of(SPLIT,BASE));
        check(InstalledPreparationReuse.requiresEmbeddedReceipt(List.of(LOCAL),LOCAL), "Own signer always requires retained original receipt");
        check(!InstalledPreparationReuse.requiresEmbeddedReceipt(List.of(PUBLISHER),LOCAL), "Foreign signer can become a new source identity");
        check(!InstalledPreparationReuse.requiresEmbeddedReceipt(List.of(PUBLISHER),null), "Missing new local key does not reject foreign sources");
        check(InstalledPreparationReuse.requireMatch(installed,receipt,LOCAL).originIdentity.equals(source.originIdentity), "Embedded reselect preserves original publisher origin");
        check(InstalledPreparationReuse.requireMatch(installed,receipt,LOCAL).namespaceId.equals(source.namespaceId), "Embedded reselect preserves namespace");
        rejects(() -> InstalledPreparationReuse.requiresEmbeddedReceipt(List.of(LOCAL,PUBLISHER),LOCAL), "Mixed source signer must not masquerade as own preparation");
        rejects(() -> InstalledPreparationReuse.requireMatch(installed,receipt,PUBLISHER), "Wrong local key must fail");
        rejects(() -> InstalledPreparationReuse.requireMatch(installed,receipt,null), "Missing retained key must fail");
        rejects(() -> InstalledPreparationReuse.requireMatch(new InstalledPreparationReuse.Installed(source.gamePackage,42,source.sharedUid,List.of(LOCAL),List.of(BASE,SPLIT)),receipt,LOCAL), "Embedded APK cannot retain shared UID");
        rejects(() -> InstalledPreparationReuse.requireMatch(new InstalledPreparationReuse.Installed("com.example.other",42,null,List.of(LOCAL),List.of(BASE,SPLIT)),receipt,LOCAL), "Different package cannot inherit namespace");
        rejects(() -> InstalledPreparationReuse.requireMatch(new InstalledPreparationReuse.Installed(source.gamePackage,43,null,List.of(LOCAL),List.of(BASE,SPLIT)),receipt,LOCAL), "Different version cannot reuse exact receipt");
        rejects(() -> InstalledPreparationReuse.requireMatch(new InstalledPreparationReuse.Installed(source.gamePackage,42,null,List.of(LOCAL),List.of(BASE)),receipt,LOCAL), "Missing split cannot reuse receipt");
        rejects(() -> InstalledPreparationReuse.requireMatch(new InstalledPreparationReuse.Installed(source.gamePackage,42,null,List.of(LOCAL),List.of(BASE,BASE)),receipt,LOCAL), "Duplicate base cannot replace split");
        rejects(() -> InstalledPreparationReuse.requireMatch(installed,new InstalledPreparationReuse.Receipt(source,42,PUBLISHER,List.of(BASE,SPLIT),true),LOCAL), "Receipt output signer must match phone key");
        rejects(() -> InstalledPreparationReuse.requireMatch(installed,new InstalledPreparationReuse.Receipt(source,42,LOCAL,List.of(BASE,SPLIT)),LOCAL), "Legacy helper receipt cannot validate embedded installation");
        InstalledPreparationReuse.Installed legacy = new InstalledPreparationReuse.Installed(source.gamePackage,42,source.sharedUid,List.of(LOCAL),List.of(BASE,SPLIT));
        check(InstalledPreparationReuse.requireMatch(legacy,new InstalledPreparationReuse.Receipt(source,42,LOCAL,List.of(BASE,SPLIT)),LOCAL).originIdentity.equals(source.originIdentity), "Legacy source recovery remains available");
        System.out.println("Installed embedded reuse checks passed: " + checks);
    }
}

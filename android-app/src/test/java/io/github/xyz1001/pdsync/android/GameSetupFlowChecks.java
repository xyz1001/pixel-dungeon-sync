package io.github.xyz1001.pdsync.android;

public final class GameSetupFlowChecks {
    private static int checks;
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] ignored) {
        GameSetupFlow flow = new GameSetupFlow();
        require(!flow.busy()); require(!flow.rootAuthorized());
        long a = flow.select("game.one");
        long old = flow.beginWork(); require(flow.busy());
        long b = flow.select("game.two"); long current = flow.beginWork();
        require(b > a); require(!flow.finishWork(old)); require(flow.busy());
        flow.authorizeRoot(a, "game.one"); require(!flow.rootAuthorized());
        require(flow.finishWork(current)); require(!flow.busy());
        flow.authorizeRoot(b, "game.two"); require(flow.rootAuthorized());
        require(flow.beginActivation()); require(!flow.beginActivation());
        require(flow.consumePending(GameSetupFlow.Pending.ACTIVATION));
        require(!flow.consumePending(GameSetupFlow.Pending.ACTIVATION)); require(!flow.beginActivation());
        flow.beginPending(GameSetupFlow.Pending.UNKNOWN_SOURCES);
        require(!flow.consumePending(GameSetupFlow.Pending.SYSTEM_CONFIRMATION));
        require(flow.consumePending(GameSetupFlow.Pending.UNKNOWN_SOURCES));
        flow.beginPending(GameSetupFlow.Pending.SYSTEM_CONFIRMATION);
        flow.select("game.three");
        require(!flow.consumePending(GameSetupFlow.Pending.SYSTEM_CONFIRMATION));
        require(!flow.rootAuthorized()); require(flow.beginActivation());
        flow.cancelPending(); require(flow.pending() == GameSetupFlow.Pending.NONE);
        long work = flow.beginWork(); require(flow.finishWork(work)); require(!flow.finishWork(work));
        require(InstallUiCopy.uninstallWarning("different signing certificate").startsWith("修改后的安装包签名不同"));
        require(InstallUiCopy.uninstallWarning("different shared-UID configuration").contains("共享 UID"));
        require(!InstallUiCopy.uninstallWarning("different shared-UID configuration").contains("签名不同"));
        require(InstallUiCopy.uninstallWarning("installed version is newer").contains("版本低于"));
        require(InstallUiCopy.uninstallWarning(null).contains("不兼容"));
        require(InstallUiCopy.uninstallWarning("anything").equals("当前游戏与安装包不兼容，需要先卸载原游戏。卸载会丢失已有存档。"));
        require("准备安装包".equals(InstallUiCopy.PREPARING.text())); require("启动安装包".equals(InstallUiCopy.STARTING.text()));
        System.out.println("GameSetupFlowChecks: " + checks + " passed");
    }
}

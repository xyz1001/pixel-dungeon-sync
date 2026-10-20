package io.github.xyz1001.pdsync.android;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;

/** Host execution of the real native-dialog adapter, including each actual button listener. */
public final class GameModeChoiceDialogChecks {
    private static int checks;
    private static void require(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }

    private static final class Host implements GameModeChoice.Actions<String> {
        final GameModeChoice<String> choice = new GameModeChoice<>();
        boolean active = true;
        int owner;
        int window;
        int roots, apks, cancels;
        GameModeChoice.Source<String> acted;
        @Override public void root(GameModeChoice.Request<String> request) { roots++; acted = request.source; }
        @Override public void apk(GameModeChoice.Request<String> request) { apks++; acted = request.source; }
        AlertDialog show() {
            final int windowOwner = owner;
            final int token = ++window;
            return GameModeChoiceDialog.show(new Activity(), choice, choice.pending(), () -> active && windowOwner == owner && token == window,
                    this, () -> cancels++, null);
        }
        void dismiss(AlertDialog dialog) { window++; dialog.dismiss(); }
    }

    public static void main(String[] ignored) {
        for (boolean files : new boolean[] { false, true }) {
            Host host = new Host();
            GameModeChoice.Source<String> source = files ? GameModeChoice.Source.files(List.of("content://base", "content://split"))
                    : GameModeChoice.Source.installed("game.already.bridged");
            GameModeChoice.Request<String> request = host.choice.offer(source);
            AlertDialog dialog = host.show();
            require(dialog.shown); require("选择存档访问方式".equals(dialog.title)); require(GameModeChoiceDialog.ACCESS_EXPLANATION.text().equals(dialog.message));
            require(new ArrayList<>(dialog.buttons.keySet()).equals(List.of("使用 Root", "修改 APK", "取消")));
            require(host.roots == 0 && host.apks == 0 && host.cancels == 0);
            dialog.click("使用 Root");
            require(host.roots == 1 && host.apks == 0 && host.acted == source);
            dialog.click("修改 APK"); dialog.click("使用 Root");
            require(host.roots == 1 && host.apks == 0); // Double/stale callbacks never choose twice.
            require(host.choice.retry(request));
            host.show().click("修改 APK");
            require(host.roots == 1 && host.apks == 1 && host.acted == source); // Retry preserves the SAF source.
            require(host.choice.complete(request)); require(host.choice.pending() == null);
            require(!host.choice.complete(request));

            host.choice.offer(source);
            host.show().click("取消");
            require(host.choice.pending() == null && host.cancels == 1 && host.roots == 1 && host.apks == 1);
            host.choice.offer(source);
            host.show().cancel(); // Back or tapping outside the dialog is cancellation, never an implicit mode.
            require(host.choice.pending() == null && host.cancels == 2 && host.roots == 1 && host.apks == 1);

            request = host.choice.offer(source);
            AlertDialog oldWindow = host.show();
            host.active = false; host.dismiss(oldWindow);
            oldWindow.click("使用 Root"); oldWindow.cancel();
            require(host.choice.awaiting(request) && host.roots == 1 && host.apks == 1 && host.cancels == 2);
            host.owner++; host.active = true;
            AlertDialog newWindow = host.show(); // A new foreground owner re-shows the same inert request.
            require(host.choice.awaiting(request) && host.roots == 1 && host.apks == 1);
            oldWindow.click("使用 Root"); oldWindow.cancel();
            require(host.choice.awaiting(request) && host.roots == 1 && host.apks == 1 && host.cancels == 2);
            newWindow.click("修改 APK"); require(host.apks == 2 && host.acted == source);
            require(host.choice.complete(request));

            host.choice.offer(source); oldWindow = host.show();
            GameModeChoice.Request<String> newer = host.choice.offer(GameModeChoice.Source.installed("game.other"));
            oldWindow.cancel(); oldWindow.click("使用 Root");
            require(host.choice.awaiting(newer) && host.roots == 1 && host.apks == 2);
            AlertDialog finalWindow = host.show(); host.choice.discard(); finalWindow.click("修改 APK");
            require(host.choice.pending() == null && host.apks == 2);
        }
        List<String> original = new ArrayList<>(List.of("content://base"));
        GameModeChoice.Source<String> copy = GameModeChoice.Source.files(original);
        original.clear(); require(copy.apks.equals(List.of("content://base")));
        try { copy.apks.clear(); throw new AssertionError("Source must be immutable"); }
        catch (UnsupportedOperationException expected) { checks++; }

        Host failedPreparation = new Host();
        GameModeChoice.Request<String> files = failedPreparation.choice.offer(copy);
        failedPreparation.show().click("修改 APK");
        require(failedPreparation.choice.complete(files));
        require(failedPreparation.apks == 1 && failedPreparation.roots == 0);
        GameModeChoice.Request<String> retry = failedPreparation.choice.offer(failedPreparation.acted);
        AlertDialog retryDialog = failedPreparation.show(); // A failed prepare reoffers exactly the original SAF source.
        require(failedPreparation.choice.awaiting(retry) && failedPreparation.apks == 1 && failedPreparation.roots == 0);
        retryDialog.click("使用 Root");
        require(failedPreparation.roots == 1 && failedPreparation.acted == copy);

        Host background = new Host();
        GameModeChoice.Request<String> paused = background.choice.offer(copy);
        AlertDialog dismissed = background.show();
        background.active = false; background.dismiss(dismissed);
        background.active = true;
        dismissed.click("使用 Root"); dismissed.cancel();
        require(background.choice.awaiting(paused) && background.roots == 0 && background.cancels == 0);
        AlertDialog resumed = background.show();
        dismissed.click("修改 APK"); require(background.apks == 0 && background.choice.awaiting(paused));
        resumed.click("修改 APK"); require(background.apks == 1 && background.acted == copy);
        Host denied = new Host();
        denied.choice.offer(GameModeChoice.Source.installed("game.example"));
        AlertDialog explanation = GameModeChoiceDialog.show(new Activity(), denied.choice, denied.choice.pending(),
                () -> true, denied, () -> denied.cancels++, "未获得 Root 权限。");
        require(explanation.message.equals(GameModeChoiceDialog.ACCESS_EXPLANATION.text() + "\n\n未获得 Root 权限。"));
        require(denied.roots == 0 && denied.apks == 0);
        explanation.click("取消"); require(denied.cancels == 1 && denied.choice.pending() == null);
        System.out.println("GameModeChoiceDialogChecks: " + checks + " passed (real adapter listeners; no device UI)");
    }
}

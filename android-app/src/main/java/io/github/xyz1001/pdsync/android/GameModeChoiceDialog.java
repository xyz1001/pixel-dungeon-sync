package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.function.BooleanSupplier;

/** Native dialog adapter; dismissal never implies Root, modification or a persistent selection. */
final class GameModeChoiceDialog {
    static final pdsync.i18n.UiText ACCESS_EXPLANATION = AndroidMessages.GAME_MODE_CHOICE_DIALOG_01;

    private GameModeChoiceDialog() { }

    static <T> AlertDialog show(Activity activity, GameModeChoice<T> choice,
            GameModeChoice.Request<T> request, BooleanSupplier active,
            GameModeChoice.Actions<T> actions, Runnable cancelled, String message) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(AndroidMessages.GAME_MODE_CHOICE_DIALOG_02.text())
                .setMessage(message == null || message.isEmpty() ? ACCESS_EXPLANATION.text()
                        : ACCESS_EXPLANATION.text() + "\n\n" + message)
                .setPositiveButton(AndroidMessages.GAME_MODE_CHOICE_DIALOG_03.text(), (ignored, which) -> {
                    if (active.getAsBoolean()) choice.dispatch(request, GameModeChoice.Mode.ROOT, actions);
                })
                .setNegativeButton(AndroidMessages.GAME_MODE_CHOICE_DIALOG_04.text(), (ignored, which) -> {
                    if (active.getAsBoolean()) choice.dispatch(request, GameModeChoice.Mode.APK, actions);
                })
                .setNeutralButton(AndroidMessages.GAME_MODE_CHOICE_DIALOG_05.text(), (ignored, which) -> {
                    if (active.getAsBoolean() && choice.cancel(request)) cancelled.run();
                })
                .setOnCancelListener(ignored -> {
                    if (active.getAsBoolean() && choice.cancel(request)) cancelled.run();
                })
                .create();
        dialog.show();
        return dialog;
    }
}

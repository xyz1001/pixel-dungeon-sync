package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Minimal appreciation page; local usernames are explicitly distinct from authenticated metadata. */
final class ManagedAccountPanel {
    private ManagedAccountPanel() { }

    static void render(Activity activity, LinearLayout body, LinearLayout footer,
                       ManagedAccountFlow flow, Runnable refresh, Runnable back) {
        ManagedAccountFlow.View view = flow.view();
        if (view.image != null) image(activity, body, view.image);
        if (view.localUsername != null) {
            label(activity, body, view.entitlementLoaded ? AndroidMessages.MANAGED_ACCOUNT_PANEL_01.text() + view.username
                    : AndroidMessages.MANAGED_ACCOUNT_PANEL_02.text() + view.localUsername);
            button(activity, body, AndroidMessages.MANAGED_ACCOUNT_PANEL_03.text(), () -> flow.copyUsername(view.generation, username -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(AndroidMessages.MANAGED_ACCOUNT_PANEL_04.text(), username));
            }));
            label(activity, body, AndroidMessages.MANUAL_PAYMENT_COPY_01.text());
        }
        label(activity, body, ManagedAccountFlow.expiry(view));
        String accessStatus = ManagedAccountFlow.accessStatus(view);
        if (!accessStatus.isEmpty()) label(activity, body, accessStatus);
        if (!view.message.isEmpty()) label(activity, body, view.message);
        if (view.busy) body.addView(new ProgressBar(activity));
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(actions);
        button(activity, actions, AndroidMessages.MANAGED_ACCOUNT_PANEL_05.text(), back);
        button(activity, actions, AndroidMessages.MANAGED_ACCOUNT_PANEL_06.text(), refresh).setEnabled(view.canRefresh);
    }

    private static void image(Activity activity, LinearLayout body, byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 2048 || bounds.outHeight > 2048
                || (long) bounds.outWidth * bounds.outHeight > 2_000_000) {
            label(activity, body, AndroidMessages.MANAGED_ACCOUNT_PANEL_07.text());
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) { label(activity, body, AndroidMessages.MANAGED_ACCOUNT_PANEL_07.text()); return; }
        ImageView image = new ImageView(activity);
        image.setContentDescription(AndroidMessages.MANAGED_ACCOUNT_PANEL_08.text());
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(image, new LinearLayout.LayoutParams(-1, dp(activity, 240)));
    }

    private static void label(Activity activity, LinearLayout parent, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        parent.addView(view);
    }
    private static Button button(Activity activity, LinearLayout parent, String text, Runnable action) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }
    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

package io.github.xyz1001.pdsync.android;

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

/** A small, explicitly sandbox-labelled child of the existing official connection page. */
final class ManualPaymentPanel {
    private ManualPaymentPanel() { }

    static void render(Activity activity, LinearLayout body, LinearLayout footer,
                       ManualPaymentSession session, String backLabel, Runnable back, Runnable next) {
        ManualPaymentFlow.View view = session.view();
        if (!view.sandbox) throw new IllegalArgumentException("Sandbox session required");
        label(activity, body, "沙箱", 14);
        if (!view.message.isEmpty()) label(activity, body, view.message, 16);
        byte[] png = session.paymentImage();
        if (png != null) showImage(activity, body, png);
        else if (!session.imageMessage().isEmpty()) label(activity, body, session.imageMessage(), 14);
        String username = session.authenticatedUsername();
        if (username != null) {
            label(activity, body, "用户名 " + username, 16);
            button(activity, body, "复制用户名", session.usernameCopyAction(currentUsername -> {
                ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("用户名", currentUsername));
            }));
            label(activity, body, ManualPaymentCopy.usernameRemark(), 14);
        }
        if (view.busy) body.addView(new ProgressBar(activity));

        label(activity, body, ManualPaymentCopy.expiry(view.entitlement), 14);
        if (view.canTestDav && !view.canContinue)
            button(activity, body, "测试连接", session::testDav).setEnabled(!view.busy);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(row);
        button(activity, row, backLabel, back);
        button(activity, row, "刷新", session::refresh).setEnabled(!view.busy);
        button(activity, row, "下一步", () -> { if (session.canContinue()) next.run(); }).setEnabled(session.canContinue());
    }

    private static void showImage(Activity activity, LinearLayout body, byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 2048 || bounds.outHeight > 2048
                || (long) bounds.outWidth * bounds.outHeight > 2_000_000) {
            label(activity, body, "赞赏码无法显示，请刷新。", 14);
            return;
        }
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (image == null) { label(activity, body, "赞赏码无法显示，请刷新。", 14); return; }
        ImageView view = new ImageView(activity);
        view.setContentDescription("沙箱赞赏码");
        view.setImageBitmap(image);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(view, new LinearLayout.LayoutParams(-1, dp(activity, 220)));
    }
    private static void label(Activity activity, LinearLayout body, String text, int size) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        body.addView(view);
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

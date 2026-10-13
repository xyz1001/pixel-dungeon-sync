package io.github.xyz1001.pdsync.android;

import android.app.Activity;
import android.app.AlertDialog;
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
        label(activity, body, view.message, 16);
        if (view.busy) body.addView(new ProgressBar(activity));

        ManualOrderClient.Order order = view.order;
        if (order == null || order.state == ManualOrderClient.State.CANCELLED
                || order.state == ManualOrderClient.State.REJECTED || order.state == ManualOrderClient.State.APPROVED) {
            if (!view.canContinue) {
                for (ManualOrderClient.Plan plan : view.plans) {
                    Button choice = button(activity, body,
                            (plan.id.equals(view.selectedPlanId) ? "✓ " : "") + plan.displayName + "\n"
                                    + ManualPaymentCopy.amount(plan.amountMinor, plan.currency) + " · "
                                    + ManualPaymentCopy.term(plan.term, plan.days),
                            () -> session.selectPlan(plan.id));
                    choice.setEnabled(!view.busy && !view.outcomeUnknown);
                }
                button(activity, body, view.outcomeUnknown ? "重试创建" : renewal(view) ? "续费" : "购买",
                        session::create).setEnabled(view.canCreate);
            }
        }
        if (order != null) {
            label(activity, body, ManualPaymentCopy.amount(order.amountMinor, order.currency)
                    + " · " + ManualPaymentCopy.term(order.term, order.days), 18);
            if (order.state == ManualOrderClient.State.CREATED) {
                // The code and immutable amount are displayed only after a durable server order exists.
                label(activity, body, "备注 " + order.remarkCode, 24);
                if (view.paymentDisplayMatches && view.selectedPlan != null) {
                    label(activity, body, view.selectedPlan.paymentInstructions, 16);
                    byte[] png = session.paymentImage();
                    if (png != null) showImage(activity, body, png);
                    else if (!session.imageMessage().isEmpty()) label(activity, body, session.imageMessage(), 14);
                } else label(activity, body, "付款信息需要更新，请刷新。", 16);
                button(activity, body, "我已付款", session::submit).setEnabled(view.canSubmit && view.paymentDisplayMatches);
            }
        }
        if (view.canCancel && order != null) button(activity, body, "取消订单", () -> {
            Runnable confirmation = session.cancellationConfirmation();
            new AlertDialog.Builder(activity).setMessage("取消该订单？")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("取消订单", (dialog, which) -> confirmation.run()).show();
        });
        if (view.canTestDav && !view.canContinue)
            button(activity, body, "测试连接", session::testDav).setEnabled(!view.busy);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(row);
        button(activity, row, backLabel, back);
        button(activity, row, "刷新", session::refresh).setEnabled(!view.busy);
        button(activity, row, "下一步", () -> { if (session.canContinue()) next.run(); }).setEnabled(session.canContinue());
    }

    private static boolean renewal(ManualPaymentFlow.View view) {
        return view.entitlement != null && view.entitlement.expiresMs != null;
    }
    private static void showImage(Activity activity, LinearLayout body, byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 2048 || bounds.outHeight > 2048
                || (long) bounds.outWidth * bounds.outHeight > 2_000_000) {
            label(activity, body, "收款图无法显示，请刷新。", 14);
            return;
        }
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (image == null) { label(activity, body, "收款图无法显示，请刷新。", 14); return; }
        ImageView view = new ImageView(activity);
        view.setContentDescription("沙箱收款图");
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

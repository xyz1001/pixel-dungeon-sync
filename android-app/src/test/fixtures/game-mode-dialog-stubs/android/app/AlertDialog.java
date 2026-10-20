package android.app;

import android.content.DialogInterface;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes the production adapter's actual listeners; it does not simulate Android window drawing. */
public final class AlertDialog implements DialogInterface {
    public String title, message;
    public boolean shown;
    public final Map<String, OnClickListener> buttons = new LinkedHashMap<>();
    private OnCancelListener cancel;
    public void show() { shown = true; }
    public void dismiss() { shown = false; }
    public void click(String label) { buttons.get(label).onClick(this, 0); dismiss(); }
    public void cancel() { cancel.onCancel(this); dismiss(); }

    public static final class Builder {
        private final AlertDialog dialog = new AlertDialog();
        public Builder(Activity activity) { }
        public Builder setTitle(String title) { dialog.title = title; return this; }
        public Builder setMessage(String message) { dialog.message = message; return this; }
        public Builder setPositiveButton(String label, OnClickListener listener) { dialog.buttons.put(label, listener); return this; }
        public Builder setNegativeButton(String label, OnClickListener listener) { dialog.buttons.put(label, listener); return this; }
        public Builder setNeutralButton(String label, OnClickListener listener) { dialog.buttons.put(label, listener); return this; }
        public Builder setOnCancelListener(OnCancelListener listener) { dialog.cancel = listener; return this; }
        public AlertDialog create() { return dialog; }
    }
}

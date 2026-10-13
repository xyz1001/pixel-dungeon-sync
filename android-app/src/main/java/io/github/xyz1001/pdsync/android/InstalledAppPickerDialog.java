package io.github.xyz1001.pdsync.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One local, searchable picker shared by Root and embedded-bridge setup. */
final class InstalledAppPickerDialog {
    private InstalledAppPickerDialog() { }

    static void show(Activity activity, String title, List<InstalledAppPickerModel.Entry> candidates,
            String selectedPackage, InstalledAppPickerModel.State state, Consumer<String> select) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 16);
        content.setPadding(padding, 0, padding, 0);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_FILTER);
        search.setHint("搜索名称或包名");
        search.setText(state.query());
        search.setSelection(search.length());
        content.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        CheckBox showAll = new CheckBox(activity);
        showAll.setText("显示全部应用");
        showAll.setChecked(state.showAll());
        content.addView(showAll);
        TextView empty = new TextView(activity);
        empty.setText("没有匹配的应用");
        empty.setPadding(padding, padding, padding, padding);
        content.addView(empty);
        ListView list = new ListView(activity);
        // Keep search and Cancel reachable on compact screens; the results scroll separately.
        int listHeight = Math.min(dp(activity, 320), activity.getResources().getDisplayMetrics().heightPixels / 3);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(activity, 100), listHeight)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(adapter);
        List<InstalledAppPickerModel.Entry> visible = new ArrayList<>();
        Runnable refresh = () -> {
            visible.clear();
            visible.addAll(state.visible(candidates, selectedPackage));
            adapter.setNotifyOnChange(false);
            adapter.clear();
            for (InstalledAppPickerModel.Entry item : visible) {
                String selected = item.packageName.equals(selectedPackage) ? "已选择 · " : "";
                String version = item.versionName.isEmpty() ? "" : " " + item.versionName;
                adapter.add(selected + item.label + version + "\n" + item.packageName);
            }
            adapter.notifyDataSetChanged();
            // A pinned current selection is not a search match. Keep both it and the
            // empty-result hint visible when the query matches no selectable candidate.
            empty.setVisibility(state.visible(candidates, null).isEmpty() ? View.VISIBLE : View.GONE);
            list.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                state.setQuery(text.toString()); refresh.run();
            }
            public void afterTextChanged(Editable text) { }
        });
        showAll.setOnCheckedChangeListener((button, checked) -> {
            state.setShowAll(checked); refresh.run();
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(content)
                .setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            String packageName = visible.get(position).packageName;
            dialog.dismiss(); select.accept(packageName);
        });
        refresh.run();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        });
        dialog.show();
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

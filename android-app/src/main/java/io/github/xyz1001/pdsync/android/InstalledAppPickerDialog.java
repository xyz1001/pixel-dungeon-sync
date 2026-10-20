package io.github.xyz1001.pdsync.android;

import androidx.activity.ComponentActivity;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One local, searchable M3 picker shared by Root and embedded-bridge setup. */
final class InstalledAppPickerDialog {
    private InstalledAppPickerDialog() { }

    static void show(ComponentActivity activity, String title, List<InstalledAppPickerModel.Entry> candidates,
            String selectedPackage, InstalledAppPickerModel.State state, Consumer<String> select) {
        show(activity, title, candidates, selectedPackage, state, () -> !activity.isDestroyed(), select, null);
    }

    static void show(ComponentActivity activity, String title, List<InstalledAppPickerModel.Entry> candidates,
            String selectedPackage, InstalledAppPickerModel.State state, BooleanSupplier active,
            Consumer<String> select, Runnable chooseFile) {
        AndroidInstalledAppPickerComposition.show(activity, candidates, selectedPackage, state, active, select, chooseFile);
    }
}

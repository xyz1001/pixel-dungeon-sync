package io.github.xyz1001.pdsync.android;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.xyz1001.pdsync.android.ConnectionEntryModel.Back;
import io.github.xyz1001.pdsync.android.ConnectionEntryModel.Mode;
import io.github.xyz1001.pdsync.android.ConnectionEntryModel.Origin;

/** Host-only checks run the production model, without Android or any service/store. */
public final class ConnectionEntryModelChecks {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void invalid(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static ConnectionEntryModel setup() {
        return new ConnectionEntryModel(Origin.SETUP, null);
    }

    private static ConnectionEntryModel rotate(ConnectionEntryModel model) {
        // Mirrors plain enum-name Bundle values, with no credential or proof payload.
        return ConnectionEntryModel.restore(Origin.valueOf(model.origin().name()),
                Mode.valueOf(model.mode().name()), model.selectedService() == null
                        ? null : Mode.valueOf(model.selectedService().name()));
    }

    private static void firstTimeBackNavigation() {
        ConnectionEntryModel model = setup();
        check(model.origin() == Origin.SETUP, "New setup retains its origin");
        check(model.mode() == Mode.CHOICE, "First-time entry shows the two-service chooser");
        check(model.selectedService() == null, "No provider is inferred before first selection");
        check(model.back(false) == Back.PATH, "Initial chooser Back returns to PATH");
        check(model.choose(Mode.CUSTOM, false), "First custom choice reports a new source");
        check(model.mode() == Mode.CUSTOM, "Custom choice opens the endpoint/user/password form");
        check(model.back(false) == Back.CHOICE, "New custom form Back returns to the chooser");
        check(model.mode() == Mode.CHOICE, "Back changes visible mode to CHOICE");
        check(model.selectedService() == Mode.CUSTOM, "Back retains the last selected source");
        check(model.back(false) == Back.PATH, "Second Back returns to PATH");
        check(model.choose(Mode.OFFICIAL, false), "Changing from custom to official is a source change");
        check(model.mode() == Mode.OFFICIAL, "Official choice opens its account form");
        check(model.back(false) == Back.CHOICE, "New official form Back returns to the chooser");
        check(model.back(false) == Back.PATH, "Official flow also returns to PATH after chooser");
    }

    private static void settingsBackNavigation() {
        ConnectionEntryModel legacy = new ConnectionEntryModel(Origin.SETTINGS, null);
        check(legacy.mode() == Mode.CUSTOM, "Legacy provider-less connection opens its current custom form");
        check(legacy.selectedService() == Mode.CUSTOM, "Legacy default requires no endpoint inference");
        check(legacy.back(false) == Back.SETTINGS, "Existing form Back returns directly to Settings");
        check(legacy.mode() == Mode.CUSTOM, "Leaving the flow does not mutate its selected service");

        for (Mode existing : new Mode[] { Mode.CUSTOM, Mode.OFFICIAL }) {
            ConnectionEntryModel model = new ConnectionEntryModel(Origin.SETTINGS, existing);
            check(model.mode() == existing, "Explicit existing mode opens its own form");
            check(model.showChooser(false), "Change service explicitly opens the chooser");
            check(model.selectedService() == existing, "Opening chooser does not switch saved service");
            check(model.back(false) == Back.SETTINGS, "Existing chooser Back returns to original Settings");
            Mode other = existing == Mode.CUSTOM ? Mode.OFFICIAL : Mode.CUSTOM;
            check(model.choose(other, false), "Selecting the other service reports a source switch");
            check(model.back(false) == Back.SETTINGS, "Replacement form still returns to original Settings");
            check(model.origin() == Origin.SETTINGS, "Changing service never changes navigation origin");
        }
    }

    private static void repeatedSelectionsAndProofSignal() {
        ConnectionEntryModel model = setup();
        check(!model.showChooser(false), "Showing the initial chooser again is a no-op");
        for (Mode service : new Mode[] { Mode.CUSTOM, Mode.OFFICIAL, Mode.CUSTOM }) {
            check(model.choose(service, false), "Every actual source switch requests proof invalidation");
            check(!model.choose(service, false), "Repeated same-form selection preserves proof");
            check(model.showChooser(false), "Explicit change service opens chooser");
            check(!model.showChooser(false), "Repeated chooser display is a no-op");
            check(model.choose(service, false), "Reopening the same source from chooser invalidates earlier proof");
            check(model.mode() == service, "Same-source choice still opens the form");
            check(model.back(false) == Back.CHOICE, "Setup Back reopens chooser");
            check(model.choose(service, false), "Reopening a form after Back invalidates earlier proof");
            check(!model.choose(service, false), "Repeating the now-visible form preserves its proof");
        }
    }

    private static void busyNavigationIsInert() {
        for (Origin origin : Origin.values()) {
            for (Mode visible : Mode.values()) {
                ConnectionEntryModel model = new ConnectionEntryModel(origin, null);
                if (visible != Mode.CHOICE) model.choose(visible, false);
                else model.showChooser(false);
                Mode selected = model.selectedService();
                for (Mode target : new Mode[] { Mode.CUSTOM, Mode.OFFICIAL }) {
                    check(!model.choose(target, true), "Busy choose reports no source switch");
                    check(model.mode() == visible && model.selectedService() == selected,
                            "Busy choose changes no state");
                }
                check(!model.showChooser(true), "Busy change-service is rejected");
                check(model.back(true) == Back.STAY, "Busy Back stays in place");
                check(model.mode() == visible && model.selectedService() == selected,
                        "Busy Back/change-service preserve both mode and selection");
                check(model.origin() == origin, "Busy events preserve origin");
            }
        }
    }

    private static void rotationAndValidation() {
        ConnectionEntryModel first = rotate(setup());
        check(first.mode() == Mode.CHOICE && first.selectedService() == null,
                "Unselected first-time chooser survives rotation");
        check(first.back(false) == Back.PATH, "Rotated unselected chooser returns to PATH");
        for (Origin origin : Origin.values()) {
            for (Mode service : new Mode[] { Mode.CUSTOM, Mode.OFFICIAL }) {
                ConnectionEntryModel model = new ConnectionEntryModel(origin, null);
                model.choose(service, false);
                ConnectionEntryModel restoredForm = rotate(model);
                check(restoredForm != model, "Rotation creates independent presentation state");
                check(restoredForm.mode() == service && restoredForm.selectedService() == service,
                        "Rotation retains form and selected service");
                check(restoredForm.origin() == origin, "Rotation retains the original return destination");
                check(!restoredForm.choose(service, false), "Rotation does not turn a repeat choice into a switch");
                model.showChooser(false);
                ConnectionEntryModel restoredChooser = rotate(model);
                check(restoredChooser.mode() == Mode.CHOICE && restoredChooser.selectedService() == service,
                        "Chooser rotation retains the last source without opening its form");
                check(restoredChooser.choose(service, false), "Choice after chooser rotation is still a visible mode change");
                check(restoredChooser.back(false) == (origin == Origin.SETUP ? Back.CHOICE : Back.SETTINGS),
                        "Rotated form follows its original Back behavior");
                check(restoredForm.mode() == service, "Restored state is independent of the earlier instance");
            }
        }

        invalid(() -> new ConnectionEntryModel(null, null), "Origin is required");
        invalid(() -> new ConnectionEntryModel(Origin.SETUP, Mode.CUSTOM), "New setup cannot claim an existing provider");
        invalid(() -> new ConnectionEntryModel(Origin.SETTINGS, Mode.CHOICE), "Existing service cannot be CHOICE");
        invalid(() -> first.choose(null, false), "Null is not a choice");
        invalid(() -> first.choose(Mode.CHOICE, false), "The chooser is not a service");
        invalid(() -> ConnectionEntryModel.restore(null, Mode.CHOICE, null), "Restored origin is required");
        invalid(() -> ConnectionEntryModel.restore(Origin.SETUP, null, null), "Restored mode is required");
        invalid(() -> ConnectionEntryModel.restore(Origin.SETUP, Mode.CHOICE, Mode.CHOICE), "Retained source cannot be CHOICE");
        invalid(() -> ConnectionEntryModel.restore(Origin.SETTINGS, Mode.CHOICE, null), "Settings cannot lose its source");
        invalid(() -> ConnectionEntryModel.restore(Origin.SETUP, Mode.CUSTOM, null), "A form must have a selected source");
        invalid(() -> ConnectionEntryModel.restore(Origin.SETUP, Mode.CUSTOM, Mode.OFFICIAL), "A form cannot mismatch its source");
        check(first.mode() == Mode.CHOICE && first.selectedService() == null, "Invalid selections preserve earlier state");
    }

    private static void secretsDraftsAndPersistenceRemainExternal() {
        // The only state is three enum values. No object can carry credentials, drafts,
        // saved profiles, auth tokens, persistence callbacks, a network client or Context.
        check(Modifier.isFinal(ConnectionEntryModel.class.getModifiers()), "No subclass can add model-side effects");
        check(ConnectionEntryModel.class.getDeclaredFields().length == 3, "Model has only three non-secret UI fields");
        for (Field field : ConnectionEntryModel.class.getDeclaredFields()) {
            check(field.getType() == Mode.class || field.getType() == Origin.class,
                    "Only navigation enums are stored: " + field.getName());
            check(Modifier.isPrivate(field.getModifiers()), "Model fields are private: " + field.getName());
        }
        for (Constructor<?> constructor : ConnectionEntryModel.class.getDeclaredConstructors()) {
            for (Class<?> type : constructor.getParameterTypes()) {
                check(type == Mode.class || type == Origin.class, "Constructor cannot accept a store, client or credentials");
            }
        }
        for (Method method : ConnectionEntryModel.class.getDeclaredMethods()) {
            for (Class<?> type : method.getParameterTypes()) {
                check(type == Mode.class || type == Origin.class || type == boolean.class,
                        "Navigation API cannot accept credentials, drafts, I/O or persistence: " + method.getName());
            }
        }
    }

    public static void main(String[] args) {
        firstTimeBackNavigation();
        settingsBackNavigation();
        repeatedSelectionsAndProofSignal();
        busyNavigationIsInert();
        rotationAndValidation();
        secretsDraftsAndPersistenceRemainExternal();
        System.out.println("Connection-entry model checks passed: " + checks
                + " assertions; host-only navigation, no Android SDK, network, persistence or device execution.");
    }
}

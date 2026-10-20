package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Memory-only source choice. A source is inert until one current dialog button dispatches it. */
final class GameModeChoice<T> {
    enum Mode { ROOT, APK }

    static final class Source<T> {
        final String installedPackage;
        final List<T> apks;

        private Source(String installedPackage, List<T> apks) {
            this.installedPackage = installedPackage;
            this.apks = apks;
        }

        static <T> Source<T> installed(String packageName) {
            if (packageName == null || packageName.isEmpty()) throw new IllegalArgumentException(AndroidMessages.GAME_MODE_CHOICE_01.text());
            return new Source<>(packageName, null);
        }

        static <T> Source<T> files(List<T> apks) {
            if (apks == null || apks.isEmpty()) throw new IllegalArgumentException(AndroidMessages.GAME_MODE_CHOICE_02.text());
            List<T> copy = new ArrayList<>(apks);
            for (T apk : copy) Objects.requireNonNull(apk, "Missing APK URI");
            return new Source<>(null, Collections.unmodifiableList(copy));
        }
    }

    static final class Request<T> {
        final Source<T> source;
        private boolean dispatched;
        private Request(Source<T> source) { this.source = source; }
    }

    interface Actions<T> {
        void root(Request<T> request);
        void apk(Request<T> request);
    }

    private Request<T> pending;

    Request<T> offer(Source<T> source) { pending = new Request<>(Objects.requireNonNull(source)); return pending; }
    Request<T> pending() { return pending; }
    boolean awaiting(Request<T> request) { return request != null && pending == request && !request.dispatched; }

    boolean dispatch(Request<T> request, Mode mode, Actions<T> actions) {
        if (!awaiting(request)) return false;
        request.dispatched = true;
        if (mode == Mode.ROOT) actions.root(request); else actions.apk(request);
        return true;
    }

    boolean retry(Request<T> request) {
        if (pending != request) return false;
        request.dispatched = false;
        return true;
    }

    boolean complete(Request<T> request) {
        if (request == null || pending != request || !request.dispatched) return false;
        pending = null;
        return true;
    }

    boolean cancel(Request<T> request) {
        if (!awaiting(request)) return false;
        pending = null;
        return true;
    }

    void discard() { pending = null; }
}

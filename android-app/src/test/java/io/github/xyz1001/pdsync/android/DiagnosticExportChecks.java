package io.github.xyz1001.pdsync.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Executable fake-provider tests. No Android device, network or real user files. */
public final class DiagnosticExportChecks {
    private static int checks;
    private static final byte[] ZIP = "synthetic-bounded-diagnostic-zip".getBytes(StandardCharsets.UTF_8);
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Provider implements DiagnosticExportWriter.Destination {
        final Map<String, byte[]> files = new HashMap<>();
        final String mode;
        String name;
        int creates, deletes, writes;
        Provider(String mode) { this.mode = mode; files.put("existing.zip", new byte[]{42}); }
        public void createNew(String filename) throws IOException {
            creates++;
            if (mode.equals("readonly")) throw new IOException("arbitrary-provider-sensitive-message");
            if (files.containsKey(filename)) throw new IOException("conflict");
            name = filename;
            files.put(name, new byte[0]);
        }
        public OutputStream openWrite() throws IOException {
            writes++;
            if (mode.equals("nullwrite")) return null;
            if (mode.equals("openfail")) throw new IOException("provider unavailable");
            return new ByteArrayOutputStream() {
                @Override public void write(byte[] input) throws IOException {
                    if (mode.equals("diskfull")) { super.write(input, 0, 3); throw new IOException("disk full"); }
                    super.write(input);
                }
                @Override public void close() throws IOException {
                    byte[] data = toByteArray();
                    if (mode.equals("truncated")) data = Arrays.copyOf(data, 3);
                    if (mode.equals("corrupt")) data[0] ^= 1;
                    if (mode.equals("trailing")) data = Arrays.copyOf(data, data.length + 1);
                    files.put(name, data);
                    if (mode.equals("closefail")) throw new IOException("provider close failed");
                }
            };
        }
        public InputStream openRead() throws IOException {
            if (mode.equals("nullread")) return null;
            if (mode.equals("readfail") || mode.equals("cleanupfail")) throw new IOException("provider read unavailable");
            return new ByteArrayInputStream(files.get(name));
        }
        public void removeCreated() throws IOException {
            deletes++;
            if (mode.equals("cleanupfail")) throw new IOException("provider deletion unavailable");
            files.remove(name);
        }
    }
    public static void main(String[] args) throws Exception {
        Provider ok = new Provider("ok");
        DiagnosticExportWriter.write(ZIP, "new.zip", ok);
        check(Arrays.equals(ZIP, ok.files.get("new.zip")), "all bytes verified");
        check(ok.deletes == 0, "successful export retained");
        check(Arrays.equals(new byte[]{42}, ok.files.get("existing.zip")), "old export unchanged");
        for (String mode : new String[]{"readonly", "nullwrite", "openfail", "diskfull", "truncated", "corrupt", "trailing", "closefail", "nullread", "readfail", "cleanupfail"}) {
            Provider bad = new Provider(mode);
            try { DiagnosticExportWriter.write(ZIP, "new.zip", bad); throw new AssertionError(mode + " must fail"); }
            catch (IOException expected) { check(true, mode + " reported"); }
            check(Arrays.equals(new byte[]{42}, bad.files.get("existing.zip")), mode + " preserves old logs");
            check(bad.deletes == (mode.equals("readonly") ? 0 : 1), mode + " cleanup own attempt only");
            if (!mode.equals("cleanupfail")) check(!bad.files.containsKey("new.zip"), mode + " no partial output");
        }
        Provider conflict = new Provider("ok");
        try { DiagnosticExportWriter.write(ZIP, "existing.zip", conflict); throw new AssertionError("conflict"); }
        catch (IOException expected) { check(conflict.deletes == 0 && conflict.writes == 0, "collision never overwritten or deleted"); }
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[DiagnosticExportWriter.MAX_SNAPSHOT_BYTES + 1]}) {
            Provider untouched = new Provider("ok");
            try { DiagnosticExportWriter.write(invalid, "new.zip", untouched); throw new AssertionError("bounded snapshot"); }
            catch (IOException expected) { check(untouched.creates == 0, "reject invalid snapshot before touching provider"); }
        }
        DiagnosticExportState state = new DiagnosticExportState();
        check(state.request() != 0, "initial request accepted");
        check(state.request() == 0, "repeated picker tap ignored");
        check(state.cancelSelection(), "cancel accepted");
        check(!state.busy() && state.selected() == 0, "cancellation does not export");
        check(!state.cancelSelection(), "duplicate cancellation ignored");
        state.restorePicker(true);
        check(!state.busy(), "stale pre-rotation saved picker state cannot revive a completed picker");
        long first = state.request();
        check(state.selected() == first, "one picker result enters write");
        check(state.selected() == 0 && state.request() == 0, "duplicate result and repeated write tap ignored");
        state.restorePicker(true);
        check(!state.selecting() && state.busy(), "rotation cannot restart an ongoing write");
        check(!state.finish(first + 1), "stale completion ignored");
        check(state.finish(first), "matching completion releases button");
        long second = state.request();
        check(second != first && state.selected() == second, "next explicit export has new generation");
        check(!state.finish(first) && state.busy(), "old completion cannot finish newer request");
        state.finish(second);
        DiagnosticExportState recreatedPicker = new DiagnosticExportState();
        recreatedPicker.restorePicker(true);
        check(recreatedPicker.selecting() && recreatedPicker.selected() != 0, "pending picker can return after process recreation");
        DiagnosticExportState recreatedWrite = new DiagnosticExportState();
        recreatedWrite.restorePicker(false);
        check(!recreatedWrite.busy() && recreatedWrite.selected() == 0, "process death never replays a write or stale result");
        System.out.println("Diagnostic SAF export: " + checks + " host checks passed; no Android device or network used.");
    }
}

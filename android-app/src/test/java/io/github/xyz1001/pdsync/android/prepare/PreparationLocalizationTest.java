package io.github.xyz1001.pdsync.android.prepare;

import java.io.IOException;
import java.util.Locale;
import pdsync.i18n.PrepareMessages;
import pdsync.i18n.UiLocale;
import pdsync.i18n.UiText;

/** Host-only locale checks: no device, account, or APK execution. */
public final class PreparationLocalizationTest {
    public static void main(String[] args) throws Exception {
        try {
            for (PreparationDiagnostics.Stage stage : PreparationDiagnostics.Stage.values()) {
                PreparationDiagnostics.Failure failure = PreparationDiagnostics.failure(stage,
                        new IOException("Duplicate ZIP entry: private-file"));
                UiLocale.setLocale(Locale.SIMPLIFIED_CHINESE);
                String chinese = failure.userMessage();
                String raw = failure.getMessage();
                String diagnostic = PreparationDiagnostics.report(failure);
                check(chinese.contains("准备未完成") && chinese.contains(stage.code), "Chinese stage output");
                UiLocale.setLocale(Locale.US);
                String english = failure.userMessage();
                check(english.startsWith("Preparation did not finish:") && english.contains(stage.code), "English stage output");
                check(!english.matches(".*[\\u4e00-\\u9fff].*"), "No Chinese in English stage output");
                check(PreparationDiagnostics.isSafeUserMessage(chinese), "Old Chinese notice remains allowlisted");
                check(PreparationDiagnostics.isSafeUserMessage(english), "English notice is allowlisted");
                check(!PreparationDiagnostics.isSafeUserMessage(english + " private-file"), "Appended text rejected");
                check(!PreparationDiagnostics.isSafeUserMessage(english.replace(stage.code, "OTHER")), "Altered code rejected");
                check(raw.equals(failure.getMessage()), "Raw diagnostic label stable");
                check(diagnostic.equals(PreparationDiagnostics.report(failure)), "Diagnostic classification stable");
                check(!english.contains("private-file"), "Cause data absent from user message");
                UiLocale.setLocale(Locale.FRANCE);
                check(english.equals(failure.userMessage()), "Unsupported locale falls back to English");
                UiLocale.setLocale(Locale.TRADITIONAL_CHINESE);
                check(chinese.equals(failure.userMessage()), "Chinese variants select Chinese");
            }
            for (java.lang.reflect.Field field : PrepareMessages.class.getFields()) {
                UiText text = (UiText) field.get(null);
                check(!text.chinese().isBlank() && !text.english().isBlank(), "Paired resource: " + text.key());
            }
            check(!PreparationDiagnostics.isSafeUserMessage(null), "Null rejected");
            check(!PreparationDiagnostics.isSafeUserMessage(""), "Empty rejected");
            System.out.println("Preparation localization: 10 stages, bilingual allowlist, locale fallback, and diagnostic stability passed");
        } finally { UiLocale.useSystemLocale(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

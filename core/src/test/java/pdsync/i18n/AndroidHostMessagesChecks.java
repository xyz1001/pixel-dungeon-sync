package pdsync.i18n;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Standalone catalog check: javac UiLocale, UiText, AndroidHostMessages and this class. */
public final class AndroidHostMessagesChecks {
    public static void main(String[] args) throws Exception {
        Set<String> keys = new HashSet<>();
        for (var field : AndroidHostMessages.class.getFields()) {
            if (field.getType() != UiText.class) continue;
            UiText message = (UiText) field.get(null);
            require(keys.add(message.key()), "Duplicate key: " + message.key());
            UiLocale.setLocale(Locale.SIMPLIFIED_CHINESE);
            require(message.text().equals(message.chinese()), "Chinese: " + message.key());
            UiLocale.setLocale(Locale.forLanguageTag("zh-TW"));
            require(message.text().equals(message.chinese()), "Chinese region: " + message.key());
            for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ja-JP")}) {
                UiLocale.setLocale(locale);
                require(message.text().equals(message.english()), "English fallback: " + message.key());
            }
        }
        UiLocale.setLocale(Locale.ENGLISH);
        require(AndroidHostMessages.FILE_COUNT.text(3).equals("3 files"), "File count");
        require(AndroidHostMessages.CONFIRM_RUN_IDENTITY_FOR_272.text("slot-A").equals("Confirm run identity for slot-A"), "Run identity");
        require(AndroidHostMessages.GAME_APK_S_90.text(2, 42).contains("2 game APK(s), 42"), "APK parameters");
        require(AndroidHostMessages.isBriefNotice(AndroidHostMessages.GAME_LAUNCH_REQUESTED_AFTER_EXITING_NORMALLY_19.chinese()), "Chinese brief notice");
        require(AndroidHostMessages.isBriefNotice(AndroidHostMessages.GAME_LAUNCH_REQUESTED_AFTER_EXITING_NORMALLY_19.english()), "English brief notice, independent of length");
        require(!AndroidHostMessages.isBriefNotice(AndroidHostMessages.SELECT_A_GAME_0.english()), "Initial notice stays hidden");
        require(!AndroidHostMessages.isBriefNotice("Cloud records loaded."), "Routine history notice stays hidden");
        UiLocale.useSystemLocale();
        System.out.println("Android host localization checks passed: " + keys.size() + " resources");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

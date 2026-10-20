package pdsync.i18n;

import java.util.Locale;
import java.util.Objects;

/** Process presentation locale. Android supplies its current resource configuration. */
public final class UiLocale {
    private static volatile Locale override;
    private UiLocale() {}
    public static Locale locale() {
        Locale value = override;
        return value == null ? Locale.getDefault() : value;
    }
    public static boolean isChinese() { return "zh".equals(locale().getLanguage()); }
    public static Locale formattingLocale() { return isChinese() ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH; }
    public static void setLocale(Locale locale) { override = Objects.requireNonNull(locale); }
    public static void useSystemLocale() { override = null; }
}

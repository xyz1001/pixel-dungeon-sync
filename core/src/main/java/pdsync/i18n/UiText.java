package pdsync.i18n;

import java.util.*;
import java.util.regex.*;

/** An explicit, paired message resource. Original save/account/path data are only format arguments. */
public final class UiText {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([-#+ 0,(]*)(\\d*)(?:\\.(\\d+))?([a-zA-Z%])");
    private final String key;
    private final String chinese;
    private final String english;
    public UiText(String key, String chinese, String english) {
        this.key = Objects.requireNonNull(key);
        this.chinese = Objects.requireNonNull(chinese);
        this.english = Objects.requireNonNull(english);
        if (key.trim().isEmpty() || chinese.trim().isEmpty() || english.trim().isEmpty()) throw new IllegalArgumentException("Empty message resource: " + key);
        if (!signature(chinese).equals(signature(english))) throw new IllegalArgumentException("Mismatched message arguments: " + key);
    }
    public String key() { return key; }
    public String chinese() { return chinese; }
    public String english() { return english; }
    public String text(Object... arguments) {
        String template = UiLocale.isChinese() ? chinese : english;
        return arguments.length == 0 ? template : String.format(UiLocale.formattingLocale(), template, arguments);
    }
    private static List<String> signature(String template) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int implicit = 0;
        while (matcher.find()) {
            String type = matcher.group(5);
            if ("%".equals(type) || "n".equals(type)) continue;
            String index = matcher.group(1);
            result.add((index == null ? Integer.toString(++implicit) : index) + ":" + type.toLowerCase(Locale.ROOT));
        }
        Collections.sort(result);
        return result;
    }
}

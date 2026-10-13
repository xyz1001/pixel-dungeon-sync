// Host-only catalog boundary. Never package these fixtures into an APK.
package android.content;
import java.util.HashSet;
import java.util.Set;
public class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    private final String action;
    private final Set<String> categories = new HashSet<>();
    public Intent(String action) { this.action = action; }
    public Intent addCategory(String category) { categories.add(category); return this; }
    public String getAction() { return action; }
    public boolean hasCategory(String category) { return categories.contains(category); }
}

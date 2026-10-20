package android.content;
public final class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    public final String action;
    public Intent(String action) { this.action = action; }
    public Intent addCategory(String category) { return this; }
}

package android.content;

public class ContextWrapper extends Context {
    public ContextWrapper(Context base) {}

    @Override
    public Object getSystemService(String name) {
        return null;
    }
}

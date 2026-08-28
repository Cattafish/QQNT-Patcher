package android.app;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
public class Dialog {
    public Dialog(Context context) {}
    public Dialog(Context context, int themeResId) {}
    public void show() {}
    public void dismiss() {}
    public void setContentView(View view) {}
    public void setContentView(View view, ViewGroup.LayoutParams params) {}
    public Window getWindow() { return null; }
}

package android.app;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
public class Activity extends Context {
    public Intent getIntent() { return null; }
    public void finish() {}
    public void setContentView(View view, ViewGroup.LayoutParams params) {}
    public void startActivity(Intent intent) {}
    public void runOnUiThread(Runnable action) {}
}
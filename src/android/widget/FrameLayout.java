package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class FrameLayout extends ViewGroup {
    public FrameLayout(Context context) { super(context); }
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public int gravity;
        public LayoutParams(int width, int height) { super(width, height); }
        public LayoutParams(int width, int height, int gravity) { super(width, height); this.gravity = gravity; }
    }
}

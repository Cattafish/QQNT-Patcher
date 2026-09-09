package android.widget;

import android.content.Context;
import android.view.ViewGroup;

public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;
    public LinearLayout() {}
    public LinearLayout(Context context) {}
    public void setOrientation(int orientation) {}
    public void setGravity(int gravity) {}

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;
        public int gravity;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }
    }
}
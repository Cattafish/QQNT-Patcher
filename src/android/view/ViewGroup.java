package android.view;

import android.content.Context;

public class ViewGroup extends View {
    public ViewGroup() {}
    public ViewGroup(Context context) {}

    public int getChildCount() { return 0; }
    public View getChildAt(int index) { return null; }
    public int indexOfChild(View child) { return 0; }
    public void addView(View child) {}
    public void addView(View child, int index) {}
    public void addView(View child, LayoutParams params) {}
    public void addView(View child, int index, LayoutParams params) {}
    public void removeView(View view) {}

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width;
        public int height;
        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;

        public MarginLayoutParams(int width, int height) { super(width, height); }
        public MarginLayoutParams(LayoutParams source) { super(source.width, source.height); }
        public void setMargins(int left, int top, int right, int bottom) {}
    }
}

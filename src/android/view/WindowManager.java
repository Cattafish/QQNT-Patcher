package android.view;

public interface WindowManager {
    Display getDefaultDisplay();

    public static class LayoutParams extends ViewGroup.LayoutParams {
        public static final int FLAG_DIM_BEHIND = 2;
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width;
        public int height;
        public int gravity;

        public LayoutParams() {
            super(0, 0);
        }
    }
}

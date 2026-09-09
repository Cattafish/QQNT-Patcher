package android.widget;

import android.view.View;

public class PopupWindow {
    public PopupWindow() {}
    public PopupWindow(View contentView, int width, int height, boolean focusable) {}
    public void setOutsideTouchable(boolean touchable) {}
    public void setFocusable(boolean focusable) {}
    public void setElevation(float elevation) {}
    public boolean isShowing() { return false; }
    public void showAtLocation(View parent, int gravity, int x, int y) {}
    public void update(int x, int y, int width, int height) {}
    public void dismiss() {}
}
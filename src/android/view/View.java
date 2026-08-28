package android.view;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public View() {}
    public View(Context context) {}

    public void setVisibility(int visibility) {}
    public void setAlpha(float alpha) {}
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public int getLeft() { return 0; }
    public void setBackgroundColor(int color) {}
    public void setBackground(Drawable background) {}
    public void setPadding(int left, int top, int right, int bottom) {}
    public void setLayoutParams(ViewGroup.LayoutParams params) {}
    public void postInvalidateDelayed(long delayMilliseconds) {}
    protected void onDraw(Canvas canvas) {}

    public interface OnClickListener {
        void onClick(View v);
    }
    public void setOnClickListener(OnClickListener l) {}
}

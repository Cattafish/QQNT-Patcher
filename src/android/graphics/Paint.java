package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int color) {}
    public void setStyle(Style style) {}
    public void setShader(Shader shader) {}
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
}

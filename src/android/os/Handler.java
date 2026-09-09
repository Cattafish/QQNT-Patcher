package android.os;

public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { return false; }
    public boolean postDelayed(Runnable r, long delayMillis) { return false; }
}

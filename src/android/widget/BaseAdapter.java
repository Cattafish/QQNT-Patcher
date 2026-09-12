package android.widget;

public abstract class BaseAdapter {
    public abstract int getCount();
    public abstract Object getItem(int position);
    public abstract long getItemId(int position);
}

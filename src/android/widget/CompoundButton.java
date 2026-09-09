package android.widget;
import android.content.Context;
import android.view.View;
public class CompoundButton extends View {
    public CompoundButton() {}
    public CompoundButton(Context context) {}
    public void setChecked(boolean checked) {}
    public boolean isChecked() { return false; }
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {}
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton buttonView, boolean isChecked);
    }
}

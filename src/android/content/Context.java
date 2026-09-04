package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;

public class Context {
    public AssetManager getAssets() { return null; }
    public Resources getResources() { return null; }
    public ClassLoader getClassLoader() { return null; }
    public String getPackageName() { return null; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public void startActivity(Intent intent) {}
    public File getFilesDir() { return null; }
    public File getCodeCacheDir() { return null; }
    public File[] getExternalMediaDirs() { return null; }
    public Context getApplicationContext() { return this; }
}

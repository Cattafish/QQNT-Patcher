package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.qqnt.patch.ConfigManager;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FloatingBallManager {

    private static final String TAG = "QQ_DEBUG";
    private static final int BALL_TAG_ID = 0x7f099999;
    private static volatile boolean sRegistered = false;
    private static WeakReference<Activity> sCurrentActivity = null;

    public static volatile int sActiveChatType = 0;
    public static volatile String sActivePeerUin = "";
    public static volatile boolean sInAIO = false;

    private static int sLastX = -1;
    private static int sLastY = -1;

    public static void init(Context context) {
        if (sRegistered || context == null) return;
        sRegistered = true;

        try {
            Application app = (Application) context.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
                @Override
                public void onActivityStarted(Activity activity) {}
                @Override
                public void onActivityResumed(Activity activity) {
                    sCurrentActivity = new WeakReference<>(activity);
                    if (sInAIO) {
                        attachBallToActivity(activity);
                    }
                }
                @Override
                public void onActivityPaused(Activity activity) {
                    removeBallFromActivity(activity);
                }
                @Override
                public void onActivityStopped(Activity activity) {}
                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override
                public void onActivityDestroyed(Activity activity) {}
            });
            Log.i(TAG, "[FloatingBall] 生命周期注册成功");
        } catch (Throwable t) {
            Log.e(TAG, "[FloatingBall] 注册异常", t);
        }
    }

    public static Activity resolveCurrentActivity() {
        if (sCurrentActivity != null && sCurrentActivity.get() != null) {
            return sCurrentActivity.get();
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
            if (activities != null) {
                for (Object record : activities.values()) {
                    if (record == null) continue;
                    Class<?> recordClz = record.getClass();
                    Field pausedField = recordClz.getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        Field activityField = recordClz.getDeclaredField("activity");
                        activityField.setAccessible(true);
                        Activity act = (Activity) activityField.get(record);
                        if (act != null) {
                            sCurrentActivity = new WeakReference<>(act);
                            return act;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void onAIODelegateShow(Object delegate) {
        if (delegate == null) return;
        sInAIO = true;
        try {
            Method m = delegate.getClass().getMethod("getAIOContact");
            Object contact = m.invoke(delegate);
            if (contact != null) {
                parseAIOContact(contact.toString());
            }
        } catch (Throwable ignored) {}

        Activity act = resolveCurrentActivity();
        if (act != null) {
            act.runOnUiThread(() -> attachBallToActivity(act));
        }
    }

    public static void onAIODelegateHide() {
        sInAIO = false;
        sActiveChatType = 0;
        sActivePeerUin = "";
        Activity act = resolveCurrentActivity();
        if (act != null) {
            act.runOnUiThread(() -> removeBallFromActivity(act));
        }
    }

    public static void updateActiveAIO(int chatType, String peerUin) {
        if (chatType != 0 && peerUin != null && !peerUin.isEmpty()) {
            sActiveChatType = chatType;
            sActivePeerUin = peerUin;
            sInAIO = true;
            Activity act = resolveCurrentActivity();
            if (act != null) {
                act.runOnUiThread(() -> attachBallToActivity(act));
            }
        }
    }

    private static void parseAIOContact(String input) {
        if (input == null) return;
        Pattern p = Pattern.compile("(\\w+)=([^,)]*)");
        Matcher m = p.matcher(input);
        int chatType = 0;
        String peerUid = "";
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(2).replace("'", "").trim();
            if ("chatType".equals(k)) {
                try { chatType = Integer.parseInt(v); } catch (Throwable ignored) {}
            } else if ("peerUid".equals(k)) {
                peerUid = v;
            }
        }
        if (chatType != 0 && !peerUid.isEmpty()) {
            sActiveChatType = chatType;
            sActivePeerUin = peerUid;
            Log.i(TAG, "[FloatingBall] 锁定群聊: " + peerUid);
        }
    }

    public static void refreshVisibility() {
        Activity act = resolveCurrentActivity();
        if (act != null) {
            act.runOnUiThread(() -> {
                if (ConfigManager.isFloatingBallEnabled() && sInAIO) {
                    attachBallToActivity(act);
                } else {
                    removeBallFromActivity(act);
                }
            });
        }
    }

    private static void attachBallToActivity(Activity activity) {
        if (activity == null || !ConfigManager.isFloatingBallEnabled() || !sInAIO) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View existing = decor.findViewById(BALL_TAG_ID);
            if (existing != null) {
                existing.setVisibility(View.VISIBLE);
                return;
            }

            final int size = dp2px(activity, 44);
            if (sLastX < 0 || sLastY < 0) {
                int screenW = activity.getResources().getDisplayMetrics().widthPixels;
                int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                sLastX = screenW - size - dp2px(activity, 12);
                sLastY = (int) (screenH * 0.65f);
            }

            final ImageView ball = new ImageView(activity);
            ball.setId(BALL_TAG_ID);
            ball.setBackground(null); // 纯透明无底色

            try (InputStream is = activity.getAssets().open("zzz_icon.png")) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    ball.setImageBitmap(bmp);
                }
            } catch (Throwable ignored) {}

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
            lp.leftMargin = sLastX;
            lp.topMargin = sLastY;

            ball.setOnTouchListener(new View.OnTouchListener() {
                private float startRawX, startRawY;
                private int origLeft, origTop;
                private boolean isDragging;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            FrameLayout.LayoutParams currentLp = (FrameLayout.LayoutParams) v.getLayoutParams();
                            origLeft = currentLp.leftMargin;
                            origTop = currentLp.topMargin;
                            isDragging = false;
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startRawX;
                            float dy = event.getRawY() - startRawY;
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                                isDragging = true;
                            }
                            if (isDragging) {
                                FrameLayout.LayoutParams moveLp = (FrameLayout.LayoutParams) v.getLayoutParams();
                                moveLp.leftMargin = (int) (origLeft + dx);
                                moveLp.topMargin = (int) (origTop + dy);
                                v.setLayoutParams(moveLp);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (isDragging) {
                                FrameLayout.LayoutParams upLp = (FrameLayout.LayoutParams) v.getLayoutParams();
                                sLastX = upLp.leftMargin;
                                sLastY = upLp.topMargin;
                            } else {
                                showActionMenu(activity);
                            }
                            return true;
                    }
                    return false;
                }
            });

            decor.addView(ball, lp);
        } catch (Throwable t) {
            Log.e(TAG, "[FloatingBall] 挂载异常: ", t);
        }
    }

    private static void removeBallFromActivity(Activity activity) {
        if (activity == null) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View v = decor.findViewById(BALL_TAG_ID);
            if (v != null) {
                decor.removeView(v);
            }
        } catch (Throwable ignored) {}
    }

    private static void showActionMenu(Activity activity) {
        if (activity == null) return;

        List<PluginManager.PluginItem> plugins = PluginManager.scanAllPlugins(activity);
        List<ActionItem> actions = new ArrayList<>();
        for (PluginManager.PluginItem p : plugins) {
            if (p.isEnabled && p.menuItems != null) {
                for (Map.Entry<String, String> entry : p.menuItems.entrySet()) {
                    actions.add(new ActionItem(p.id, p.name, entry.getKey(), entry.getValue()));
                }
            }
        }

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(activity, 20);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(activity, 20));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("脚本快捷菜单");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1D1D1F"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 15));
        root.addView(title);

        if (actions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("当前已开启的脚本暂未注册菜单\n(请在设置中开启包含 addItem 的脚本)");
            empty.setTextSize(14);
            empty.setTextColor(Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(activity, 10), 0, dp2px(activity, 15));
            root.addView(empty);
        } else {
            for (ActionItem action : actions) {
                Button btn = new Button(activity);
                btn.setText(action.actionName + " (" + action.pluginName + ")");
                btn.setTextSize(15);
                btn.setTextColor(Color.WHITE);
                btn.setAllCaps(false);

                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(Color.parseColor("#007AFF"));
                btnBg.setCornerRadius(dp2px(activity, 12));
                btn.setBackground(btnBg);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 46));
                lp.bottomMargin = dp2px(activity, 10);

                btn.setOnClickListener(v -> {
                    dialog.dismiss();
                    int cType = sActiveChatType != 0 ? sActiveChatType : 2;
                    String peerUin = sActivePeerUin;
                    Log.i(TAG, "[FloatingBall] 点击菜单动作 -> cType=" + cType + ", peerUin=" + peerUin);
                    PluginManager.invokePluginMenu(action.pluginId, action.callback, cType, peerUin, action.actionName);
                });
                root.addView(btn, lp);
            }
        }

        Button reloadBtn = new Button(activity);
        reloadBtn.setText("重新扫描全部脚本");
        reloadBtn.setTextSize(14);
        reloadBtn.setTextColor(Color.parseColor("#34C759"));
        reloadBtn.setAllCaps(false);

        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(Color.parseColor("#F2F2F7"));
        rBg.setCornerRadius(dp2px(activity, 12));
        reloadBtn.setBackground(rBg);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44));
        reloadBtn.setOnClickListener(v -> {
            dialog.dismiss();
            PluginManager.reloadAll(activity);
            Toast.makeText(activity, "已提交重新扫描指令", Toast.LENGTH_SHORT).show();
        });
        root.addView(reloadBtn, rLp);

        dialog.setContentView(root);
        dialog.show();

        if (dialog.getWindow() != null) {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static int dp2px(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class ActionItem {
        String pluginId;
        String pluginName;
        String actionName;
        String callback;
        ActionItem(String pId, String pName, String aName, String cb) {
            this.pluginId = pId;
            this.pluginName = pName;
            this.actionName = aName;
            this.callback = cb;
        }
    }
}

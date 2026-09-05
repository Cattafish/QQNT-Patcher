package com.tencent.qqnt.patch.plugin;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
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
    private static volatile boolean sRegistered = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> sCurrentActivity = null;
    private static WeakReference<Object> sCurrentAIODelegate = null;

    public static volatile int sActiveChatType = 0;
    public static volatile String sActivePeerUid = "";
    public static volatile String sActivePeerUin = "";
    public static volatile String sActivePeerName = "";
    public static volatile String sActiveGuild = "";
    public static volatile boolean sInAIO = false;

    private static PopupWindow sPopupWindow = null;
    private static View sFloatBtn = null;
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
                    Log.i(TAG, "[FloatingBall] onActivityResumed: " + activity.getClass().getSimpleName() + ", sInAIO=" + sInAIO);
                    if (sInAIO && isAIOActivity(activity)) {
                        showView(activity);
                    } else {
                        hideView();
                    }
                }
                @Override
                public void onActivityPaused(Activity activity) {
                    Log.i(TAG, "[FloatingBall] onActivityPaused: " + activity.getClass().getSimpleName() + " -> 隐藏浮窗");
                    hideView();
                }
                @Override
                public void onActivityStopped(Activity activity) {}
                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (sCurrentActivity != null && sCurrentActivity.get() == activity) {
                        hideView();
                        sCurrentActivity = null;
                    }
                }
            });
            Log.i(TAG, "[FloatingBall] 引擎初始化成功");
        } catch (Throwable t) {
            Log.e(TAG, "[FloatingBall] 注册异常", t);
        }
    }

    private static boolean isAIOActivity(Activity act) {
        if (act == null) return false;
        String name = act.getClass().getName();
        if (name.contains("Setting") || name.contains("Preference") || name.contains("Plugin") || name.contains("Clean")) {
            return false;
        }
        return true;
    }

    public static Activity resolveCurrentActivity() {
        if (sCurrentActivity != null && sCurrentActivity.get() != null) {
            Activity act = sCurrentActivity.get();
            if (!act.isFinishing() && !act.isDestroyed()) {
                return act;
            }
        }
        try {
            Class<?> qBaseClz = Class.forName("com.tencent.mobileqq.app.QBaseActivity");
            Field topField = qBaseClz.getDeclaredField("sTopActivity");
            topField.setAccessible(true);
            Object top = topField.get(null);
            if (top instanceof Activity) {
                Activity act = (Activity) top;
                sCurrentActivity = new WeakReference<>(act);
                return act;
            }
        } catch (Throwable ignored) {}

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
                    Field pausedField = null;
                    try {
                        pausedField = recordClz.getDeclaredField("paused");
                    } catch (Throwable e) {
                        pausedField = recordClz.getDeclaredField("mPaused");
                    }
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

    /**
     * 进入聊天会话 (AIODelegate.show)
     */
    public static void onAIODelegateShow(Object delegate) {
        Log.i(TAG, "[FloatingBall] === 捕获进入聊天会话 (AIODelegate.show) ===");
        if (delegate == null) return;
        sCurrentAIODelegate = new WeakReference<>(delegate);
        sInAIO = true;

        refreshContactFromDelegate(delegate);

        sMainHandler.post(() -> {
            Activity act = resolveCurrentActivity();
            Log.i(TAG, "[FloatingBall] 准备展现浮窗, Activity: " + (act != null ? act.getClass().getSimpleName() : "null"));
            if (act != null && isAIOActivity(act)) {
                hideView();
                showView(act);
            }
        });
    }

    /**
     * 双保险激活：进入会话渲染消息气泡时必调
     */
    public static void onAIOMsgItemBind(Object msgRecordObj) {
        if (msgRecordObj == null) return;
        try {
            Class<?> clz = msgRecordObj.getClass();
            Field fChatType = clz.getDeclaredField("chatType");
            int chatType = fChatType.getInt(msgRecordObj);

            if (chatType == 1 || chatType == 2) {
                Field fPeerUin = clz.getDeclaredField("peerUin");
                long pUin = fPeerUin.getLong(msgRecordObj);
                Field fPeerUid = clz.getDeclaredField("peerUid");
                String pUid = (String) fPeerUid.get(msgRecordObj);

                String target = (pUin > 0) ? String.valueOf(pUin) : pUid;
                if (chatType == 1 && target != null && target.startsWith("u_")) {
                    target = MsgSender.getUinFromUid(target);
                }

                if (!sInAIO || !target.equals(sActivePeerUin)) {
                    sActiveChatType = chatType;
                    sActivePeerUin = target;
                    sInAIO = true;
                    Log.i(TAG, "[FloatingBall] 双保险：聊天气泡渲染激活会话 -> " + sActivePeerUin);

                    sMainHandler.post(() -> {
                        Activity act = resolveCurrentActivity();
                        if (act != null && isAIOActivity(act)) {
                            showView(act);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 退出聊天会话 (AIODelegate.hide)
     */
    public static void onAIODelegateHide() {
        Log.i(TAG, "[FloatingBall] === 捕获退出聊天会话 (AIODelegate.hide) -> 隐藏浮窗 ===");
        sInAIO = false;
        sActiveChatType = 0;
        sActivePeerUid = "";
        sActivePeerUin = "";
        sActivePeerName = "";
        sCurrentAIODelegate = null;
        sMainHandler.post(FloatingBallManager::hideView);
    }

    public static void refreshContactFromDelegate(Object delegate) {
        if (delegate == null) return;
        try {
            Object contact = null;
            try {
                Method m = delegate.getClass().getMethod("getAIOContact");
                contact = m.invoke(delegate);
            } catch (Throwable ignored) {
                Field f = delegate.getClass().getDeclaredField("aioContact");
                f.setAccessible(true);
                contact = f.get(delegate);
            }

            if (contact != null) {
                String input = contact.toString();
                Log.i(TAG, "[FloatingBall] 会话 Contact: " + input);
                Pattern regex = Pattern.compile("(\\w+)=([^,)]*)");
                Matcher m = regex.matcher(input);

                int chatType = 0;
                String peerUid = "";
                String guild = "";
                String peerName = "";

                while (m.find()) {
                    String key = m.group(1);
                    String val = m.group(2).replace("'", "").trim();
                    if ("chatType".equals(key)) {
                        try { chatType = Integer.parseInt(val); } catch (Throwable ignored) {}
                    } else if ("peerUid".equals(key)) {
                        peerUid = val;
                    } else if ("guildId".equals(key)) {
                        guild = val;
                    } else if ("nick".equals(key)) {
                        peerName = val;
                    }
                }

                sActiveChatType = (chatType != 0) ? chatType : 2;
                sActivePeerUid = peerUid;
                sActiveGuild = guild;
                sActivePeerName = peerName;

                if (sActiveChatType == 2) {
                    sActivePeerUin = peerUid;
                } else {
                    String uin = MsgSender.getUinFromUid(peerUid);
                    sActivePeerUin = (!uin.isEmpty()) ? uin : peerUid;
                }

                Log.i(TAG, "[FloatingBall] 解析会话成功: cType=" + sActiveChatType + ", peerUin=" + sActivePeerUin);
            } else {
                if (sActiveChatType == 0) sActiveChatType = 2;
            }
        } catch (Throwable t) {
            Log.e(TAG, "[FloatingBall] 解析 contact 异常", t);
            if (sActiveChatType == 0) sActiveChatType = 2;
        }
    }

    private static void showView(Activity activity) {
        if (activity == null || !ConfigManager.isFloatingBallEnabled() || !sInAIO) {
            return;
        }
        if (sPopupWindow != null && sPopupWindow.isShowing()) {
            return;
        }

        try {
            int size = dp2px(activity, 44f);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;

            SharedPreferences sp = activity.getSharedPreferences("zzz_floating_pref", Context.MODE_PRIVATE);
            sLastX = sp.getInt("float_x", screenW - size - dp2px(activity, 16f));
            sLastY = sp.getInt("float_y", (int) (screenH * 0.62f));

            ImageView imageView = new ImageView(activity);
            try (InputStream is = activity.getAssets().open("zzz_icon.png")) {
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) imageView.setImageBitmap(bmp);
            } catch (Throwable ignored) {}

            imageView.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            sFloatBtn = imageView;

            sPopupWindow = new PopupWindow(imageView, size, size, false);
            sPopupWindow.setOutsideTouchable(false);
            sPopupWindow.setFocusable(false);
            sPopupWindow.setElevation(0f);

            setupTouchListener(activity, sp, size);

            View decor = activity.getWindow().getDecorView();
            decor.post(() -> {
                try {
                    if (sInAIO && sPopupWindow != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        sPopupWindow.showAtLocation(decor, Gravity.NO_GRAVITY, sLastX, sLastY);
                        Log.i(TAG, "[FloatingBall] 悬浮球成功显示在 Window (X=" + sLastX + ", Y=" + sLastY + ")");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "[FloatingBall] showAtLocation 执行异常", t);
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "[FloatingBall] PopupWindow 准备阶段异常", t);
        }
    }

    private static void hideView() {
        try {
            if (sPopupWindow != null) {
                sPopupWindow.dismiss();
                sPopupWindow = null;
                Log.i(TAG, "[FloatingBall] 悬浮球已关闭隐藏");
            }
            sFloatBtn = null;
        } catch (Throwable ignored) {}
    }

    private static void setupTouchListener(Activity activity, SharedPreferences sp, int size) {
        if (sFloatBtn == null) return;

        sFloatBtn.setOnTouchListener(new View.OnTouchListener() {
            private float touchStartX, touchStartY;
            private int initialTouchX, initialTouchY;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        initialTouchX = sLastX;
                        initialTouchY = sLastY;
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchStartX;
                        float dy = event.getRawY() - touchStartY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true;
                        }
                        if (isDragging && sPopupWindow != null) {
                            sLastX = (int) (initialTouchX + dx);
                            sLastY = (int) (initialTouchY + dy);
                            sPopupWindow.update(sLastX, sLastY, -1, -1);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            sp.edit().putInt("float_x", sLastX).putInt("float_y", sLastY).apply();
                        } else {
                            showActionMenu(activity);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public static void refreshVisibility() {
        Activity act = resolveCurrentActivity();
        if (act != null) {
            act.runOnUiThread(() -> {
                if (ConfigManager.isFloatingBallEnabled() && sInAIO && isAIOActivity(act)) {
                    showView(act);
                } else {
                    hideView();
                }
            });
        }
    }

    private static void showActionMenu(Activity activity) {
        if (activity == null) return;

        if (sCurrentAIODelegate != null && sCurrentAIODelegate.get() != null) {
            refreshContactFromDelegate(sCurrentAIODelegate.get());
        }

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
        int pad = dp2px(activity, 20f);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(activity, 20f));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("脚本快捷菜单");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1D1D1F"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp2px(activity, 15f));
        root.addView(title);

        if (actions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("当前已开启的脚本暂未注册菜单\n(请在设置中开启包含 addItem 的脚本)");
            empty.setTextSize(14);
            empty.setTextColor(Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp2px(activity, 10f), 0, dp2px(activity, 15f));
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
                btnBg.setCornerRadius(dp2px(activity, 12f));
                btn.setBackground(btnBg);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 46f));
                lp.bottomMargin = dp2px(activity, 10f);

                btn.setOnClickListener(v -> {
                    dialog.dismiss();
                    int cType = sActiveChatType != 0 ? sActiveChatType : 2;
                    String peerUin = sActivePeerUin;
                    Log.i(TAG, "[FloatingBall] 点击菜单动作 -> 当前界面 cType=" + cType + ", peerUin=" + peerUin);
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
        rBg.setCornerRadius(dp2px(activity, 12f));
        reloadBtn.setBackground(rBg);

        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 44f));
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
        if (c == null || c.getResources() == null || c.getResources().getDisplayMetrics() == null) {
            return (int) (dp * 2f + 0.5f);
        }
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

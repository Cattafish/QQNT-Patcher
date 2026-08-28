package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class ZzzSettingActivity {

    public static final String EXTRA_FLAG = "open_zzz_settings";
    private static final String HOST_ACTIVITY = "com.tencent.mobileqq.activity.QQBrowserActivity";

    public static void start(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), HOST_ACTIVITY);
            intent.putExtra(EXTRA_FLAG, true);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "打开设置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean onHijackCreate(Activity activity) {
        if (activity == null || activity.getIntent() == null) return false;
        if (!activity.getIntent().getBooleanExtra(EXTRA_FLAG, false)) return false;

        try {
            initMaterial3UI(activity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void initMaterial3UI(Activity activity) {
        // 主背景
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F8F9FA"));

        // 1. M3 顶部导航栏
        LinearLayout appBar = new LinearLayout(activity);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setBackgroundColor(Color.parseColor("#FFFFFF"));
        appBar.setPadding(dip2px(activity, 16), dip2px(activity, 45), dip2px(activity, 16), dip2px(activity, 16));

        TextView btnBack = new TextView(activity);
        btnBack.setText("←");
        btnBack.setTextSize(22f);
        btnBack.setTextColor(Color.parseColor("#1F1F1F"));
        btnBack.setPadding(0, 0, dip2px(activity, 16), 0);
        btnBack.setOnClickListener(v -> activity.finish());
        appBar.addView(btnBack);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("Zzz 设置");
        tvTitle.setTextSize(20f);
        tvTitle.setTextColor(Color.parseColor("#1F1F1F"));
        appBar.addView(tvTitle);

        root.addView(appBar);

        // 2. 滚动内容区域
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dip2px(activity, 16), dip2px(activity, 16), dip2px(activity, 16), dip2px(activity, 30));

        // --- 功能 ---
        content.addView(createSectionHeader(activity, "功能"));
        LinearLayout cardCore = createM3Card(activity);
        cardCore.addView(createAntiRevokeSwitch(activity));
        content.addView(cardCore);

        // --- 高级 ---
        content.addView(createSectionHeader(activity, "高级"));
        LinearLayout cardAdv = createM3Card(activity);
        cardAdv.addView(createDebugLogSwitch(activity));
        content.addView(cardAdv);

        // --- 关于 ---
        content.addView(createSectionHeader(activity, "关于"));
        LinearLayout cardAbout = createM3Card(activity);
        cardAbout.addView(createM3InfoRow(activity, "版本号", "v0.0.1"));
        content.addView(cardAbout);

        scrollView.addView(content);
        root.addView(scrollView);

        activity.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static TextView createSectionHeader(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(Color.parseColor("#0B57D0"));
        tv.setPadding(dip2px(context, 8), dip2px(context, 12), 0, dip2px(context, 6));
        return tv;
    }

    private static LinearLayout createM3Card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dip2px(context, 16), dip2px(context, 6), dip2px(context, 16), dip2px(context, 6));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#FFFFFF"));
        gd.setCornerRadius(dip2px(context, 16));
        card.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, dip2px(context, 8));
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * 防撤回开关：通过 Linux 文件标记跨进程同步
     */
    private static View createAntiRevokeSwitch(Activity activity) {
        File flagFile = new File(activity.getFilesDir(), "zzz_anti_revoke_off");
        boolean isEnabled = !flagFile.exists(); // 文件不存在代表开启

        return createBaseSwitchRow(activity, "消息防撤回", "阻止群聊与私聊消息被撤回抹除", isEnabled, (btn, checked) -> {
            try {
                if (checked) {
                    flagFile.delete(); // 开启 -> 删除禁用标记
                } else {
                    flagFile.createNewFile(); // 关闭 -> 写入禁用标记
                }
            } catch (Throwable ignored) {}
            Toast.makeText(activity, "消息防撤回" + (checked ? " 已开启" : " 已关闭"), Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 调试日志开关
     */
    private static View createDebugLogSwitch(Activity activity) {
        File flagFile = new File(activity.getFilesDir(), "zzz_debug_log_on");
        boolean isEnabled = flagFile.exists();

        return createBaseSwitchRow(activity, "调试日志输出", "在 Logcat 中输出协议拦截诊断信息", isEnabled, (btn, checked) -> {
            try {
                if (checked) {
                    flagFile.createNewFile();
                } else {
                    flagFile.delete();
                }
            } catch (Throwable ignored) {}
            Toast.makeText(activity, "调试日志" + (checked ? " 已开启" : " 已关闭"), Toast.LENGTH_SHORT).show();
        });
    }

    private static View createBaseSwitchRow(Context context, String title, String subtitle, boolean isChecked, Switch.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip2px(context, 10), 0, dip2px(context, 10));

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textBox.setLayoutParams(textLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(15f);
        tvTitle.setTextColor(Color.parseColor("#1F1F1F"));

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(12f);
        tvSub.setTextColor(Color.parseColor("#757575"));
        tvSub.setPadding(0, dip2px(context, 2), 0, 0);

        textBox.addView(tvTitle);
        textBox.addView(tvSub);

        Switch sw = new Switch(context);
        sw.setChecked(isChecked);
        sw.setOnCheckedChangeListener(listener);

        row.addView(textBox);
        row.addView(sw);
        return row;
    }

    private static View createM3InfoRow(Context context, String title, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip2px(context, 10), 0, dip2px(context, 10));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(14f);
        tvTitle.setTextColor(Color.parseColor("#1F1F1F"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(titleLp);

        TextView tvVal = new TextView(context);
        tvVal.setText(value);
        tvVal.setTextSize(13f);
        tvVal.setTextColor(Color.parseColor("#757575"));

        row.addView(tvTitle);
        row.addView(tvVal);
        return row;
    }

    private static int dip2px(Context context, float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
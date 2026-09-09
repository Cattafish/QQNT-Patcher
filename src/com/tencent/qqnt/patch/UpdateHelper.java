package com.tencent.qqnt.patch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateHelper {

    /**
     * QQ 启动时调用的静默检测（无任何 UI 干扰）
     */
    public static void checkUpdateSilent() {
        new Thread(() -> {
            try {
                URL url = new URL(ConfigManager.UPDATE_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client/" + ConfigManager.VERSION);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String latestTag = extractJsonField(sb.toString(), "tag_name");
                    if (latestTag != null && isNewerVersion(latestTag, ConfigManager.VERSION)) {
                        ConfigManager.setHasNewVersion(true);
                    } else {
                        ConfigManager.setHasNewVersion(false);
                    }
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    /**
     * 设置页面内用户手动点击检查更新
     */
    public static void checkUpdate(Activity activity) {
        if (activity == null) return;
        Toast.makeText(activity, "正在检查更新...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                URL url = new URL(ConfigManager.UPDATE_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "QQNT-Patcher-Client/" + ConfigManager.VERSION);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    String json = sb.toString();
                    String latestTag = extractJsonField(json, "tag_name");
                    String releaseUrl = extractJsonField(json, "html_url");

                    if (releaseUrl == null || releaseUrl.isEmpty()) {
                        releaseUrl = ConfigManager.GITHUB_REPO_URL + "/releases";
                    }

                    final String targetUrl = releaseUrl;
                    final String tag = latestTag;

                    activity.runOnUiThread(() -> {
                        if (tag != null && isNewerVersion(tag, ConfigManager.VERSION)) {
                            ConfigManager.setHasNewVersion(true);
                            Toast.makeText(activity, "发现新版本: " + tag + "，即将前往下载", Toast.LENGTH_LONG).show();
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                activity.startActivity(intent);
                            } catch (Throwable t) {
                                Toast.makeText(activity, "打开浏览器失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            ConfigManager.setHasNewVersion(false);
                            Toast.makeText(activity, "当前已是最新版本 (" + ConfigManager.VERSION + ")", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, "检查更新失败 (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "检查更新异常: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private static String extractJsonField(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static boolean isNewerVersion(String latest, String current) {
        try {
            String v1 = latest.startsWith("v") ? latest.substring(1) : latest;
            String v2 = current.startsWith("v") ? current.substring(1) : current;

            String[] p1 = v1.split("\\.");
            String[] p2 = v2.split("\\.");

            int len = Math.max(p1.length, p2.length);
            for (int i = 0; i < len; i++) {
                int n1 = i < p1.length ? Integer.parseInt(p1[i].replaceAll("\\D+", "")) : 0;
                int n2 = i < p2.length ? Integer.parseInt(p2[i].replaceAll("\\D+", "")) : 0;
                if (n1 > n2) return true;
                if (n1 < n2) return false;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
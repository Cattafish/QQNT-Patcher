import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import me.yxp.qfun.plugin.bean.FriendInfo;
import me.yxp.qfun.plugin.bean.GroupInfo;
import me.yxp.qfun.plugin.bean.MemberInfo;
import me.yxp.qfun.plugin.bean.MsgData;

log("全功能测试脚本已载入初始化...");

addItem("全功能体检控制台", "showTestConsole");
addMenuItem("【测试】气泡诊断", "onMenuDiagnose");
addMenuItem("【测试】引用回复", "onMenuReplyTest");

public void showTestConsole(int chatType, String peerUin, String peerName) {
    Activity activity = getNowActivity();
    if (activity == null) {
        toast("获取当前 Activity 失败");
        return;
    }

    activity.runOnUiThread(new Runnable() {
        public void run() {
            try {
                Dialog dialog = new Dialog(activity);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                }

                int pad = dp2px(activity, 16);
                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(pad, pad, pad, pad);

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#FFFFFF"));
                bg.setCornerRadius(dp2px(activity, 16));
                root.setBackground(bg);

                TextView title = new TextView(activity);
                title.setText("QQNT-Patcher 全功能体检");
                title.setTextSize(18);
                title.setTextColor(Color.parseColor("#1C1C1E"));
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, 0, 0, dp2px(activity, 12));
                root.addView(title);

                ScrollView scroll = new ScrollView(activity);
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);

                // 按钮 1：测试票据 (含全异常保护)
                addBtn(activity, content, "1. 测试票据鉴权 (Skey/Pskey/RKey)", Color.parseColor("#007AFF"), v -> {
                    try {
                        String skey = getSkey();
                        String realSkey = getRealSkey();
                        String pskey = getPskey("vip.qq.com");
                        String bkn = String.valueOf(getBkn(skey));
                        String gtk = getGTK("vip.qq.com");
                        String fRKey = getFriendRKey();
                        String gRKey = getGroupRKey();

                        String report = "【票据鉴权测试】\n"
                                + "Skey: " + (skey.isEmpty() ? "未就绪" : skey) + "\n"
                                + "RealSkey: " + (realSkey.isEmpty() ? "未就绪" : "已获取") + "\n"
                                + "Pskey(vip): " + (pskey.isEmpty() ? "未获取" : "已获取") + "\n"
                                + "Bkn: " + bkn + ", GTK: " + gtk + "\n"
                                + "好友RKey: " + (fRKey.isEmpty() ? "等待下发" : "已捕获") + "\n"
                                + "群聊RKey: " + (gRKey.isEmpty() ? "等待下发" : "已捕获");
                        log(report);
                        toast("已提取票据，详见日志");
                        qqToast(2, "Skey: " + (skey.isEmpty() ? "未就绪" : "获取成功"));
                    } catch (Throwable t) {
                        toast("提取异常: " + t.getMessage());
                        log("提取票据异常: " + t);
                    }
                });

                // 按钮 2：测试群与好友数据
                addBtn(activity, content, "2. 测试好友与群列表数据", Color.parseColor("#34C759"), v -> {
                    try {
                        List groups = getGroupList();
                        List friends = getAllFriend();
                        int gCount = groups != null ? groups.size() : 0;
                        int fCount = friends != null ? friends.size() : 0;

                        String info = "群聊数量: " + gCount + " 个\n好友数量: " + fCount + " 个";
                        if (gCount > 0) {
                            GroupInfo firstG = (GroupInfo) groups.get(0);
                            info += "\n首个群: " + firstG.groupName + " (" + firstG.group + ")";
                        }
                        log("【关系链测试】\n" + info);
                        qqToast(2, "群: " + gCount + "个, 好友: " + fCount + "个");
                    } catch (Throwable t) {
                        toast("关系链获取异常: " + t.getMessage());
                    }
                });

                // 按钮 3：当前群成员与禁言测试
                if (chatType == 2) {
                    addBtn(activity, content, "3. 测试群成员与禁言 (当前群)", Color.parseColor("#FF9500"), v -> {
                        try {
                            boolean shut = isShutUp(peerUin);
                            List members = getGroupMemberList(peerUin);
                            int mCount = members != null ? members.size() : 0;
                            qqToast(2, "全员禁言: " + (shut ? "开启" : "关闭") + ", 成员: " + mCount + "人");
                        } catch (Throwable t) {
                            toast("群测试异常: " + t.getMessage());
                        }
                    });
                }

                // 按钮 4：发送测试
                addBtn(activity, content, "4. 测试拍一拍与发送", Color.parseColor("#5856D6"), v -> {
                    try {
                        if (peerUin != null && !peerUin.isEmpty()) {
                            sendPai(peerUin, peerUin, chatType);
                            sendMsg(peerUin, "【脚本指令】全功能自动化体检测试消息", chatType);
                            qqToast(2, "已向当前会话提交测试发送");
                        } else {
                            toast("当前未在会话中");
                        }
                    } catch (Throwable t) {
                        toast("发送测试异常: " + t.getMessage());
                    }
                });

                scroll.addView(content);
                root.addView(scroll, new LinearLayout.LayoutParams(-1, dp2px(activity, 320)));

                dialog.setContentView(root);
                dialog.show();
                if (dialog.getWindow() != null) {
                    int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.85);
                    dialog.getWindow().setLayout(w, -2);
                }
            } catch (Exception e) {
                toast("弹窗异常: " + e.getMessage());
            }
        }
    });
}

public void onMenuDiagnose(Object msgDataObj) {
    if (msgDataObj instanceof MsgData) {
        MsgData md = (MsgData) msgDataObj;
        String tip = "气泡诊断成功！\n"
                + "发送者: " + md.senderName + " (" + md.userUin + ")\n"
                + "MsgId: " + md.msgId + "\n"
                + "内容: " + md.msg;
        log(tip);
        qqToast(2, "气泡诊断: " + md.senderName);
    }
}

public void onMenuReplyTest(Object msgDataObj) {
    if (msgDataObj instanceof MsgData) {
        MsgData md = (MsgData) msgDataObj;
        sendReplyMsg(md.peerUin, md.msgId, "【脚本引用回复测试成功！】", md.type);
        qqToast(2, "已提交引用回复");
    }
}

public void chatInterface(int cType, String peerUin, String name) {
    log("进入会话 -> cType=" + cType + ", peerUin=" + peerUin + ", name=" + name);
    qqToast(2, "会话感知: " + (name != null && !name.isEmpty() ? name : peerUin));
}

public void onPaiYiPai(String peerUin, int chatType, String opUin) {
    log("收到拍一拍事件: peerUin=" + peerUin + ", opUin=" + opUin);
    toast("监听到拍一拍: 来自 " + opUin);
}

public void shutUpGroup(String troopUin, String memberUin, long time, String opUin) {
    String state = (time == 0) ? "被解除禁言" : ("被禁言 " + time + " 秒");
    log("群禁言变动 -> 群: " + troopUin + ", 成员: " + memberUin + ", " + state + ", 操作人: " + opUin);
    toast("群禁言提醒: " + memberUin + " " + state);
}

public void onMsg(Object msgObj) {
    if (msgObj instanceof MsgData) {
        MsgData md = (MsgData) msgObj;
        if ("#体检".equals(md.msg.trim())) {
            String skey = getSkey();
            String replyText = "【QQNT-Patcher 脚本引擎体检报告】\n"
                    + "当前账号: " + myUin + "\n"
                    + "Skey状态: " + (skey.isEmpty() ? "未提取" : "正常") + "\n"
                    + "消息接口: 正常\n"
                    + "引擎版本: QFun API Fully Compatible";
            md.reply(replyText);
        }
    }
}

void addBtn(Context ctx, LinearLayout parent, String text, int color, View.OnClickListener l) {
    Button btn = new Button(ctx);
    btn.setText(text);
    btn.setTextColor(Color.WHITE);
    btn.setTextSize(13);
    btn.setAllCaps(false);
    GradientDrawable gd = new GradientDrawable();
    gd.setColor(color);
    gd.setCornerRadius(dp2px(ctx, 10));
    btn.setBackground(gd);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp2px(ctx, 42));
    lp.bottomMargin = dp2px(ctx, 8);
    btn.setOnClickListener(l);
    parent.addView(btn, lp);
}

int dp2px(Context c, float dp) {
    return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
}

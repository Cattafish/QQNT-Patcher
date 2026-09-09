package com.tencent.qqnt.patch.plugin;

import java.util.ArrayList;

public class TroopMemberJoinHandler {

    public static void onPushReceive(ArrayList<Byte> byteList) {
        if (byteList == null || byteList.isEmpty()) return;
        try {
            byte[] data = new byte[byteList.size()];
            for (int i = 0; i < byteList.size(); i++) data[i] = byteList.get(i);

            // 检索 UID 字符串 (u_ 开头) 与群号
            long troopUin = 0L;
            String memberUid = "";

            int pos = 0;
            while (pos < data.length - 10) {
                if (data[pos] == 'u' && data[pos + 1] == '_') {
                    int end = pos;
                    while (end < data.length && data[end] >= 32 && data[end] <= 126 && data[end] != ' ' && data[end] != '\n') {
                        end++;
                    }
                    memberUid = new String(data, pos, end - pos);
                    break;
                }
                pos++;
            }

            // 提取群号
            for (int i = 0; i < Math.min(data.length - 4, 100); i++) {
                if (data[i] == 0x08) { // Tag 1 (group_code)
                    long val = 0;
                    for (int s = 0, p = i + 1; s < 64 && p < data.length; s += 7) {
                        byte b = data[p++];
                        val |= (long) (b & 0x7F) << s;
                        if ((b & 0x80) == 0) {
                            if (val > 10000L) troopUin = val;
                            break;
                        }
                    }
                    if (troopUin > 0L) break;
                }
            }

            if (troopUin > 0L && !memberUid.isEmpty()) {
                final long finalGid = troopUin;
                final String finalUid = memberUid;
                new Thread(() -> {
                    String uin = "";
                    for (int i = 0; i < 6; i++) {
                        uin = MsgSender.getUinFromUid(finalUid);
                        if (uin != null && !uin.isEmpty() && !uin.equals("0") && !uin.equals(finalUid)) break;
                        try { Thread.sleep(150); } catch (Exception ignored) {}
                    }
                    PluginManager.dispatchTroopJoin(String.valueOf(finalGid), (uin != null && !uin.isEmpty()) ? uin : finalUid);
                }).start();
            }
        } catch (Throwable ignored) {}
    }
}

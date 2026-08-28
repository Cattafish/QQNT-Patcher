# -*- coding: utf-8 -*-
"""
Patch 规则定义文件
"""

RULES = [
    {
        "name": "防撤回核心拦截 (onMsfPush)",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;",
        "target_method": "onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V",
        "type": "REPLACE",
        "smali": """
.method public onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    .registers 10

    # === [AntiRevoke Patch] ===
    invoke-static {p0, p1, p2}, Lcom/tencent/qqnt/patch/AntiRevokeHelper;->handleMsfPush(Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession;Ljava/lang/String;[B)[B
    move-result-object p2

    if-nez p2, :cond_pass
    return-void

    :cond_pass
    iget-wide v1, p0, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->nativeRef:J
    move-object v0, p0
    move-object v3, p1
    move-object v4, p2
    move-object v5, p3
    invoke-direct/range {v0 .. v5}, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->native_onMsfPush(JLjava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    return-void
.end method
"""
    }
]

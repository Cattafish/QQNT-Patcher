package me.yxp.qfun.plugin.loader;

import com.tencent.qqnt.patch.AppContext;
import me.yxp.qfun.plugin.bean.PluginInfo;

import java.util.ArrayList;
import java.util.List;

public class PluginManager {
    public static final PluginManager INSTANCE = new PluginManager();

    public List<PluginInfo> getPlugins() {
        List<PluginInfo> list = new ArrayList<>();
        try {
            List<com.tencent.qqnt.patch.plugin.PluginManager.PluginItem> scanned = 
                    com.tencent.qqnt.patch.plugin.PluginManager.scanAllPlugins(AppContext.get());
            for (com.tencent.qqnt.patch.plugin.PluginManager.PluginItem item : scanned) {
                list.add(new PluginInfo(item.id, item.name, "1.0", "", item.isRunning));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public void reloadPlugin(PluginInfo pluginInfo) {
        if (pluginInfo == null || pluginInfo.getId().isEmpty()) return;
        try {
            com.tencent.qqnt.patch.plugin.PluginManager.setPluginActive(
                    AppContext.get(), pluginInfo.getId(), true, null
            );
        } catch (Throwable ignored) {}
    }
}

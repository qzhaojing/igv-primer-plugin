package org.broad.igv.primer;

import org.broad.igv.dev.api.IGVPlugin;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.PanelName;

import java.util.Collections;

/**
 * IGV Plugin SPI 入口。
 * 注册方式：类名写入 igv.jar 内 org/broad/igv/ui/resources/builtin_plugin_list.txt。
 * 启动时 IGV 通过 Class.forName 反射加载并调用 init()。
 */
public class PrimerPlugin implements IGVPlugin {

    private static boolean inited = false;

    public void init() {
        if (inited) return;
        inited = true;
        try {
            // 启动即创建引物轨，右键即可用；无引物时渲染为空
            PrimerTrack t = new PrimerTrack();
            IGV.getInstance().addTracks(Collections.singletonList(t), PanelName.DATA_PANEL);
        } catch (Throwable e) {
            org.apache.log4j.Logger.getLogger(PrimerPlugin.class).error("PrimerPlugin init failed", e);
        }
    }
}

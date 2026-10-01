package org.broad.igv.primer;

import org.broad.igv.dev.api.IGVPlugin;
import org.broad.igv.track.Track;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.PanelName;
import org.broad.igv.ui.panel.TrackPanel;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collections;
import java.util.List;

/**
 * IGV Plugin SPI 入口。
 * 注册方式：类名写入 igv.jar 内 org/broad/igv/ui/resources/builtin_plugin_list.txt。
 * 启动时 IGV 通过 Class.forName 反射加载并调用 init()。
 */
public class PrimerPlugin implements IGVPlugin {

    private static boolean inited = false;
    private static PrimerTrack primerTrack = null;

    public void init() {
        if (inited) return;
        inited = true;
        try {
            // 启动即创建引物轨，右键即可用；无引物时渲染为空
            primerTrack = new PrimerTrack();
            IGV.getInstance().addTracks(Collections.singletonList(primerTrack), PanelName.DATA_PANEL);
            // v0.1.34：IGV 打开 session 时会重建数据面板并清掉引物轨（它不进 session 文件），
            // 这里起一个轻量守护定时器，定时确认引物轨存在，不在就自动加回，
            // 从而保证「打开 session 不影响引物轨」。间隔 1.5s，开销可忽略。
            startPrimerTrackKeeper();
        } catch (Throwable e) {
            org.apache.log4j.Logger.getLogger(PrimerPlugin.class).error("PrimerPlugin init failed", e);
        }
    }

    private void startPrimerTrackKeeper() {
        Timer keeper = new Timer(1500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    IGV igv = IGV.getInstance();
                    if (igv == null) return;
                    boolean present = false;
                    List<TrackPanel> panels = igv.getTrackPanels();
                    for (TrackPanel p : panels) {
                        for (Track t : p.getTracks()) {
                            if (t instanceof PrimerTrack) { present = true; break; }
                        }
                        if (present) break;
                    }
                    if (!present) {
                        if (primerTrack == null) primerTrack = new PrimerTrack();
                        igv.addTracks(Collections.singletonList(primerTrack), PanelName.DATA_PANEL);
                    }
                } catch (Throwable ignore) {
                    // 守护线程容错：任何异常都不影响主线程
                }
            }
        });
        keeper.setRepeats(true);
        keeper.start();
    }
}

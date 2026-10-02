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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IGV Plugin SPI 入口。
 * 注册方式：类名写入 igv.jar 内 org/broad/igv/ui/resources/builtin_plugin_list.txt。
 * 启动时 IGV 通过 Class.forName 反射加载并调用 init()。
 *
 * v0.1.36 多轨：维护 knownTracks（所有存活轨）/ groupTracks（分组→轨）注册表。
 *  - 默认创建一条手动添加轨（group=null）；
 *  - 每次导入 BED 通过 ensureTrackForGroup 为来源分组新建/认领一条独立轨；
 *  - 关闭轨从注册表注销，守护不再重加；
 *  - 守护定时器确保已知轨在「打开 session」被清空后重新挂载。
 */
public class PrimerPlugin implements IGVPlugin {

    private static boolean inited = false;
    private static final Set<PrimerTrack> knownTracks = new LinkedHashSet<PrimerTrack>();
    private static final Map<String, PrimerTrack> groupTracks = new ConcurrentHashMap<String, PrimerTrack>();

    public void init() {
        if (inited) return;
        inited = true;
        try {
            // 启动即创建手动添加轨（group=null），右键即可用；无引物时渲染为空
            PrimerTrack manual = new PrimerTrack();   // 默认手动添加轨
            IGV.getInstance().addTracks(Collections.singletonList(manual), PanelName.DATA_PANEL);
            knownTracks.add(manual);
            groupTracks.put(null, manual);
            // v0.1.34：IGV 打开 session 时会重建数据面板并清掉引物轨（它不进 session 文件），
            // 这里起一个轻量守护定时器，定时确认已知引物轨存在，不在就自动加回，
            // 从而保证「打开 session 不影响引物轨」。间隔 1.5s，开销可忽略。
            startPrimerTrackKeeper();
        } catch (Throwable e) {
            org.apache.log4j.Logger.getLogger(PrimerPlugin.class).error("PrimerPlugin init failed", e);
        }
    }

    /**
     * 为每个来源分组确保一条独立引物轨。
     *  - 已存在该分组的轨 → 直接返回（重复导入同一文件不新建）；
     *  - 否则若存在一个空的默认手动轨（group==null 且无未分组引物），认领它（首次导入恰好占满默认轨，不额外新增）；
     *  - 否则新建独立轨并加入 IGV 面板。
     */
    public static synchronized void ensureTrackForGroup(String group) {
        if (groupTracks.containsKey(group)) return;
        // 认领唯一存在的空手动轨（首次导入 → 占满默认轨，实现"导入两个 = 两条轨"而非三条）
        if (group != null) {
            for (PrimerTrack t : knownTracks) {
                if (t.getGroup() == null && PrimerStore.countGroup(null) == 0) {
                    t.bindGroup(group);
                    groupTracks.put(group, t);
                    return;
                }
            }
        }
        // 否则新建独立轨
        PrimerTrack t = new PrimerTrack(group);
        try { IGV.getInstance().addTracks(Collections.singletonList(t), PanelName.DATA_PANEL); } catch (Throwable ignore) { }
        knownTracks.add(t);
        groupTracks.put(group, t);
    }

    /** 关闭一条引物轨：从 IGV 面板移除，并从注册表注销（守护不再重加）。 */
    public static synchronized void closeTrack(PrimerTrack t) {
        if (t == null) return;
        try {
            TrackPanel tp = TrackPanel.getParentPanel(t);
            if (tp != null) tp.removeTracks(Collections.singletonList(t));
        } catch (Throwable ignore) { }
        try { IGV.getInstance().repaintDataPanels(); } catch (Throwable ignore) { }
        knownTracks.remove(t);
        // 注销 groupTracks 中指向该轨的条目
        for (java.util.Iterator<Map.Entry<String, PrimerTrack>> it = groupTracks.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() == t) it.remove();
        }
        PrimerTrack.ALL.remove(t);
    }

    private void startPrimerTrackKeeper() {
        Timer keeper = new Timer(1500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    IGV igv = IGV.getInstance();
                    if (igv == null) return;
                    // 重新挂载所有已知轨（session 打开会清空插件轨）
                    for (PrimerTrack t : new java.util.ArrayList<PrimerTrack>(knownTracks)) {
                        boolean present = false;
                        for (TrackPanel p : igv.getTrackPanels()) {
                            for (Track tr : p.getTracks()) {
                                if (tr == t) { present = true; break; }
                            }
                            if (present) break;
                        }
                        if (!present) {
                            try { igv.addTracks(Collections.singletonList(t), PanelName.DATA_PANEL); } catch (Throwable ignore) { }
                        }
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

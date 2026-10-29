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

    /** 插件版本号：显示在引物轨工具条最右侧（开发期对版用）。改功能时同步 +1。 */
    public static final String VERSION = "v0.1.96";

    private static boolean inited = false;
    private static final Set<PrimerTrack> knownTracks = new LinkedHashSet<PrimerTrack>();
    private static final Map<String, PrimerTrack> groupTracks = new ConcurrentHashMap<String, PrimerTrack>();

    public void init() {
        if (inited) return;
        inited = true;
        try {
            // v0.1.42：把引物接入 IGV 顶栏搜索框（官方扩展点），输入引物名/基因名/分组名可跳转
            PrimerSearcher.register();
            // 启动即创建手动添加轨（group=null），右键即可用；无引物时渲染为空
            PrimerTrack manual = new PrimerTrack();   // 默认手动添加轨
            IGV.getInstance().addTracks(Collections.singletonList(manual), PanelName.DATA_PANEL);
            knownTracks.add(manual);
            // 注意：groupTracks 是 ConcurrentHashMap，不允许 null 键；
            // v0.1.36 曾在此 put(null,...) 抛 NPE，导致守护定时器未启动 → 打开 session 后引物轨无法自动恢复。
            // 手动轨只登记在 knownTracks，查重统一走 knownTracks 遍历。
            // v0.1.34：IGV 打开 session 时会重建数据面板并清掉引物轨（它不进 session 文件），
            // 这里起一个轻量守护定时器，定时确认已知引物轨存在，不在就自动加回，
            // 从而保证「打开 session 不影响引物轨」。间隔 1.5s，开销可忽略。
            startPrimerTrackKeeper();
            // v0.1.65：全局 Shift+拖动选区监听（无需进入编辑模式）； keeper 里也会补挂新面板
            try { PrimerEditTool.ensureShiftSelectors(); } catch (Throwable ignore) { }
            // v0.1.68：常驻快捷键（Del 删 / N 新建 / E 编辑 / F 翻转 / V 定位 / Esc 取消 / Ctrl+配对）
            try { PrimerEditTool.ensureShortcutKeys(); } catch (Throwable ignore) { }
            // v0.1.71：5′ 附加序列库（~/.igv_primer_tails.xml，首次自动落内置预置）
            try { TailLibrary.init(); } catch (Throwable ignore) { }
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
        // 查重：已有同分组轨直接返回（group==null 即手动轨；不用 groupTracks.containsKey，
        // 因为 ConcurrentHashMap 不允许 null 键，containsKey(null) 也会 NPE）
        for (PrimerTrack t : knownTracks) {
            String g = t.getGroup();
            if (group == null ? g == null : group.equals(g)) return;
        }
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
        if (group != null) groupTracks.put(group, t);
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
        // v0.1.45：关掉最后一条轨后必须补一条兜底轨，否则「导入 BED」入口随轨消失 → 无法再导入
        ensureFallbackTrack();
    }

    /** v0.1.96：返回当前所有存活引物轨（供右键菜单枚举「移动到/复制到」目标轨）。 */
    public static java.util.List<PrimerTrack> getKnownTracks() {
        return new java.util.ArrayList<PrimerTrack>(knownTracks);
    }

    /** v0.1.54：按分组取轨（无则返回 null）。 */
    public static PrimerTrack getTrackForGroup(String group) {
        if (group == null) {
            for (PrimerTrack t : knownTracks) if (t.getGroup() == null) return t;
            return null;
        }
        PrimerTrack t = groupTracks.get(group);
        if (t != null) return t;
        for (PrimerTrack k : knownTracks) if (group.equals(k.getGroup())) return k;   // 兜底：注册表未同步时
        return null;
    }

    /** v0.1.47：仅从注册表注销该轨（由 session 还原接管布局），不做 IGV 面板移除、也不触发兜底新建。 */
    public static synchronized void forgetTrack(PrimerTrack t) {
        if (t == null) return;
        knownTracks.remove(t);
        for (java.util.Iterator<Map.Entry<String, PrimerTrack>> it = groupTracks.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() == t) it.remove();
        }
        PrimerTrack.ALL.remove(t);
    }

    /**
     * v0.1.45：保证始终至少存在一条引物轨（group==null 的手动轨）。
     * 「导入 BED / 导出 BED / 进入编辑」等入口只挂在引物轨自带的工具条与右键菜单上；
     * 一旦最后一条轨被关闭（且原手动轨已被首个导入的 BED 认领走），入口随轨一起消失，就再也无法导入。
     * 这里在关闭轨后、以及守护定时器发现「一条不剩」时自动补一条空的手动轨兜底。
     */
    public static synchronized void ensureFallbackTrack() {
        if (!knownTracks.isEmpty()) return;   // 还有轨，无需兜底
        try {
            PrimerTrack t = new PrimerTrack();   // group==null 手动轨；构造时已加入 PrimerTrack.ALL
            IGV.getInstance().addTracks(Collections.singletonList(t), PanelName.DATA_PANEL);
            knownTracks.add(t);
            try { IGV.getInstance().repaintDataPanels(); } catch (Throwable ignore) { }
        } catch (Throwable ignore) {
            // IGV 尚未就绪等异常：下一轮守护定时器会再试
        }
    }

    private void startPrimerTrackKeeper() {
        Timer keeper = new Timer(1500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                try {
                    IGV igv = IGV.getInstance();
                    if (igv == null) return;
                    // v0.1.47：当前 session 换了另一份 → 从中还原引物轨（含分组/连线/布局/颜色）
                    PrimerSession.checkSessionSync(igv);
                    // v0.1.96：统计面板当前还剩几条引物轨——
                    // 用于区分「用户用 IGV 原生 Remove Track 删掉了某条」与「session 打开清空了全部」。
                    int inPanel = 0;
                    for (TrackPanel p : igv.getTrackPanels())
                        for (Track tr : p.getTracks())
                            if (tr instanceof PrimerTrack) inPanel++;
                    // 逐条处理已知轨：在面板里 → 不动；不在 → 按下面规则处理
                    for (PrimerTrack t : new java.util.ArrayList<PrimerTrack>(knownTracks)) {
                        boolean present = false;
                        for (TrackPanel p : igv.getTrackPanels()) {
                            for (Track tr : p.getTracks()) {
                                if (tr == t) { present = true; break; }
                            }
                            if (present) break;
                        }
                        if (present) continue;   // 仍在面板 → 不动
                        if (inPanel >= 1 && t.getGroup() != null) {
                            // 面板里还有别的轨，但本条不见了 → 这是用户用原生 Remove Track 删除的命名轨。
                            // 原生删除不回调插件，这里补完语义：删除其引物并从注册表注销（让它真正消失）。
                            PrimerStore.removeGroup(t.getGroup());
                            forgetTrack(t);
                        } else {
                            // 面板已无任何引物轨（用户删了最后一条 / 打开 session 清空）→ 还原语义：原样挂回
                            try { igv.addTracks(Collections.singletonList(t), PanelName.DATA_PANEL); } catch (Throwable ignore) { }
                        }
                    }
                    // v0.1.45：一条轨都不剩时补一条手动轨（兼容被 IGV 原生方式删除/打开 session 清空的情况），
                    // 保证「导入 BED」入口永远在，不会因最后一条轨被关而锁死。
                    ensureFallbackTrack();
                    // v0.1.65：session 重建数据面板后，给新 DataPanel 补挂 Shift 选区监听
                    try { PrimerEditTool.ensureShiftSelectors(); } catch (Throwable ignore) { }
                    // v0.1.68：快捷键监听保活（极端情况被摘时补挂）
                    try { PrimerEditTool.ensureShortcutKeys(); } catch (Throwable ignore) { }
                } catch (Throwable ignore) {
                    // 守护线程容错：任何异常都不影响主线程
                }
            }
        });
        keeper.setRepeats(true);
        keeper.start();
    }
}

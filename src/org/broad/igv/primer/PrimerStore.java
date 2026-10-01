package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.TrackPanel;

import javax.swing.SwingUtilities;

import java.awt.Rectangle;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 引物仓库：全局唯一列表 + 序列刷新 + 全量评估 + 配对二聚体。
 */
public class PrimerStore {

    private static final List<Primer> primers = new ArrayList<Primer>();
    private static PrimerTrack track;
    public static Primer selected;
    public static String lastChr;   // 最近渲染的染色体（快速添加用）

    // 渲染时记录每条引物在屏幕上的矩形（含 y），供编辑工具做命中检测
    public static final java.util.Map<Primer, Rectangle> screenRects =
            new java.util.HashMap<Primer, Rectangle>();

    // 渲染时记录每条引物所在行号（供上下拖动换行计算用）
    public static final java.util.Map<Primer, Integer> screenRows =
            new java.util.HashMap<Primer, Integer>();

    // 右键菜单"显示序列"开关：选中后在引物下方画碱基序列
    public static boolean showSeq = false;
    public static boolean autosaveEnabled = true;   // 自动保存引物到 ~/.igv_primer_autosave.bed（防丢失）

    // 默认设计参数（由添加对话框记忆；R1/R2 默认 0，可在对话框手动改并"保存配置"持久化）
    public static int defaultLen = 20;
    public static char defaultStrand = '+';
    public static String defaultRole = "R1";
    public static int defaultReadF = 0;
    public static int defaultReadR = 0;
    public static boolean defaultIncludeLen = false;  // 测序长度是否包含引物长度

    // 失败判定阈值（v0.1.15：右键菜单"设置失败判定阈值"可改；evaluate 与 evaluatePairs 共用，写入默认配置文件）
    public static int failLenMin = 18, failLenMax = 30;        // 引物长度 (nt)
    public static double failTmMin = 55, failTmMax = 65;       // Tm (℃)
    public static double failGcMin = 30, failGcMax = 75;       // GC (%)
    public static double failHairpinTh = -3.5;                 // hairpin ΔG <= 此值 判为强
    public static double failSelfTh = -5.0;                    // self-dimer ΔG <= 此值 判为强
    public static int failHetero3pTh = 4;                      // 配对 3' 互补 >= 此值 判二聚体
    public static double failHeteroDgTh = -5.0;                // 配对二聚体 ΔG <= 此值 判二聚体

    static {
        loadDefaults();
    }

    public static synchronized List<Primer> getPrimers() {
        return new ArrayList<Primer>(primers);
    }

    public static synchronized void setTrack(PrimerTrack t) {
        track = t;
    }

    public static synchronized void add(Primer p) {
        primers.add(p);
        linkByName(p);
        refreshSequence(p);
        refresh();
        autosave(true);
    }

    public static synchronized void remove(Primer p) {
        primers.remove(p);
        if (selected == p) selected = null;
        cleanOrphanPairs();   // 被删引物的原配对组若只剩 1 条，散组防悬空
        refresh();
        autosave(true);
    }

    public static synchronized void clear() {
        primers.clear();
        selected = null;
        refresh();
        autosave(true);
    }

    public static synchronized void addAll(List<Primer> list) {
        primers.addAll(list);
        for (Primer p : list) linkByName(p);   // 导入 BED 时按 pairWith 名回链配对（重连 ampliconId）
        refresh();
        autosave(true);
    }

    // ---------- 自动保存（IGV session XML 不保存插件注入的 PrimerTrack，用它兜底防丢失） ----------

    private static long lastAutosave = 0;

    /** force=true 立即写盘（增删清空等结构变更）；false 节流 1.5s（拖动/刷新高频调用） */
    public static void autosave(boolean force) {
        if (!autosaveEnabled) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastAutosave < 1500) return;
        lastAutosave = now;
        try {
            ExportUtils.writeBED(ExportUtils.autosaveFile());
        } catch (Throwable ignore) {
        }
    }

    /** 拉取参考序列并重算单引物指标 + 配对指标 */
    public static void refreshSequence(Primer p) {
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) {
            p.seq = "";
        } else {
            byte[] b = g.getSequence(p.chr, p.start, p.end);
            p.seq = b == null ? "" : new String(b).toUpperCase();
        }
        PrimerMetrics.evaluate(p);
    }

    public static void refreshAll() {
        for (Primer p : primers) refreshSequence(p);
        evaluatePairs();
        refresh();
    }

    /** 配对评估：同 ampliconId 的 R1/R2 之间 heteroDG 与 3' 互补 */
    public static void evaluatePairs() {
        for (Primer p : primers) {
            p.heteroDG = 0;
            p.max3pComp = 0;
        }
        for (int i = 0; i < primers.size(); i++) {
            Primer a = primers.get(i);
            for (int j = i + 1; j < primers.size(); j++) {
                Primer b = primers.get(j);
                boolean paired = a.ampliconId != null && a.ampliconId.equals(b.ampliconId);
                boolean cross = !paired && a.chr.equals(b.chr);
                if (!paired && !cross) continue;
                double dg = PrimerMetrics.dimerDG(a.seq, b.seq);
                int c3 = PrimerMetrics.max3pComplement(a.seq, b.seq);
                a.heteroDG = Math.min(a.heteroDG, dg);
                a.max3pComp = Math.max(a.max3pComp, c3);
                b.heteroDG = Math.min(b.heteroDG, dg);
                b.max3pComp = Math.max(b.max3pComp, c3);
                if (c3 >= failHetero3pTh || dg <= failHeteroDgTh) {
                    flag(a, "二聚体(3'comp=" + c3 + ")");
                    flag(b, "二聚体(3'comp=" + c3 + ")");
                }
            }
        }
    }

    private static void flag(Primer p, String reason) {
        p.pass = false;
        p.failReasons = (p.failReasons.isEmpty() ? "" : p.failReasons + " ") + reason;
    }

    /**
     * 轻量化刷新（v0.1.17）：
     *  - 仅当引物轨"高度（行数）"发生变化（新增/删除导致出现或消失一行）时，才调用昂贵的 IGV.doRefresh()
     *    （它会重绘全部轨道并重载数据，是添加/点击卡顿的根因）；
     *  - 其余改动（改色/配对/选择/同排新增等不影响行数）只重绘引物轨自身所在面板，不碰其他 14 条轨道。
     */
    public static void refresh() {
        // 轻量化刷新（v0.1.17）：行数变化才全量 doRefresh，否则仅重绘引物轨面板。
        // v0.1.18：doRefresh()/repaint() 必须在 EDT 执行；此处保证调用方无论在主线程还是后台线程都安全。
        if (track != null) {
            final int oldH = track.getHeight();
            final int newH = PrimerTrack.computeNeededHeight(lastChr);
            track.update();
            if (newH != oldH) {
                runOnEDT(new Runnable() {
                    public void run() { IGV.getInstance().doRefresh(); }   // 行数变化 → 全量重排（重算各轨布局高度）
                });
            } else {
                runOnEDT(new Runnable() {
                    public void run() { repaintTrackOnly(); }              // 仅引物轨区域重绘
                });
            }
        } else {
            runOnEDT(new Runnable() {
                public void run() { IGV.getInstance().doRefresh(); }       // 未挂载轨时退回全量（兜底，极少触发）
            });
        }
        autosave(false);   // 节流自动保存（磁盘 I/O，保持同步、不进 EDT）
    }

    /** 在 EDT 上执行 r；若当前已是 EDT 则立即执行。使 refresh() 对后台线程调用方同样安全。 */
    private static void runOnEDT(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /** 仅重绘引物轨自身所在的 TrackPanel；取不到面板时退化为 repaintDataPanels（仍比重绘全部数据便宜）。 */
    private static void repaintTrackOnly() {
        if (track == null) return;
        try {
            TrackPanel tp = TrackPanel.getParentPanel(track);
            if (tp != null) {
                tp.repaint();
                return;
            }
        } catch (Throwable ignore) {
        }
        IGV.getInstance().repaintDataPanels();
    }

    public static synchronized String nextName() {
        int max = 0;
        for (Primer p : primers) {
            if (p.name != null && p.name.startsWith("P")) {
                try {
                    max = Math.max(max, Integer.parseInt(p.name.substring(1).split("[^0-9]")[0]));
                } catch (Exception ignore) {
                }
            }
        }
        return String.format("P%03d", max + 1);
    }

    /** 是否存在同名引物；exclude 非空时排除它（编辑自身名称不算重复） */
    public static synchronized boolean hasName(String name) {
        return hasName(name, null);
    }

    public static synchronized boolean hasName(String name, Primer exclude) {
        if (name == null) return false;
        for (Primer p : primers) {
            if (p == exclude) continue;
            if (name.equals(p.name)) return true;
        }
        return false;
    }

    /** 名称末位数字 +1（无数字则追加 1）；保留原数字位数（前补 0）。例：P001→P002、A12B3→A12B4、primer→primer1 */
    public static String incrementTrailingNumber(String s) {
        int i = s.length() - 1;
        while (i >= 0 && !Character.isDigit(s.charAt(i))) i--;
        if (i < 0) return s + "1";
        int j = i;
        while (j >= 0 && Character.isDigit(s.charAt(j))) j--;
        j++;
        String prefix = s.substring(0, j);
        String digits = s.substring(j, i + 1);
        int val = Integer.parseInt(digits) + 1;
        String fmt = String.format("%0" + digits.length() + "d", val);
        return prefix + fmt;
    }

    /** 在原名称末位数字 +1 基础上，循环避开已存在同名，返回唯一名称 */
    public static synchronized String uniqueIncrementedName(String base) {
        String n = incrementTrailingNumber(base);
        while (hasName(n)) n = incrementTrailingNumber(n);
        return n;
    }

    /** v0.1.16：复制选定引物——在原引物正下方生成同款副本（名称末位数字+1），独立、不并入原配对组 */
    public static synchronized void duplicate(Primer src) {
        if (src == null) return;
        Primer c = src.copy();
        c.ampliconId = null;   // 复制体独立，不污染原配对组连线
        c.pairWith = null;
        c.name = uniqueIncrementedName(src.name);
        Integer r = screenRows.get(src);
        c.rowOverride = (r == null) ? null : (r + 1);   // 紧贴原引物下方一行
        primers.add(c);
        refreshSequence(c);
        refresh();
        autosave(true);
    }

    private static int groupSeq = 0;
    public static synchronized String nextAmplicon() {
        return "G" + (++groupSeq);
    }

    /** 按名称配对：p.pairWith 或 某引物的 pairWith 指向 p.name → 共享 ampliconId（即连线 + 算异源二聚体） */
    public static synchronized void linkByName(Primer p) {
        for (Primer q : primers) {
            if (q == p) continue;
            boolean match = (p.pairWith != null && p.pairWith.equals(q.name))
                    || (q.pairWith != null && q.pairWith.equals(p.name));
            if (match) {
                String aid = (p.ampliconId != null) ? p.ampliconId
                        : (q.ampliconId != null ? q.ampliconId : nextAmplicon());
                p.ampliconId = aid;
                q.ampliconId = aid;
            }
        }
    }

    /** 按名称列表配对：p + 所有同名引物共享一个配对组 ID（支持一条引物配多条，即 1:N）。
     *  优先并入任一伙伴已有的组（复用其 ID）；若伙伴均无组，则复用 p 的组 ID 或生成新组。
     *  v0.1.4：只接受与 p 异链（F/R 相对）的伙伴，同链引物直接跳过（避免出现不严谨的同链"配对"）。 */
    public static synchronized void linkByNames(Primer p, String[] names) {
        String gid = null;
        for (String nm : names) {
            nm = nm.trim();
            if (nm.isEmpty()) continue;
            for (Primer q : primers) {
                if (nm.equals(q.name) && q.ampliconId != null) { gid = q.ampliconId; break; }
            }
            if (gid != null) break;
        }
        if (gid == null) gid = (p.ampliconId != null) ? p.ampliconId : nextAmplicon();
        p.ampliconId = gid;
        for (String nm : names) {
            nm = nm.trim();
            if (nm.isEmpty()) continue;
            for (Primer q : primers) {
                if (q != p && nm.equals(q.name) && q.strand != p.strand) q.ampliconId = gid;
            }
        }
    }

    /** 返回 p 所在配对组的其他成员名称（逗号连接），用于编辑框预填。 */
    public static synchronized String groupPartnerNames(Primer p) {
        if (p.ampliconId == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Primer q : primers) {
            if (q != p && p.ampliconId.equals(q.ampliconId)) {
                if (sb.length() > 0) sb.append(",");
                sb.append(q.name);
            }
        }
        return sb.toString();
    }

    // ---------- Ctrl+点击 快捷配对（v0.1.2） ----------

    /**
     * 把 a 与 b 直接连成一对（Ctrl+点击）：
     *  - 双方各自的既有配对先解除（保证一对一，避免出现串线的大组）；
     *  - 分配新 ampliconId，双向写 pairWith（随 BED 导出/导入复原）。
     */
    public static synchronized void pairPrimer(Primer a, Primer b) {
        if (a == null || b == null || a == b) return;
        // v0.1.4：只允许 F(+) 与 R(-) 异链配对，同链拒绝（弹窗提示，不做任何改动）
        if (a.strand == b.strand) {
            String tag = a.strand == '+' ? "F(+)" : "R(-)";
            javax.swing.JOptionPane.showMessageDialog(
                    org.broad.igv.ui.IGV.getMainFrame(),
                    "配对失败：" + a.name + " 与 " + b.name + " 同为 " + tag + " 链。\n"
                            + "只允许 F(+) 与 R(-) 异链配对，请一条选正向引物、一条选反向引物。",
                    "引物配对", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        unpairAll(a);
        unpairAll(b);
        String aid = nextAmplicon();
        a.ampliconId = aid;
        b.ampliconId = aid;
        a.pairWith = b.name;
        b.pairWith = a.name;
        evaluatePairs();
        refresh();
        autosave(true);
    }

    /**
     * 取消 a 与 b 之间的配对（Ctrl+Shift+点击）：
     *  - 两者不同组或均未配对 → 静默忽略，不报错；
     *  - 同组 size==2 → 双方都散组；size>2 → a、b 移出，其余成员保留原组。
     */
    public static synchronized void unpairPrimer(Primer a, Primer b) {
        if (a == null || b == null || a == b) return;
        if (a.ampliconId == null || !a.ampliconId.equals(b.ampliconId)) return; // 无配对：忽略
        List<Primer> members = new ArrayList<Primer>();
        for (Primer q : primers) if (a.ampliconId.equals(q.ampliconId)) members.add(q);
        clearPair(a);
        clearPair(b);
        if (members.size() - 2 < 2) {
            for (Primer q : members) clearPair(q);   // 剩余不足 2 条 → 整组散开
        }
        evaluatePairs();
        refresh();
        autosave(true);
    }

    /** 解除 p 参与的所有配对：同组其他成员全部散组，双向清 pairWith。 */
    private static void unpairAll(Primer p) {
        if (p.ampliconId != null) {
            for (Primer q : primers) {
                if (q != p && p.ampliconId.equals(q.ampliconId)) clearPair(q);
            }
        }
        clearPair(p);
    }

    /**
     * v0.1.11：Ctrl+点击 增量配对（支持 1对多）：把 b 并入 a 所在配对组，不清除既有成员。
     *  - 仅允许 F(+)/R(-) 异链，同链拒绝并提示；
     *  - 若 a、b 各自已有不同配对组，则合并为同一组（实现 1v多 / 多v多 汇聚）；
     *  - 双向写 pairWith（随 BED 导出/导入复原）。
     */
    public static synchronized void mergePair(Primer a, Primer b) {
        if (a == null || b == null || a == b) return;
        if (a.strand == b.strand) { warnSameStrand(a, b); return; }
        String gid;
        if (a.ampliconId != null && b.ampliconId != null && !a.ampliconId.equals(b.ampliconId)) {
            String target = a.ampliconId;            // 合并 b 的组进 a 的组
            for (Primer q : primers) if (b.ampliconId.equals(q.ampliconId)) q.ampliconId = target;
            gid = target;
        } else {
            gid = (a.ampliconId != null) ? a.ampliconId
                    : (b.ampliconId != null ? b.ampliconId : nextAmplicon());
        }
        a.ampliconId = gid;
        b.ampliconId = gid;
        a.pairWith = b.name;
        b.pairWith = a.name;
        evaluatePairs();
        refresh();
        autosave(true);
    }

    private static void warnSameStrand(Primer a, Primer b) {
        String tag = a.strand == '+' ? "F(+)" : "R(-)";
        javax.swing.JOptionPane.showMessageDialog(
                org.broad.igv.ui.IGV.getMainFrame(),
                "配对失败：" + a.name + " 与 " + b.name + " 同为 " + tag + " 链。\n"
                        + "只允许 F(+) 与 R(-) 异链配对。",
                "引物配对", javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    /** v0.1.7：解除 p 的全部配对（同组成员同时散组，双向清 pairWith）；无配对静默返回。
     *  语义：删除其中一条的配对，另一条与它的配对必须同时清除。 */
    public static synchronized void unpairAllFor(Primer p) {
        if (p == null) return;
        if (p.ampliconId == null) return;   // 无配对：静默忽略
        unpairAll(p);
        evaluatePairs();
        refresh();
        autosave(true);
    }

    /** v0.1.9：把 p 从当前配对组中摘出（清 p 的 ampliconId/pairWith）；
     *  若原组剩余不足 2 条，剩余成员一并散组。用于编辑框重设配对前先脱离旧组。 */
    public static synchronized void detach(Primer p) {
        if (p == null || p.ampliconId == null) return;
        List<Primer> rest = new ArrayList<Primer>();
        for (Primer q : primers) {
            if (q != p && p.ampliconId.equals(q.ampliconId)) rest.add(q);
        }
        clearPair(p);
        if (rest.size() < 2) {
            for (Primer q : rest) clearPair(q);
        }
    }

    /**
     * v0.1.18：右键「取消该引物配对」——只把该引物移出其配对组、断开与之相关的所有连线，
     *  同组其余成员之间的连线（若仍 >=2 条）保留，不被一并散开。
     *  与 unpairAllFor（整组散开）的区别：仅影响该引物的连线，不波及其他成员间关系。
     *  未配对引物静默忽略；跨线程调用安全（经由线程安全的 refresh）。
     */
    public static synchronized void unpairForSelected(Primer p) {
        if (p == null || p.ampliconId == null) return;   // 未配对：静默忽略
        String gid = p.ampliconId;
        List<Primer> rest = new ArrayList<Primer>();
        for (Primer q : primers) {
            if (q != p && gid.equals(q.ampliconId)) rest.add(q);
        }
        clearPair(p);                                    // 清该引物自身配对
        for (Primer q : rest) {                          // 清伙伴指向该引物的 pairWith，避免悬空指针
            if (p.name.equals(q.pairWith)) q.pairWith = null;
        }
        if (rest.size() < 2) {                           // 其余不足 2 条 → 整组散开
            for (Primer q : rest) clearPair(q);
        }
        evaluatePairs();
        refresh();
        autosave(true);
    }

    private static void clearPair(Primer p) {
        p.ampliconId = null;
        p.pairWith = null;
    }

    // ---------- 批量操作（v0.1.8） ----------

    /** 删除全部未通过评估的引物（pass==false，含被二聚体 flag 的）。返回删除条数。
     *  同时清理被删引物遗留的孤立配对（残留 ampliconId/pairWith），避免悬空连线。
     *  注意：无撤销机制，调用方应在 UI 层先 confirm。 */
    public static synchronized int removeFailed() {
        int cnt = 0;
        java.util.List<Primer> keep = new java.util.ArrayList<Primer>();
        for (Primer p : primers) {
            if (p.pass) keep.add(p);
            else cnt++;
        }
        if (cnt == 0) return 0;
        primers.clear();
        primers.addAll(keep);
        cleanOrphanPairs();
        if (selected != null && !primers.contains(selected)) selected = null;
        refresh();
        autosave(true);
        return cnt;
    }

    /** 解除全部配对：所有引物 ampliconId/pairWith 置空，并重算二聚体标记。 */
    public static synchronized void clearAllPairs() {
        boolean any = false;
        for (Primer p : primers) {
            if (p.ampliconId != null || p.pairWith != null) any = true;
            p.ampliconId = null;
            p.pairWith = null;
        }
        if (!any) return;
        evaluatePairs();
        refresh();
        autosave(true);
    }

    /** 批量设置全部引物的测序读段长度 readLen（nt）。readLen>0 才生效；会改变 readRegion→布局/连线。 */
    public static synchronized void setAllReadLen(int len) {
        if (len <= 0) return;
        for (Primer p : primers) p.readLen = len;
        refresh();
        autosave(true);
    }

    /**
     * v0.1.14：仅对指定「套」（= 来源 BED 分组 Primer.group）生效的测序长度设置，按角色 R1/R2 分别赋值；
     * 其他 BED 套不受影响，也不改动全局默认配置。group 为 null 表示"未分组（手动添加）"那一套。
     * incLen=true 表示填写值含引物本身长度，实际延伸 = 填写值 - 引物长度（下限 0）。返回受影响条数。
     */
    public static synchronized int setReadLenForGroup(String group, int lenR1, int lenR2, boolean incLen) {
        int v1 = Math.max(0, lenR1), v2 = Math.max(0, lenR2);
        int cnt = 0;
        for (Primer p : primers) {
            String pg = p.group;
            boolean match = (group == null) ? (pg == null) : group.equals(pg);
            if (!match) continue;
            int raw = "R1".equals(p.role) ? v1 : v2;
            p.readLen = incLen ? Math.max(0, raw - (p.end - p.start)) : raw;
            cnt++;
        }
        if (cnt > 0) {
            refresh();
            autosave(true);
        }
        return cnt;
    }

    /**
     * v0.1.13：全部引物公用一套测序长度——按角色 R1/R2 分别赋值，并写入默认配置（新增引物沿用）。
     * incLen=true 表示填写值含引物本身长度，实际延伸 = 填写值 - 引物长度（下限 0）。
     */
    public static synchronized void setReadLenByRole(int lenR1, int lenR2, boolean incLen) {
        int v1 = Math.max(0, lenR1), v2 = Math.max(0, lenR2);
        for (Primer p : primers) {
            int raw = "R1".equals(p.role) ? v1 : v2;
            p.readLen = incLen ? Math.max(0, raw - (p.end - p.start)) : raw;
        }
        defaultReadF = v1;
        defaultReadR = v2;
        defaultIncludeLen = incLen;
        saveDefaults(defaultLen, defaultStrand, defaultRole, defaultReadF, defaultReadR, defaultIncludeLen);
        refresh();
        autosave(true);
    }

    /** 清理孤立配对：组内仅剩 1 条成员的 ampliconId 失去意义，清掉其 ampliconId/pairWith。 */
    private static void cleanOrphanPairs() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        for (Primer p : primers) {
            if (p.ampliconId != null) {
                counts.put(p.ampliconId, counts.getOrDefault(p.ampliconId, 0) + 1);
            }
        }
        for (Primer p : primers) {
            if (p.ampliconId != null && counts.get(p.ampliconId) < 2) {
                p.ampliconId = null;
                p.pairWith = null;
            }
        }
    }

    // ---------- 默认参数持久化（~/.igv_primer_defaults.properties） ----------

    private static File configFile() {
        return new File(System.getProperty("user.home"), ".igv_primer_defaults.properties");
    }

    /** 把当前对话框值写入配置文件，下次打开自动沿用 */
    public static void saveDefaults(int len, char strand, String role, int rf, int rr, boolean incLen) {
        defaultLen = len;
        defaultStrand = strand;
        defaultRole = role;
        defaultReadF = rf;
        defaultReadR = rr;
        defaultIncludeLen = incLen;
        try {
            Properties props = new Properties();
            props.setProperty("len", String.valueOf(len));
            props.setProperty("strand", String.valueOf(strand));
            props.setProperty("role", role);
            props.setProperty("readF", String.valueOf(rf));
            props.setProperty("readR", String.valueOf(rr));
            props.setProperty("includeLen", String.valueOf(incLen));
            props.store(new FileWriter(configFile()), "IGV primer designer defaults");
        } catch (Exception ignore) {
        }
    }

    /** v0.1.15：把当前失败判定阈值写回默认配置文件（read-modify-write，不破坏其他 key）。 */
    public static void saveFailConfig() {
        try {
            Properties props = new Properties();
            File f = configFile();
            if (f.exists()) {
                FileReader fr = new FileReader(f);
                try { props.load(fr); } finally { fr.close(); }
            }
            props.setProperty("fail.lenMin", String.valueOf(failLenMin));
            props.setProperty("fail.lenMax", String.valueOf(failLenMax));
            props.setProperty("fail.tmMin", String.valueOf(failTmMin));
            props.setProperty("fail.tmMax", String.valueOf(failTmMax));
            props.setProperty("fail.gcMin", String.valueOf(failGcMin));
            props.setProperty("fail.gcMax", String.valueOf(failGcMax));
            props.setProperty("fail.hairpinTh", String.valueOf(failHairpinTh));
            props.setProperty("fail.selfTh", String.valueOf(failSelfTh));
            props.setProperty("fail.hetero3pTh", String.valueOf(failHetero3pTh));
            props.setProperty("fail.heteroDgTh", String.valueOf(failHeteroDgTh));
            props.store(new FileWriter(f), "IGV primer designer defaults");
        } catch (Exception ignore) {
        }
    }

    public static void loadDefaults() {
        try {
            File f = configFile();
            if (!f.exists()) return;
            Properties props = new Properties();
            props.load(new FileReader(f));
            if (props.containsKey("len")) defaultLen = Integer.parseInt(props.getProperty("len"));
            if (props.containsKey("strand")) defaultStrand = props.getProperty("strand").charAt(0);
            if (props.containsKey("role")) defaultRole = props.getProperty("role");
            if (props.containsKey("readF")) defaultReadF = Integer.parseInt(props.getProperty("readF"));
            if (props.containsKey("readR")) defaultReadR = Integer.parseInt(props.getProperty("readR"));
            if (props.containsKey("includeLen")) defaultIncludeLen = Boolean.parseBoolean(props.getProperty("includeLen"));
            if (props.containsKey("autosave")) autosaveEnabled = Boolean.parseBoolean(props.getProperty("autosave"));
            if (props.containsKey("fail.lenMin")) failLenMin = Integer.parseInt(props.getProperty("fail.lenMin"));
            if (props.containsKey("fail.lenMax")) failLenMax = Integer.parseInt(props.getProperty("fail.lenMax"));
            if (props.containsKey("fail.tmMin")) failTmMin = Double.parseDouble(props.getProperty("fail.tmMin"));
            if (props.containsKey("fail.tmMax")) failTmMax = Double.parseDouble(props.getProperty("fail.tmMax"));
            if (props.containsKey("fail.gcMin")) failGcMin = Double.parseDouble(props.getProperty("fail.gcMin"));
            if (props.containsKey("fail.gcMax")) failGcMax = Double.parseDouble(props.getProperty("fail.gcMax"));
            if (props.containsKey("fail.hairpinTh")) failHairpinTh = Double.parseDouble(props.getProperty("fail.hairpinTh"));
            if (props.containsKey("fail.selfTh")) failSelfTh = Double.parseDouble(props.getProperty("fail.selfTh"));
            if (props.containsKey("fail.hetero3pTh")) failHetero3pTh = Integer.parseInt(props.getProperty("fail.hetero3pTh"));
            if (props.containsKey("fail.heteroDgTh")) failHeteroDgTh = Double.parseDouble(props.getProperty("fail.heteroDgTh"));
        } catch (Exception ignore) {
        }
    }

    /** 开关自动保存并写回配置文件 */
    public static void setAutosave(boolean on) {
        autosaveEnabled = on;
        try {
            Properties props = new Properties();
            File f = configFile();
            if (f.exists()) {
                FileReader fr = new FileReader(f);
                try { props.load(fr); } finally { fr.close(); }
            }
            props.setProperty("autosave", String.valueOf(on));
            props.store(new FileWriter(f), "IGV primer designer defaults");
        } catch (Exception ignore) {
        }
    }
}

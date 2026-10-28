package org.broad.igv.primer;

import org.broad.igv.primer.Primer.HeteroHit;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.TrackPanel;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

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
    public static Primer selected;
    public static String lastChr;   // 最近渲染的染色体（快速添加用）

    // 注：screenRects/screenRows 已下放到每条 PrimerTrack 实例（多轨各自记录，避免渲染时互相覆盖）。

    // 右键菜单"显示序列"开关：选中后在引物下方画碱基序列
    public static boolean showSeq = false;
    /** v0.1.70：每个配对组独占一行（多对引物不再挤同一行）；关=紧凑共行（旧行为）。 */
    public static boolean onePairPerRow = true;
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

    // MFEprimer 外部引擎（可选）：配置 exe 路径后用其热力学 dimer 计算替代内置估算；空=内置估算
    public static String mfeExePath = "";
    // 导出引物时自动跑一次 MFEprimer qc（替代导出后再手动 dimer 一遍）
    public static boolean mfeAutoQcOnExport = false;

    static {
        loadDefaults();
    }

    public static synchronized List<Primer> getPrimers() {
        return new ArrayList<Primer>(primers);
    }

    public static synchronized void add(Primer p) {
        if (p != null) dedupNames(java.util.Arrays.asList(p));   // v0.1.63：重名防护（防按名寻址连错实例）
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
        dedupNames(list);   // v0.1.63：重名防护（防按名寻址连错实例）
        primers.addAll(list);
        for (Primer p : list) {
            refreshSequence(p);   // v0.1.43：导入/恢复也要刷序列与指标（否则 p.seq 空 → 编辑框/导出 FASTA 全空）
            linkByName(p);        // 导入 BED 时按 pairWith 名回链配对（重连 ampliconId）
        }
        refresh();
        autosave(true);
    }

    // ---------- 分组（来源 BED 文件名）操作：多轨各自独立增删改/存关 ----------

    /** 返回指定分组（group）的引物列表（group==null 表示"未分组/手动添加"）。 */
    public static synchronized List<Primer> getPrimersByGroup(String group) {
        java.util.List<Primer> out = new java.util.ArrayList<Primer>();
        for (Primer p : primers) {
            boolean match = (group == null) ? (p.group == null) : group.equals(p.group);
            if (match) out.add(p);
        }
        return out;
    }

    /** 统计指定分组的引物条数。 */
    public static synchronized int countGroup(String group) {
        int n = 0;
        for (Primer p : primers) {
            boolean match = (group == null) ? (p.group == null) : group.equals(p.group);
            if (match) n++;
        }
        return n;
    }

    /** 移除整个分组的引物（关闭轨道时调用）；返回删除条数。同步清理孤立配对并刷新。 */
    public static synchronized int removeGroup(String group) {
        java.util.List<Primer> keep = new java.util.ArrayList<Primer>();
        int cnt = 0;
        for (Primer p : primers) {
            boolean match = (group == null) ? (p.group == null) : group.equals(p.group);
            if (match) cnt++; else keep.add(p);
        }
        if (cnt == 0) return 0;
        primers.clear();
        primers.addAll(keep);
        if (selected != null && !primers.contains(selected)) selected = null;
        cleanOrphanPairs();   // 被删分组遗留的孤立配对（残留 ampliconId/pairWith）清理
        refresh();
        autosave(true);
        return cnt;
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
            // v0.1.52：不再无条件清空——从 FASTA 导入（或手填）的序列在没有加载基因组时应保留
            if (p.seq == null) p.seq = "";
        } else {
            byte[] b = g.getSequence(p.chr, p.start, p.end);
            String fwd = b == null ? "" : new String(b).toUpperCase();
            // v0.1.43：p.seq 统一存「实际引物序列 5'->3'」。反向链引物 = 正链区间序列的反向互补，
            // 这样编辑框/导出/轨内显示/指标计算（Tm/GC/hairpin/self）全部一致且生物学正确。
            p.seq = (p.strand == '+' || fwd.isEmpty()) ? fwd : PrimerMetrics.revComp(fwd);
        }
        PrimerMetrics.evaluate(p);
    }

    public static void refreshAll() {
        for (Primer p : primers) refreshSequence(p);
        evaluatePairs();
        refresh();
    }

    /**
     * v0.1.29 卡死帮凶修复：二聚体评估结果缓存（key = 两条引物 seq 拼接）。
     * evaluatePairs 是 O(n²)：200 条引物 ≈ 2 万对 × dimerDG/3'互补 逐碱基对齐，在 EDT 上一次要 1~2 秒；
     * 序列没变时直接复用缓存，第二次起毫秒级。仅当某条引物序列被重算（refreshSequence）后对应 key 才会失效（自然 miss）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, double[]> dimerCache =
            new java.util.concurrent.ConcurrentHashMap<String, double[]>();
    // MFEprimer 结果缓存（key=seqA\u0001seqB → [ΔG, 3'comp]），优先于 dimerCache 使用
    private static final java.util.concurrent.ConcurrentHashMap<String, double[]> mfeCache =
            new java.util.concurrent.ConcurrentHashMap<String, double[]>();

    /**
     * 配对二聚体评估（按需调用，平时不运行）：
     *  - 仅当用户显式触发（右键「评估二聚体」或编辑面板「评估二聚体」按钮）时才执行 O(n²) 全量计算；
     *  - 先清空所有引物的上次二聚体标记，再逐对计算 heteroDG / 3'互补，命中阈值写 dimerReason；
     *  - 全局重算 pass = 非二聚体原因(failReasons)为空 且 dimerReason 为空。
     * 序列没变时 dimerCache 命中，第二次起毫秒级。
     */
    public static void evaluatePairs() {
        java.util.Map<Primer, java.util.List<HeteroHit>> acc =
                new java.util.LinkedHashMap<Primer, java.util.List<HeteroHit>>();
        for (Primer p : primers) {
            p.heteroDG = 0;
            p.max3pComp = 0;
            p.dimerReason = "";
            acc.put(p, new java.util.ArrayList<HeteroHit>());
        }
        for (int i = 0; i < primers.size(); i++) {
            Primer a = primers.get(i);
            if (a.seq == null || a.seq.length() < 4) continue;   // 无序列（参考未加载）：跳过昂贵对齐
            for (int j = i + 1; j < primers.size(); j++) {
                Primer b = primers.get(j);
                if (b.seq == null || b.seq.length() < 4) continue;
                // v0.1.39：多重 PCR —— 异源二聚体对【全体系所有引物】计算，不限于配对/同染色体
                double[] res = heteroPairDG(a.seq, b.seq);
                int c3 = (int) res[1];
                a.heteroDG = Math.min(a.heteroDG, res[0]);
                a.max3pComp = Math.max(a.max3pComp, c3);
                b.heteroDG = Math.min(b.heteroDG, res[0]);
                b.max3pComp = Math.max(b.max3pComp, c3);
                acc.get(a).add(new HeteroHit(b.name, res[0], c3));
                acc.get(b).add(new HeteroHit(a.name, res[0], c3));
                if (c3 >= failHetero3pTh || res[0] <= failHeteroDgTh) {
                    a.dimerReason = "二聚体(3'comp=" + c3 + ")";
                    b.dimerReason = "二聚体(3'comp=" + c3 + ")";
                }
            }
        }
        // 每引物取 Top-5 最危险（ΔG 最负优先）
        for (Primer p : primers) {
            java.util.List<HeteroHit> lst = acc.get(p);
            java.util.Collections.sort(lst, new java.util.Comparator<HeteroHit>() {
                public int compare(HeteroHit x, HeteroHit y) { return Double.compare(x.dg, y.dg); }
            });
            p.topHetero = lst.size() <= 5 ? lst
                    : new java.util.ArrayList<HeteroHit>(lst.subList(0, 5));
            p.pass = p.failReasons.isEmpty() && p.dimerReason.isEmpty();
        }
    }

    /** 取一对引物序列的异源二聚体 [ΔG, 3'comp]：优先 mfe 缓存 → 内置缓存 → 实时算并缓存 */
    private static double[] heteroPairDG(String a, String b) {
        String key = a + "\u0001" + b;
        double[] r = mfeCache.get(key);
        if (r != null) return r;
        r = dimerCache.get(key);
        if (r != null) return r;
        r = new double[]{PrimerMetrics.dimerDG(a, b), PrimerMetrics.max3pComplement(a, b)};
        if (dimerCache.size() > 200000) dimerCache.clear();   // 防止序列反复编辑时无限膨胀
        dimerCache.put(key, r);
        return r;
    }

    /**
     * 若配置了 MFEprimer exe，运行 dimer 计算全体系二聚体 ΔG/3'comp 存入 mfeCache（key=seqA\u0001seqB）。
     * 失败（路径无效 / 进程异常 / 超时）则清空 mfeCache，之后 heteroPairDG 自动回退内置估算。
     * 默认不配置 → 直接返回（mfeCache 空），完全不影响内置路径。
     */
    public static void updateMfeCache() { updateMfeCache(getPrimers()); }
    public static void updateMfeCache(java.util.List<Primer> all) {
        mfeCache.clear();
        if (mfeExePath == null || mfeExePath.trim().isEmpty()) return;
        java.io.File exe = new java.io.File(mfeExePath.trim());
        if (!exe.exists() || !exe.isFile()) return;
        java.io.File fa = null, out = null;
        try {
            fa = java.io.File.createTempFile("mfe_in_", ".fasta");
            out = java.io.File.createTempFile("mfe_out_", ".txt");
            java.util.Map<String, String> seqOf = new java.util.LinkedHashMap<String, String>();
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (Primer p : all) {
                if (p.seq == null || p.seq.length() < 4) continue;
                String id = "P" + (idx++);
                seqOf.put(id, p.seq);
                sb.append(">").append(id).append("\n").append(p.seq).append("\n");
            }
            if (seqOf.isEmpty()) return;
            java.nio.file.Files.write(fa.toPath(), sb.toString().getBytes("UTF-8"));
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "dimer",
                    "-i", fa.getAbsolutePath(), "-o", out.getAbsolutePath(), "-p");
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            pr.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            String txt = new String(java.nio.file.Files.readAllBytes(out.toPath()), "UTF-8");
            java.util.regex.Pattern dimerPat = java.util.regex.Pattern
                    .compile("Dimer\\s+\\d+:\\s*(\\S+)\\s+x\\s+(\\S+)");
            java.util.regex.Pattern dgPat = java.util.regex.Pattern
                    .compile("Delta G\\s*=\\s*(-?[\\d.]+)");
            java.util.regex.Matcher dm = dimerPat.matcher(txt);
            while (dm.find()) {
                String na = dm.group(1), nb = dm.group(2);
                String sa = seqOf.get(na), sb2 = seqOf.get(nb);
                if (sa == null || sb2 == null) continue;
                int from = dm.end();
                java.util.regex.Matcher gm = dgPat.matcher(txt);
                double dg = 0;
                if (gm.find(from)) dg = Double.parseDouble(gm.group(1));
                double c3 = PrimerMetrics.max3pComplement(sa, sb2);   // 3'comp 用内置快速算，ΔG 用 MFEprimer
                mfeCache.put(sa + "\u0001" + sb2, new double[]{dg, c3});
            }
        } catch (Exception ex) {
            System.err.println("[MFEprimer] dimer 计算失败，退回内置估算: " + ex);
            mfeCache.clear();
        } finally {
            if (fa != null) fa.delete();
            if (out != null) out.delete();
        }
    }

    /**
     * 运行一次 MFEprimer 评估（spec / coverage / qc），后台执行不卡 UI，结束后弹报告。
     * 复用现有 mfeExePath 配置与「IGV 当前基因组」作为背景数据库，无需用户另给 FASTA。
     *
     * @param sub    MFEprimer 子命令：spec / coverage / qc
     * @param title  报告窗口标题
     * @param extra  附加命令行参数（如 spec 的 -s/-S/-c）
     */
    public static void runMfeAssess(final String sub, final String title, final List<String> extra) {
        runMfeAssess(sub, title, extra, null);
    }

    /**
     * 同 {@link #runMfeAssess(String, String, List)}，但 existing 非空时就地刷新该窗口
     * （重新检测用，避免关窗再弹窗闪烁），并在早退/失败/取消时恢复其「重新检测」按钮。
     */
    public static void runMfeAssess(final String sub, final String title, final List<String> extra, final MfeReportDialog existing) {
        if (mfeExePath == null || mfeExePath.trim().isEmpty()) {
            if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
            else JOptionPane.showMessageDialog(null, "尚未配置 MFEprimer 路径。\n请打开「MFEprimer 质量检查」面板，点击「⚙ 设置 MFEprimer 路径」按钮填写可执行文件绝对路径。");
            return;
        }
        final List<Primer> snap = getPrimers();
        if (snap.isEmpty()) {
            if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
            else JOptionPane.showMessageDialog(null, "当前没有可导评估的引物。");
            return;
        }
        // 轻量子命令：qc（二聚体+发夹）/ dimer / hairpin，都无需背景基因组，秒级
        final boolean light = "qc".equals(sub) || "dimer".equals(sub) || "hairpin".equals(sub);
        final MfeReportDialog[] target = {null};
        final Runnable rerun = new Runnable() {
            public void run() { runMfeAssess(sub, title, extra, target[0]); }   // target[0] = 当前显示的窗口
        };
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() {
                try {
                    File primers = MfeCli.writePrimerFasta(snap);
                    if (primers == null) {
                        if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
                        else JOptionPane.showMessageDialog(null, "没有可评估的引物序列（序列长度不足）。");
                        return null;
                    }

                    if (light) {
                        final String t = title;
                        if ("qc".equals(sub)) {
                            StringBuilder raw = new StringBuilder();
                            File outD = File.createTempFile("mfe_qc_dimer_", ".txt"); outD.deleteOnExit();
                            String[] r1 = MfeCli.run("dimer", primers, null, outD, java.util.Collections.singletonList("-p"));
                            String dRaw = r1[1] != null && !r1[1].isEmpty() ? r1[1] : r1[0];
                            raw.append("===== 二聚体 (dimer) =====\n").append(dRaw).append("\n");
                            // 发夹：单序列结构，无需基因组；单步失败不影响二聚体结果
                            try {
                                File outH = File.createTempFile("mfe_qc_hairpin_", ".txt"); outH.deleteOnExit();
                                String[] r2 = MfeCli.run("hairpin", primers, null, outH, null);
                                String hRaw = r2[1] != null && !r2[1].isEmpty() ? r2[1] : r2[0];
                                raw.append("===== 发夹 (hairpin) =====\n").append(hRaw).append("\n");
                            } catch (Exception hx) {
                                raw.append("===== 发夹 (hairpin) =====\n[hairpin 运行失败：").append(hx.getMessage()).append("]\n");
                            }
                            final String rawS = raw.toString();
                            final String summary = MfeReportDialog.summarize(dRaw, "");
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    if (existing != null) existing.replaceContent(t, summary, rawS);
                                    else { target[0] = new MfeReportDialog(t, summary, rawS, rerun); target[0].setVisible(true); }
                                }
                            });
                            return null;
                        }
                        // dimer / hairpin 单跑：无需基因组
                        String subLabel = "dimer".equals(sub) ? "二聚体" : "发夹";
                        File out = File.createTempFile("mfe_" + sub + "_", ".txt"); out.deleteOnExit();
                        String[] res = MfeCli.run(sub, primers, null, out, extra);
                        String body = res[1] != null && !res[1].isEmpty() ? res[1] : res[0];
                        final String rawS = "===== " + subLabel + " (" + sub + ") =====\n" + body + "\n";
                        final String summary = MfeReportDialog.summarize(body, "");
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                if (existing != null) existing.replaceContent(t, summary, rawS);
                                else { target[0] = new MfeReportDialog(t, summary, rawS, rerun); target[0].setVisible(true); }
                            }
                        });
                        return null;
                    }

                    // 其余 sub（spec / coverage）：需要背景基因组
                    // 大基因组尺寸警示：人类 ~3Gb 全量 dump 很慢，提示改用"仅含引物染色体"
                    long total = MfeGenome.totalBp();
                    boolean limit = false;
                    if (total > 150_000_000L) {
                        final int[] res = {-1};
                        try {
                            SwingUtilities.invokeAndWait(new Runnable() {
                                public void run() {
                                    res[0] = JOptionPane.showConfirmDialog(null,
                                            "当前基因组约 " + (total / 1_000_000) + " Mb，全量导出建索引可能很慢。\n"
                                            + "是否仅导出含引物的染色体（更快，但 off-target 只覆盖这些染色体）？\n"
                                            + "（选「否」= 全量导出；「取消」= 放弃本次评估）",
                                            "大基因组提示", JOptionPane.YES_NO_CANCEL_OPTION);
                                }
                            });
                        } catch (Exception ignore) {}
                        int r = res[0];
                        if (r == JOptionPane.CANCEL_OPTION) {
                            if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
                            return null;
                        }
                        limit = (r == JOptionPane.YES_OPTION);
                    }
                    File genome = MfeGenome.dump(limit);
                    if (genome == null) {
                        if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
                        else JOptionPane.showMessageDialog(null, "无法导出当前基因组（是否已加载参考序列？）");
                        return null;
                    }
                    File out = File.createTempFile("mfe_out_", ".txt");
                    out.deleteOnExit();
                    String[] res = MfeCli.run(sub, primers, genome, out, extra);
                    final String summary = MfeReportDialog.summarize(res[0], res[1]);
                    final String raw = (res[1] != null && !res[1].isEmpty() ? res[1] : res[0]);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (existing != null) existing.replaceContent(title, summary, raw);
                            else { target[0] = new MfeReportDialog(title, summary, raw, rerun); target[0].setVisible(true); }
                        }
                    });
                } catch (Exception ex) {
                    final String msg = ex.getMessage();
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            if (existing != null) { existing.restoreRerunButton(); existing.restoreFuncButtons(); }
                            else JOptionPane.showMessageDialog(null, "MFEprimer 评估失败: " + msg);
                        }
                    });
                }
                return null;
            }
        }.execute();
    }

    /** 打开 MFEprimer 综合面板（右键「MFEprimer 质量检查」直接打开，空面板，点功能按钮开始评估）。 */
    public static void openMfePanel() {
        new MfeReportDialog("MFEprimer 质量检查", true).setVisible(true);
    }

    /** 导出引物时若开启自动 qc 且已配置 MFEprimer，则后台跑一次 qc（不弹"未配置"提示）。 */
    public static void maybeAutoQcOnExport() {
        if (mfeAutoQcOnExport && mfeExePath != null && !mfeExePath.trim().isEmpty()) {
            runMfeAssess("qc", "MFEprimer 质量检查 (qc)", java.util.Collections.emptyList());
        }
    }

    /**
     * 清空所有引物的二聚体评估标记（O(n)，不跑 O(n²)）：配对结构或序列变化后调用，
     * 避免陈旧二聚体 FAIL 标记残留。之后用户显式「评估二聚体」才会重新计算。
     */
    public static synchronized void resetDimerFlags() {
        for (Primer p : primers) {
            p.heteroDG = 0;
            p.max3pComp = 0;
            p.dimerReason = "";
            if (p.failReasons == null) p.failReasons = "";
            p.pass = p.failReasons.isEmpty();
        }
    }

    /**
     * 仅评估单条引物 p 与全部现有引物的二聚体（O(n)），结果写入 p 自身（不改全局）；
     * 用于添加/编辑面板的实时预览，避免每次跑 O(n²) 全量。复用 dimerCache。
     */
    public static synchronized void evaluateOneAgainstAll(Primer p) {
        p.heteroDG = 0;
        p.max3pComp = 0;
        p.dimerReason = "";
        java.util.List<HeteroHit> lst = new java.util.ArrayList<HeteroHit>();
        if (p.seq == null || p.seq.length() < 4) {
            p.topHetero = lst;
            if (p.failReasons == null) p.failReasons = "";
            p.pass = p.failReasons.isEmpty();
            return;
        }
        for (Primer q : primers) {
            if (q == p) continue;
            if (q.seq == null || q.seq.length() < 4) continue;
            // v0.1.39：多重 PCR —— 与【全体系所有其他引物】计算异源二聚体，不限于配对/同染色体
            double[] res = heteroPairDG(p.seq, q.seq);
            int c3 = (int) res[1];
            p.heteroDG = Math.min(p.heteroDG, res[0]);
            p.max3pComp = Math.max(p.max3pComp, c3);
            lst.add(new HeteroHit(q.name, res[0], c3));
            if (c3 >= failHetero3pTh || res[0] <= failHeteroDgTh) {
                p.dimerReason = "二聚体(3'comp=" + c3 + ")";
            }
        }
        java.util.Collections.sort(lst, new java.util.Comparator<HeteroHit>() {
            public int compare(HeteroHit x, HeteroHit y) { return Double.compare(x.dg, y.dg); }
        });
        p.topHetero = lst.size() <= 5 ? lst
                : new java.util.ArrayList<HeteroHit>(lst.subList(0, 5));
        if (p.failReasons == null) p.failReasons = "";
        p.pass = p.failReasons.isEmpty() && p.dimerReason.isEmpty();
    }

    /**
     * 轻量化刷新（v0.1.17 升级为多轨）：
     *  - 仅当某条引物轨"高度（行数）"发生变化（新增/删除导致出现或消失一行）时，才调用昂贵的 IGV.doRefresh()
     *    （它会重绘全部轨道并重载数据，是添加/点击卡顿的根因）；
     *  - 其余改动（改色/配对/选择/同排新增等不影响行数）只重绘各引物轨面板，不碰其他轨道。
     *  多轨刷新逻辑委托给 PrimerTrack.refreshAllTracks（逐轨计算高度、决定是否全量/局部重绘，且均走 EDT）。
     */
    public static void refresh() {
        PrimerTrack.refreshAllTracks(lastChr);
        autosave(false);   // 节流自动保存（磁盘 I/O，保持同步、不进 EDT）
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

    /**
     * v0.1.66：primer3 分组续号——返回该分组下一个可用的引物对编号（从 1 起）。
     *
     * 根因（老板截图实锤）：二次设计时 Pair.toPrimers 用结果集内序号 idx+1 命名
     * （group_P1_F/P1_R），第二次又从 P1 开始，撞名后 unique()/dedupNames 只能加
     * _2 后缀（P1_F_2、P1_R_2）——丑且断号。
     *
     * 规则：扫全部引物名，匹配 ^group_P(\d+)(_[FR])?(_\d+)?$（分组名前缀锚定，
     * 兼容 dedupNames 的 _2 后缀名与单引物只有一侧的情况），取最大编号 + 1；
     * 无匹配返回 1。BED 导入的同前缀名同样计入（续号全局一致）。
     */
    public static synchronized int nextPairNumber(String group) {
        if (group == null) return 1;
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(group) + "_P(\\d+)(?:_[FR])?(?:_\\d+)?$");
        int max = 0;
        for (Primer p : primers) {
            if (p.name == null) continue;
            java.util.regex.Matcher m = pat.matcher(p.name);
            if (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (Exception ignore) {
                }
            }
        }
        return max + 1;
    }

    /**
     * v0.1.67：按基因组坐标命中引物（不分行；行感知命中在 PrimerTrack.hitTest(x,y,chr)）。
     * lastFirst=true 时**倒序**遍历——后加入/后画的优先，与绘制"上层优先"一致；
     * tooltip 的 bp 兜底路径专用（根因：旧兜底正序返回先加入者，P1_F/P2_F 同在视野
     * 左侧时鼠标停在 P2_F 上也显示 P1_F 信息，老板截图实锤串显）。
     */
    public static synchronized Primer hitByPosition(List<Primer> ps, String chr, int bp, boolean lastFirst) {
        if (ps == null || chr == null) return null;
        if (lastFirst) {
            for (int i = ps.size() - 1; i >= 0; i--) {
                Primer p = ps.get(i);
                if (chr.equals(p.chr) && bp >= p.start && bp < p.end) return p;
            }
        } else {
            for (Primer p : ps) {
                if (chr.equals(p.chr) && bp >= p.start && bp < p.end) return p;
            }
        }
        return null;
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
        c.pairedWith = new java.util.LinkedHashSet<String>();   // 复制体独立，不污染原连线
        c.name = uniqueIncrementedName(src.name);
        Integer r = PrimerTrack.allScreenRows().get(src);
        c.rowOverride = (r == null) ? null : (r + 1);   // 紧贴原引物下方一行
        primers.add(c);
        refreshSequence(c);
        refresh();
        autosave(true);
    }

    private static int groupSeq = 0;
    public static synchronized String nextAmplicon() {
        String id;
        do { id = "G" + (++groupSeq); } while (idInUse(id));
        return id;
    }
    private static boolean idInUse(String id) {
        for (Primer p : primers) if (id.equals(p.ampliconId)) return true;
        return false;
    }

    /** 按名称回链配对（导入/新增后调用）：遍历 p.pairedWith 里记录的伙伴名，确保双方在集合中互相引用（边对称）。 */
    public static synchronized void linkByName(Primer p) {
        if (p.pairedWith == null || p.pairedWith.isEmpty()) return;
        for (String nm : new java.util.ArrayList<String>(p.pairedWith)) {
            for (Primer q : primers) {
                if (q != p && nm.equals(q.name)) {
                    if (q.pairedWith == null) q.pairedWith = new java.util.LinkedHashSet<String>();
                    q.pairedWith.add(p.name);   // 保证边对称
                    break;   // v0.1.63：同名多实例时只连第一个——否则每个同名副本都被加边，边必炸
                }
            }
        }
    }

    /**
     * v0.1.63：同组编号配对修复（primer3 设计结果专用）。
     * 背景：P{n}_F/P{n}_R 是设计时同批生成的配对，但 Ctrl+点击误连 / BED 导入带入的历史错边，
     * 会让 F 连到别的编号的 R（实例：P3_F 连了 P1_R/P2_R/P4_R，同编号 P3_R 反而孤立）。
     * 保守规则（只修高置信度错误，不误伤合法用法）：
     *  - 仅处理名字匹配 {@code <前缀>_P<数字>_F / _R} 的引物；
     *  - 同一编号 F、R 都存在 → 清掉双方全部旧边，重建唯一 F↔R 边；
     *  - 编号只有单侧 → 不动（单引物设计结果）；
     *  - 非该命名风格（如 Rv0678-6 手动多重 PCR 的 1 对多）→ 完全不碰。
     * 返回修复的编号数（0=无需修复）。
     */
    public static synchronized int repairNumberedPairs(String group) {
        java.util.Map<Integer, Primer> fs = new java.util.LinkedHashMap<Integer, Primer>();
        java.util.Map<Integer, Primer> rs = new java.util.LinkedHashMap<Integer, Primer>();
        for (Primer p : primers) {
            if (group == null ? p.group != null : !group.equals(p.group)) continue;
            int[] nn = parseNumbered(p.name);
            if (nn == null) continue;
            (nn[1] > 0 ? fs : rs).put(nn[0], p);
        }
        int fixed = 0;
        for (java.util.Map.Entry<Integer, Primer> en : fs.entrySet()) {
            Primer f = en.getValue();
            Primer r = rs.get(en.getKey());
            if (r == null) continue;                      // 单侧：单引物设计，保持原样
            // v0.1.65：语义修正——仅当同编号伴侣存在但**未互连**时才清边重建。
            // 旧逻辑「非唯一单边就重建」会把 v0.1.65 去重后的合法 1-F-多-R 结构
            // （P1_F 连 P1_R..P5_R，多条 R 共享一条同序列 F）错修成单边。
            // 已互连即合法（1 对多是正常结构），不动。
            boolean linked = f.pairedWith != null && f.pairedWith.contains(r.name)
                    && r.pairedWith != null && r.pairedWith.contains(f.name);
            if (linked) continue;
            dropAllEdges(f);
            dropAllEdges(r);
            if (f.pairedWith == null) f.pairedWith = new java.util.LinkedHashSet<String>();
            if (r.pairedWith == null) r.pairedWith = new java.util.LinkedHashSet<String>();
            f.pairedWith.add(r.name);
            r.pairedWith.add(f.name);
            fixed++;
        }
        if (fixed > 0) {
            resetDimerFlags();
            refresh();
            autosave(true);
        }
        return fixed;
    }

    /** 解析 {@code <前缀>_P<数字>_F/_R} → {编号, +1/-1}；非该风格返回 null。 */
    static int[] parseNumbered(String name) {
        if (name == null) return null;
        int i = name.lastIndexOf("_P");
        if (i < 0) return null;
        int j = i + 2, k = j;
        while (k < name.length() && Character.isDigit(name.charAt(k))) k++;
        if (k == j || k + 2 != name.length()) return null;   // 数字后必须恰为 _F/_R
        if (name.charAt(k) != '_') return null;
        char d = name.charAt(k + 1);
        if (d != 'F' && d != 'R') return null;
        try {
            return new int[]{Integer.parseInt(name.substring(j, k)), d == 'F' ? 1 : -1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 清掉 p 的全部边（双向：同时从每个对端的 pairedWith 移除 p.name）。 */
    private static void dropAllEdges(Primer p) {
        if (p.pairedWith == null) return;
        for (String nm : new java.util.ArrayList<String>(p.pairedWith)) {
            Primer q = byName(nm);
            if (q != null && q != p && q.pairedWith != null) q.pairedWith.remove(p.name);
        }
        p.pairedWith.clear();
    }

    /** 按名字找引物（重名返回第一个）。 */
    private static Primer byName(String name) {
        for (Primer q : primers) if (name.equals(q.name)) return q;
        return null;
    }

    /**
     * v0.1.63：入列前去重——store 内已存在的名字自动加 _2/_3 后缀并占用。
     * 根因：pairedWith 按名字寻址，一旦出现同名引物，linkByName/渲染的 nameMap 都会连到
     * 错误实例（幽灵连线的放大器）。used 集合失效的路径（BED 导入分组名不匹配等）曾让
     * 同名引物叠加进 store。
     */
    private static int dedupNames(List<Primer> list) {
        java.util.Set<String> used = new java.util.HashSet<String>();
        for (Primer p : primers) if (p.name != null) used.add(p.name);
        int renamed = 0;
        for (Primer p : list) {
            if (p.name == null) continue;
            if (used.add(p.name)) continue;
            for (int i = 2; i < 1000; i++) {
                String c = p.name + "_" + i;
                if (used.add(c)) { p.name = c; renamed++; break; }
            }
        }
        return renamed;
    }

    /**
     * v0.1.65：primer3 结果加入前同序列去重。
     * primer3 对多个候选产物常挑同一条最优 F（或 R）——直接加入会产生多条
     * chr/start/end/strand/seq 完全相同的冗余引物（BED 多条重复、悬浮提示同位串显）。
     * 规则：与同分组已有引物 + 本批次内先加入者按五元组全同判重，保留首条；
     * 被合并引物的配对边在全体引物（批次保留者 + store 已有）中重连到保留者
     * （对称边由随后 addAll 的 linkByName 补齐）。返回合并掉的数量。
     */
    public static synchronized int mergeDuplicatePrimers(List<Primer> add, String group) {
        java.util.Map<String, Primer> seen = new java.util.HashMap<String, Primer>();
        for (Primer p : primers) {
            if (group == null ? p.group != null : !group.equals(p.group)) continue;
            seen.put(primerFingerprint(p), p);
        }
        java.util.List<Primer> keep = new java.util.ArrayList<Primer>();
        java.util.Map<String, String> rename = new java.util.LinkedHashMap<String, String>();
        int merged = 0;
        for (Primer p : add) {
            Primer first = seen.get(primerFingerprint(p));
            if (first == p) { keep.add(p); continue; }   // 已在 store 中的同对象：保留（幂等）
            if (first != null) {
                if (p.name != null && !p.name.equals(first.name)) rename.put(p.name, first.name);
                merged++;
            } else {
                seen.put(primerFingerprint(p), p);
                keep.add(p);
            }
        }
        if (merged == 0) return 0;
        for (Primer p : keep) remapEdges(p, rename);
        for (Primer p : primers) remapEdges(p, rename);   // store 中指向旧名的边也改连
        add.clear();
        add.addAll(keep);
        return merged;
    }

    /** 引物身份指纹：位置+方向+序列全同视为同一条引物。 */
    private static String primerFingerprint(Primer p) {
        return p.chr + "|" + p.start + "|" + p.end + "|" + p.strand + "|" + (p.seq == null ? "" : p.seq);
    }

    /** 把 p.pairedWith 中的旧名按 rename 映射替换为新名（保持集合去重与顺序）。 */
    private static void remapEdges(Primer p, java.util.Map<String, String> rename) {
        if (p.pairedWith == null || p.pairedWith.isEmpty() || rename.isEmpty()) return;
        boolean hit = false;
        for (String nm : p.pairedWith) if (rename.containsKey(nm)) { hit = true; break; }
        if (!hit) return;
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        for (String nm : p.pairedWith) out.add(rename.containsKey(nm) ? rename.get(nm) : nm);
        p.pairedWith.clear();
        p.pairedWith.addAll(out);
    }

    /** 按名称列表配对（编辑框保存 / 新建时调用）：p 与每个异链且能形成 PCR 产物的同名引物各加一条独立边（1:N 自然支持）。 */
    public static synchronized void linkByNames(Primer p, String[] names) {
        if (p.pairedWith == null) p.pairedWith = new java.util.LinkedHashSet<String>();
        else p.pairedWith.clear();
        for (String nm : names) {
            nm = nm.trim();
            if (nm.isEmpty()) continue;
            for (Primer q : primers) {
                if (q != p && nm.equals(q.name) && q.strand != p.strand) {
                    Primer f = p.strand == '+' ? p : q;
                    Primer r = p.strand == '+' ? q : p;
                    if (canFormProduct(f, r)) addEdge(p, q);   // 仅当能形成 PCR 产物才连
                }
            }
        }
    }

    /**
     * 返回编辑框「配对引物名称」预填值：列出与本引物直接相连的所有对端名称（pairedWith 边集合），
     * 逗号分隔。一条引物可同时连多条（1 对多），全部列出；无配对返回空串。
     */
    public static synchronized String groupPartnerNames(Primer p) {
        if (p.pairedWith == null || p.pairedWith.isEmpty()) return "";
        return String.join(", ", p.pairedWith);
    }

    // ---------- 配对（边模型，v0.1.41） ----------
    // 每条 F-R 连线是独立的「边」，存于两端 Primer.pairedWith 集合。一个引物可同时持有多条边（1 对多），
    // 增/删只作用于单条边，不影响该引物的其他边。布局按边的连通分量同排；渲染逐边画线。

    /** 判断 f(+)/r(-) 能否形成 PCR 产物：要求 R.end - F.start > 0。 */
    private static boolean canFormProduct(Primer f, Primer r) {
        return (r.end - f.start) > 0;
    }

    /** 两引物之间是否已存在一条连线（边）。 */
    public static boolean isPaired(Primer a, Primer b) {
        return a.pairedWith != null && a.pairedWith.contains(b.name);
    }

    /** 加一条边（双向、对称）。调用方需先校验异链 + 可形成 PCR 产物。 */
    private static void addEdge(Primer a, Primer b) {
        if (a.pairedWith == null) a.pairedWith = new java.util.LinkedHashSet<String>();
        if (b.pairedWith == null) b.pairedWith = new java.util.LinkedHashSet<String>();
        a.pairedWith.add(b.name);
        b.pairedWith.add(a.name);
    }

    /** 删一条边（双向、对称）。无此边则静默。 */
    private static void removeEdge(Primer a, Primer b) {
        if (a.pairedWith != null) a.pairedWith.remove(b.name);
        if (b.pairedWith != null) b.pairedWith.remove(a.name);
    }

    /** 解除 p 参与的全部边（清 p 的 pairedWith，并清各伙伴指向 p 的引用）。 */
    private static void removeAllEdges(Primer p) {
        if (p.pairedWith == null) return;
        java.util.List<String> partners = new java.util.ArrayList<String>(p.pairedWith);
        p.pairedWith.clear();
        for (String nm : partners) {
            for (Primer q : primers) {
                if (nm.equals(q.name) && q.pairedWith != null) q.pairedWith.remove(p.name);
            }
        }
    }

    private static void clearEdges(Primer p) {
        if (p.pairedWith != null) p.pairedWith.clear();
    }

    /**
     * Ctrl+点击 切换 a 与 b 之间的一条边（v0.1.41 边模型）：
     *  - 已连 → 只删 a-b 这一条边，不影响 a 或 b 的其他边；
     *  - 未连 → 校验异链 + 可形成 PCR 产物后，仅加 a-b 这一条边（天然支持 1 对多：R1 可同时连 F1/F2/F3）。
     *  同链拒绝（弹窗提示）。
     */
    public static synchronized void toggleEdge(Primer a, Primer b) {
        if (a == null || b == null || a == b) return;
        if (isPaired(a, b)) {
            removeEdge(a, b);
        } else {
            if (a.strand == b.strand) { warnSameStrand(a, b); return; }
            Primer f = a.strand == '+' ? a : b;
            Primer r = a.strand == '+' ? b : a;
            if (!canFormProduct(f, r)) {
                javax.swing.JOptionPane.showMessageDialog(
                        org.broad.igv.ui.IGV.getMainFrame(),
                        "配对失败：" + f.name + "(F) 与 " + r.name + "(R) 之间无法形成 PCR 产物\n"
                                + "（要求 R.end - F.start > 0，当前 = " + (r.end - f.start) + "）。\n"
                                + "请确认正向引物位于反向引物上游。",
                        "引物配对", javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            addEdge(a, b);
        }
        resetDimerFlags();
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

    /** 右键「取消该引物配对」/编辑框重设前脱离：解除该引物参与的全部边（不影响其他引物之间的边）。 */
    public static synchronized void unpairForSelected(Primer p) {
        if (p == null || p.pairedWith == null || p.pairedWith.isEmpty()) return;   // 未配对：静默忽略
        removeAllEdges(p);
        resetDimerFlags();
        refresh();
        autosave(true);
    }

    /** 解除 p 的全部配对（与 unpairForSelected 语义一致：仅清 p 参与的边）。保留别名便于调用方。 */
    public static synchronized void unpairAllFor(Primer p) {
        unpairForSelected(p);
    }

    /** 编辑框保存前：让 p 脱离所有旧连线，随后由 linkByNames 重建新边。 */
    public static synchronized void detach(Primer p) {
        if (p == null) return;
        removeAllEdges(p);
    }

    /** 解除全部配对：清掉所有引物的边，并重算二聚体标记。 */
    public static synchronized void clearAllPairs() {
        boolean any = false;
        for (Primer p : primers) {
            if (p.pairedWith != null && !p.pairedWith.isEmpty()) any = true;
            clearEdges(p);
        }
        if (!any) return;
        resetDimerFlags();   // 全部取消配对：清二聚体标记
        refresh();
        autosave(true);
    }

    // ---------- 批量操作（v0.1.8） ----------

    /** 删除全部未通过评估的引物（pass==false，含被二聚体 flag 的）。返回删除条数。
     *  同时清理被删引物遗留的孤立配对（残留边），避免悬空连线。
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

    /** 清理孤立配对：去掉指向已删引物（名称无对应 Primer）的悬空边，保证每条边两端都存在。 */
    private static void cleanOrphanPairs() {
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (Primer p : primers) names.add(p.name);
        for (Primer p : primers) {
            if (p.pairedWith == null) continue;
            java.util.List<String> bad = new java.util.ArrayList<String>();
            for (String nm : p.pairedWith) if (!names.contains(nm)) bad.add(nm);
            for (String nm : bad) p.pairedWith.remove(nm);
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
            props.setProperty("mfe.exePath", mfeExePath == null ? "" : mfeExePath);
            props.setProperty("mfe.autoQcOnExport", String.valueOf(mfeAutoQcOnExport));
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
            if (props.containsKey("mfe.exePath")) mfeExePath = props.getProperty("mfe.exePath");
            if (props.containsKey("mfe.autoQcOnExport")) mfeAutoQcOnExport = Boolean.parseBoolean(props.getProperty("mfe.autoQcOnExport"));
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

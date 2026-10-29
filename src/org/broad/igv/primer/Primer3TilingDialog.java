package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.2.0：primer3 平铺设计（Tiling）——给定一个大的目标区域，自动划分为互相重叠的
 * 产物窗口序列，逐窗调 primer3（区内模式：设计带固定在窗口两端内侧），把整个区域覆盖住。
 * 典型用途：覆盖整个基因/外显子群/变异簇的重测序 amplicon 方案。
 *
 * 划窗规则（{@link #tileWindows}，纯函数可测）：
 *  - 区域 ≤ 产物 max → 单窗（不切）；
 *  - 否则窗长取产物 max（窗数最少），步长 = 窗长 − 重叠；
 *  - 末窗剩余不足产物 min 时，末窗起点左移使末窗长恰为产物 min
 *    （起点不会越过上一窗起点，相邻重叠不增）；
 *  - 窗数上限 {@link #MAX_WINDOWS}（防超大区域把 primer3 拖死）。
 *
 * 每窗设计（与 Primer3Dialog 区内模式一致）：
 *  - 产物区间 = 窗口；左/右设计带 = 窗口两端内侧（带宽 = min(窗长, max(lenMax, min(200, 窗长/2)))）；
 *  - SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 白名单 + PRIMER_PRODUCT_SIZE_RANGE 与用户产物范围取交；
 *  - 0 对时按降级阶梯重试（复用 Primer3Dialog.relaxAt，区域约束永不动）；
 *  - 成功窗的对坐标统一换算为基因组坐标，T 编号连续（T1..Tk），交
 *    Primer3ResultDialog（tag="T"）勾选落轨——相邻窗重叠区若挑中同一引物，
 *    落轨时自动合并去重（PrimerStore.mergeDuplicatePrimers）。
 *
 * 重叠语义：重叠指「产物窗口（设计区间）」的重叠；实际扩增子重叠 ≈ 该值 ± 设计带宽度，
 * 结果表里有每对引物的精确位置，可自行核对。
 */
public class Primer3TilingDialog {

    /** 窗数上限（防超大区域一次跑几千个 primer3 调用）。 */
    public static final int MAX_WINDOWS = 500;

    /**
     * 划窗（纯函数，无 IO）。
     * @param s,e      目标区域（1-based inclusive）
     * @param ampMin   产物长度 min（也是末窗最短长度）
     * @param ampMax   产物长度 max（= 常规窗长）
     * @param overlap  相邻窗重叠 bp（0 ≤ overlap ≤ ampMax-50 由调用方校验；此处仅防死循环）
     * @return 窗口列表（每个 int[]{ws, we}，1-based inclusive，按位置升序）；参数非法返回空表
     */
    public static List<int[]> tileWindows(int s, int e, int ampMin, int ampMax, int overlap) {
        List<int[]> out = new ArrayList<int[]>();
        if (s <= 0 || e < s || ampMin <= 0 || ampMax < ampMin || overlap < 0) return out;
        int L = e - s + 1;
        if (L <= ampMax) {          // 整段一窗装得下：不切
            out.add(new int[]{s, e});
            return out;
        }
        int windowLen = ampMax;
        int step = Math.max(1, windowLen - overlap);
        int ws = s;
        while (true) {
            int rem = e - ws + 1;
            if (rem <= windowLen) {
                if (rem >= ampMin || out.isEmpty()) {
                    out.add(new int[]{ws, e});
                } else {
                    // 末窗过短：起点左移使末窗长≈ampMin（不越过上一窗起点）
                    int prevStart = out.get(out.size() - 1)[0];
                    int nws = Math.max(prevStart + 1, e - ampMin + 1);
                    out.add(new int[]{nws, e});
                }
                break;
            }
            out.add(new int[]{ws, ws + windowLen - 1});
            ws += step;
        }
        return out;
    }

    /** 入口：打开平铺设计对话框（结果默认进 sourceGroup 对应轨；null=手动添加轨）。 */
    public static void open(String sourceGroup) {
        if (!Primer3Core.available()) {
            JOptionPane.showMessageDialog(IGV.getMainFrame(),
                    "primer3_core.exe 不可用（jar 资源缺失或无法释放到 ~/.igv_primer3/）。",
                    "primer3 平铺设计", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Dlg(sourceGroup).setVisible(true);
    }

    private static class Dlg extends JDialog {
        private final String sourceGroup;
        private final JTextField chrF = new JTextField(6);
        private final JTextField groupF = new JTextField(10);
        private final JTextField sF = new JTextField(10);
        private final JTextField eF = new JTextField(10);
        private final JTextField ampMinF = new JTextField("400", 5);
        private final JTextField ampMaxF = new JTextField("700", 5);
        private final JTextField overlapF = new JTextField("100", 5);
        private final JTextField perWinF = new JTextField("1", 3);
        // 引物参数（与 Primer3Dialog 默认一致）
        private final JTextField lenMinF = new JTextField("18", 4);
        private final JTextField lenOptF = new JTextField("20", 4);
        private final JTextField lenMaxF = new JTextField("27", 4);
        private final JTextField tmMinF = new JTextField("57", 4);
        private final JTextField tmOptF = new JTextField("60", 4);
        private final JTextField tmMaxF = new JTextField("63", 4);
        private final JTextField gcMinF = new JTextField("30", 4);
        private final JTextField gcMaxF = new JTextField("70", 4);
        private final JCheckBox relaxChk = new JCheckBox("单窗 0 对时自动降级重试（区域约束不动）", true);
        private final JLabel statusL = new JLabel(" ");
        private final JLabel countL = new JLabel(" ");
        private final JButton go = new JButton("开始平铺设计");

        Dlg(String sourceGroup) {
            super(IGV.getMainFrame(), "primer3 平铺设计（Tiling 覆盖目的区域）", true);
            this.sourceGroup = sourceGroup;
            groupF.setText(sourceGroup == null ? "" : sourceGroup);
            setLayout(new BorderLayout(6, 6));

            // ---- 区域预填：优先鼠标选区（不钳屏幕——平铺就是给大区域用的），无选区用当前视口 ----
            String[] sel = PrimerEditTool.currentSelection();
            String[] vp = Primer3Dialog.currentViewRange();
            if (sel != null && vp != null && !sel[0].equals(vp[0])) sel = null;
            String[] src = sel != null ? sel : vp;
            if (src != null) {
                chrF.setText(src[0]);
                // 选区/视口均为 0-based（选区 end 含、视口 end 不含）→ 1-based inclusive
                int a = Integer.parseInt(src[1]) + 1;
                int b = Integer.parseInt(src[2]);
                sF.setText(String.valueOf(a));
                eF.setText(String.valueOf(b));
            } else if (PrimerStore.lastChr != null) {
                chrF.setText(PrimerStore.lastChr);
            }

            // ---- 顶部：chr / 分组 ----
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            top.add(new JLabel("染色体:"));
            top.add(chrF);
            top.add(new JLabel("分组名（空=手动添加轨）:"));
            top.add(groupF);

            // ---- 目标区域 + 平铺参数 ----
            JPanel region = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            region.add(new JLabel("目标区域:"));
            region.add(sF);
            region.add(new JLabel("-"));
            region.add(eF);
            region.add(new JLabel("bp    产物长度:"));
            region.add(ampMinF);
            region.add(new JLabel("-"));
            region.add(ampMaxF);
            region.add(new JLabel("bp    相邻重叠:"));
            region.add(overlapF);
            region.add(new JLabel("bp    每窗对数:"));
            region.add(perWinF);

            // ---- 引物参数 ----
            JPanel pg = new JPanel(new GridLayout(2, 6, 4, 2));
            pg.add(new JLabel("引物长 min/opt/max:", SwingConstants.RIGHT));
            pg.add(lenMinF); pg.add(lenOptF); pg.add(lenMaxF);
            pg.add(new JLabel("Tm min/opt/max °C:", SwingConstants.RIGHT));
            pg.add(tmMinF); pg.add(tmOptF); pg.add(tmMaxF);
            pg.add(new JLabel("GC% min/max:", SwingConstants.RIGHT));
            pg.add(gcMinF); pg.add(gcMaxF);
            pg.add(new JLabel(""));

            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.add(top);
            center.add(region);
            center.add(countL);
            center.add(pg);
            center.add(relaxChk);
            statusL.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            center.add(statusL);
            add(center, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btns.add(go);
            JButton cancel = new JButton("取消");
            btns.add(cancel);
            add(btns, BorderLayout.SOUTH);
            cancel.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { dispose(); }
            });
            go.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { runTiling(); }
            });

            // 窗数实时预览
            javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
            };
            sF.getDocument().addDocumentListener(dl);
            eF.getDocument().addDocumentListener(dl);
            ampMinF.getDocument().addDocumentListener(dl);
            ampMaxF.getDocument().addDocumentListener(dl);
            overlapF.getDocument().addDocumentListener(dl);
            updateCount();

            pack();
            setLocationRelativeTo(IGV.getMainFrame());
        }

        private void updateCount() {
            try {
                int s = Integer.parseInt(sF.getText().trim());
                int e = Integer.parseInt(eF.getText().trim());
                int aMin = Integer.parseInt(ampMinF.getText().trim());
                int aMax = Integer.parseInt(ampMaxF.getText().trim());
                int ov = Integer.parseInt(overlapF.getText().trim());
                if (s > e) { int t = s; s = e; e = t; }
                List<int[]> w = tileWindows(s, e, aMin, aMax, ov);
                if (w.isEmpty()) { countL.setText(" "); return; }
                countL.setText("  <html>目标 " + (e - s + 1) + " bp → <b>" + w.size()
                        + "</b> 个窗口（每窗 ≤ " + aMax + "bp，相邻重叠 ≥ " + ov + "bp；末窗过短时自动左移补齐）</html>");
            } catch (Exception ignore) {
                countL.setText(" ");
            }
        }

        private int i(JTextField f, String what) throws Exception {
            try {
                return Integer.parseInt(f.getText().trim());
            } catch (NumberFormatException nfe) {
                throw new Exception(what + " 不是有效整数: " + f.getText().trim());
            }
        }

        private void runTiling() {
            statusL.setText(" ");
            try {
                // ---- 参数校验（同步完成；跑窗过程放后台，防 EDT 冻结）----
                final int lenMin = i(lenMinF, "引物长 min"), lenMax = i(lenMaxF, "引物长 max");
                final int tmMin = i(tmMinF, "Tm min"), tmMax = i(tmMaxF, "Tm max");
                final int gcMin = i(gcMinF, "GC min"), gcMax = i(gcMaxF, "GC max");
                final int ampMin = i(ampMinF, "产物 min"), ampMax = i(ampMaxF, "产物 max");
                final int overlap = i(overlapF, "重叠");
                final int perWin = i(perWinF, "每窗对数");
                final String chr = chrF.getText().trim();
                String grp = groupF.getText().trim();
                final String group = grp.isEmpty() ? null : grp;
                if (chr.isEmpty()) throw new Exception("染色体不能为空");
                if (lenMin > lenMax) throw new Exception("引物长 min > max");
                if (tmMin > tmMax) throw new Exception("Tm min > max");
                if (gcMin > gcMax) throw new Exception("GC min > max");
                if (ampMin > ampMax) throw new Exception("产物 min > max");
                if (overlap < 0 || overlap > ampMax - 50)
                    throw new Exception("重叠需在 0 ~ 产物max-50 之间（当前 " + overlap + "）");
                if (perWin < 1 || perWin > 5) throw new Exception("每窗对数需在 1-5");
                int s = i(sF, "目标区域起"), e = i(eF, "目标区域止");
                if (s > e) { int t = s; s = e; e = t; sF.setText(String.valueOf(s)); eF.setText(String.valueOf(e)); }
                final int regS = s, regE = e;

                Genome g = GenomeManager.getInstance().getCurrentGenome();
                if (g == null) throw new Exception("未加载参考基因组。请先在 IGV 顶部基因组下拉框选择/加载基因组（需要含序列的 .fasta/.2bit/genome），否则取不到模板序列。");
                if (g.getChromosome(chr) == null) throw new Exception("染色体「" + chr + "」不在当前基因组中");
                final int chrLen = g.getChromosome(chr).getLength();
                if (regS < 1 || regE > chrLen) throw new Exception("目标区域应在 1-" + chrLen + " 内");
                if (regE - regS + 1 < ampMin) throw new Exception("目标区域（" + (regE - regS + 1) + "bp）小于产物 min（" + ampMin + "bp）");
                final List<int[]> wins = tileWindows(regS, regE, ampMin, ampMax, overlap);
                if (wins.isEmpty()) throw new Exception("目标区域非法");
                if (wins.size() > MAX_WINDOWS)
                    throw new Exception("划出 " + wins.size() + " 个窗口，超过上限 " + MAX_WINDOWS
                            + "；请增大产物长度或缩小目标区域");
                final Genome gg = g;
                final boolean relax = relaxChk.isSelected();

                go.setEnabled(false);
                final List<Primer3Core.Pair> all = new ArrayList<Primer3Core.Pair>();
                final List<String> failed = new ArrayList<String>();
                final int N = wins.size();

                new SwingWorker<Object, String>() {
                    @Override
                    protected Object doInBackground() {
                        int pad = Math.max(2 * lenMax, 120);
                        String p3id = (group == null ? "manual" : group) + "_" + chr + "_tiling";
                        for (int wi = 0; wi < N; wi++) {
                            int ws = wins.get(wi)[0], we = wins.get(wi)[1];
                            String wlab = "W" + (wi + 1) + "(" + ws + "-" + we + ")";
                            publish("平铺设计中… 窗口 " + (wi + 1) + "/" + N + "：" + wlab);
                            try {
                                int wL = we - ws + 1;
                                int bd = Math.min(wL, Math.max(lenMax, Math.min(200, wL / 2)));
                                int us = ws, ul = bd, ds = we - bd + 1, dl = bd;
                                int ts = Math.max(0, ws - 1 - pad);
                                int te = Math.min(chrLen, we + pad);
                                byte[] sb = gg.getSequence(chr, ts, te);
                                if (sb == null || sb.length == 0) { failed.add(wlab + "（取不到序列）"); continue; }
                                String seq = new String(sb).toUpperCase();

                                Map<String, String> params = new LinkedHashMap<String, String>();
                                params.put("PRIMER_MIN_SIZE", String.valueOf(lenMin));
                                params.put("PRIMER_OPT_SIZE", lenOptF.getText().trim());
                                params.put("PRIMER_MAX_SIZE", String.valueOf(lenMax));
                                params.put("PRIMER_MIN_TM", String.valueOf(tmMin));
                                params.put("PRIMER_OPT_TM", tmOptF.getText().trim());
                                params.put("PRIMER_MAX_TM", String.valueOf(tmMax));
                                params.put("PRIMER_MIN_GC", String.valueOf(gcMin));
                                params.put("PRIMER_MAX_GC", String.valueOf(gcMax));
                                params.put("PRIMER_PICK_LEFT_PRIMER", "1");
                                params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
                                params.put("PRIMER_NUM_RETURN", String.valueOf(perWin));
                                params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST",
                                        (us - 1 - ts) + "," + ul + "," + (ds - 1 - ts) + "," + dl);
                                int aMin = Math.max(1, wL - ul - dl + 1), aMax2 = wL;
                                int uMin = Math.max(ampMin, aMin), uMax = Math.min(ampMax, aMax2);
                                if (uMin > uMax) { uMin = aMin; uMax = aMax2; }
                                params.put("PRIMER_PRODUCT_SIZE_RANGE", uMin + "-" + uMax);

                                List<Primer3Core.Pair> ps = Primer3Core.design(p3id, seq, params);
                                if (ps.isEmpty() && relax) {
                                    for (int lv = 1; lv <= Primer3Dialog.MAX_RELAX_LEVEL && ps.isEmpty(); lv++) {
                                        Primer3Dialog.Relaxed r = Primer3Dialog.relaxAt(params, lv);
                                        ps = Primer3Core.design(p3id, seq, r.params);
                                    }
                                }
                                if (ps.isEmpty()) { failed.add(wlab); continue; }
                                for (Primer3Core.Pair p : ps) {
                                    p.idx = all.size();                 // 结果表 # 列必须全局唯一
                                    if (p.leftSeq != null) p.leftStart += ts;    // 换算为基因组坐标
                                    if (p.rightSeq != null) p.rightStart += ts;  // （结果对话框 tmplOffset=0）
                                    all.add(p);
                                }
                            } catch (Exception wex) {
                                failed.add(wlab + "（" + wex.getMessage() + "）");
                            }
                        }
                        return null;
                    }

                    @Override
                    protected void process(List<String> chunks) {
                        statusL.setText("<html><font color='gray'>" + chunks.get(chunks.size() - 1) + "</font></html>");
                    }

                    @Override
                    protected void done() {
                        go.setEnabled(true);
                        try { get(); } catch (Exception ex) {
                            statusL.setText("<html><font color='red'>平铺失败: " + ex.getMessage() + "</font></html>");
                            return;
                        }
                        if (all.isEmpty()) {
                            StringBuilder tip = new StringBuilder("<html>所有 " + N + " 个窗口均未设计出引物对。<br>");
                            if (Primer3Core.lastExplain != null)
                                tip.append("最近一次 primer3 统计：<br>").append(Primer3Core.lastExplain.replace("\n", "<br>")).append("<br>");
                            tip.append("可尝试：放宽 Tm/GC、增大产物范围、减小重叠。</html>");
                            JOptionPane.showMessageDialog(Dlg.this, tip.toString(),
                                    "primer3 平铺设计", JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                        dispose();
                        // T 编号连续：第 i 个结果对 = T(i+1)
                        int[] pairNos = new int[all.size()];
                        for (int k = 0; k < pairNos.length; k++) pairNos[k] = k + 1;
                        StringBuilder note = new StringBuilder();
                        note.append("平铺设计：目标 ").append(regS).append("-").append(regE)
                                .append("（").append(N).append(" 窗 × 产物 ").append(ampMin).append("-").append(ampMax)
                                .append("bp、重叠 ").append(overlap).append("bp）");
                        if (!failed.isEmpty()) {
                            note.append("；⚠ ").append(failed.size()).append(" 窗无解: ");
                            for (int k = 0; k < failed.size() && k < 8; k++) {
                                if (k > 0) note.append("，");
                                note.append(failed.get(k));
                            }
                            if (failed.size() > 8) note.append(" 等").append(failed.size()).append("窗");
                        }
                        Primer3ResultDialog.show(chr, group, all, 0, "T", pairNos, note.toString());
                    }
                }.execute();
            } catch (Exception ex) {
                statusL.setText("<html><font color='red'>" + escape(ex.getMessage()) + "</font></html>");
            }
        }

        private static String escape(String s) {
            return s == null ? "未知错误" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}

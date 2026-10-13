package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.1.56：primer3 设计引物对话框（设计入口）。
 * v0.1.57：支持三种设计模式——
 *  - 成对（MODE_PAIR）：简单=产物区间（SEQUENCE_TARGET，上下游自动找）；高级=上下游双区
 *    （SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 产物区间白名单，左引物落上游侧、右引物落下游侧）；
 *  - 单引物 F 向（MODE_LEFT）/ R 向（MODE_RIGHT）：指定一个区域，引物必须落在选区内
 *    （SEQUENCE_INCLUDED_REGION 限定搜索范围 + SEQUENCE_TARGET 中段吸引）。
 * 打开时自动读取 PrimerEditTool 的鼠标选区（空白处拖拽掠过区间）预填 chr 与起止。
 * 坐标输入均为 IGV 惯例 1-based inclusive。
 *
 * v0.1.59：无鼠标选区时回退自动填充当前 IGV 视口（chr+range）；起止倒转自动交换；
 * 产物区间长于产物上限时自动放大上限（否则 primer3 必无解）；新增可折叠「高级参数」
 * 面板（盐浓度/dNTP/引物浓度、二聚体/发夹阈值、多聚X/N/GC钳，留空=primer3 默认）。
 *
 * 参数只暴露常用项（长度/Tm/GC/产物范围/返回对数），预设一键填充；高级参数走默认值。
 */
public class Primer3Dialog {

    public static final int MODE_PAIR = 0;    // 成对
    public static final int MODE_LEFT = 1;    // 仅 F 向（+）
    public static final int MODE_RIGHT = 2;   // 仅 R 向（-）

    /** 入口：打开设计对话框（默认成对）。 */
    public static void open() {
        open(MODE_PAIR);
    }

    /** 入口：打开设计对话框；有鼠标选区时自动预填。 */
    public static void open(int mode) {
        if (!Primer3Core.available()) {
            JOptionPane.showMessageDialog(IGV.getMainFrame(),
                    "primer3_core.exe 不可用（jar 资源缺失或无法释放到 ~/.igv_primer3/）。",
                    "primer3 设计", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Dlg(mode).setVisible(true);
    }

    /**
     * 区域 → 模板范围 + primer3 区域参数（纯计算，无 IO，便于测试）。
     *
     * @param mode   MODE_PAIR / MODE_LEFT / MODE_RIGHT
     * @param adv    true=高级模式（仅成对支持；单引物忽略）
     * @param s,e    简单模式区间（1-based inclusive；单引物=引物区域，成对=产物区间）
     * @param us,ul  高级模式上游区起(1-based)/长
     * @param ds,dl  高级模式下游区起(1-based)/长
     * @return {tmplStart0, tmplEnd0}（0-based，end exclusive）
     */
    static int[] regionToTemplate(int mode, boolean adv, int s, int e,
                                  int us, int ul, int ds, int dl,
                                  int chrLen, int lenMin, int lenMax) throws Exception {
        boolean single = (mode == MODE_LEFT || mode == MODE_RIGHT);
        if (single || !adv) {
            // 简单/单引物：区间 ± padding 作模板（容纳最长引物 + primer3 边界）
            int s0 = s - 1;
            if (s0 < 0 || e > chrLen || s0 >= e)
                throw new Exception("区域非法（应在 1-" + chrLen + " 内且起<止）");
            if (single && e - s0 < lenMin)
                throw new Exception("引物区域长度（" + (e - s0) + "bp）小于最短引物长（" + lenMin + "）");
            int pad = Math.max(lenMax * 2, 120);
            int ts = Math.max(0, s0 - pad);
            int te = Math.min(chrLen, e + pad);
            return new int[]{ts, te};
        }
        // 高级模式（成对）：模板覆盖上下游区并各扩一个引物长度
        int us0 = us - 1;
        int ds0 = ds - 1;
        if (us0 < 0 || us0 + ul > chrLen || ds0 < 0 || ds0 + dl > chrLen)
            throw new Exception("上/下游区超出染色体范围");
        if (ul < lenMin || dl < lenMin)
            throw new Exception("上/下游区长度不能小于最短引物长（" + lenMin + "）");
        // v0.1.57：修复 v0.1.56 校验条件写反的 bug（原条件把"两区分离"这一正常场景误判为非法）。
        // 正确语义：上游区起点必须不晚于下游区起点（允许部分重叠；禁止上游整体在右侧）。
        if (us0 > ds0)
            throw new Exception("上游区应在下游区左侧（允许部分重叠）");
        int pad = lenMax * 2;
        int ts = Math.max(0, Math.min(us0, ds0) - pad);
        int te = Math.min(chrLen, Math.max(us0 + ul, ds0 + dl) + pad);
        return new int[]{ts, te};
    }

    /**
     * v0.1.59：当前 IGV 视口（主 frame）的 chr + 0-based [start, end)。
     * 无基因组/无实例时返回 null（headless 或未初始化场景安全）。
     */
    public static String[] currentViewRange() {
        try {
            org.broad.igv.ui.panel.ReferenceFrame f =
                    IGV.getInstance().getSession().getReferenceFrame();
            if (f == null || f.getChrName() == null) return null;
            int s = (int) f.getOrigin();
            int e = (int) Math.ceil(f.getEnd());
            if (e <= s || s < 0) return null;
            return new String[]{f.getChrName(), String.valueOf(s), String.valueOf(e)};
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * v0.1.59：区间超长时取中段截断（0-based，end exclusive）。
     * 用于把整条染色体级的视口缩成可设计的区间；不超过 maxLen 原样返回。
     */
    public static int[] clampMid(int s0, int e0, int maxLen) {
        if (e0 - s0 <= maxLen) return new int[]{s0, e0};
        int mid = (s0 + e0) / 2;
        int ns = Math.max(s0, mid - maxLen / 2);
        return new int[]{ns, ns + maxLen};
    }

    /** v0.1.59：高级参数收集——文本框非空才写入（空 = 用 primer3 自己的默认值）。 */
    public static void putIfPresent(Map<String, String> params, String tag, JTextField f) {
        String v = f.getText().trim();
        if (!v.isEmpty()) params.put(tag, v);
    }

    private static class Dlg extends JDialog {
        private final int mode;   // MODE_PAIR / MODE_LEFT / MODE_RIGHT
        private final JComboBox modeBox = new JComboBox(new String[]{"简单：指定产物区间", "高级：分别指定上/下游区"});
        private final JTextField chrF = new JTextField(6);
        private final JTextField groupF = new JTextField("primer3", 10);
        // 简单模式
        private final JTextField tStartF = new JTextField(8);
        private final JTextField tEndF = new JTextField(8);
        // 高级模式
        private final JTextField uStartF = new JTextField(8);
        private final JTextField uLenF = new JTextField(6);
        private final JTextField dStartF = new JTextField(8);
        private final JTextField dLenF = new JTextField(6);
        // 参数
        private final JTextField lenMinF = new JTextField("18", 4);
        private final JTextField lenOptF = new JTextField("20", 4);
        private final JTextField lenMaxF = new JTextField("27", 4);
        private final JTextField tmMinF = new JTextField("57", 4);
        private final JTextField tmOptF = new JTextField("60", 4);
        private final JTextField tmMaxF = new JTextField("63", 4);
        private final JTextField gcMinF = new JTextField("30", 4);
        private final JTextField gcMaxF = new JTextField("70", 4);
        private final JTextField prodMinF = new JTextField("100", 6);
        private final JTextField prodMaxF = new JTextField("300", 6);
        private final JTextField numRetF = new JTextField("5", 4);
        // v0.1.59：高级参数（均可空=primer3 默认值；实战中最常导致无解/结果漂移的项）
        private final JTextField saltMonoF = new JTextField("50", 5);    // PRIMER_SALT_MONOVALENT mM
        private final JTextField saltDiF = new JTextField("1.5", 5);     // PRIMER_SALT_DIVALENT mM
        private final JTextField dntpF = new JTextField("0.6", 5);       // PRIMER_DNTP_CONC mM
        private final JTextField dnaConcF = new JTextField("50", 5);     // PRIMER_DNA_CONC nM
        private final JTextField complAnyF = new JTextField("", 5);      // PRIMER_MAX_COMPL_ANY_TH
        private final JTextField complEndF = new JTextField("", 5);      // PRIMER_MAX_COMPL_END_TH
        private final JTextField hairpinF = new JTextField("", 5);       // PRIMER_MAX_HAIRPIN_TH
        private final JTextField selfAnyF = new JTextField("", 5);       // PRIMER_MAX_SELF_ANY_TH
        private final JTextField selfEndF = new JTextField("", 5);       // PRIMER_MAX_SELF_END_TH
        private final JTextField polyXF = new JTextField("", 5);         // PRIMER_MAX_POLY_X
        private final JTextField nsF = new JTextField("", 5);            // PRIMER_MAX_NS_ACCEPTED
        private final JTextField gcClampF = new JTextField("", 5);       // PRIMER_GC_CLAMP 0/1
        private final JTextField maxEndGcF = new JTextField("", 5);      // PRIMER_MAX_END_GC

        private final JPanel simplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel advPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel advParams = new JPanel(new GridLayout(3, 10, 4, 2));  // v0.1.59 高级参数（默认折叠）
        private final JLabel statusL = new JLabel(" ");

        Dlg(int mode) {
            super(IGV.getMainFrame(), titleOf(mode), true);
            this.mode = mode;
            boolean single = (mode != MODE_PAIR);
            setLayout(new BorderLayout(6, 6));

            // ---- 顶部：模式/chr/分组 ----
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            top.add(new JLabel("模式:"));
            top.add(modeBox);
            top.add(new JLabel("染色体:"));
            // v0.1.59：优先鼠标选区；无选区回退当前 IGV 视口（自动填充，减少手工输入）
            String[] sel = PrimerEditTool.currentSelection();
            boolean fromView = false;
            if (sel == null) {
                sel = currentViewRange();
                fromView = true;
            }
            chrF.setText(sel != null ? sel[0] : (PrimerStore.lastChr == null ? "" : PrimerStore.lastChr));
            top.add(chrF);
            top.add(new JLabel("分组名:"));
            top.add(groupF);

            // ---- 区域面板 ----
            JLabel simpleLbl = new JLabel(single ? "引物区域:" : "产物区间:");
            simplePanel.add(simpleLbl);
            simplePanel.add(tStartF);
            simplePanel.add(new JLabel("-"));
            simplePanel.add(tEndF);
            simplePanel.add(new JLabel(single
                    ? "bp（引物必须落在该区域内，primer3 按参数挑最优）"
                    : "bp（上下游引物由 primer3 在区间两侧自动选择）"));
            advPanel.add(new JLabel("上游区:"));
            advPanel.add(uStartF);
            advPanel.add(new JLabel("起 + 长"));
            advPanel.add(uLenF);
            advPanel.add(new JLabel("bp    下游区:"));
            advPanel.add(dStartF);
            advPanel.add(new JLabel("起 + 长"));
            advPanel.add(dLenF);
            advPanel.add(new JLabel("bp"));

            JPanel regionPanel = new JPanel();
            regionPanel.setLayout(new BoxLayout(regionPanel, BoxLayout.Y_AXIS));
            regionPanel.add(simplePanel);
            regionPanel.add(advPanel);
            advPanel.setVisible(false);
            if (single) {
                // 单引物只有一种区域模式：禁用模式框并换成正确文案（避免显示"产物区间"误导）
                modeBox.setModel(new DefaultComboBoxModel(new String[]{"简单：指定引物区域"}));
                modeBox.setEnabled(false);
                prodMinF.setEnabled(false);  // 单引物无产物概念
                prodMaxF.setEnabled(false);
            }

            // v0.1.59：鼠标选区/视口预填（1-based inclusive 显示）。
            // 起止倒转自动交换；超长区间取中段（单引物 2kb：INCLUDED 搜索空间过大 primer3 慢；
            // 成对 19kb：再大产物无实际意义，且留出模板 pad 余量避开 20kb 模板上限）
            if (sel != null) {
                int s0 = Integer.parseInt(sel[1]);
                int e0 = Integer.parseInt(sel[2]);
                if (s0 > e0) { int t = s0; s0 = e0; e0 = t; }
                int maxLen = single ? 2000 : 19000;
                int[] c = clampMid(s0, e0, maxLen);
                if (c[1] - c[0] < e0 - s0) {
                    statusL.setText("<html><font color='gray'>已按当前"
                            + (fromView ? "视图" : "选中区域") + "填充 " + s0 + 1 + "-" + e0
                            + "（" + (e0 - s0) + "bp 过长，取中段 " + maxLen + "bp）</font></html>");
                } else if (fromView) {
                    statusL.setText("<html><font color='gray'>已按当前视图自动填充 "
                            + chrF.getText() + ":" + (s0 + 1) + "-" + e0 + "</font></html>");
                }
                s0 = c[0];
                e0 = c[1];
                tStartF.setText(String.valueOf(s0 + 1));
                tEndF.setText(String.valueOf(e0));
            }

            // ---- 参数网格 ----
            JPanel pg = new JPanel(new GridLayout(2, 6, 4, 2));
            pg.add(new JLabel("引物长 min/opt/max:", SwingConstants.RIGHT));
            pg.add(lenMinF);
            pg.add(lenOptF);
            pg.add(lenMaxF);
            pg.add(new JLabel("Tm min/opt/max °C:", SwingConstants.RIGHT));
            pg.add(tmMinF);
            pg.add(tmOptF);
            pg.add(tmMaxF);
            pg.add(new JLabel("GC% min/max:", SwingConstants.RIGHT));
            pg.add(gcMinF);
            pg.add(gcMaxF);
            pg.add(new JLabel(single ? "（单引物无产物范围）" : "产物 bp min/max:", SwingConstants.RIGHT));
            pg.add(prodMinF);
            pg.add(prodMaxF);
            pg.add(new JLabel(single ? "返回条数:" : "返回对数:", SwingConstants.RIGHT));
            pg.add(numRetF);
            pg.add(new JLabel(""));

            // ---- 预设 ----
            JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            presets.add(new JLabel("预设:"));
            presets.add(btn("默认", new String[]{"18", "20", "27", "57", "60", "63", "30", "70", "100", "300", "5"}));
            presets.add(btn("qPCR", new String[]{"18", "20", "25", "59", "60", "62", "40", "60", "80", "150", "5"}));
            presets.add(btn("Sanger", new String[]{"18", "22", "27", "56", "60", "64", "35", "65", "300", "800", "5"}));
            presets.add(btn("克隆", new String[]{"20", "25", "30", "58", "62", "66", "40", "60", "500", "1500", "5"}));

            // ---- v0.1.59：高级参数（默认折叠；留空=primer3 默认值） ----
            final JButton advToggle = new JButton("高级参数 ▸（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）");
            advToggle.setMargin(new Insets(2, 6, 2, 6));
            // 行1：Tm 计算基础（盐/dNTP/引物浓度）
            advParams.add(new JLabel("盐 mono mM:", SwingConstants.RIGHT)); advParams.add(saltMonoF);
            advParams.add(new JLabel("盐 di mM:", SwingConstants.RIGHT));  advParams.add(saltDiF);
            advParams.add(new JLabel("dNTP mM:", SwingConstants.RIGHT));   advParams.add(dntpF);
            advParams.add(new JLabel("引物 nM:", SwingConstants.RIGHT));   advParams.add(dnaConcF);
            advParams.add(new JLabel("")); advParams.add(new JLabel(""));
            // 行2：二聚体/发夹阈值（调低更严，无解时可适当调高）
            advParams.add(new JLabel("二聚ANY:", SwingConstants.RIGHT));  advParams.add(complAnyF);
            advParams.add(new JLabel("二聚END:", SwingConstants.RIGHT));  advParams.add(complEndF);
            advParams.add(new JLabel("发卡:", SwingConstants.RIGHT));     advParams.add(hairpinF);
            advParams.add(new JLabel("自连ANY:", SwingConstants.RIGHT));  advParams.add(selfAnyF);
            advParams.add(new JLabel("自连END:", SwingConstants.RIGHT));  advParams.add(selfEndF);
            // 行3：组成约束
            advParams.add(new JLabel("多聚X:", SwingConstants.RIGHT));    advParams.add(polyXF);
            advParams.add(new JLabel("N个数:", SwingConstants.RIGHT));    advParams.add(nsF);
            advParams.add(new JLabel("GC钳0/1:", SwingConstants.RIGHT));  advParams.add(gcClampF);
            advParams.add(new JLabel("3'GC数:", SwingConstants.RIGHT));   advParams.add(maxEndGcF);
            JButton advReset = new JButton("恢复默认");
            advReset.setMargin(new Insets(2, 6, 2, 6));
            advReset.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    saltMonoF.setText("50"); saltDiF.setText("1.5"); dntpF.setText("0.6"); dnaConcF.setText("50");
                    complAnyF.setText(""); complEndF.setText(""); hairpinF.setText("");
                    selfAnyF.setText(""); selfEndF.setText(""); polyXF.setText("");
                    nsF.setText(""); gcClampF.setText(""); maxEndGcF.setText("");
                }
            });
            advParams.add(advReset);
            advParams.setVisible(false);
            advToggle.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    boolean show = !advParams.isVisible();
                    advParams.setVisible(show);
                    advToggle.setText(show
                            ? "高级参数 ▾（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）"
                            : "高级参数 ▸（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）");
                    pack();
                }
            });
            JPanel advWrap = new JPanel(new BorderLayout(0, 2));
            advWrap.add(advToggle, BorderLayout.NORTH);
            advWrap.add(advParams, BorderLayout.CENTER);

            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.add(top);
            form.add(regionPanel);
            form.add(presets);
            form.add(pg);
            form.add(advWrap);
            form.add(statusL);
            add(form, BorderLayout.CENTER);

            // ---- 按钮 ----
            JButton go = new JButton("设计");
            JButton cancel = new JButton("取消");
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btns.add(go);
            btns.add(cancel);
            add(btns, BorderLayout.SOUTH);

            modeBox.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    boolean adv = modeBox.getSelectedIndex() == 1;
                    simplePanel.setVisible(!adv);
                    advPanel.setVisible(adv);
                    pack();
                }
            });
            cancel.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });
            go.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    runDesign();
                }
            });

            pack();
            setLocationRelativeTo(IGV.getMainFrame());
        }

        private static String titleOf(int mode) {
            switch (mode) {
                case MODE_LEFT:  return "primer3 设计 F 向单引物（+）";
                case MODE_RIGHT: return "primer3 设计 R 向单引物（-）";
                default:         return "primer3 设计成对引物";
            }
        }

        private JButton btn(String name, final String[] vals) {
            JButton b = new JButton(name);
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    lenMinF.setText(vals[0]);
                    lenOptF.setText(vals[1]);
                    lenMaxF.setText(vals[2]);
                    tmMinF.setText(vals[3]);
                    tmOptF.setText(vals[4]);
                    tmMaxF.setText(vals[5]);
                    gcMinF.setText(vals[6]);
                    gcMaxF.setText(vals[7]);
                    if (mode == MODE_PAIR) {
                        prodMinF.setText(vals[8]);
                        prodMaxF.setText(vals[9]);
                    }
                    numRetF.setText(vals[10]);
                }
            });
            return b;
        }

        private int i(JTextField f, String what) throws Exception {
            try {
                return Integer.parseInt(f.getText().trim());
            } catch (NumberFormatException nfe) {
                throw new Exception(what + " 不是有效整数: " + f.getText().trim());
            }
        }

        private void runDesign() {
            statusL.setText(" ");
            try {
                // ---- 参数 ----
                int lenMin = i(lenMinF, "引物长 min"), lenMax = i(lenMaxF, "引物长 max");
                int tmMin = i(tmMinF, "Tm min"), tmMax = i(tmMaxF, "Tm max");
                int gcMin = i(gcMinF, "GC min"), gcMax = i(gcMaxF, "GC max");
                int prodMin = i(prodMinF, "产物 min"), prodMax = i(prodMaxF, "产物 max");
                int numRet = i(numRetF, "返回条数");
                String chr = chrF.getText().trim();
                String group = groupF.getText().trim();
                if (chr.isEmpty()) throw new Exception("染色体不能为空");
                if (group.isEmpty()) group = "primer3";
                if (lenMin > lenMax) throw new Exception("引物长 min > max");
                if (tmMin > tmMax) throw new Exception("Tm min > max");
                if (gcMin > gcMax) throw new Exception("GC min > max");
                if (mode == MODE_PAIR && prodMin > prodMax) throw new Exception("产物 min > max");
                if (numRet < 1 || numRet > 20) throw new Exception("返回条数需在 1-20");

                Genome g = GenomeManager.getInstance().getCurrentGenome();
                if (g == null) throw new Exception("未加载参考基因组。请先在 IGV 顶部基因组下拉框选择/加载基因组（需要含序列的 .fasta/.2bit/genome），否则取不到模板序列。");
                if (g.getChromosome(chr) == null) throw new Exception("染色体「" + chr + "」不在当前基因组中");
                int chrLen = g.getChromosome(chr).getLength();

                // ---- 区域 ----
                Map<String, String> params = new LinkedHashMap<String, String>();
                params.put("PRIMER_MIN_SIZE", String.valueOf(lenMin));
                params.put("PRIMER_OPT_SIZE", lenOptF.getText().trim());
                params.put("PRIMER_MAX_SIZE", String.valueOf(lenMax));
                params.put("PRIMER_MIN_TM", String.valueOf(tmMin));
                params.put("PRIMER_OPT_TM", tmOptF.getText().trim());
                params.put("PRIMER_MAX_TM", String.valueOf(tmMax));
                params.put("PRIMER_MIN_GC", String.valueOf(gcMin));
                params.put("PRIMER_MAX_GC", String.valueOf(gcMax));
                params.put("PRIMER_NUM_RETURN", String.valueOf(numRet));

                // v0.1.59：高级参数（留空=primer3 默认，不写入）
                putIfPresent(params, "PRIMER_SALT_MONOVALENT", saltMonoF);
                putIfPresent(params, "PRIMER_SALT_DIVALENT", saltDiF);
                putIfPresent(params, "PRIMER_DNTP_CONC", dntpF);
                putIfPresent(params, "PRIMER_DNA_CONC", dnaConcF);
                putIfPresent(params, "PRIMER_MAX_COMPL_ANY_TH", complAnyF);
                putIfPresent(params, "PRIMER_MAX_COMPL_END_TH", complEndF);
                putIfPresent(params, "PRIMER_MAX_HAIRPIN_TH", hairpinF);
                putIfPresent(params, "PRIMER_MAX_SELF_ANY_TH", selfAnyF);
                putIfPresent(params, "PRIMER_MAX_SELF_END_TH", selfEndF);
                putIfPresent(params, "PRIMER_MAX_POLY_X", polyXF);
                putIfPresent(params, "PRIMER_MAX_NS_ACCEPTED", nsF);
                putIfPresent(params, "PRIMER_GC_CLAMP", gcClampF);
                putIfPresent(params, "PRIMER_MAX_END_GC", maxEndGcF);

                int s = i(tStartF, "区域起"), e = i(tEndF, "区域止");
                // v0.1.59：起止倒转自动交换（手工输入/反向拖选常见，原来直接报"区域非法"）
                if (s > e) {
                    int t = s; s = e; e = t;
                    tStartF.setText(String.valueOf(s));
                    tEndF.setText(String.valueOf(e));
                    statusL.setText("提示: 起止已倒转，自动交换为 " + s + "-" + e);
                }
                boolean adv = modeBox.getSelectedIndex() == 1;
                boolean single = (mode != MODE_PAIR);
                if (single) {
                    // 单引物：只 pick 一侧；无产物范围
                    params.put("PRIMER_PICK_LEFT_PRIMER", mode == MODE_LEFT ? "1" : "0");
                    params.put("PRIMER_PICK_RIGHT_PRIMER", mode == MODE_RIGHT ? "1" : "0");
                } else {
                    params.put("PRIMER_PICK_LEFT_PRIMER", "1");
                    params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
                    // v0.1.60：简单模式产物区间即 SEQUENCE_TARGET。primer3 语义=产物必须**覆盖**整个
                    // target（不是重叠），故最小产物 = target长 + 两侧引物长；上限放到 tLen 甚至
                    // tLen 以内都必然无解。上限自适应为 tLen + 2×最长引物，并提示。
                    if (!adv) {
                        int tLen = e - (s - 1);
                        int needMax = tLen + 2 * lenMax;
                        if (prodMax < needMax) {
                            prodMax = needMax;
                            statusL.setText("提示: 产物上限已放大为 " + prodMax
                                    + "bp（覆盖 " + tLen + "bp 区间 + 两侧引物最长各 " + lenMax + "bp）");
                        }
                    }
                    params.put("PRIMER_PRODUCT_SIZE_RANGE", prodMin + "-" + prodMax);
                }

                // 仅高级+成对才读上/下游区字段：单引物时 modeBox 禁用（永远简单模式），
                // advPanel 隐藏且字段为空，无条件解析会抛「上游区起 不是有效整数」
                int us = 0, ul = 0, ds = 0, dl = 0;
                if (adv && !single) {
                    us = i(uStartF, "上游区起"); ul = i(uLenF, "上游区长");
                    ds = i(dStartF, "下游区起"); dl = i(dLenF, "下游区长");
                }

                int[] tmpl = regionToTemplate(mode, adv && !single, s, e, us, ul, ds, dl,
                        chrLen, lenMin, lenMax);
                int tmplStart = tmpl[0], tmplEnd = tmpl[1];

                if (single || !adv) {
                    int s0 = s - 1;
                    if (single) {
                        // 引物必须落在选区内：INCLUDED_REGION 限定搜索范围；
                        // SEQUENCE_TARGET 取选区中段（≤20bp）吸引引物靠近中部
                        int tLen = Math.min(e - s0, 20);
                        int tStart = s0 + (e - s0 - tLen) / 2;
                        params.put("SEQUENCE_INCLUDED_REGION", (s0 - tmplStart) + "," + (e - s0));
                        params.put("SEQUENCE_TARGET", (tStart - tmplStart) + "," + tLen);
                    } else {
                        params.put("SEQUENCE_TARGET", (s0 - tmplStart) + "," + (e - s0));
                    }
                } else {
                    // 高级模式（成对）：primer3 无单独的"左右引物区"官方 tag（PRIMER_LEFT_OK_REGION 不存在，
                    // 未知 tag 会被静默忽略），用产物区间白名单 SEQUENCE_PRIMER_PAIR_OK_REGION_LIST
                    // 实现等价约束：产物必须跨上游区→下游区，左引物自然落上游侧、右引物落下游侧。
                    int pStart = Math.min(us - 1, ds - 1);
                    int pEnd = Math.max(us - 1 + ul, ds - 1 + dl);
                    params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", (pStart - tmplStart) + "," + (pEnd - pStart));
                }
                int need = single ? 2 * lenMax : (prodMax + 2 * lenMax);
                if (tmplEnd - tmplStart > 20000) throw new Exception("模板区过大（>20kb），primer3 会很慢；请缩小区间或分块设计");
                if (tmplEnd - tmplStart < need)
                    throw new Exception("模板区（" + (tmplEnd - tmplStart) + "bp）装不下" +
                            (single ? "两侧引物长度，请扩大区域" : "最长产物（" + prodMax + "bp）+ 两侧引物，请扩大区间或减小产物上限"));

                // ---- 取模板序列 ----
                byte[] sb = g.getSequence(chr, tmplStart, tmplEnd);
                if (sb == null || sb.length == 0) throw new Exception("取不到序列：" + chr + ":" + (tmplStart + 1) + "-" + tmplEnd + "（该区间可能不在基因组内）");
                String seq = new String(sb).toUpperCase();
                int nCount = 0;
                for (int k = 0; k < seq.length(); k++) if (seq.charAt(k) == 'N') nCount++;
                if (nCount * 10 > seq.length() * 3)
                    statusL.setText("警告: 模板含 N 比例 " + (nCount * 100 / seq.length()) + "%，设计结果可能不理想");

                // ---- 调 primer3 ----
                List<Primer3Core.Pair> pairs = Primer3Core.design(group + "_" + chr, seq, params);
                if (pairs.isEmpty()) {
                    // v0.1.60：附带 primer3 EXPLAIN 诊断（considered/ok 统计），不再只说"放宽参数"
                    StringBuilder tip = new StringBuilder();
                    tip.append("<html>primer3 未找到合适").append(single ? "引物" : "引物对").append("。<br>");
                    if (Primer3Core.lastExplain != null) {
                        for (String ln : Primer3Core.lastExplain.split("\n"))
                            tip.append(ln).append("<br>");
                        tip.append("<br>");
                    }
                    tip.append("可尝试：放宽 Tm/GC 范围、")
                       .append(single ? "扩大区域" : "增大产物范围、扩大区域")
                       .append("、调整高级参数（盐浓度/二聚体阈值）。</html>");
                    JOptionPane.showMessageDialog(this, tip.toString(),
                            "primer3 设计", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                dispose();
                Primer3ResultDialog.show(chr, group, pairs, tmplStart);
            } catch (Exception ex) {
                statusL.setText("<html><font color='red'>" + escape(ex.getMessage()) + "</font></html>");
            }
        }

        private static String escape(String s) {
            return s == null ? "未知错误" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}

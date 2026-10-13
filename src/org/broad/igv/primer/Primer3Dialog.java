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
 *
 * 两种区域模式：
 *  - 简单：指定一个产物区间（SEQUENCE_TARGET），primer3 在区间两侧自动找上下游引物；
 *  - 高级：分别指定上游引物可存在的区与下游引物可存在的区（PRIMER_LEFT/RIGHT_OK_REGION），
 *    适合"上游必须落在保守区 A、下游必须落在保守区 B"的场景（如多重 panel 分区占位）。
 * 坐标输入均为 IGV 惯例 1-based inclusive。
 *
 * 参数只暴露常用项（长度/Tm/GC/产物范围/返回对数），预设一键填充；高级参数走默认值。
 */
public class Primer3Dialog {

    /** 入口：打开设计对话框。 */
    public static void open() {
        if (!Primer3Core.available()) {
            JOptionPane.showMessageDialog(IGV.getMainFrame(),
                    "primer3_core.exe 不可用（jar 资源缺失或无法释放到 ~/.igv_primer3/）。",
                    "primer3 设计", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Dlg().setVisible(true);
    }

    private static class Dlg extends JDialog {
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

        private final JPanel simplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel advPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JLabel statusL = new JLabel(" ");

        Dlg() {
            super(IGV.getMainFrame(), "primer3 设计引物", true);
            setLayout(new BorderLayout(6, 6));

            // ---- 顶部：模式/chr/分组 ----
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            top.add(new JLabel("模式:"));
            top.add(modeBox);
            top.add(new JLabel("染色体:"));
            chrF.setText(PrimerStore.lastChr == null ? "" : PrimerStore.lastChr);
            top.add(chrF);
            top.add(new JLabel("分组名:"));
            top.add(groupF);

            // ---- 区域面板 ----
            simplePanel.add(new JLabel("产物区间:"));
            simplePanel.add(tStartF);
            simplePanel.add(new JLabel("-"));
            simplePanel.add(tEndF);
            simplePanel.add(new JLabel("bp（上下游引物由 primer3 在区间两侧自动选择）"));
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
            pg.add(new JLabel("产物 bp min/max:", SwingConstants.RIGHT));
            pg.add(prodMinF);
            pg.add(prodMaxF);
            pg.add(new JLabel("返回对数:", SwingConstants.RIGHT));
            pg.add(numRetF);
            pg.add(new JLabel(""));

            // ---- 预设 ----
            JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            presets.add(new JLabel("预设:"));
            presets.add(btn("默认", new String[]{"18", "20", "27", "57", "60", "63", "30", "70", "100", "300", "5"}));
            presets.add(btn("qPCR", new String[]{"18", "20", "25", "59", "60", "62", "40", "60", "80", "150", "5"}));
            presets.add(btn("Sanger", new String[]{"18", "22", "27", "56", "60", "64", "35", "65", "300", "800", "5"}));
            presets.add(btn("克隆", new String[]{"20", "25", "30", "58", "62", "66", "40", "60", "500", "1500", "5"}));

            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.add(top);
            form.add(regionPanel);
            form.add(presets);
            form.add(pg);
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
                    prodMinF.setText(vals[8]);
                    prodMaxF.setText(vals[9]);
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
                int numRet = i(numRetF, "返回对数");
                String chr = chrF.getText().trim();
                String group = groupF.getText().trim();
                if (chr.isEmpty()) throw new Exception("染色体不能为空");
                if (group.isEmpty()) group = "primer3";
                if (lenMin > lenMax) throw new Exception("引物长 min > max");
                if (tmMin > tmMax) throw new Exception("Tm min > max");
                if (gcMin > gcMax) throw new Exception("GC min > max");
                if (prodMin > prodMax) throw new Exception("产物 min > max");
                if (numRet < 1 || numRet > 20) throw new Exception("返回对数需在 1-20");

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
                params.put("PRIMER_PRODUCT_SIZE_RANGE", prodMin + "-" + prodMax);

                int tmplStart, tmplEnd;   // 0-based exclusive end
                boolean adv = modeBox.getSelectedIndex() == 1;
                if (!adv) {
                    // 简单模式：产物区间 ± padding 作模板（容纳最长引物 + primer3 边界）
                    int s = i(tStartF, "产物区间起") - 1;
                    int e = i(tEndF, "产物区间止");
                    if (s < 0 || e > chrLen || s >= e) throw new Exception("产物区间非法（应在 1-" + chrLen + " 内且起<止）");
                    int pad = Math.max(lenMax * 2, 120);
                    tmplStart = Math.max(0, s - pad);
                    tmplEnd = Math.min(chrLen, e + pad);
                    params.put("SEQUENCE_TARGET", (s - tmplStart) + "," + (e - s));
                } else {
                    // 高级模式：primer3 无单独的"左右引物区"官方 tag（PRIMER_LEFT_OK_REGION 不存在，
                    // 未知 tag 会被静默忽略），用产物区间白名单 SEQUENCE_PRIMER_PAIR_OK_REGION_LIST
                    // 实现等价约束：产物必须跨上游区→下游区，左引物自然落上游侧、右引物落下游侧。
                    int us = i(uStartF, "上游区起") - 1;
                    int ul = i(uLenF, "上游区长");
                    int ds = i(dStartF, "下游区起") - 1;
                    int dl = i(dLenF, "下游区长");
                    if (us < 0 || us + ul > chrLen || ds < 0 || ds + dl > chrLen)
                        throw new Exception("上/下游区超出染色体范围");
                    if (ul < lenMin || dl < lenMin)
                        throw new Exception("上/下游区长度不能小于最短引物长（" + lenMin + "）");
                    if (ds + dl < us || us + ul < ds)
                        throw new Exception("上游区应在下游区左侧（允许部分重叠）");
                    int pad = lenMax * 2;
                    tmplStart = Math.max(0, Math.min(us, ds) - pad);
                    tmplEnd = Math.min(chrLen, Math.max(us + ul, ds + dl) + pad);
                    int pStart = Math.min(us, ds);
                    int pEnd = Math.max(us + ul, ds + dl);
                    params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", (pStart - tmplStart) + "," + (pEnd - pStart));
                }
                if (tmplEnd - tmplStart > 20000) throw new Exception("模板区过大（>20kb），primer3 会很慢；请缩小区间或分块设计");
                if (tmplEnd - tmplStart < prodMax + 2 * lenMax)
                    throw new Exception("模板区（" + (tmplEnd - tmplStart) + "bp）装不下最长产物（" + prodMax + "bp）+ 两侧引物，请扩大区间或减小产物上限");

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
                    JOptionPane.showMessageDialog(this,
                            "primer3 未找到合适引物对。\n可尝试：放宽 Tm/GC 范围、增大产物范围、扩大区域、减少约束。",
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

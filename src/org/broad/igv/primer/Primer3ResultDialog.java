package org.broad.igv.primer;

import org.broad.igv.ui.IGV;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.1.56：primer3 设计结果对话框。
 *
 * 表格逐对展示（产物大小/序列/Tm/GC/二聚体/位置），勾选后把 R1(+)/R2(-) 以配对边加入
 * 指定分组的引物轨（走 PrimerStore.addAll → linkByName 按 pairedWith 自动连线）。
 * 加入的引物保持脏标记（无来源文件），提醒用户「另存为」落盘。
 */
public class Primer3ResultDialog {

    /** 打开结果对话框（模态）。 */
    public static void show(final String chr, final String group, final List<Primer3Core.Pair> pairs, final int tmplOffset) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new Dlg(chr, group, pairs, tmplOffset).setVisible(true);
            }
        });
    }

    private static class Dlg extends JDialog {
        private final DefaultTableModel model;
        private final List<Primer3Core.Pair> pairs;
        private final String chr;
        private final String group;
        private final int tmplOffset;

        Dlg(String chr, final String group, List<Primer3Core.Pair> pairs, int tmplOffset) {
            super(IGV.getMainFrame(), "primer3 设计结果（" + chr + " · " + pairs.size() + " 对）", true);
            this.pairs = pairs;
            this.chr = chr;
            this.group = group;
            this.tmplOffset = tmplOffset;

            String[] cols = {"选", "#", "产物bp", "F 引物序列", "R 引物序列", "Tm F/R", "GC F/R",
                    "3'端二聚体", "位置 (F / R)"};
            model = new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 0;
                }

                @Override
                public Class<?> getColumnClass(int column) {
                    return column == 0 ? Boolean.class : Object.class;
                }
            };
            for (Primer3Core.Pair p : pairs) {
                int fs = tmplOffset + p.leftStart + 1, fe = tmplOffset + p.leftStart + p.leftLen;
                int rs = tmplOffset + p.rightStart + 1, re = tmplOffset + p.rightStart + p.rightLen;
                model.addRow(new Object[]{
                        Boolean.TRUE,
                        p.idx + 1,
                        p.productSize,
                        p.leftSeq,
                        p.rightSeq,
                        String.format("%.1f/%.1f", p.leftTm, p.rightTm),
                        String.format("%.0f/%.0f", p.leftGc, p.rightGc),
                        Double.isNaN(p.complAny) ? "-" : String.format("%.1f/%.1f", p.complAny, p.complEnd),
                        chr + ":" + fs + "-" + fe + " (+) / " + rs + "-" + re + " (-)",
                });
            }

            final JTable table = new JTable(model);
            table.setAutoCreateRowSorter(true);
            table.setRowHeight(20);
            table.getColumnModel().getColumn(3).setPreferredWidth(150);
            table.getColumnModel().getColumn(4).setPreferredWidth(150);
            table.getColumnModel().getColumn(8).setPreferredWidth(240);
            JScrollPane scroll = new JScrollPane(table);
            add(scroll, BorderLayout.CENTER);

            // ---- 底部按钮 ----
            JButton all = new JButton("全选");
            JButton none = new JButton("全不选");
            final JButton go = new JButton("加入选中到引物轨");
            JButton cancel = new JButton("取消");
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btns.add(all);
            btns.add(none);
            btns.add(go);
            btns.add(cancel);
            add(btns, BorderLayout.SOUTH);

            JLabel tip = new JLabel("加入后 F(绿,+)/R(蓝,-) 以配对线相连；引物为内存态（轨名带 *），用「另存为」可落盘为标准引物 BED");
            tip.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            add(tip, BorderLayout.NORTH);

            all.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(Boolean.TRUE, r, 0);
                }
            });
            none.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(Boolean.FALSE, r, 0);
                }
            });
            cancel.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });
            go.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    addSelected(chr, group);
                }
            });

            pack();
            setSize(900, 400);
            setLocationRelativeTo(IGV.getMainFrame());
        }

        private void addSelected(String chr, String group) {
            // 收集勾选行（注意行排序后 model 行号与 pairs 序不一致，用 # 列回查）
            List<Primer3Core.Pair> chosen = new ArrayList<Primer3Core.Pair>();
            for (int r = 0; r < model.getRowCount(); r++) {
                Object v = model.getValueAt(r, 0);
                if (Boolean.TRUE.equals(v)) {
                    int idx = ((Integer) model.getValueAt(r, 1)) - 1;
                    if (idx >= 0 && idx < pairs.size()) chosen.add(pairs.get(idx));
                }
            }
            if (chosen.isEmpty()) {
                JOptionPane.showMessageDialog(this, "未勾选任何引物对", "primer3 设计结果", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // 组装 Primer（F/R 配对 + 指标），并与同分组已有引物去重名（重复设计防撞车）
            Set<String> used = new HashSet<String>();
            for (Primer p : PrimerStore.getPrimersByGroup(group)) used.add(p.name);
            List<Primer> add = new ArrayList<Primer>();
            for (Primer3Core.Pair p : chosen) {
                add.addAll(p.toPrimers(chr, group, tmplOffset, used));
            }
            dispose();
            PrimerPlugin.ensureTrackForGroup(group);
            PrimerStore.addAll(add);
            JOptionPane.showMessageDialog(this,
                    "已加入 " + chosen.size() + " 对引物（" + add.size() + " 条）到分组「" + group + "」。\n"
                            + "轨名带 * 表示未落盘：工具条「另存为」可保存为标准引物 BED。",
                    "primer3 设计结果", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}

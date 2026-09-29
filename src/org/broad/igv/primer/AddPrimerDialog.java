package org.broad.igv.primer;

import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.awt.*;

/**
 * 添加/编辑引物对话框：
 *  - 方向 (+/-)、角色 (R1/R2)、引物长度、NGS 测序条件（F/R 测序长度 nt）
 *  - 确定后按 3' 端自动生成测序延长区
 */
public class AddPrimerDialog {

    public static void show(final int bp, final Primer existing) {
        Frame owner = IGV.getMainFrame();
        final JDialog dlg = new JDialog(owner, existing == null ? "添加引物" : "编辑引物 " + existing.name, true);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        final JTextField nameF = new JTextField(existing == null ? PrimerStore.nextName() : existing.name, 10);
        final JTextField chrF = new JTextField(existing == null ? currentChr() : existing.chr, 12);
        // v0.1.13：新增引物时起点（左端）对齐鼠标坐标，终点 = 起点 + 默认引物长度
        final JTextField startF = new JTextField(String.valueOf(existing == null ? bp : existing.start), 10);
        final JTextField endF = new JTextField(String.valueOf(existing == null ? bp + PrimerStore.defaultLen : existing.end), 10);
        final JComboBox strandC = new JComboBox(new Object[]{"+ (F)", "- (R)"});
        if (existing != null) strandC.setSelectedIndex(existing.strand == '-' ? 1 : 0);
        else strandC.setSelectedIndex(PrimerStore.defaultStrand == '-' ? 1 : 0);
        final JComboBox roleC = new JComboBox(new Object[]{"R1", "R2"});
        if (existing != null && existing.role != null) roleC.setSelectedItem(existing.role);
        else roleC.setSelectedItem(PrimerStore.defaultRole);
        int initRead = existing != null ? existing.readLen : PrimerStore.defaultReadF;
        final JSpinner readF = new JSpinner(new SpinnerNumberModel(initRead, 0, 1000, 5));
        final JSpinner readR = new JSpinner(new SpinnerNumberModel(existing != null ? existing.readLen : PrimerStore.defaultReadR, 0, 1000, 5));
        // v8：测序长度口径——勾选后填 61 且引物 20nt → 实际延伸 41（存延伸部分，显示/导出按总长标注）
        final JCheckBox incLen = new JCheckBox("包含引物长度");
        incLen.setSelected(PrimerStore.defaultIncludeLen);
        incLen.setToolTipText("勾选：填写值=引物+延伸总长；不勾：填写值=延伸部分长度");
        final JTextField pairF = new JTextField(existing != null ? PrimerStore.groupPartnerNames(existing) : "", 16);
        final JLabel ampF = new JLabel(existing == null ? PrimerStore.nextAmplicon() : existing.ampliconId);

        int row = 0;
        addRow(form, gc, row++, "名称", nameF);
        addRow(form, gc, row++, "染色体", chrF);
        addRow(form, gc, row++, "起始(0-based)", startF);
        addRow(form, gc, row++, "终止(exclusive)", endF);
        addRow(form, gc, row++, "方向", strandC);
        addRow(form, gc, row++, "角色", roleC);
        addRow(form, gc, row++, "F 测序长度 nt (R1)", readF);
        addRow(form, gc, row++, "R 测序长度 nt (R2)", readR);
        addRow(form, gc, row++, "测序长度口径", incLen);
        addRow(form, gc, row++, "配对引物名称(可填多条,逗号分隔)", pairF);
        addRow(form, gc, row++, "扩增子 ID", ampF);

        if (existing == null) {
            JLabel tip = new JLabel("确定后：引物自动生成测序延长区（3'端起 readLen nt）");
            tip.setForeground(Color.GRAY);
            addRow(form, gc, row++, "", tip);
        }

        JButton ok = new JButton(existing == null ? "添加" : "更新");
        JButton cancel = new JButton("取消");
        JButton saveCfg = new JButton("保存配置");
        JPanel btns = new JPanel();
        btns.add(ok);
        btns.add(cancel);
        btns.add(saveCfg);

        dlg.setLayout(new BorderLayout());
        dlg.add(form, BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);

        final String ampliconId = existing == null ? PrimerStore.nextAmplicon() : existing.ampliconId;

        ok.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    String name = nameF.getText().trim();
                    String chr = chrF.getText().trim();
                    int s = Integer.parseInt(startF.getText().trim());
                    int en = Integer.parseInt(endF.getText().trim());
                    if (en - s < 10) {
                        JOptionPane.showMessageDialog(dlg, "引物长度太小（<10nt）");
                        return;
                    }
                    if (en - s > 100) {
                        JOptionPane.showMessageDialog(dlg, "引物区段太大（>100nt），请填写引物本身区间（18-30nt）");
                        return;
                    }
                    char strand = strandC.getSelectedIndex() == 0 ? '+' : '-';
                    String role = (String) roleC.getSelectedItem();
                    int rf = (Integer) readF.getValue();
                    int rr = (Integer) readR.getValue();
                    String pairText = pairF.getText().trim();
                    String[] pairNames = pairText.isEmpty() ? new String[0]
                            : pairText.split("[,;\\s]+");
                    PrimerStore.defaultLen = en - s;
                    PrimerStore.defaultStrand = strand;
                    PrimerStore.defaultRole = role;
                    // v0.1.14：不再用单条引物的填写值覆盖公共默认测序长度（避免 R1/R2 默认值逐条漂移）；
                    // 公共长度只由右键「本套/全部」或"保存配置"改变，单条设置仅作用于当前引物。

                    int raw = "R1".equals(role) ? rf : rr;
                    // v8：勾选"包含引物长度" → 实际延伸 = 填写值 - 引物长度（下限 0）
                    int readLen = incLen.isSelected() ? Math.max(0, raw - (en - s)) : raw;

                    if (existing == null) {
                        Primer p = new Primer(name, chr, s, en, strand, role, readLen, ampliconId);
                        p.pairWith = pairText.isEmpty() ? null : pairText;
                        // v0.1.14：新引物归入当前选中引物所属「套」，便于按套批量设置
                        p.group = PrimerStore.selected != null ? PrimerStore.selected.group : null;
                        PrimerStore.add(p);
                        if (pairNames.length > 0) PrimerStore.linkByNames(p, pairNames);
                        PrimerStore.evaluatePairs();
                    } else {
                        existing.name = name;
                        existing.chr = chr;
                        existing.start = s;
                        existing.end = en;
                        existing.strand = strand;
                        existing.role = role;
                        existing.readLen = readLen;
                        existing.pairWith = pairText.isEmpty() ? null : pairText;
                        // v0.1.9：先脱离旧配对组再按填写内容重连——否则清空/改名后 ampliconId 仍残留，配对取消不掉
                        PrimerStore.detach(existing);
                        if (pairNames.length > 0) PrimerStore.linkByNames(existing, pairNames);
                        PrimerStore.refreshSequence(existing);
                        PrimerStore.evaluatePairs();
                    }
                    PrimerStore.refresh();
                    dlg.dispose();
                } catch (NumberFormatException nfe) {
                    JOptionPane.showMessageDialog(dlg, "坐标/数字格式错误");
                }
            }
        });
        cancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dlg.dispose();
            }
        });

        // 保存配置：把当前对话框的 长度/方向/角色/测序长度 持久化到 ~/.igv_primer_defaults.properties，下次打开自动沿用
        saveCfg.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    int s = Integer.parseInt(startF.getText().trim());
                    int en = Integer.parseInt(endF.getText().trim());
                    int len = Math.max(10, en - s);
                    char st = strandC.getSelectedIndex() == 0 ? '+' : '-';
                    String rl = (String) roleC.getSelectedItem();
                    int rf = (Integer) readF.getValue();
                    int rr = (Integer) readR.getValue();
                    PrimerStore.saveDefaults(len, st, rl, rf, rr, incLen.isSelected());
                    JOptionPane.showMessageDialog(dlg, "已保存默认配置（长度=" + len
                            + " 方向=" + st + " 角色=" + rl + " R1=" + rf + " R2=" + rr
                            + " 含引物长度=" + incLen.isSelected() + "）\n下次打开自动沿用");
                } catch (NumberFormatException nfe) {
                    JOptionPane.showMessageDialog(dlg, "坐标/数字格式错误");
                }
            }
        });

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** 双击空白快速添加：默认长度/方向/角色 */
    public static void quickAdd(int bp) {
        String chr = currentChr();
        if (chr == null) return;
        int raw = "R1".equals(PrimerStore.defaultRole) ? PrimerStore.defaultReadF : PrimerStore.defaultReadR;
        int readLen = PrimerStore.defaultIncludeLen ? Math.max(0, raw - PrimerStore.defaultLen) : raw;
        // v0.1.13：起点（左端）对齐鼠标坐标
        Primer p = new Primer(PrimerStore.nextName(), chr, bp, bp + PrimerStore.defaultLen,
                PrimerStore.defaultStrand, PrimerStore.defaultRole, readLen, PrimerStore.nextAmplicon());
        // v0.1.14：归入当前选中引物所属「套」
        p.group = PrimerStore.selected != null ? PrimerStore.selected.group : null;
        PrimerStore.add(p);
        PrimerStore.evaluatePairs();
        PrimerStore.refresh();
    }

    private static String currentChr() {
        return PrimerStore.lastChr;
    }

    private static void addRow(JPanel p, GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridy = row;
        gc.gridx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1;
        p.add(field, gc);
    }
}

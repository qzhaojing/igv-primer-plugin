package org.broad.igv.primer;

import org.broad.igv.primer.Primer.HeteroHit;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * 添加/编辑引物对话框：
 *  - 基本信息（名称/染色体/区段/方向/角色）
 *  - 测序设置（R1/R2 测序长度、口径、配对引物、扩增子 ID）
 *  - 序列与评估：实时显示参考序列、Tm/GC/hairpin/self，二聚体按需评估（按钮触发，平时不计算）
 * 确定后按 3' 端自动生成测序延长区。
 */
public class AddPrimerDialog {

    public static void show(final int bp, final Primer existing, final String group) {
        Frame owner = IGV.getMainFrame();
        final JDialog dlg = new JDialog(owner, existing == null ? "添加引物" : "编辑引物 " + existing.name, true);
        dlg.setLayout(new BorderLayout(6, 6));

        // ===== 基本信息 =====
        JPanel basic = section("基本信息");
        GridBagConstraints gcb = new GridBagConstraints();
        gcb.insets = new Insets(2, 4, 2, 4);
        gcb.anchor = GridBagConstraints.WEST;

        final JTextField nameF = new JTextField(existing == null ? PrimerStore.nextName() : existing.name, 10);
        final JLabel nameWarn = new JLabel("");
        nameWarn.setForeground(Color.RED);
        final JTextField chrF = new JTextField(existing == null ? currentChr() : existing.chr, 12);
        final JTextField startF = new JTextField(String.valueOf(existing == null ? bp : existing.start), 10);
        final JTextField endF = new JTextField(String.valueOf(existing == null ? bp + PrimerStore.defaultLen : existing.end), 10);
        final JComboBox strandC = new JComboBox(new Object[]{"+ (F)", "- (R)"});
        if (existing != null) strandC.setSelectedIndex(existing.strand == '-' ? 1 : 0);
        else strandC.setSelectedIndex(PrimerStore.defaultStrand == '-' ? 1 : 0);
        final JComboBox roleC = new JComboBox(new Object[]{"R1", "R2"});
        if (existing != null && existing.role != null) roleC.setSelectedItem(existing.role);
        else roleC.setSelectedItem(PrimerStore.defaultRole);

        int r = 0;
        addRow(basic, gcb, r++, "名称", nameF);
        addRow(basic, gcb, r++, "名称状态", nameWarn);
        addRow(basic, gcb, r++, "染色体", chrF);
        addRow(basic, gcb, r++, "起始(0-based)", startF);
        addRow(basic, gcb, r++, "终止(exclusive)", endF);
        addRow(basic, gcb, r++, "方向", strandC);
        addRow(basic, gcb, r++, "角色", roleC);

        // ===== 测序设置 =====
        JPanel seqSet = section("测序设置");
        GridBagConstraints gcs = new GridBagConstraints();
        gcs.insets = new Insets(2, 4, 2, 4);
        gcs.anchor = GridBagConstraints.WEST;
        int rf0 = existing != null ? existing.readLen : PrimerStore.defaultReadF;
        final JSpinner readF = new JSpinner(new SpinnerNumberModel(rf0, 0, 1000, 5));
        final JSpinner readR = new JSpinner(new SpinnerNumberModel(existing != null ? existing.readLen : PrimerStore.defaultReadR, 0, 1000, 5));
        final JCheckBox incLen = new JCheckBox("包含引物长度");
        incLen.setSelected(PrimerStore.defaultIncludeLen);
        incLen.setToolTipText("勾选：填写值=引物+延伸总长；不勾：填写值=延伸部分长度");
        final JTextField pairF = new JTextField(existing != null ? PrimerStore.groupPartnerNames(existing) : "", 16);
        final JLabel ampF = new JLabel(existing == null ? "（新建时留空，配对时生成）" : existing.ampliconId);
        int r2 = 0;
        addRow(seqSet, gcs, r2++, "F 测序长度 nt (R1)", readF);
        addRow(seqSet, gcs, r2++, "R 测序长度 nt (R2)", readR);
        addRow(seqSet, gcs, r2++, "测序长度口径", incLen);
        addRow(seqSet, gcs, r2++, "配对引物名称(可填多条,逗号分隔)", pairF);
        addRow(seqSet, gcs, r2++, "扩增子 ID", ampF);

        // ===== 序列与评估 =====
        JPanel eval = section("序列与评估（打开即自动显示，无需点击）");
        GridBagConstraints gce = new GridBagConstraints();
        gce.insets = new Insets(2, 4, 2, 4);
        gce.anchor = GridBagConstraints.WEST;
        final JTextArea seqArea = new JTextArea(2, 26);
        seqArea.setEditable(false);
        seqArea.setLineWrap(true);
        seqArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane seqScroll = new JScrollPane(seqArea);
        final JLabel tmL = new JLabel("-");
        final JLabel gcL = new JLabel("-");
        final JLabel hairL = new JLabel("-");
        final JLabel selfL = new JLabel("-");
        final JLabel heteroL = new JLabel("-");
        final JLabel compL = new JLabel("-");
        final JLabel statusL = new JLabel("—");
        statusL.setForeground(Color.BLUE);

        // 二聚体排布形状（左 self / 右 hetero），打开即自动显示，信息左右排列省纵向空间
        final JTextArea selfArea = new JTextArea(3, 36);
        selfArea.setEditable(false);
        selfArea.setLineWrap(false);
        selfArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        final JTextArea heteroArea = new JTextArea(3, 36);
        heteroArea.setEditable(false);
        heteroArea.setLineWrap(false);
        heteroArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // v0.1.39：全体系异源二聚体 Top-5 文本框（与其他所有引物相比，ΔG 最负优先）
        final JTextArea topArea = new JTextArea(6, 36);
        topArea.setEditable(false);
        topArea.setLineWrap(false);
        topArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel selfBox = new JPanel(new BorderLayout());
        selfBox.setBorder(new TitledBorder("self-dimer"));
        selfBox.add(selfArea, BorderLayout.CENTER);
        JPanel heteroBox = new JPanel(new BorderLayout());
        heteroBox.setBorder(new TitledBorder("最差异源二聚体（全体系）"));
        heteroBox.add(heteroArea, BorderLayout.CENTER);
        JPanel dimerPanel = new JPanel();
        dimerPanel.setLayout(new BoxLayout(dimerPanel, BoxLayout.X_AXIS));
        dimerPanel.add(selfBox);
        dimerPanel.add(Box.createHorizontalStrut(12));
        dimerPanel.add(heteroBox);
        JPanel topBox = new JPanel(new BorderLayout());
        topBox.setBorder(new TitledBorder("Top-5 异源二聚体（全体系所有引物）"));
        topBox.add(new JScrollPane(topArea), BorderLayout.CENTER);

        int r3 = 0;
        addRow(eval, gce, r3++, "序列", seqScroll);
        addRow(eval, gce, r3++, "Tm", tmL);
        addRow(eval, gce, r3++, "GC%", gcL);
        addRow(eval, gce, r3++, "hairpin ΔG", hairL);
        addRow(eval, gce, r3++, "self-dimer ΔG", selfL);
        addRow(eval, gce, r3++, "异源二聚体 ΔG", heteroL);
        addRow(eval, gce, r3++, "3' 互补碱基数", compL);
        addRow(eval, gce, r3++, "评估状态", statusL);
        gce.gridwidth = 2;
        addRow(eval, gce, r3++, "二聚体排布", dimerPanel);
        addRow(eval, gce, r3++, "异源二聚体 Top-5", topBox);
        gce.gridwidth = 1;

        // 容器
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(basic);
        form.add(seqSet);
        form.add(eval);

        // ===== 底部按钮 =====
        final JButton ok = new JButton(existing == null ? "添加" : "更新");
        JButton cancel = new JButton("取消");
        JButton saveCfg = new JButton("保存配置");
        JPanel btns = new JPanel();
        btns.add(ok);
        btns.add(cancel);
        btns.add(saveCfg);

        dlg.add(new JScrollPane(form), BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);

        // 当前展示用的引物对象（preview / eval 结果）
        final Primer[] cur = {null};

        // 名称实时校验：空 / 重名 → 提示并禁用「添加/更新」
        final Runnable updateNameStatus = new Runnable() {
            public void run() {
                String n = nameF.getText().trim();
                if (n.isEmpty()) {
                    nameWarn.setText("名称不能为空");
                    ok.setEnabled(false);
                } else if (PrimerStore.hasName(n, existing)) {
                    nameWarn.setText("名称重复");
                    ok.setEnabled(false);
                } else {
                    nameWarn.setText("");
                    ok.setEnabled(true);
                }
            }
        };
        nameF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateNameStatus.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateNameStatus.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateNameStatus.run(); }
        });
        updateNameStatus.run();

        // 把 p 的指标写到面板
        final Runnable display = new Runnable() {
            public void run() {
                Primer p = cur[0];
                if (p == null) {
                    seqArea.setText("");
                    tmL.setText("-"); gcL.setText("-"); hairL.setText("-"); selfL.setText("-");
                    heteroL.setText("-"); compL.setText("-");
                    statusL.setText("—"); statusL.setForeground(Color.BLUE);
                    return;
                }
                seqArea.setText(p.seq == null ? "" : p.seq);
                tmL.setText(fmt(p.tm));
                gcL.setText(fmt(p.gc) + (Double.isNaN(p.gc) ? "" : "%"));
                hairL.setText(String.format("%.1f", p.hairpinDG));
                selfL.setText(String.format("%.1f", p.selfDG));
                heteroL.setText(String.format("%.1f", p.heteroDG));
                compL.setText(String.format("%d", p.max3pComp));
                if (!p.dimerReason.isEmpty()) {
                    statusL.setText("不通过： " + p.dimerReason);
                    statusL.setForeground(Color.RED);
                } else if (p.heteroDG != 0 || p.max3pComp != 0 || !p.failReasons.isEmpty()) {
                    String s = "通过";
                    if (!p.failReasons.isEmpty()) s = "不通过： " + p.failReasons;
                    else s = "通过（heteroDG=" + String.format("%.1f", p.heteroDG) + ", 3'comp=" + p.max3pComp + "）";
                    statusL.setText(s);
                    statusL.setForeground(p.failReasons.isEmpty() ? new Color(0, 128, 0) : Color.RED);
                } else {
                    statusL.setText("未评估二聚体（点「评估二聚体」）");
                    statusL.setForeground(Color.BLUE);
                }
            }
        };

        // 从当前输入框构建临时引物并刷新序列 + 指标 + 二聚体形状（打开即自动显示，不跑全体评估）
        final Runnable updatePreview = new Runnable() {
            public void run() {
                try {
                    String chr = chrF.getText().trim();
                    int s = Integer.parseInt(startF.getText().trim());
                    int e = Integer.parseInt(endF.getText().trim());
                    Primer p = new Primer();
                    p.name = nameF.getText().trim();
                    p.chr = chr; p.start = s; p.end = e;
                    p.strand = strandC.getSelectedIndex() == 0 ? '+' : '-';
                    p.role = (String) roleC.getSelectedItem();
                    p.seq = readSeqFromGenome(chr, s, e);
                    PrimerMetrics.evaluate(p);
                    cur[0] = p;
                    display.run();

                    // self-dimer 形状（始终自动显示）
                    PrimerMetrics.DimerShape selfSh = PrimerMetrics.dimerShape(p.seq, p.seq);
                    selfArea.setText(selfSh.valid
                            ? selfSh.topLine + "\n" + selfSh.bondLine + "\n" + selfSh.botLine
                            : "（序列过短或未加载参考基因组）");

                    // v0.1.39：全体系异源二聚体（与其他所有引物相比）—— 计算 Top-5 并渲染
                    PrimerStore.evaluateOneAgainstAll(p);
                    // 最差异源二聚体形状（#1）：展示全体系最危险伙伴的比对形状
                    if (!p.topHetero.isEmpty()) {
                        HeteroHit h0 = p.topHetero.get(0);
                        Primer partner = null;
                        for (Primer q : PrimerStore.getPrimers()) {
                            if (q.name != null && q.name.equals(h0.partnerName)
                                    && q.seq != null && q.seq.length() >= 4) { partner = q; break; }
                        }
                        if (partner != null) {
                            PrimerMetrics.DimerShape hSh = PrimerMetrics.dimerShape(p.seq, partner.seq);
                            heteroArea.setText(hSh.valid
                                    ? hSh.topLine + "\n" + hSh.bondLine + "\n" + hSh.botLine
                                    : "（无法绘制比对）");
                            heteroL.setText(h0.partnerName + " " + String.format("%.1f", h0.dg));
                        } else {
                            heteroArea.setText("（伙伴未载入序列）");
                            heteroL.setText(h0.partnerName + " " + String.format("%.1f", h0.dg));
                        }
                    } else {
                        heteroArea.setText("（无其他引物可比对；导入/添加更多引物后显示全体系异源二聚体）");
                        heteroL.setText("—");
                    }
                    // Top-5 列表
                    StringBuilder tb = new StringBuilder();
                    if (p.topHetero.isEmpty()) {
                        tb.append("（无：无其他引物 / 未评估）");
                    } else {
                        for (int k = 0; k < p.topHetero.size(); k++) {
                            HeteroHit h = p.topHetero.get(k);
                            tb.append(String.format("%d. %-14s \u0394G=%6.1f  3'comp=%d%n",
                                    k + 1, h.partnerName, h.dg, h.c3));
                        }
                    }
                    topArea.setText(tb.toString());

                    // 评估状态（合并单引物判据 + 全体系异源二聚体）
                    if (!p.failReasons.isEmpty()) {
                        statusL.setText("不通过： " + p.failReasons
                                + (p.dimerReason.isEmpty() ? "" : " " + p.dimerReason));
                        statusL.setForeground(Color.RED);
                    } else if (!p.dimerReason.isEmpty()) {
                        statusL.setText("不通过： " + p.dimerReason);
                        statusL.setForeground(Color.RED);
                    } else {
                        statusL.setText("通过（self ΔG=" + String.format("%.1f", p.selfDG) + "）");
                        statusL.setForeground(new Color(0, 128, 0));
                    }
                } catch (Exception ignore) {
                    cur[0] = null;
                    display.run();
                    selfArea.setText("");
                    heteroArea.setText("");
                    heteroL.setText("-");
                }
            }
        };

        // 输入变化即刷新序列与单引物指标
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
        };
        chrF.getDocument().addDocumentListener(dl);
        startF.getDocument().addDocumentListener(dl);
        endF.getDocument().addDocumentListener(dl);
        ActionListener al = new ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { updatePreview.run(); }
        };
        strandC.addActionListener(al);
        roleC.addActionListener(al);

        // 打开即预览一次
        updatePreview.run();

        final String ampliconId = existing == null ? PrimerStore.nextAmplicon() : existing.ampliconId;

        ok.addActionListener(new ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                try {
                    String name = nameF.getText().trim();
                    if (name.isEmpty()) {
                        JOptionPane.showMessageDialog(dlg, "名称不能为空");
                        return;
                    }
                    if (PrimerStore.hasName(name, existing)) {
                        JOptionPane.showMessageDialog(dlg, "名称重复，无法保存");
                        return;
                    }
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
                    // v0.1.14：不再用单条引物的填写值覆盖公共默认测序长度；公共长度只由右键/保存配置改变。

                    int raw = "R1".equals(role) ? rf : rr;
                    int readLen = incLen.isSelected() ? Math.max(0, raw - (en - s)) : raw;

                    if (existing == null) {
                        // v0.1.38：新建引物绝不带任何配对信息（ampliconId/pairWith 一律清空），避免与已有扩增子撞 id 而被误连。
                        Primer p = new Primer(name, chr, s, en, strand, role, readLen, null);
                        p.pairWith = null;
                        p.group = group;   // 写入打开对话框的那条引物轨所属分组
                        PrimerStore.add(p);
                        if (pairNames.length > 0) PrimerStore.linkByNames(p, pairNames);
                        // v0.1.30：添加时不再自动跑 O(n²) 二聚体评估，仅在用户点击「评估二聚体」时按需计算
                    } else {
                        existing.name = name;
                        existing.chr = chr;
                        existing.start = s;
                        existing.end = en;
                        existing.strand = strand;
                        existing.role = role;
                        existing.readLen = readLen;
                        existing.pairWith = pairText.isEmpty() ? null : pairText;
                        PrimerStore.detach(existing);
                        if (pairNames.length > 0) PrimerStore.linkByNames(existing, pairNames);
                        PrimerStore.refreshSequence(existing);
                        // v0.1.30：编辑保存时不再自动跑 O(n²) 二聚体评估
                    }
                    PrimerStore.refresh();
                    dlg.dispose();
                } catch (NumberFormatException nfe) {
                    JOptionPane.showMessageDialog(dlg, "坐标/数字格式错误");
                }
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); }
        });

        // 保存配置：把当前对话框的 长度/方向/角色/测序长度 持久化，下次打开自动沿用
        saveCfg.addActionListener(new ActionListener() {
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

        // v0.1.39：若配置了 MFEprimer，后台算一次全体系二聚体（不卡 UI），完成后刷新预览
        if (PrimerStore.mfeExePath != null && !PrimerStore.mfeExePath.trim().isEmpty()) {
            final java.util.List<Primer> snap = PrimerStore.getPrimers();
            new javax.swing.SwingWorker<Void, Void>() {
                protected Void doInBackground() {
                    PrimerStore.updateMfeCache(snap);
                    return null;
                }
                protected void done() { updatePreview.run(); }
            }.execute();
        }

        dlg.setVisible(true);
    }

    /** 双击空白快速添加：默认长度/方向/角色 */
    public static void quickAdd(int bp) {
        String chr = currentChr();
        if (chr == null) return;
        int raw = "R1".equals(PrimerStore.defaultRole) ? PrimerStore.defaultReadF : PrimerStore.defaultReadR;
        int readLen = PrimerStore.defaultIncludeLen ? Math.max(0, raw - PrimerStore.defaultLen) : raw;
        Primer p = new Primer(PrimerStore.nextName(), chr, bp, bp + PrimerStore.defaultLen,
                PrimerStore.defaultStrand, PrimerStore.defaultRole, readLen, PrimerStore.nextAmplicon());
        p.group = (PrimerStore.selected != null ? PrimerStore.selected.group : null);
        PrimerStore.add(p);
        // v0.1.30：快速添加不再自动跑 O(n²) 二聚体评估
        PrimerStore.refresh();
    }

    private static String currentChr() {
        return PrimerStore.lastChr;
    }

    /** 从参考基因组读取区段序列（大写） */
    private static String readSeqFromGenome(String chr, int s, int e) {
        try {
            Genome g = GenomeManager.getInstance().getCurrentGenome();
            if (g == null || chr == null) return "";
            byte[] b = g.getSequence(chr, s, e);
            return b == null ? "" : new String(b).toUpperCase();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.1f", v);
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder(title));
        return p;
    }

    private static void addRow(JPanel p, GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridy = row;
        gc.gridx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1;
        p.add(field, gc);
    }
}

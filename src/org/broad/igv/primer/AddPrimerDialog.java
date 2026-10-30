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

    /** v0.1.48：按名字找伙伴引物（必须已载入序列，否则画不出碱基比对） */
    private static Primer findPartnerWithSeq(String name) {
        if (name == null) return null;
        for (Primer q : PrimerStore.getPrimers()) {
            if (name.equals(q.name) && q.seq != null && q.seq.length() >= 4) return q;
        }
        return null;
    }

    /**
     * v0.1.94：返回 boolean——true=用户点了「添加/更新」，false=取消或按 Esc 关闭。
     * 旧调用方（忽略返回值）不受影响。
     * v0.1.94：对话框内按 Esc = 取消关闭（此前 Esc 落在模态框上无任何反应，
     * 而 N 布防的全局 Esc 监听因焦点在对话框被跳过 → 老板实测"esc 也没用"）。
     */
    public static boolean show(final int bp, final Primer existing, final String group) {
        Frame owner = IGV.getMainFrame();
        final JDialog dlg = new JDialog(owner, existing == null ? "添加引物" : "编辑引物 " + existing.name, true);
        final boolean[] confirmed = {false};   // v0.1.94：给 show() 的返回值用
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
        final JLabel ampF = new JLabel(existing == null ? "（新建时不配对）"
                : (existing.pairedWith != null && !existing.pairedWith.isEmpty()
                        ? "已配对: " + String.join(", ", existing.pairedWith) : "（无配对）"));
        int r2 = 0;
        addRow(seqSet, gcs, r2++, "F 测序长度 nt (R1)", readF);
        addRow(seqSet, gcs, r2++, "R 测序长度 nt (R2)", readR);
        addRow(seqSet, gcs, r2++, "测序长度口径", incLen);
        addRow(seqSet, gcs, r2++, "配对引物名称(可填多条,逗号分隔)", pairF);
        addRow(seqSet, gcs, r2++, "扩增子 ID", ampF);

        // ===== 序列与评估 =====
        // v0.1.50：内部左右两栏用与上排相同的 50/50 等宽 GridLayout，分界线与「基本信息|测序设置」对齐
        JPanel eval = new JPanel(new GridLayout(1, 2, 10, 0));
        eval.setBorder(new TitledBorder("序列与评估（打开即自动显示，无需点击）"));
        JPanel evalGrid = new JPanel(new GridBagLayout());
        GridBagConstraints gce = new GridBagConstraints();
        gce.insets = new Insets(2, 4, 2, 4);
        gce.anchor = GridBagConstraints.WEST;
        final JTextArea seqArea = new JTextArea(2, 30);
        seqArea.setEditable(true);   // v0.2.4：序列可编辑（含突变/简并码）
        seqArea.setLineWrap(true);
        seqArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane seqScroll = new JScrollPane(seqArea);
        final boolean[] prog = {false};   // 程序化写入 seqArea 时抑制其 DocumentListener
        final javax.swing.JEditorPane mmPane = new javax.swing.JEditorPane("text/html", "");
        mmPane.setEditable(false);
        mmPane.setPreferredSize(new java.awt.Dimension(360, 64));
        final JLabel seqWarn = new JLabel("");   // 非法字符警告（红）
        seqWarn.setForeground(Color.RED);
        final JLabel mmInfo = new JLabel("");    // 突变摘要（正常色）
        final JLabel tmL = new JLabel("-");
        final JLabel gcL = new JLabel("-");
        final JLabel hairL = new JLabel("-");
        final JLabel selfL = new JLabel("-");
        final JLabel heteroL = new JLabel("-");
        final JLabel compL = new JLabel("-");
        final JLabel statusL = new JLabel("—");
        statusL.setForeground(Color.BLUE);

        // 二聚体排布形状（self），打开即自动显示
        final JTextArea selfArea = new JTextArea(3, 34);
        selfArea.setEditable(false);
        selfArea.setLineWrap(false);
        selfArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // v0.1.39：全体系异源二聚体 Top-5 文本框（与其他所有引物相比，ΔG 最负优先）
        final JTextArea topArea = new JTextArea(8, 34);
        topArea.setEditable(false);
        topArea.setLineWrap(false);
        topArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel selfBox = new JPanel(new BorderLayout());
        selfBox.setBorder(new TitledBorder("self-dimer"));
        selfBox.add(selfArea, BorderLayout.CENTER);
        JPanel topBox = new JPanel(new BorderLayout());
        topBox.setBorder(new TitledBorder("Top-5 异源二聚体（全体系所有引物）"));
        topBox.add(new JScrollPane(topArea), BorderLayout.CENTER);

        int r3 = 0;
        gce.fill = GridBagConstraints.HORIZONTAL;
        gce.weightx = 1.0;
        addRow(evalGrid, gce, r3++, "序列", seqScroll);   // 序列框横向撑满左栏，便于展开查看
        addRow(evalGrid, gce, r3++, "突变比对", new JScrollPane(mmPane));
        addRow(evalGrid, gce, r3++, "", seqWarn);
        addRow(evalGrid, gce, r3++, "", mmInfo);
        gce.fill = GridBagConstraints.NONE;
        gce.weightx = 0.0;
        addRow(evalGrid, gce, r3++, "Tm", tmL);
        addRow(evalGrid, gce, r3++, "GC%", gcL);
        addRow(evalGrid, gce, r3++, "hairpin ΔG", hairL);
        addRow(evalGrid, gce, r3++, "self-dimer ΔG", selfL);
        addRow(evalGrid, gce, r3++, "异源二聚体 ΔG", heteroL);
        addRow(evalGrid, gce, r3++, "3' 互补碱基数", compL);
        addRow(evalGrid, gce, r3++, "评估状态", statusL);

        // v0.1.49：二聚体排布（self + Top-5 异源）纵向堆在评估区右侧
        JPanel dimerRight = new JPanel();
        dimerRight.setLayout(new BoxLayout(dimerRight, BoxLayout.Y_AXIS));
        dimerRight.add(selfBox);
        dimerRight.add(Box.createVerticalStrut(6));
        dimerRight.add(topBox);
        eval.add(evalGrid);     // v0.1.50：GridLayout 50/50，左=指标，右=二聚体
        eval.add(dimerRight);

        // 容器：v0.1.50 基本信息 | 测序设置 也用 50/50 等宽，与下行评估区分栏对齐
        JPanel topRow = new JPanel(new GridLayout(1, 2, 14, 0));
        topRow.add(basic);
        topRow.add(seqSet);
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(topRow);
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
                    // v0.2.7：点击图形进入编辑框时，内存里必有此引物的序列（含突变/简并/已锁定），优先直接用；
                    // 仅当内存无序列时才回退参考基因组读取。existing.seq 已是 5'→3' 实际寡核苷酸，不可再反向互补。
                    String rawSeq;
                    if (existing != null && existing.seq != null && !existing.seq.isEmpty()) {
                        rawSeq = existing.seq;
                    } else {
                        String g = readSeqFromGenome(chr, s, e);
                        rawSeq = (p.strand == '+' || g.isEmpty()) ? g : PrimerMetrics.revComp(g);
                    }
                    p.seq = rawSeq;
                    PrimerMetrics.evaluate(p);
                    cur[0] = p;
                    prog[0] = true;          // v0.2.4：抑制 seqArea 的程序化写入触发 rebuildFromSeq
                    display.run();
                    prog[0] = false;
                    renderMismatch(p, mmPane, mmInfo);
                    // v0.2.7：序列仍取不到时给明确提示，而不是悄悄全显示 "—"
                    if (p.seq == null || p.seq.isEmpty()) {
                        seqWarn.setText("未能获取序列：请确认参考基因组已加载、染色体/坐标正确；"
                                + "若此引物曾手动改过序列，请点「更新」重新保存以持久化");
                    }

                    // self-dimer 形状（始终自动显示）
                    PrimerMetrics.DimerShape selfSh = PrimerMetrics.dimerShape(p.seq, p.seq);
                    selfArea.setText(selfSh.valid
                            ? selfSh.topLine + "\n" + selfSh.bondLine + "\n" + selfSh.botLine
                            : "（序列过短或未加载参考基因组）");

                    // v0.1.39：全体系异源二聚体（与其他所有引物相比）—— 计算 Top-5 并渲染
                    PrimerStore.evaluateOneAgainstAll(p);
                    // v0.1.49：最差异源二聚体的排布可视化已并入下方 Top-5（第 1 条即最差），
                    // 这里只保留指标行的 ΔG 数值显示，去掉重复的"最差异源二聚体"排布块
                    if (!p.topHetero.isEmpty()) {
                        HeteroHit h0 = p.topHetero.get(0);
                        heteroL.setText(h0.partnerName + " " + String.format("%.1f", h0.dg));
                    } else {
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
                            // v0.1.48：每条命中补一段碱基配对排布，只看名称+ΔG 无法直观知道碱基互补位置
                            Primer pt = findPartnerWithSeq(h.partnerName);
                            if (pt == null) {
                                tb.append("      （伙伴未载入序列，无法绘制比对）").append('\n');
                            } else {
                                PrimerMetrics.DimerShape sh = PrimerMetrics.dimerShape(p.seq, pt.seq);
                                if (sh.valid) {
                                    // v0.1.85：topLine/botLine 自带 5'-…-3' 标记、bondLine 自带 3 格缩进补偿
                                    //（与 "5'-" 同宽），这里统一 5 格前缀即可三行对齐——
                                    // 旧代码再包一层 5'-…-3'（重复）且竖线行少缩 1 格（错位）。
                                    tb.append("     ").append(sh.topLine).append('\n');
                                    tb.append("     ").append(sh.bondLine).append('\n');
                                    tb.append("     ").append(sh.botLine).append('\n');
                                } else {
                                    tb.append("      （序列过短，无法绘制比对）");
                                    tb.append('\n');
                                }
                            }
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
                    heteroL.setText("-");
                }
            }
        };

        // v0.2.4：序列框编辑 → 实时重算区间（固定 5' 端）+ 指标 + 突变比对（不回写序列框，避免光标跳动）
        final Runnable rebuildFromSeq = new Runnable() {
            public void run() {
                try {
                    String cleaned = PrimerMismatch.clean(seqArea.getText());
                    boolean okv = PrimerMismatch.valid(seqArea.getText());
                    seqWarn.setText(okv ? "" : "含非法字符（仅 A/C/G/T 及简并码 RYSWKMBDHVN，U→T）");
                    Primer p = cur[0];
                    if (p == null) {
                        p = new Primer();
                        p.name = nameF.getText().trim();
                        p.chr = chrF.getText().trim();
                        try { p.start = Integer.parseInt(startF.getText().trim()); } catch (Exception ignore) {}
                        try { p.end = Integer.parseInt(endF.getText().trim()); } catch (Exception ignore) {}
                        p.strand = strandC.getSelectedIndex() == 0 ? '+' : '-';
                        cur[0] = p;
                    }
                    int len = cleaned.length();
                    if (len > 0) {
                        if (p.strand == '+') p.end = p.start + len; else p.start = p.end - len;
                    }
                    if (okv) p.seq = cleaned;   // 非法时不覆盖，保留上次合法值供比对
                    try { PrimerMetrics.evaluate(p); } catch (Exception ignore) {}
                    tmL.setText(fmt(p.tm));
                    gcL.setText(fmt(p.gc) + (Double.isNaN(p.gc) ? "" : "%"));
                    renderMismatch(p, mmPane, mmInfo);
                } catch (Exception ignore) {}
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
        // v0.2.4：序列框编辑 → 重算区间+突变比对（prog 为 true 时由 updatePreview 程序化写入，跳过避免回环）
        seqArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { if (!prog[0]) rebuildFromSeq.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { if (!prog[0]) rebuildFromSeq.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { if (!prog[0]) rebuildFromSeq.run(); }
        });
        ActionListener al = new ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { updatePreview.run(); }
        };
        strandC.addActionListener(al);
        roleC.addActionListener(al);

        // 打开即预览一次
        updatePreview.run();

        // v0.1.40：删除死变量 ampliconId（原 new Primer 已传 null，它只剩推进 nextAmplicon 计数的副作用）。
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
                    char strand = strandC.getSelectedIndex() == 0 ? '+' : '-';
                    String role = (String) roleC.getSelectedItem();
                    // v0.2.4：序列可编辑——读入并净化，固定 5' 端重算 3' 端（'+' 链保持 start，'-' 链保持 end）
                    String rawSeq = seqArea.getText();
                    String cleaned = PrimerMismatch.clean(rawSeq);
                    if (!PrimerMismatch.valid(rawSeq)) {
                        JOptionPane.showMessageDialog(dlg, "引物序列含非法字符（仅 A/C/G/T 及简并码 RYSWKMBDHVN，U 自动转 T）");
                        return;
                    }
                    int len = cleaned.length();
                    if (len < 4) {
                        JOptionPane.showMessageDialog(dlg, "引物序列太短（<4nt）");
                        return;
                    }
                    if (len > 200) {
                        JOptionPane.showMessageDialog(dlg, "引物序列过长（>200nt）");
                        return;
                    }
                    if (strand == '+') en = s + len; else s = en - len;
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
                        // v0.1.41：新建引物绝不带任何配对信息（pairedWith 留空），避免与已有连线撞名而被误连。
                        Primer p = new Primer(name, chr, s, en, strand, role, readLen, null);
                        p.pairedWith = new java.util.LinkedHashSet<String>();
                        p.group = group;   // 写入打开对话框的那条引物轨所属分组
                        p.seq = cleaned; p.seqLocked = true;   // v0.2.4：锁定编辑后的序列（含突变/简并）
                        PrimerStore.add(p);
                        PrimerStore.refreshSequence(p);   // 锁定后跳过覆盖，仅重算指标
                        if (pairNames.length > 0) PrimerStore.linkByNames(p, pairNames);
                        // v0.1.30：添加时不再自动跑 O(n²) 二聚体评估，仅在用户点击「评估二聚体」时按需计算
                    } else {
                        History.recordUndo();   // v0.2.3：撤销/重做——记录编辑前状态
                        existing.name = name;
                        existing.chr = chr;
                        existing.start = s;
                        existing.end = en;
                        existing.strand = strand;
                        existing.role = role;
                        existing.readLen = readLen;
                        existing.seq = cleaned; existing.seqLocked = true;   // v0.2.4：锁定编辑后的序列
                        PrimerStore.detach(existing);   // 先断开旧边，再按 pairNames 重建
                        if (pairNames.length > 0) PrimerStore.linkByNames(existing, pairNames);
                        PrimerStore.refreshSequence(existing);   // 锁定后跳过覆盖，仅重算指标
                        // v0.1.30：编辑保存时不再自动跑 O(n²) 二聚体评估
                    }
                    PrimerStore.refresh();
                    confirmed[0] = true;   // v0.1.94：告知 placeNewPrimer 是确认而非取消
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

        // v0.1.94：Esc = 取消关闭（模态框内键盘焦点在对话框，N 布防的全局 Esc 监听管不到这里）
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "primer.dlg.cancel");
        dlg.getRootPane().getActionMap().put("primer.dlg.cancel", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); }
        });

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
        return confirmed[0];   // v0.1.94
    }

    /** 双击空白快速添加：默认长度/方向/角色 */
    public static void quickAdd(int bp) {
        String chr = currentChr();
        if (chr == null) return;
        int raw = "R1".equals(PrimerStore.defaultRole) ? PrimerStore.defaultReadF : PrimerStore.defaultReadR;
        int readLen = PrimerStore.defaultIncludeLen ? Math.max(0, raw - PrimerStore.defaultLen) : raw;
        Primer p = new Primer(PrimerStore.nextName(), chr, bp, bp + PrimerStore.defaultLen,
                PrimerStore.defaultStrand, PrimerStore.defaultRole, readLen, null);   // v0.1.40：不再发 ampliconId，配对时才生成
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

    /** v0.2.4：渲染突变比对到面板（HTML 着色）+ 摘要标签。 */
    private static void renderMismatch(Primer p, javax.swing.JEditorPane mm, javax.swing.JLabel info) {
        PrimerMismatch.Result r = PrimerMismatch.compare(p);
        mm.setText(PrimerMismatch.toHtml(r));
        mm.setCaretPosition(0);
        info.setText(r.ok ? PrimerMismatch.summary(r) : ("⚠ " + r.note));
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

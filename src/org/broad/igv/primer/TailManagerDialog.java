package org.broad.igv.primer;

import org.broad.igv.ui.IGV;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

/**
 * v0.1.71：5′ 附加序列（Tail / Add-on）管理器。
 *
 * <p>v0.1.73 按老板给的布局重做：左半边是附加序列列表（库条目），右半边从上到下
 * F 端 / R 端 / R1 端 / R2 端四个文本框（填序列点「添加」，随时改再点即更新）、
 * 引物结构预览（以上设置生效后引物的真实长相）、保存设置 / 另存为 / 加载库。
 *
 * <p>四个「端槽位」直接存序列、存 XML，优先级高于库条目默认：
 * R1/R2 按 role 匹配（建库场景：read1/read2 两端接头不同），F/R 按 strand 匹配（克隆场景）；
 * R1 通常就是 F 向引物、R2 就是 R 向引物，两类都填时 R1/R2 更具体、优先生效。
 * 逐条设置（右键引物 / 5′ 附加设置对话框）仍然最优先。
 *
 * <p>库文件默认 <code>~/.igv_primer_tails.xml</code>，纯 XML，可版本管理、可分享。
 */
public class TailManagerDialog extends JDialog {

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);
    private final JTextField[] slotF = new JTextField[4];
    private final JTextArea structA = new JTextArea(6, 30);
    private final JLabel stateL = new JLabel(" ");

    public TailManagerDialog() {
        super(IGV.getMainFrame(), "5′ 附加序列（Tail / Add-on）库", true);
        TailLibrary.init();
        build();
        reload();
        loadSlots();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(IGV.getMainFrame());
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(new JLabel("<html><b>5′ 附加序列</b>：导出 FASTA 时拼到引物 5′ 端。"
                + "优先级：<font color='#B71C1C'>逐条（右键引物） &gt; R1/R2 端 &gt; F/R 端 &gt; 库条目默认</font>。</html>"),
                BorderLayout.NORTH);

        root.add(listPanel(), BorderLayout.WEST);
        root.add(slotPanel(), BorderLayout.CENTER);
        root.add(bottomPanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    // ---------- 左：附加序列列表（库条目） ----------

    private JPanel listPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("附加序列列表（库条目）"));
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TailLibrary.Tail t = TailLibrary.get(selectedName());
                    if (t != null) showEntryEditor(t);
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(300, 300));
        p.add(sp, BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(0, 2, 4, 4));
        btns.add(btn("新增条目...", new Runnable() { public void run() { showEntryEditor(null); } }));
        btns.add(btn("编辑...", new Runnable() {
            public void run() {
                TailLibrary.Tail t = TailLibrary.get(selectedName());
                if (t == null) { JOptionPane.showMessageDialog(TailManagerDialog.this, "请先在列表中选择条目。"); return; }
                showEntryEditor(t);
            }
        }));
        btns.add(btn("删除", new Runnable() { public void run() { deleteEntry(); } }));
        btns.add(btn("上移", new Runnable() { public void run() { move(-1); } }));
        btns.add(btn("下移", new Runnable() { public void run() { move(1); } }));
        btns.add(btn("设为统一默认", new Runnable() { public void run() { setActive(); } }));
        btns.add(btn("停用统一默认", new Runnable() { public void run() {
            TailLibrary.setActive(null);
            TailLibrary.save();
            refreshState();
            PrimerStore.refresh();
        } }));
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    // ---------- 右：四个端槽位 + 结构预览 ----------

    private JPanel slotPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                "端附加序列（左侧列表选中 → 点本行「插入」；确认后点「保存」；清空点「清除」）"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        String[] labels = {
                "F 端附加序列（+ 链引物）：",
                "R 端附加序列（- 链引物）：",
                "R1 端附加序列（read1，优先于 F 端）：",
                "R2 端附加序列（read2，优先于 R 端）："
        };
        int[] slots = {TailLibrary.SLOT_F, TailLibrary.SLOT_R, TailLibrary.SLOT_R1, TailLibrary.SLOT_R2};
        for (int i = 0; i < 4; i++) {
            final int slot = slots[i];
            c.gridx = 0; c.gridy = i; c.weightx = 0;
            p.add(new JLabel(labels[i]), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            slotF[i] = new JTextField(24);
            p.add(slotF[i], c);
            c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
            // v0.1.75：左列表选中条目 → 点「插入」把序列接到本行文本框末尾（可连续拼保护碱基+位点+接头）
            p.add(btn("插入", new Runnable() {
                public void run() { insertSelected(slot); }
            }), c);
            c.gridx = 3;
            p.add(btn("保存", new Runnable() {
                public void run() { saveSlot(slot); }
            }), c);
            c.gridx = 4;
            p.add(btn("清除", new Runnable() {
                public void run() { clearSlot(slot); }
            }), c);
        }

        // 引物结构预览
        c.gridx = 0; c.gridy = 4; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JLabel("引物结构（以上设置生效后，实际导出的样子）："), c);
        c.gridy = 5; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        structA.setEditable(false);
        structA.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
        structA.setLineWrap(true);
        structA.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(structA);
        sp.setPreferredSize(new Dimension(420, 130));
        p.add(sp, c);
        c.gridy = 6; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(stateL, c);
        return p;
    }

    private JPanel bottomPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 4));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        left.add(btn("保存设置（写入当前 XML）", new Runnable() { public void run() { doSave(false); } }));
        left.add(btn("另存为...", new Runnable() { public void run() { doSave(true); } }));
        left.add(btn("加载库...", new Runnable() { public void run() { doLoad(); } }));
        left.add(btn("恢复内置预置", new Runnable() { public void run() {
            int r = JOptionPane.showConfirmDialog(TailManagerDialog.this,
                    "恢复内置预置会覆盖当前库（自定义条目与端附加序列全部丢失），继续？",
                    "恢复预置", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
            TailLibrary.loadDefaults();
            TailLibrary.save();
            reload();
            loadSlots();
            updateStructure();
        } }));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        right.add(btn("关闭", new Runnable() { public void run() { dispose(); } }));
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private static JButton btn(String text, final Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { r.run(); }
        });
        return b;
    }

    // ---------- 端槽位 ----------

    private void loadSlots() {
        slotF[0].setText(TailLibrary.slotSeq(TailLibrary.SLOT_F));
        slotF[1].setText(TailLibrary.slotSeq(TailLibrary.SLOT_R));
        slotF[2].setText(TailLibrary.slotSeq(TailLibrary.SLOT_R1));
        slotF[3].setText(TailLibrary.slotSeq(TailLibrary.SLOT_R2));
        updateStructure();
    }

    /** 本行文本框（索引 0..3 对应 SLOT_F/R/R1/R2）。 */
    private JTextField fieldOf(int slot) {
        int idx = slot == TailLibrary.SLOT_F ? 0 : slot == TailLibrary.SLOT_R ? 1
                : slot == TailLibrary.SLOT_R1 ? 2 : 3;
        return slotF[idx];
    }

    /** 左列表选中条目 → 把其序列接到本行文本框末尾（空则直接放入）。 */
    private void insertSelected(int slot) {
        String n = selectedName();
        if (n == null) {
            JOptionPane.showMessageDialog(this, "请先在左侧列表里选中一条附加序列。");
            return;
        }
        TailLibrary.Tail t = TailLibrary.get(n);
        if (t == null || t.seq == null || t.seq.isEmpty()) {
            JOptionPane.showMessageDialog(this, "选中的条目没有序列。");
            return;
        }
        JTextField f = fieldOf(slot);
        String cur = TailLibrary.sanitize(f.getText());
        String merged = cur + t.seq;   // 追加：可连续拼「保护碱基 + 酶切位点 + 接头」
        f.setText(merged);
        JOptionPane.showMessageDialog(this, "已把「" + n + "」插入" + TailLibrary.SLOT_NAMES[slot]
                + "文本框（当前共 " + merged.length() + " nt）。\n确认无误后点本行「保存」。");
    }

    /** 保存本行：把文本框里的序列写入该端槽位并落盘。空框不做事（清除请用「清除」按钮）。 */
    private void saveSlot(int slot) {
        JTextField f = fieldOf(slot);
        String in = f.getText();
        String sq = TailLibrary.sanitize(in);
        String nm = TailLibrary.SLOT_NAMES[slot];
        if (in == null || in.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "文本框是空的，未保存。\n"
                    + "设置：输入序列，或左侧列表选中条目后点「插入」；\n"
                    + "要清空该端请点本行「清除」。", "无法保存", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (sq.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "输入内容里没有有效碱基（只认 A/C/G/T/N 等字母，数字、空格、\n"
                    + "全角字符会被清理）。请检查输入法/粘贴内容后重试。",
                    "无法保存", JOptionPane.WARNING_MESSAGE);
            return;
        }
        TailLibrary.setSlot(slot, sq);
        f.setText(sq);
        TailLibrary.save();
        updateStructure();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this, "已保存" + nm + "附加序列：" + sq
                + "（" + sq.length() + " nt，已写入库文件）");
    }

    /** 清除本端槽位（意图明确，直接清；清完即保存）。 */
    private void clearSlot(int slot) {
        String nm = TailLibrary.SLOT_NAMES[slot];
        if (TailLibrary.slotSeq(slot).isEmpty() && (fieldOf(slot).getText() == null
                || fieldOf(slot).getText().trim().isEmpty())) {
            JOptionPane.showMessageDialog(this, nm + "当前本来就没有附加序列，无需清除。");
            return;
        }
        TailLibrary.setSlot(slot, "");
        fieldOf(slot).setText("");
        TailLibrary.save();
        updateStructure();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this, "已清除" + nm + "附加序列。");
    }

    /** 引物结构预览：每个端显示实际生效的附加（R1/R2 槽优先于 F/R 槽）与示例引物序列。 */
    private void updateStructure() {
        StringBuilder sb = new StringBuilder();
        String[][] rows = {
                {"F 端（+ 链引物，role=R1）", "+", "R1"},
                {"R 端（- 链引物，role=R2）", "-", "R2"}
        };
        for (String[] row : rows) {
            String r1 = TailLibrary.slotSeqForRole(row[2]);
            String sr = TailLibrary.slotSeqForStrand(row[1].charAt(0));
            String seq;
            String src;
            if (!r1.isEmpty()) { seq = r1; src = "R1".equals(row[2]) ? "R1端槽" : "R2端槽"; }
            else if (!sr.isEmpty()) { seq = sr; src = row[1].charAt(0) == '+' ? "F端槽" : "R端槽"; }
            else { seq = ""; src = ""; }
            String ex = exampleSeq(row[1].charAt(0));
            sb.append(row[0]).append("：\n    5′- ");
            if (seq.isEmpty()) {
                sb.append("（无附加）");
            } else {
                sb.append("[").append(seq).append("] (").append(src).append("，").append(seq.length()).append("nt) - ");
            }
            sb.append("[").append(ex).append("] (引物) -3′\n");
        }
        sb.append("说明：逐条设置（右键引物 / 5′ 附加设置）最优先；R1/R2 端槽优先于 F/R 端槽；");
        sb.append("都没设时用库条目默认（").append(TailLibrary.summaryLine()).append("）。");
        structA.setText(sb.toString());
        structA.setCaretPosition(0);
        refreshState();
    }

    /** 示例引物序列：取库中该方向第一条有序列的引物；没有就用 20nt 占位。 */
    private String exampleSeq(char strand) {
        try {
            for (Primer p : PrimerStore.getPrimers()) {
                if (p.strand == strand && p.seq != null && p.seq.length() >= 10) {
                    return p.seq.length() <= 30 ? p.seq : p.seq.substring(0, 28) + "…";
                }
            }
        } catch (Throwable ignore) { }
        return "ACGTACGTACGTACGTACGT";
    }

    private void refreshState() {
        File f = TailLibrary.currentFile();
        String cur = TailLibrary.summaryLine();
        stateL.setText("<html>当前统一/方向条目默认：<b>" + cur + "</b>　端槽位已设："
                + slotNames() + "<br>库文件：" + (f == null ? "（未保存）" : f.getAbsolutePath())
                + "</html>");
    }

    private String slotNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String s = TailLibrary.slotSeq(i);
            if (!s.isEmpty()) {
                if (sb.length() > 0) sb.append("、");
                sb.append(TailLibrary.SLOT_NAMES[i]).append("(").append(s.length()).append("nt)");
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    // ---------- 库条目（列表 + 弹出编辑器） ----------

    private void reload() {
        model.clear();
        List<TailLibrary.Tail> es = TailLibrary.entries();
        for (TailLibrary.Tail t : es) {
            model.addElement(t.name + "  [" + t.type + "]  " + shortSeq(t.seq)
                    + "  " + applyLabel(t.apply));
        }
        String act = TailLibrary.activeName();
        if (act != null) list.setSelectedValue(act, true);
    }

    private static String shortSeq(String s) {
        if (s == null) return "";
        return s.length() <= 24 ? s : s.substring(0, 22) + "…(" + s.length() + "nt)";
    }

    private static String applyLabel(String a) {
        if (TailLibrary.APPLY_F.equals(a)) return "仅F";
        if (TailLibrary.APPLY_R.equals(a)) return "仅R";
        return "双向";
    }

    private String selectedName() {
        String v = list.getSelectedValue();
        if (v == null) return null;
        int i = v.indexOf("  [");
        return i > 0 ? v.substring(0, i) : v;
    }

    /** 新增 / 编辑条目的弹出小表单；init=null 为新增。保存成功返回 true。 */
    private boolean showEntryEditor(final TailLibrary.Tail init) {
        final JTextField nameF2 = new JTextField(init == null ? "" : init.name, 16);
        final JComboBox<String> typeBox2 = new JComboBox<String>(TailLibrary.TYPES);
        final JTextArea seqA2 = new JTextArea(3, 22);
        final JComboBox<String> applyBox2 = new JComboBox<String>(
                new String[]{"双向（F+R）", "仅 F 向（+）", "仅 R 向（-）"});
        final JTextField noteF2 = new JTextField(18);
        if (init != null) {
            typeBox2.setSelectedItem(init.type);
            seqA2.setText(init.seq);
            applyBox2.setSelectedIndex(TailLibrary.APPLY_F.equals(init.apply) ? 1
                    : TailLibrary.APPLY_R.equals(init.apply) ? 2 : 0);
            noteF2.setText(init.note);
        }
        seqA2.setLineWrap(true);
        typeBox2.setEditable(true);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("名称："));         form.add(nameF2);
        form.add(new JLabel("分类："));         form.add(typeBox2);
        form.add(new JLabel("序列（5′→3′）：")); form.add(new JScrollPane(seqA2));
        form.add(new JLabel("应用方向："));     form.add(applyBox2);
        form.add(new JLabel("备注："));         form.add(noteF2);
        form.setPreferredSize(new Dimension(360, 190));

        String title = (init == null ? "新增" : "编辑") + "附加序列条目";
        while (true) {
            int r = JOptionPane.showConfirmDialog(this, form, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
            String nm = nameF2.getText().trim();
            String sq = TailLibrary.sanitize(seqA2.getText());
            if (nm.isEmpty()) { JOptionPane.showMessageDialog(this, "请填写名称。"); continue; }
            if (sq.isEmpty()) { JOptionPane.showMessageDialog(this, "请填写序列（5′→3′）。"); continue; }
            String oldName = init == null ? nm : init.name;
            if (!nm.equals(oldName) && TailLibrary.get(nm) != null) {
                JOptionPane.showMessageDialog(this, "名称「" + nm + "」已存在。");
                continue;
            }
            String tp = String.valueOf(typeBox2.getSelectedItem());
            String ap = applyBox2.getSelectedIndex() == 1 ? TailLibrary.APPLY_F
                    : applyBox2.getSelectedIndex() == 2 ? TailLibrary.APPLY_R : TailLibrary.APPLY_BOTH;
            TailLibrary.Tail t = new TailLibrary.Tail(nm, sq, tp, ap, noteF2.getText().trim());
            if (init != null) {
                List<TailLibrary.Tail> es = TailLibrary.entries();
                es.set(TailLibrary.indexOf(init.name), t);
                TailLibrary.setEntries(es);
                if (init.name.equals(TailLibrary.activeName())) TailLibrary.setActive(nm);
                if (init.name.equals(TailLibrary.defaultName('+'))) TailLibrary.setDefault('+', nm);
                if (init.name.equals(TailLibrary.defaultName('-'))) TailLibrary.setDefault('-', nm);
            } else {
                TailLibrary.add(t);
            }
            TailLibrary.save();
            reload();
            list.setSelectedValue(nm, true);
            PrimerStore.refresh();
            return true;
        }
    }

    private void deleteEntry() {
        String n = selectedName();
        if (n == null) { JOptionPane.showMessageDialog(this, "请先在左侧选择条目。"); return; }
        int r = JOptionPane.showConfirmDialog(this, "删除条目「" + n + "」？", "删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        TailLibrary.remove(n);
        TailLibrary.save();
        reload();
        updateStructure();
        PrimerStore.refresh();
    }

    private void move(int d) {
        String n = selectedName();
        if (n == null) return;
        TailLibrary.move(n, d);
        TailLibrary.save();
        reload();
        list.setSelectedValue(n, true);
    }

    private void setActive() {
        String n = selectedName();
        if (n == null) { JOptionPane.showMessageDialog(this, "请先在左侧选择要启用的条目。"); return; }
        TailLibrary.setActive(n);
        TailLibrary.save();
        updateStructure();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this,
                "已设为统一默认：导出 FASTA 时「" + n + "」将拼到引物 5′ 端（"
                        + applyLabel(TailLibrary.get(n).apply) + "），端槽位与逐条设置可覆盖它。");
    }

    // ---------- 库文件 ----------

    private void doSave(boolean as) {
        File f = TailLibrary.currentFile() != null ? TailLibrary.currentFile() : TailLibrary.defaultFile();
        if (as) {
            JFileChooser fc = new JFileChooser(f.getParentFile());
            fc.setSelectedFile(new File("primer_tails.xml"));
            fc.setFileFilter(new FileNameExtensionFilter("XML 库文件", "xml"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".xml")) f = new File(f.getAbsolutePath() + ".xml");
        }
        if (TailLibrary.save(f)) {
            JOptionPane.showMessageDialog(this, "已保存到: " + f.getAbsolutePath());
            refreshState();
        } else {
            JOptionPane.showMessageDialog(this, "保存失败: " + f.getAbsolutePath(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doLoad() {
        JFileChooser fc = new JFileChooser(TailLibrary.currentFile() != null
                ? TailLibrary.currentFile().getParentFile() : TailLibrary.defaultFile().getParentFile());
        fc.setFileFilter(new FileNameExtensionFilter("XML 库文件", "xml"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!TailLibrary.load(f)) {
            JOptionPane.showMessageDialog(this, "加载失败（不是有效的附加序列库 XML）: " + f.getAbsolutePath(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        reload();
        loadSlots();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this, "已加载: " + f.getAbsolutePath()
                + "\n共 " + TailLibrary.entries().size() + " 条。");
    }

    /** 工具条 / 菜单入口。 */
    public static void open() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    new TailManagerDialog().setVisible(true);
                } catch (Throwable t) {
                    JOptionPane.showMessageDialog(null, "打开 5′ 附加序列库失败: " + t.getMessage());
                }
            }
        });
    }
}

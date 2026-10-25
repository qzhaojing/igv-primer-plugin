package org.broad.igv.primer;

import org.broad.igv.ui.IGV;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.1.72：5′ 附加序列（Tail / Add-on）作用域设置。
 *
 * <p>解决的问题：R1 / R2 接头不同、每条引物的酶切位点不同、F / R 尾巴不同，
 * 一个「全局开关」根本不够用。本对话框把设置分成三级作用域：
 * <ol>
 *   <li><b>逐条</b>：每条引物可单独指定库条目或自定义序列（最高优先级）</li>
 *   <li><b>方向默认</b>：F 向（+ 链）/ R 向（- 链）各设一个条目</li>
 *   <li><b>统一</b>：F / R 同用一个条目（v0.1.71 的旧行为）</li>
 * </ol>
 *
 * <p>入口：引物轨工具条「5′附加」按钮 / 右键菜单「设置 ▸ 5′ 附加设置...」/
 * 右键单条引物「引物操作 ▸ 本条 5′ 附加 ▸」。
 */
public class TailSetupDialog extends JDialog {

    private static final String OFF = "（不附加）";
    private static final String INHERIT = "（继承全局/方向默认）";

    private final List<Primer> rows = new ArrayList<Primer>();
    private final JRadioButton rbUniform = new JRadioButton("统一（F / R 相同）");
    private final JRadioButton rbSplit = new JRadioButton("F / R 分开设置");
    private final JComboBox<String> cbUniform = new JComboBox<String>();
    private final JComboBox<String> cbF = new JComboBox<String>();
    private final JComboBox<String> cbR = new JComboBox<String>();
    private final JComboBox<String> cbCell = new JComboBox<String>();
    private final JLabel selL = new JLabel(" ");
    private final JTable table;
    private final TModel model;

    public TailSetupDialog() {
        super(IGV.getMainFrame(), "5′ 附加序列设置（Tail / Add-on）", true);
        TailLibrary.init();
        rows.addAll(PrimerStore.getPrimers());
        model = new TModel();
        table = new JTable(model);
        build();
        loadGlobal();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(IGV.getMainFrame());
    }

    /** 静态入口（避开 JDialog 已废弃的实例方法 show()）。 */
    public static void open() {
        TailSetupDialog d = new TailSetupDialog();
        d.setVisible(true);
    }

    // ---------- 界面 ----------

    private void build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(new JLabel("<html><b>5′ 附加序列</b>：导出 FASTA 时拼到引物 5′ 端。"
                + "作用域优先级：<font color='#B71C1C'>逐条 &gt; 方向默认（F/R） &gt; 统一</font>。</html>"),
                BorderLayout.NORTH);

        root.add(globalPanel(), BorderLayout.WEST);
        root.add(perPrimerPanel(), BorderLayout.CENTER);
        root.add(bottomPanel(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JPanel globalPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("全局默认"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new java.awt.Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        p.add(rbUniform, c);
        c.gridy = 1; c.gridwidth = 1;
        p.add(new JLabel("统一："), c);
        c.gridx = 1;
        p.add(cbUniform, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2;
        p.add(rbSplit, c);
        c.gridy = 3; c.gridwidth = 1;
        c.gridx = 0; p.add(new JLabel("F 向（+）："), c);
        c.gridx = 1; p.add(cbF, c);
        c.gridy = 4;
        c.gridx = 0; p.add(new JLabel("R 向（-）："), c);
        c.gridx = 1; p.add(cbR, c);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2;
        p.add(new JLabel("<html><font size='2' color='#555555'>条目自带的「仅F/仅R」方向过滤</font></html>"), c);
        c.gridy = 6;
        p.add(new JLabel("<html><font size='2' color='#555555'>在全局默认层生效；逐条指定时忽略。</font></html>"), c);
        c.gridy = 7;
        p.add(btn("管理序列库...", new Runnable() {
            public void run() {
                TailManagerDialog.open();
                fillAll();
                loadGlobal();
                model.fireTableDataChanged();
            }
        }), c);

        ButtonGroup g = new ButtonGroup();
        g.add(rbUniform);
        g.add(rbSplit);
        ActionListener sync = new ActionListener() {
            public void actionPerformed(ActionEvent e) { syncMode(); applyGlobal(); }
        };
        rbUniform.addActionListener(sync);
        rbSplit.addActionListener(sync);
        cbUniform.addActionListener(sync);
        cbF.addActionListener(sync);
        cbR.addActionListener(sync);
        return p;
    }

    private JPanel perPrimerPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("逐条设置（优先级最高）"));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(40);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(600, 300));
        p.add(sp, BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("设为："));
        bar.add(cbCell);
        bar.add(btn("应用到选中", new Runnable() {
            public void run() { applyToSelected(); }
        }));
        bar.add(btn("自定义序列...", new Runnable() {
            public void run() { customToSelected(); }
        }));
        bar.add(btn("清除本条设置", new Runnable() {
            public void run() { clearSelected(); }
        }));
        bar.add(btn("选中全部 F 向", new Runnable() {
            public void run() { selectStrand('+'); }
        }));
        bar.add(btn("选中全部 R 向", new Runnable() {
            public void run() { selectStrand('-'); }
        }));
        bar.add(selL);
        p.add(bar, BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent e) { updateSelLabel(); }
        });
        return p;
    }

    private JPanel bottomPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        p.add(new JLabel("<html><font size='2' color='#555555'>全局设置写入 ~/.igv_primer_tails.xml；"
                + "逐条设置随引物存进 BED / 自动保存。</font></html>"));
        p.add(btn("关闭", new Runnable() {
            public void run() { dispose(); }
        }));
        return p;
    }

    private static JButton btn(String text, final Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { r.run(); }
        });
        return b;
    }

    // ---------- 全局默认 ----------

    private void fillAll() {
        fillEntries(cbUniform, false);
        fillEntries(cbF, false);
        fillEntries(cbR, false);
        fillEntries(cbCell, true);
    }

    /** 填下拉；perPrimer=true 时额外加「继承全局」项。 */
    private static void fillEntries(JComboBox<String> cb, boolean perPrimer) {
        String keep = (String) cb.getSelectedItem();
        cb.removeAllItems();
        if (perPrimer) cb.addItem(INHERIT);
        cb.addItem(OFF);
        for (TailLibrary.Tail t : TailLibrary.entries()) cb.addItem(t.name);
        if (keep != null) cb.setSelectedItem(keep);
    }

    private void loadGlobal() {
        fillAll();
        boolean sp = TailLibrary.split();
        rbSplit.setSelected(sp);
        rbUniform.setSelected(!sp);
        String u = TailLibrary.activeName();
        cbUniform.setSelectedItem(u == null ? OFF : u);
        String f = TailLibrary.defaultName('+');
        String r = TailLibrary.defaultName('-');
        cbF.setSelectedItem(f != null ? f : (u == null ? OFF : u));
        cbR.setSelectedItem(r != null ? r : (u == null ? OFF : u));
        syncMode();
    }

    private void syncMode() {
        boolean sp = rbSplit.isSelected();
        cbUniform.setEnabled(!sp);
        cbF.setEnabled(sp);
        cbR.setEnabled(sp);
    }

    private void applyGlobal() {
        if (rbSplit.isSelected()) {
            String f = (String) cbF.getSelectedItem();
            String r = (String) cbR.getSelectedItem();
            TailLibrary.setDefault('+', OFF.equals(f) ? null : f);
            TailLibrary.setDefault('-', OFF.equals(r) ? null : r);
        } else {
            String u = (String) cbUniform.getSelectedItem();
            TailLibrary.setActive(OFF.equals(u) ? null : u);
        }
        TailLibrary.save();
        model.fireTableDataChanged();
        PrimerStore.refresh();
    }

    // ---------- 逐条 ----------

    private int[] selectedRows() {
        int[] sel = table.getSelectedRows();
        return sel == null ? new int[0] : sel;
    }

    private void updateSelLabel() {
        int n = selectedRows().length;
        selL.setText(n == 0 ? "（未选中引物）" : "已选中 " + n + " 条");
    }

    private void selectStrand(char strand) {
        DefaultListSelectionModel sm = (DefaultListSelectionModel) table.getSelectionModel();
        sm.clearSelection();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).strand == strand) sm.addSelectionInterval(i, i);
        }
        updateSelLabel();
    }

    private void applyToSelected() {
        int[] sel = selectedRows();
        if (sel.length == 0) { JOptionPane.showMessageDialog(this, "先在表中选中引物（可 Ctrl / Shift 多选）。"); return; }
        String v = (String) cbCell.getSelectedItem();
        for (int i : sel) {
            Primer p = rows.get(i);
            if (INHERIT.equals(v)) { p.tailName = null; p.tailSeq = null; }
            else if (OFF.equals(v)) { p.tailName = TailLibrary.NONE; p.tailSeq = null; }
            else { p.tailName = v; p.tailSeq = null; }
        }
        afterEdit(sel.length, "已设置 " + sel.length + " 条引物的 5′ 附加：" + v);
    }

    private void customToSelected() {
        int[] sel = selectedRows();
        if (sel.length == 0) { JOptionPane.showMessageDialog(this, "先在表中选中引物（可 Ctrl / Shift 多选）。"); return; }
        String in = JOptionPane.showInputDialog(this,
                "输入该（批）引物 5′ 端要附加的序列（5′→3′，空格/数字/小写会被清理）：",
                "自定义 5′ 附加序列", JOptionPane.PLAIN_MESSAGE);
        if (in == null) return;
        String sq = TailLibrary.sanitize(in);
        if (sq.isEmpty()) { JOptionPane.showMessageDialog(this, "序列为空，未修改。"); return; }
        for (int i : sel) {
            Primer p = rows.get(i);
            p.tailSeq = sq;
            p.tailName = null;
        }
        afterEdit(sel.length, "已为 " + sel.length + " 条引物设置自定义 5′ 附加：" + sq);
    }

    private void clearSelected() {
        int[] sel = selectedRows();
        if (sel.length == 0) { JOptionPane.showMessageDialog(this, "先在表中选中引物（可 Ctrl / Shift 多选）。"); return; }
        for (int i : sel) {
            rows.get(i).tailName = null;
            rows.get(i).tailSeq = null;
        }
        afterEdit(sel.length, "已清除 " + sel.length + " 条引物的逐条设置（回到全局/方向默认）。");
    }

    private void afterEdit(int n, String msg) {
        model.fireTableDataChanged();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this, msg);
    }

    // ---------- 表格模型 ----------

    private class TModel extends AbstractTableModel {
        private final String[] cols = {"引物", "方向", "本条设置", "生效序列（5′ 附加）", "来源"};

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            Primer p = rows.get(r);
            switch (c) {
                case 0: return p.name;
                case 1: return p.strand == '+' ? "F(+)" : "R(-)";
                case 2: return ownLabel(p);
                case 3: return effLabel(p);
                case 4: return TailLibrary.scopeFor(p);
            }
            return "";
        }
    }

    private static String ownLabel(Primer p) {
        if (p.tailSeq != null && !p.tailSeq.isEmpty()) return "自定义:" + shortSeq(TailLibrary.sanitize(p.tailSeq));
        if (p.tailName != null && !p.tailName.isEmpty()) {
            return TailLibrary.NONE.equals(p.tailName) ? "不附加" : p.tailName;
        }
        return INHERIT;
    }

    private static String effLabel(Primer p) {
        String s = TailLibrary.seqFor(p);
        if (s.isEmpty()) return "（无）";
        String nm = TailLibrary.nameFor(p);
        return (nm == null ? "" : nm + "  ") + shortSeq(s);
    }

    private static String shortSeq(String s) {
        if (s == null) return "";
        return s.length() <= 22 ? s : s.substring(0, 20) + "…(" + s.length() + "nt)";
    }

    /** 供右键菜单复用：把某条引物的逐条设置设成指定值并重绘。 */
    static void setOne(Primer p, String tailName, String tailSeq) {
        p.tailName = tailName;
        p.tailSeq = tailSeq;
        PrimerStore.refresh();
    }
}

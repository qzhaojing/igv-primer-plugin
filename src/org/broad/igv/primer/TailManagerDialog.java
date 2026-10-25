package org.broad.igv.primer;

import org.broad.igv.ui.IGV;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

/**
 * v0.1.71：5′ 附加序列（Tail / Add-on）管理器。
 *
 * <p>入口：引物轨工具条「5′ 附加 ▾」按钮 / 右键菜单「设置 ▸ 5′ 附加序列...」。
 * 功能：浏览内置预置与自定义条目、新增 / 修改 / 删除 / 排序、设为启用（导出时拼接到 5′ 端）、
 * 保存 / 另存为 / 加载 XML 库、恢复内置预置。
 *
 * <p>库文件默认 <code>~/.igv_primer_tails.xml</code>，纯 XML，可版本管理、可分享。
 */
public class TailManagerDialog extends JDialog {

    private final DefaultListModel<String> model = new DefaultListModel<String>();
    private final JList<String> list = new JList<String>(model);
    private final JTextField nameF = new JTextField(14);
    private final JComboBox<String> typeBox = new JComboBox<String>(TailLibrary.TYPES);
    private final JTextArea seqA = new JTextArea(3, 22);
    private final JComboBox<String> applyBox = new JComboBox<String>(new String[]{"双向（F+R）", "仅 F 向（+）", "仅 R 向（-）"});
    private final JTextField noteF = new JTextField(18);
    private final JLabel stateL = new JLabel(" ");
    private String editing = null;   // 正在编辑的条目名；null = 新增模式

    public TailManagerDialog() {
        super(IGV.getMainFrame(), "5′ 附加序列（Tail / Add-on）库", true);
        TailLibrary.init();
        build();
        reload();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(IGV.getMainFrame());
    }

    private void build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(new JLabel("<html><b>5′ 附加序列库</b>：导出 FASTA 时拼到引物 5′ 端——"
                + "酶切位点 / 测序接头 / 通用引物尾巴 / 蛋白标签 / 同源臂 通用。</html>"), BorderLayout.NORTH);

        // ---- 左：列表 ----
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(14);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(280, 320));
        root.add(sp, BorderLayout.WEST);

        // ---- 中：编辑表单 ----
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.setBorder(BorderFactory.createTitledBorder("条目"));
        form.add(new JLabel("名称："));        form.add(nameF);
        form.add(new JLabel("分类："));        form.add(typeBox);
        form.add(new JLabel("序列（5′→3′）：")); form.add(new JScrollPane(seqA));
        form.add(new JLabel("应用方向："));    form.add(applyBox);
        form.add(new JLabel("备注："));        form.add(noteF);
        seqA.setLineWrap(true);
        typeBox.setEditable(true);

        JPanel formWrap = new JPanel(new BorderLayout(6, 6));
        formWrap.add(form, BorderLayout.NORTH);

        JPanel formBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        formBtns.add(btn("新增/清空", new Runnable() { public void run() { clearForm(); } }));
        formBtns.add(btn("保存条目", new Runnable() { public void run() { saveEntry(); } }));
        formBtns.add(btn("删除", new Runnable() { public void run() { deleteEntry(); } }));
        formBtns.add(btn("上移", new Runnable() { public void run() { move(-1); } }));
        formBtns.add(btn("下移", new Runnable() { public void run() { move(1); } }));
        formBtns.add(btn("设为当前附加", new Runnable() { public void run() { setActive(); } }));
        formBtns.add(btn("停用附加", new Runnable() { public void run() {
            TailLibrary.setActive(null);
            TailLibrary.save();
            refreshState();
            PrimerStore.refresh();
        } }));
        formWrap.add(formBtns, BorderLayout.SOUTH);
        root.add(formWrap, BorderLayout.CENTER);

        // ---- 右：库文件操作 ----
        JPanel files = new JPanel();
        files.setLayout(new BoxLayout(files, BoxLayout.Y_AXIS));
        files.setBorder(BorderFactory.createTitledBorder("库文件（XML）"));
        files.add(btn("保存库（写回当前文件）", new Runnable() { public void run() { doSave(false); } }));
        files.add(Box.createVerticalStrut(4));
        files.add(btn("另存为...", new Runnable() { public void run() { doSave(true); } }));
        files.add(Box.createVerticalStrut(4));
        files.add(btn("加载库...", new Runnable() { public void run() { doLoad(); } }));
        files.add(Box.createVerticalStrut(4));
        files.add(btn("恢复内置预置", new Runnable() { public void run() {
            int r = JOptionPane.showConfirmDialog(TailManagerDialog.this,
                    "恢复内置预置会覆盖当前库（自定义条目丢失），继续？", "恢复预置",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
            TailLibrary.loadDefaults();
            TailLibrary.save();
            clearForm();
            reload();
        } }));
        files.add(Box.createVerticalStrut(8));
        files.add(stateL);
        root.add(files, BorderLayout.EAST);

        // ---- 底：关闭 ----
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        bottom.add(btn("关闭", new Runnable() { public void run() { dispose(); } }));
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private static JButton btn(String text, final Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { r.run(); }
        });
        return b;
    }

    // ---------- 列表 / 表单 ----------

    private void reload() {
        model.clear();
        List<TailLibrary.Tail> es = TailLibrary.entries();
        for (TailLibrary.Tail t : es) {
            model.addElement(t.name + "  [" + t.type + "]  " + shortSeq(t.seq)
                    + "  " + applyLabel(t.apply));
        }
        String act = TailLibrary.activeName();
        if (act != null) list.setSelectedValue(act, true);
        refreshState();
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

    private void refreshState() {
        File f = TailLibrary.currentFile();
        String cur = TailLibrary.activeName();
        stateL.setText("<html>当前附加：<b>"
                + (cur == null ? "关（导出不加）" : cur)
                + "</b><br>库文件：<br>" + (f == null ? "（未保存）" : f.getAbsolutePath())
                + "</html>");
        stateL.setPreferredSize(new Dimension(220, 70));
    }

    private String selectedName() {
        String v = list.getSelectedValue();
        if (v == null) return null;
        int i = v.indexOf("  [");
        return i > 0 ? v.substring(0, i) : v;
    }

    private void editSelected() {
        String n = selectedName();
        if (n == null) return;
        TailLibrary.Tail t = TailLibrary.get(n);
        if (t == null) return;
        editing = n;
        nameF.setText(t.name);
        typeBox.setSelectedItem(t.type);
        seqA.setText(t.seq);
        applyBox.setSelectedIndex(TailLibrary.APPLY_F.equals(t.apply) ? 1
                : TailLibrary.APPLY_R.equals(t.apply) ? 2 : 0);
        noteF.setText(t.note);
    }

    private void clearForm() {
        editing = null;
        nameF.setText("");
        seqA.setText("");
        noteF.setText("");
        typeBox.setSelectedItem("自定义");
        applyBox.setSelectedIndex(0);
        list.clearSelection();
    }

    private void saveEntry() {
        String nm = nameF.getText().trim();
        String sq = TailLibrary.sanitize(seqA.getText());
        if (nm.isEmpty()) { JOptionPane.showMessageDialog(this, "请填写名称。"); return; }
        if (sq.isEmpty()) { JOptionPane.showMessageDialog(this, "请填写序列（5′→3′）。"); return; }
        String tp = String.valueOf(typeBox.getSelectedItem());
        String ap = applyBox.getSelectedIndex() == 1 ? TailLibrary.APPLY_F
                : applyBox.getSelectedIndex() == 2 ? TailLibrary.APPLY_R : TailLibrary.APPLY_BOTH;
        TailLibrary.Tail t = new TailLibrary.Tail(nm, sq, tp, ap, noteF.getText().trim());
        TailLibrary.Tail old = TailLibrary.get(editing != null ? editing : nm);
        if (editing != null && old != null) {
            int i = TailLibrary.indexOf(editing);
            List<TailLibrary.Tail> es = TailLibrary.entries();
            if (!nm.equals(editing) && TailLibrary.get(nm) != null) {
                JOptionPane.showMessageDialog(this, "名称「" + nm + "」已存在。");
                return;
            }
            es.set(i, t);
            TailLibrary.setEntries(es);
            if (editing.equals(TailLibrary.activeName())) TailLibrary.setActive(nm);
            editing = nm;
        } else {
            if (TailLibrary.get(nm) != null) {
                JOptionPane.showMessageDialog(this, "名称「" + nm + "」已存在（双击列表项可修改）。");
                return;
            }
            TailLibrary.add(t);
            editing = nm;
        }
        TailLibrary.save();
        reload();
    }

    private void deleteEntry() {
        String n = selectedName();
        if (n == null) { JOptionPane.showMessageDialog(this, "请先在左侧选择条目。"); return; }
        int r = JOptionPane.showConfirmDialog(this, "删除条目「" + n + "」？", "删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        TailLibrary.remove(n);
        TailLibrary.save();
        clearForm();
        reload();
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
        refreshState();
        PrimerStore.refresh();
        JOptionPane.showMessageDialog(this,
                "已启用：导出 FASTA 时「" + n + "」将拼到引物 5′ 端（" + applyLabel(TailLibrary.get(n).apply)
                        + "），header 记录 |tail=" + n + "。");
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
        clearForm();
        reload();
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

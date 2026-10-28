package org.broad.igv.primer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;

/**
 * MFEprimer 评估结果展示：上半部分尽力解析的汇总，下半部分为原始输出（可滚动）。
 * v0.1.88：顶部按钮栏——
 *  ① 分段查看（自动探测「===== 段名 =====」标记：全部/二聚体/发夹…）；
 *  ② 「不显示引物序列」开关（隐藏 Primer ID/Sequence/Length/GC/Tm/Dg 引物清单）；
 *  ③ ΔG 阈值筛选：填一个值点「筛选」，只保留 Dg / Delta G >= 阈值的行；「清除筛选」恢复。
 * 解析只做尽力而为——即便版本差异导致行匹配不上，取消筛选/开关即可看到完整原始输出。
 */
public class MfeReportDialog extends JDialog {

    /** 段头行：===== 二聚体 (dimer) ===== */
    private static final Pattern SEC_HEAD = Pattern.compile("(?m)^=====\\s*(.+?)\\s*=====$");
    /** 引物清单数据行：ID 序列 长度 GC Tm Dg（行尾最后一个数即 Dg, kcal/mol） */
    private static final Pattern PRIMER_ROW = Pattern.compile(
            "^\\s*(\\S+)\\s+([ACGTUacgtu]+)\\s+(\\d+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+(-?[\\d.]+)\\s*$");
    /** 二聚体/发夹详情里的 ΔG 行：Delta G = -10.35 kcal/mol */
    private static final Pattern DELTA_G = Pattern.compile("(?i)delta\\s*g\\s*[:=]\\s*(-?[\\d.]+)");

    private String summary;
    private String raw;
    private Runnable rerun;
    /** 是否为综合功能面板模式（顶部显示功能选择按钮，可空面板打开） */
    private final boolean isPanel;
    /** 功能按钮栏（仅面板模式）：二聚体/发夹/特异性/覆盖度/质量检查 */
    private JPanel funcBar;
    /** 选项按钮栏（显示分段/隐藏/筛选/重新检测/导出），replaceContent 时重建 */
    private JPanel bar;
    private JButton reBtn;
    /** 面板模式下记录上次点击的功能，供「重新检测」重跑 */
    private String lastSub = "";
    private String lastTitle = "";
    private java.util.List<String> lastExtra = java.util.Collections.emptyList();
    /** 短段名 -> 段文本（含段头行）；空 Map = 原始输出无段标记 */
    private final Map<String, String> sections = new LinkedHashMap<String, String>();
    private final javax.swing.JTextField dgField = new javax.swing.JTextField(6);
    private final JTextArea ta = new JTextArea();
    private String currentSection = "";   // "" = 全部
    private boolean hideSeq = false;
    private Double dgFilter = null;

    public MfeReportDialog(String title, String summary, String raw, Runnable rerun) {
        this(title, summary, raw, rerun, false);
    }

    /** 面板模式：空面板打开，顶部含功能按钮，点按钮开始评估。 */
    public MfeReportDialog(String title, boolean isPanel) {
        this(title, "", "", null, isPanel);
        // 面板模式：「重新检测」= 重跑上次点击的功能
        this.rerun = new Runnable() {
            public void run() {
                if (lastSub != null && !lastSub.isEmpty())
                    PrimerStore.runMfeAssess(lastSub, lastTitle, lastExtra, MfeReportDialog.this);
            }
        };
    }

    public MfeReportDialog(String title, String summary, String raw, Runnable rerun, boolean isPanel) {
        super((java.awt.Frame) null, title, false);   // 非模态：弹窗打开时仍可操作 IGV 对照改引物
        this.summary = summary;
        this.raw = raw;
        this.rerun = rerun;
        this.isPanel = isPanel;
        splitSections();
        setLayout(new BorderLayout());
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        if (isPanel) {
            funcBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            addFuncButtons(funcBar);
            north.add(funcBar);
        }
        bar = new JPanel();
        north.add(bar);
        add(north, BorderLayout.NORTH);
        buildOptBar(bar);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ta.setEditable(false);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(820, 560));
        add(sp, BorderLayout.CENTER);
        refresh();
        pack();
        setLocationRelativeTo(null);
    }

    // ---------------- 段探测 ----------------

    /** 扫描 raw 里的「===== 段名 =====」行，切成具名段（短名取段名第一个空格前的词）。 */
    private void splitSections() {
        String body = raw == null ? "" : raw;
        Matcher m = SEC_HEAD.matcher(body);
        List<Integer> starts = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        while (m.find()) { starts.add(m.start()); names.add(m.group(1)); }
        for (int i = 0; i < names.size(); i++) {
            int s = starts.get(i);
            int e = (i + 1 < starts.size()) ? starts.get(i + 1) : body.length();
            String shortName = names.get(i).trim();
            int spIdx = shortName.indexOf(' ');
            if (spIdx > 0) shortName = shortName.substring(0, spIdx);
            sections.put(shortName, body.substring(s, e));
        }
    }

    // ---------------- 按钮栏 ----------------

    private void buildOptBar(JPanel bar) {
        bar.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.removeAll();
        if (!sections.isEmpty()) {
            bar.add(new JLabel("显示:"));
            javax.swing.ButtonGroup grp = new javax.swing.ButtonGroup();
            JToggleButton all = new JToggleButton("全部");
            all.setSelected(true);
            all.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    currentSection = ""; refresh();
                }
            });
            grp.add(all); bar.add(all);
            for (final String name : sections.keySet()) {
                JToggleButton b = new JToggleButton(name);
                b.addActionListener(new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        currentSection = name; refresh();
                    }
                });
                grp.add(b); bar.add(b);
            }
            bar.add(new JLabel(" | "));
        }

        JToggleButton hideBtn = new JToggleButton("不显示引物序列");
        hideBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                hideSeq = hideBtn.isSelected(); refresh();
            }
        });
        bar.add(hideBtn);

        bar.add(new JLabel("ΔG ≥"));
        bar.add(dgField);
        JButton filterBtn = new JButton("筛选");
        filterBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String t = dgField.getText().trim();
                if (t.isEmpty()) { dgFilter = null; refresh(); return; }
                try {
                    dgFilter = Double.parseDouble(t);
                } catch (NumberFormatException nfe) {
                    JOptionPane.showMessageDialog(MfeReportDialog.this,
                            "ΔG 阈值请填数字，例如 -20", "筛选", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                refresh();
            }
        });
        bar.add(filterBtn);
        JButton clearBtn = new JButton("清除筛选");
        clearBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dgField.setText(""); dgFilter = null; refresh();
            }
        });
        bar.add(clearBtn);

        reBtn = new JButton("重新检测");
        reBtn.setToolTipText("用当前引物（含本次改过的）重新跑一遍 MFEprimer 并刷新本窗口");
        reBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (rerun == null) return;
                // 防抖：点击后立刻禁用+显示检测中，后台跑完/失败/取消才恢复，避免连点叠多个任务
                reBtn.setEnabled(false);
                reBtn.setText("检测中…");
                rerun.run();   // runMfeAssess(existing=this)：成功→replaceContent（含恢复按钮），失败/取消→restoreRerunButton
            }
        });
        bar.add(reBtn);

        JButton exportBtn = new JButton("导出报告");
        exportBtn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                fc.setSelectedFile(new File("mfe_report.txt"));
                if (fc.showSaveDialog(MfeReportDialog.this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    try {
                        File f = fc.getSelectedFile();
                        if (!f.getName().toLowerCase().endsWith(".txt")) {
                            f = new File(f.getParentFile(), f.getName() + ".txt");
                        }
                        Files.write(f.toPath(), ta.getText().getBytes("UTF-8"));
                        JOptionPane.showMessageDialog(MfeReportDialog.this, "已导出到:\n" + f.getAbsolutePath());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(MfeReportDialog.this, "导出失败: " + ex.getMessage());
                    }
                }
            }
        });
        bar.add(exportBtn);
        bar.revalidate();
        bar.repaint();
    }

    // ---------------- 功能按钮栏（仅面板模式） ----------------

    /** 在 funcBar 上放 5 个功能按钮：二聚体/发夹/特异性/覆盖度/质量检查。 */
    private void addFuncButtons(JPanel funcBar) {
        String[][] defs = {
            {"二聚体", "dimer", "MFEprimer 二聚体"},
            {"发夹", "hairpin", "MFEprimer 发夹"},
            {"特异性", "spec", "MFEprimer 特异性"},
            {"覆盖度", "coverage", "MFEprimer 覆盖度"},
            {"质量检查", "qc", "MFEprimer 质量检查"},
        };
        for (final String[] d : defs) {
            final String label = d[0], sub = d[1], t = d[2];
            JButton b = new JButton(label);
            b.setToolTipText("运行 MFEprimer " + sub);
            b.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (PrimerStore.mfeExePath == null || PrimerStore.mfeExePath.trim().isEmpty()) {
                        JOptionPane.showMessageDialog(MfeReportDialog.this,
                                "尚未配置 MFEprimer 路径。\n请打开「引物操作 ▸ 设置失败判定条件」填入 MFEprimer 可执行文件绝对路径。");
                        return;
                    }
                    java.util.List<String> extra;
                    if ("dimer".equals(sub)) extra = java.util.Collections.singletonList("-p");
                    else if ("spec".equals(sub)) extra = MfeCli.defaultSpecArgs();
                    else extra = java.util.Collections.emptyList();
                    lastSub = sub; lastTitle = t; lastExtra = extra;
                    setFuncButtonsEnabled(false);   // 防抖：跑的时候禁用全部功能按钮
                    PrimerStore.runMfeAssess(sub, t, extra, MfeReportDialog.this);
                }
            });
            funcBar.add(b);
        }
    }

    private void setFuncButtonsEnabled(boolean en) {
        if (funcBar == null) return;
        for (Component c : funcBar.getComponents()) {
            if (c instanceof JButton) ((JButton) c).setEnabled(en);
        }
    }

    /** 恢复功能按钮为可点（后台跑完/失败/取消时调用，防抖期间卡死防护）。 */
    public void restoreFuncButtons() {
        setFuncButtonsEnabled(true);
    }

    // ---------------- 渲染 ----------------

    /** 重新检测成功后就地刷新本窗口：标题/内容/分段/筛选全部重置为全新结果（不关窗、不闪）。 */
    public void replaceContent(String title, String summary, String raw) {
        setTitle(title);
        this.summary = summary;
        this.raw = raw;
        this.sections.clear();
        splitSections();
        this.currentSection = "";
        this.hideSeq = false;
        this.dgFilter = null;
        if (dgField != null) dgField.setText("");
        buildOptBar(bar);   // 段按钮可能随新结果变化，整体重建
        refresh();
        restoreRerunButton();
        restoreFuncButtons();
    }

    /** 恢复「重新检测」按钮为可点状态（后台跑完/失败/取消时调用，防止防抖期间卡死）。 */
    public void restoreRerunButton() {
        if (reBtn != null) { reBtn.setText("重新检测"); reBtn.setEnabled(true); }
    }

    private void refresh() {
        ta.setText(render());
        ta.setCaretPosition(0);
    }

    private String render() {
        if (isPanel && (raw == null || raw.trim().isEmpty())) {
            return "点击上方按钮开始评估：\n"
                + "  • 二聚体 / 发夹 —— 秒级，无需基因组（仅 3' 端互补）\n"
                + "  • 特异性 / 覆盖度 —— 需当前 IGV 基因组建索引（大基因组会提示）\n"
                + "  • 质量检查 —— 二聚体+发夹合一快查\n\n"
                + "结果将在本面板下方显示；评估进行中按钮变灰，期间可继续在 IGV 中编辑引物。";
        }
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.trim().isEmpty()) {
            sb.append(summary.trim()).append("\n\n");
        }
        sb.append("================ 原始输出 ================\n");
        String body = (!currentSection.isEmpty() && sections.containsKey(currentSection))
                ? sections.get(currentSection)
                : (raw == null ? "" : raw);
        if (!hideSeq && dgFilter == null) {
            sb.append(body);
            return sb.toString();
        }
        for (String line : body.split("\n", -1)) {
            if (hideSeq && (line.trim().startsWith("Primer ID")
                    || PRIMER_ROW.matcher(line).matches())) continue;
            if (dgFilter != null) {
                Matcher pr = PRIMER_ROW.matcher(line);
                if (pr.matches()) {
                    double dg;
                    try { dg = Double.parseDouble(pr.group(6)); } catch (NumberFormatException ignore) { dg = 0; }
                    if (dg < dgFilter.doubleValue()) continue;
                } else {
                    Matcher dm = DELTA_G.matcher(line);
                    if (dm.find()) {
                        double dg;
                        try { dg = Double.parseDouble(dm.group(1)); } catch (NumberFormatException ignore) { dg = 0; }
                        if (dg < dgFilter.doubleValue()) continue;
                    }
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 从 MFEprimer 文本输出中尽力提取每个引物的 amplicon / off-target 计数汇总。 */
    public static String summarize(String stdout, String fileOut) {
        String text = (fileOut != null && !fileOut.isEmpty()) ? fileOut : (stdout == null ? "" : stdout);
        if (text.isEmpty()) return "(无输出)";
        StringBuilder sb = new StringBuilder();
        int amp = countMatches(text, "(?i)amplicon");
        int off = countMatches(text, "(?i)off[- ]?target");
        int primers = countMatches(text, "(?i)^>|Primer");
        sb.append("概要（自动统计，仅供参考）：\n");
        sb.append("  amplicon 提及次数: ").append(amp).append('\n');
        sb.append("  off-target 提及次数: ").append(off).append('\n');
        sb.append("  引物记录数: ").append(primers).append('\n');
        return sb.toString();
    }

    private static int countMatches(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}

package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.util.LongRunningTask;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出/导入：
 *  1) BED9（block=引物本体, thick=引物本体, itemRgb=方向色/fail红, name 内嵌 role+readLen）→ 可直接拖回 IGV 查看（引物长度纯净，测序区由插件按 readLen 单独绘制）
 *  2) 引物序列 FASTA（按各自方向输出 5'->3'）
 *  3) 测序读段 FASTA（引物3'端起 readLen nt 的参考序列，R2 输出反链方向）
 *  4) 导入 BED 恢复编辑态
 */
public class ExportUtils {

    private static File lastDir = null;

    private static File pick(boolean save, String defName, String desc, String ext) {
        JFileChooser fc = new JFileChooser(lastDir);
        if (defName != null) fc.setSelectedFile(new File(defName));
        fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        int r = save ? fc.showSaveDialog(null) : fc.showOpenDialog(null);
        if (r != JFileChooser.APPROVE_OPTION) return null;
        lastDir = fc.getSelectedFile().getParentFile();
        return fc.getSelectedFile();
    }

    // ---------- 1) BED ----------

    public static void exportBED() {
        File f = pick(true, "primers.bed", "BED 文件", "bed");
        if (f == null) return;
        try {
            writeBED(f);
            JOptionPane.showMessageDialog(null, "BED 已导出: " + f.getAbsolutePath()
                    + "\n可直接拖入 IGV 查看（箭头方向/thick 测序区/颜色已含）");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    /** 静默写 BED（自动保存用，不弹窗）。格式与 exportBED 一致，可被 parseBED 完整还原（配对组/颜色/测序长度）。 */
    public static synchronized void writeBED(File f) throws Exception {
        BufferedWriter w = new BufferedWriter(new FileWriter(f));
        try {
            w.write("track name=\"Primer Designer\" description=\"primers; thick=primer body; color=strand/fail\" itemRgb=\"On\"");
            w.newLine();
            for (Primer p : PrimerStore.getPrimers()) {
                // thick 限制在 [start,end] 内（= 引物本体），测序延长区单独展示、不合并进引物长度
                // v0.1.6：把当前布局行号写死进 name（|r行号），导入/自动保存可原样恢复上下排布
                Integer row = PrimerStore.screenRows.containsKey(p)
                        ? PrimerStore.screenRows.get(p) : p.rowOverride;
                w.write(String.format("%s\t%d\t%d\t%s\t0\t%c\t%d\t%d\t%s",
                        p.chr, p.start, p.end, p.bedName(row), p.strand, p.start, p.end, p.colorHex()));
                w.newLine();
            }
        } finally {
            w.close();
        }
    }

    /** 自动保存文件：~/.igv_primer_autosave.bed（session 不保存插件轨，用它兜底防丢失） */
    public static File autosaveFile() {
        return new File(System.getProperty("user.home"), ".igv_primer_autosave.bed");
    }

    /** 从自动保存恢复：静默解析并加入可编辑的 PrimerTrack，返回条数；无文件或解析失败返回 0。 */
    public static int restoreAutosave() {
        File f = autosaveFile();
        if (!f.exists()) return 0;
        try {
            List<Primer> all = new ArrayList<Primer>();
            int n = parseBED(f, "autosave", all);
            if (n == 0) return 0;
            PrimerStore.addAll(all);
            PrimerStore.refreshAll();
            return n;
        } catch (Exception ignore) {
            return 0;
        }
    }

    // ---------- 2) 引物序列 ----------

    public static void exportPrimerFasta() {
        File f = pick(true, "primers.fasta", "FASTA 文件", "fa");
        if (f == null) return;
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            for (Primer p : PrimerStore.getPrimers()) {
                String seq = primerSeq(p);
                w.write(">" + p.name + "|" + p.role + "|" + p.rangeStr() + "|" + (p.strand == '+' ? "F" : "R")
                        + "|len=" + p.length()
                        + (Double.isNaN(p.tm) ? "" : "|Tm=" + String.format("%.1f", p.tm))
                        + (Double.isNaN(p.gc) ? "" : "|GC=" + String.format("%.0f%%", p.gc))
                        + (p.pass ? "" : "|FAIL"));
                w.newLine();
                for (int i = 0; i < seq.length(); i += 60) {
                    w.write(seq.substring(i, Math.min(seq.length(), i + 60)));
                    w.newLine();
                }
            }
            w.close();
            JOptionPane.showMessageDialog(null, "引物序列已导出: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    /** 引物序列：按各自方向输出 5'->3'（反链引物输出反向互补） */
    private static String primerSeq(Primer p) {
        if (p.seq == null) return "";
        return p.strand == '+' ? p.seq : PrimerMetrics.revComp(p.seq);
    }

    // ---------- 3) 测序读段（引物 + 测序延长区） ----------

    public static void exportReadFasta() {
        File f = pick(true, "reads.fasta", "FASTA 文件", "fa");
        if (f == null) return;
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) {
            JOptionPane.showMessageDialog(null, "参考基因组未加载");
            return;
        }
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            for (Primer p : PrimerStore.getPrimers()) {
                int[] rr = p.readRegion();
                int s = Math.max(0, rr[0]);
                int e = Math.max(s, rr[1]);
                byte[] b = g.getSequence(p.chr, s, e);
                String fwd = b == null ? "" : new String(b).toUpperCase();
                String read = p.strand == '+' ? fwd : PrimerMetrics.revComp(fwd);
                w.write(">" + p.name + "|" + p.role + "|read" + p.readLen + "|" + p.chr + ":" + (s + 1) + "-" + e
                        + "|" + (p.strand == '+' ? "F" : "R"));
                w.newLine();
                for (int i = 0; i < read.length(); i += 60) {
                    w.write(read.substring(i, Math.min(read.length(), i + 60)));
                    w.newLine();
                }
            }
            w.close();
            JOptionPane.showMessageDialog(null, "测序读段已导出: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    // ---------- 4) 导入 BED（支持多选，每个 BED 一个独立分组/行） ----------

    public static void importBED() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("BED 文件", "bed"));
        int r = fc.showOpenDialog(null);
        if (r != JFileChooser.APPROVE_OPTION) return;
        File[] fs = fc.getSelectedFiles();
        if (fs.length == 0) return;
        lastDir = fs[0].getParentFile();
        try {
            List<Primer> all = new ArrayList<Primer>();
            StringBuilder msg = new StringBuilder();
            int total = 0;
            for (File f : fs) {
                // 分组名 = 文件名（去 .bed 后缀），同组才可共用一行
                String group = f.getName().replaceAll("(?i)\\.bed$", "");
                int n = parseBED(f, group, all);
                total += n;
                msg.append(f.getName()).append(" → ").append(n).append(" 条\n");
            }
            if (total == 0) {
                JOptionPane.showMessageDialog(null, "未解析到有效 BED 行（需 ≥6 列）");
                return;
            }
            PrimerStore.addAll(all);
            PrimerStore.refreshAll();
            JOptionPane.showMessageDialog(null, "已导入 " + total + " 条引物（每个 BED 独立分组分行）：\n" + msg);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导入失败: " + ex.getMessage());
        }
    }

    /** 解析单个 BED，追加到 out，返回解析条数。group 写入每条引物；内嵌 |p配对名 解析为 pairWith（导入后按名回链配对）；|r行号 恢复布局行。 */
    private static int parseBED(File f, String group, List<Primer> out) throws Exception {
        int n = 0;
        BufferedReader r = new BufferedReader(new FileReader(f));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("track") || line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] t = line.split("\t");
            if (t.length < 6) continue;
            String name = t[3];
            String role = "R1";
            int readLen = 50;
            String g = group;   // 默认：来源文件名；BED 内嵌 |gXXX 时优先
            String pairWith = null;
            String color = null;
            int row = -1;       // v0.1.6：|r行号 → rowOverride（布局定死恢复）；-1 = 自动布局
            int pipe = name.indexOf('|');
            String aId = null;
            if (pipe > 0) {
                String[] parts = name.split("\\|");
                name = parts[0];
                for (int i = 1; i < parts.length; i++) {
                    String seg = parts[i];
                    if (seg.startsWith("read") && seg.length() > 4) {
                        try { readLen = Integer.parseInt(seg.substring(4)); } catch (Exception ignore) {}
                    } else if (seg.startsWith("g") && seg.length() > 1) {
                        g = seg.substring(1);
                    } else if (seg.startsWith("a") && seg.length() > 1) {
                        aId = seg.substring(1);
                    } else if (seg.startsWith("c") && seg.length() > 1) {
                        color = seg.substring(1);
                    } else if (seg.startsWith("p") && seg.length() > 1) {
                        pairWith = seg.substring(1);
                    } else if (seg.startsWith("r") && seg.length() > 1 && Character.isDigit(seg.charAt(1))) {
                        try { row = Integer.parseInt(seg.substring(1)); } catch (Exception ignore) {}
                    }
                }
                // 兼容：第 2 段为纯 role（无前缀）时设为角色
                if (parts.length > 1 && !parts[1].startsWith("read") && !parts[1].startsWith("g")
                        && !parts[1].startsWith("a") && !parts[1].startsWith("c")
                        && !parts[1].startsWith("p")) {
                    role = parts[1];
                }
            }
            Primer p = new Primer(name, t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                    "+".equals(t[5]) ? '+' : '-', role, readLen,
                    aId != null ? aId : PrimerStore.nextAmplicon());
            p.group = g;
            p.pairWith = pairWith;
            p.color = color;
            if (row >= 0) p.rowOverride = row;   // 恢复导出时定死的布局行
            out.add(p);
            n++;
        }
        r.close();
        return n;
    }
}

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
import java.io.StringReader;
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

    /** v0.1.15：导出前若目标文件已存在，弹窗询问是否覆盖；true=允许覆盖，false=取消导出 */
    private static boolean confirmOverwrite(File f) {
        if (!f.exists()) return true;
        int r = JOptionPane.showConfirmDialog(null,
                "文件已存在，是否覆盖？\n" + f.getAbsolutePath(),
                "覆盖确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    // ---------- 1) BED ----------

    public static void exportBED() { exportBED(null); }

    /** 导出指定分组的引物到 BED（group==null 导出全部）。每个来源 BED 各成独立轨，导出互不污染。 */
    public static void exportBED(String group) {
        File f = pick(true, "primers.bed", "BED 文件", "bed");
        if (f == null) return;
        if (!confirmOverwrite(f)) return;
        try {
            List<Primer> list = (group == null) ? PrimerStore.getPrimers() : PrimerStore.getPrimersByGroup(group);
            int n = list.size();
            if (n == 0) { JOptionPane.showMessageDialog(null, "本轨没有可导出的引物。"); return; }
            writeBED(f, list);
            JOptionPane.showMessageDialog(null, "已导出 " + n + " 条引物到 BED: " + f.getAbsolutePath()
                    + (group == null ? "" : "（来源：" + group + "）")
                    + "\n可直接拖入 IGV 查看（箭头方向/thick 测序区/颜色已含）");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    // ---------- v0.1.54：保存回来源文件 / 另存为 ----------

    /** 保存本轨到来源文件；无来源文件时自动转为「另存为」。 */
    public static void saveTrack(PrimerTrack t) {
        if (t == null) return;
        if (t.sourceFile == null) { saveTrackAs(t); return; }
        writeTrackTo(t, t.sourceFile);
    }

    /** 本轨另存为新文件（选完路径即成为本轨的来源文件，之后「保存」直接回写它）。 */
    public static void saveTrackAs(PrimerTrack t) {
        if (t == null) return;
        java.util.List<Primer> list = PrimerStore.getPrimersByGroup(t.getGroup());
        if (list.isEmpty()) { JOptionPane.showMessageDialog(null, "本轨没有可保存的引物。"); return; }
        String def = (t.getGroup() == null ? "primers" : t.getGroup()) + ".bed";
        File f = pick(true, def, "BED 文件", "bed");
        if (f == null) return;
        if (!f.getName().toLowerCase().endsWith(".bed")) f = new File(f.getAbsolutePath() + ".bed");
        if (!confirmOverwrite(f)) return;
        writeTrackTo(t, f);
    }

    /** 静默写盘 + 更新轨的保存状态（不弹成功框时用） */
    private static void writeTrackTo(PrimerTrack t, File f) {
        java.util.List<Primer> list = PrimerStore.getPrimersByGroup(t.getGroup());
        if (list.isEmpty()) { JOptionPane.showMessageDialog(null, "本轨没有可保存的引物。"); return; }
        try {
            writeBED(f, list);
            t.markSaved(f);
            PrimerStore.refresh();
            JOptionPane.showMessageDialog(null, "已保存 " + list.size() + " 条引物到：\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "保存失败: " + ex.getMessage());
        }
    }

    /** 静默写 BED（自动保存用，不弹窗）。格式与 exportBED 一致，可被 parseBED 完整还原（配对组/颜色/测序长度）。 */
    public static synchronized void writeBED(File f) throws Exception {
        writeBED(f, PrimerStore.getPrimers());
    }

    /** 静默写指定引物列表到 BED（按组导出/自动保存复用）。边模型：bedName 已内嵌 |p伙伴，直接写即可。 */
    private static synchronized void writeBED(File f, List<Primer> primers) throws Exception {
        BufferedWriter w = new BufferedWriter(new FileWriter(f));
        try {
            w.write("track name=\"Primer Designer\" description=\"primers; thick=primer body; color=strand/fail\" itemRgb=\"On\"");
            w.newLine();
            w.write(toBedText(primers));
        } finally {
            w.close();
        }
    }

    /**
     * 把引物列表渲染成 BED 正文（不含 track 头行）。导出文件与 session 保存共用同一份序列化，
     * 内含 role / readLen / 分组 |g / 配对边 |p / 颜色 |c / 布局行 |r，可被 parseLines 完整还原。
     */
    static synchronized String toBedText(List<Primer> primers) {
        StringBuilder sb = new StringBuilder();
        java.util.Map<Primer, Integer> rows = null;
        try { rows = PrimerTrack.allScreenRows(); } catch (Throwable ignore) { }  // 渲染上下文缺失时退化为 rowOverride
        for (Primer p : primers) {
            Integer row = (rows != null && rows.containsKey(p)) ? rows.get(p) : p.rowOverride;
            sb.append(String.format("%s\t%d\t%d\t%s\t0\t%c\t%d\t%d\t%s",
                    p.chr, p.start, p.end, p.bedName(row), p.strand, p.start, p.end, p.colorHex()));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 自动保存文件：~/.igv_primer_autosave.bed（session 不保存插件轨，用它兜底防丢失；包含所有分组） */
    public static File autosaveFile() {
        return new File(System.getProperty("user.home"), ".igv_primer_autosave.bed");
    }

    /** 从自动保存恢复：静默解析（分组从 BED 内嵌 |g 还原），按分组重建独立轨，返回条数；无文件或解析失败返回 0。 */
    public static int restoreAutosave() {
        File f = autosaveFile();
        if (!f.exists()) return 0;
        try {
            List<Primer> all = new ArrayList<Primer>();
            int n = parseBED(f, null, all);   // group 默认 null，有 |g 则还原到原分组
            if (n == 0) return 0;
            PrimerStore.addAll(all);
            // 为每个出现的分组确保一条独立轨
            java.util.Set<String> groups = new java.util.HashSet<String>();
            for (Primer p : all) groups.add(p.group);
            for (String g : groups) PrimerPlugin.ensureTrackForGroup(g);
            PrimerStore.refreshAll();
            return n;
        } catch (Exception ignore) {
            return 0;
        }
    }

    // ---------- 2) 引物序列 ----------

    /**
     * v0.1.52：header 补齐还原所需的全部元信息（分组|g / 配对边|p / 颜色|c / 布局行|r / 测序长度），
     * 使导出的 FASTA 也能被 importBED() 完整还原成图（与 BED 等价的自包含格式）。
     * 显示信息保持不变，新增段追加在末尾，旧格式文件仍可被解析。
     */
    private static String fastaHeader(Primer p, Integer row) {
        StringBuilder h = new StringBuilder(">" + p.name + "|" + p.role
                + "|" + p.rangeStr() + "|" + (p.strand == '+' ? "F" : "R")
                + "|len=" + p.length()
                + (Double.isNaN(p.tm) ? "" : "|Tm=" + String.format("%.1f", p.tm))
                + (Double.isNaN(p.gc) ? "" : "|GC=" + String.format("%.0f%%", p.gc))
                + (p.pass ? "" : "|FAIL"));
        h.append("|read").append(p.readLen);
        if (p.group != null && !p.group.isEmpty()) h.append("|g").append(p.group);
        if (p.pairedWith != null && !p.pairedWith.isEmpty())
            h.append("|p").append(String.join(";", p.pairedWith));
        if (p.color != null && !p.color.isEmpty()) h.append("|c").append(p.color);
        if (row != null) h.append("|r").append(row);
        return h.toString();
    }

    public static void exportPrimerFasta() {
        File f = pick(true, "primers.fasta", "FASTA 文件", "fa");
        if (f == null) return;
        if (!confirmOverwrite(f)) return;
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            int n = 0;
            java.util.Map<Primer, Integer> rows = null;
            try { rows = PrimerTrack.allScreenRows(); } catch (Throwable ignore) { }
            for (Primer p : PrimerStore.getPrimers()) {
                String seq = primerSeq(p);
                Integer row = (rows != null && rows.containsKey(p)) ? rows.get(p) : p.rowOverride;
                w.write(fastaHeader(p, row));
                w.newLine();
                for (int i = 0; i < seq.length(); i += 60) {
                    w.write(seq.substring(i, Math.min(seq.length(), i + 60)));
                    w.newLine();
                }
                n++;
            }
            w.close();
            JOptionPane.showMessageDialog(null, "已导出 " + n + " 条引物序列到: " + f.getAbsolutePath()
                    + "\n（header 内含坐标/分组/配对/布局行，可直接再用「导入」还原成图）");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    /** 引物序列：p.seq 已存实际引物 5'->3'（R 在写入时已是反向互补），直接返回即可 */
    private static String primerSeq(Primer p) {
        return p.seq == null ? "" : p.seq;
    }

    // ---------- 3) 测序读段（引物 + 测序延长区） ----------

    public static void exportReadFasta() {
        File f = pick(true, "reads.fasta", "FASTA 文件", "fa");
        if (f == null) return;
        if (!confirmOverwrite(f)) return;
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) {
            JOptionPane.showMessageDialog(null, "参考基因组未加载");
            return;
        }
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            int n = 0;
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
                n++;
            }
            w.close();
            JOptionPane.showMessageDialog(null, "已导出 " + n + " 条引物的测序读段到: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导出失败: " + ex.getMessage());
        }
    }

    /** 去掉常见后缀得到分组名（BED/FASTA 通用） */
    private static String stripExt(String filename) {
        return filename.replaceAll("(?i)\\.(bed|fa|fasta|fna|fas|seq|txt)$", "");
    }

    /** 嗅探：首条非空行以 '>' 开头即 FASTA，否则按 BED 处理 */
    private static boolean looksLikeFasta(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                return s.startsWith(">");
            }
        } catch (Exception ignore) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignore) { }
        }
        return false;
    }

    // ---------- 4) 导入 BED / FASTA（多选，每个文件一个独立分组/轨） ----------

    public static void importBED() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("BED / FASTA 文件", "bed", "fa", "fasta", "fna", "fas", "txt"));
        int r = fc.showOpenDialog(null);
        if (r != JFileChooser.APPROVE_OPTION) return;
        File[] fs = fc.getSelectedFiles();
        if (fs.length == 0) return;
        lastDir = fs[0].getParentFile();
        try {
            StringBuilder msg = new StringBuilder();
            int total = 0, skipped = 0;
            for (File f : fs) {
                // 分组名 = 文件名（去后缀）；每个文件一个独立分组 → 一条独立引物轨
                String group = stripExt(f.getName());
                List<Primer> list = new ArrayList<Primer>();
                // v0.1.52：同一入口同时支持 BED 与 FASTA，按内容嗅探分发
                int n = looksLikeFasta(f) ? parseFASTA(f, group, list) : parseBED(f, group, list);
                if (n == 0) {
                    skipped++;
                    continue;
                }
                int replaced = PrimerStore.removeGroup(group);   // v0.1.53：同组重导入=替换，防止同名引物叠加画重影
                if (replaced > 0) {
                    msg.append("  （分组「").append(group).append("」已存在 ").append(replaced)
                       .append(" 条旧引物，已替换）\n");
                }
                PrimerPlugin.ensureTrackForGroup(group);   // 空轨认领 / 否则新建独立轨
                PrimerStore.addAll(list);
                // v0.1.54：记住来源文件，之后「保存」直接回写这个文件（脏标记随之清零）
                PrimerTrack tk = PrimerPlugin.getTrackForGroup(group);
                if (tk != null) tk.markSaved(f);
                total += n;
                msg.append(f.getName()).append(" → ").append(n).append(" 条（独立轨：「").append(group).append("」）\n");
            }
            if (total == 0) {
                JOptionPane.showMessageDialog(null, "未解析到有效引物：\n"
                        + "• BED 需 ≥6 列\n• FASTA header 需含区间（如 |chr1:1001-1020|）");
                return;
            }
            PrimerStore.refreshAll();
            String tail = skipped > 0 ? ("\n另有 " + skipped + " 个文件无有效引物（缺少坐标信息）") : "";
            JOptionPane.showMessageDialog(null, "已导入 " + total + " 条引物（每个文件独立成轨，可分别编辑/导出/关闭）：\n" + msg + tail);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "导入失败: " + ex.getMessage());
        }
    }

    /** 解析单个 BED，追加到 out，返回解析条数。group 写入每条引物；内嵌 |p伙伴 解析为本引物的边（pairedWith）；
     *  旧格式 |a组 在解析后按 F-R 重建边（见 postProcessLegacyGroups）；|r行号 恢复布局行。 */
    private static int parseBED(File f, String group, List<Primer> out) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        try { return parseLines(r, group, out); } finally { r.close(); }
    }

    /** 从 BED 文本解析（v0.1.47：session 恢复用）。格式与文件版一致；解析失败返回 0。 */
    public static int parseBEDText(String text, String group, List<Primer> out) {
        try {
            return parseLines(new BufferedReader(new StringReader(text)), group, out);
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static int parseLines(BufferedReader r, String group, List<Primer> out) throws Exception {
        int n = 0;
        String line;
        java.util.List<Primer> legacyGroupMembers = new java.util.ArrayList<Primer>();
        while ((line = r.readLine()) != null) {
            if (line.startsWith("track") || line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] t = line.split("\t");
            if (t.length < 6) continue;
            String name = t[3];
            String role = "R1";
            int readLen = 50;
            String g = group;   // 默认：来源文件名；BED 内嵌 |gXXX 时优先
            String color = null;
            int row = -1;       // v0.1.6：|r行号 → rowOverride（布局定死恢复）；-1 = 自动布局
            String aId = null;
            java.util.List<String> partners = new java.util.ArrayList<String>();  // v0.1.46：先收集，引物入列后再写 pairedWith
            if (name.indexOf('|') > 0) {
                String[] parts = name.split("\\|");
                name = parts[0];
                for (int i = 1; i < parts.length; i++) {
                    String seg = parts[i];
                    if (seg.startsWith("read") && seg.length() > 4) {
                        try { readLen = Integer.parseInt(seg.substring(4)); } catch (Exception ignore) {}
                    } else if (seg.startsWith("g") && seg.length() > 1) {
                        // v0.1.53：分组以导入入口传入的（文件名/轨）为准，行内 |g 仅在无分组时采纳。
                        // 否则 primers091.bed 内嵌 |gprimers 会把引物全塞进旧 "primers" 组：
                        // 新建的 primers091 轨空空如也（看似导入失败），同名引物还叠加进旧组。
                        if (group == null) g = seg.substring(1);
                    } else if (seg.startsWith("a") && seg.length() > 1) {
                        aId = seg.substring(1);
                    } else if (seg.startsWith("c") && seg.length() > 1) {
                        color = seg.substring(1);
                    } else if (seg.startsWith("p") && seg.length() > 1) {
                        // 边模型：|p 后跟若干伙伴名，';' 分隔（v0.1.41+）
                        // v0.1.46 修复：此处本引物尚未加入 out，原 addEdgeName(out, name, ...) 按名找不到自己 → 边全丢。
                        // 改为先收集，out.add(p) 之后再写入 pairedWith。
                        for (String nm : seg.substring(1).split(";")) {
                            nm = nm.trim();
                            if (!nm.isEmpty()) partners.add(nm);
                        }
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
                    "+".equals(t[5]) ? '+' : '-', role, readLen, null);
            p.group = g;
            p.color = color;
            if (row >= 0) p.rowOverride = row;   // 恢复导出时定死的布局行
            out.add(p);
            if (!partners.isEmpty()) {           // v0.1.46：引物已在 out 中，此时才写边
                if (p.pairedWith == null) p.pairedWith = new java.util.LinkedHashSet<String>();
                p.pairedWith.addAll(partners);
            }
            n++;
            if (aId != null) { p.ampliconId = aId; legacyGroupMembers.add(p); }  // 旧格式：挂到组，稍后重建边
        }
        postProcessLegacyGroups(legacyGroupMembers);   // 旧 |a 组 → F-R 边
        sanitizeEdges(out);                            // 校验所有边（跨染色体/跨度过大/无法成产物 → 剥离）
        return n;
    }

    // ---------- FASTA 导入（v0.1.52：header 含完整元信息时可完整还原成图） ----------

    /** FASTA 单条记录的中间态 */
    private static class FastaRec {
        String name, role = "R1", chr = null, color = null, group = null;
        int start = -1, end = -1, readLen = 50, row = -1;
        char strand = '+';
        java.util.List<String> partners = new ArrayList<String>();
        StringBuilder seq = new StringBuilder();
    }

    /**
     * 解析本插件导出的引物 FASTA。header 形如：
     *   &gt;P039|R1|chr1:1001-1020|F|len=20|Tm=60.0|GC=50%|read50|gprimers|pP040|c008000|r0
     * 坐标段为必填（无坐标的野生 FASTA 会被跳过）；|g|p|c|r 用于还原分组/配对边/颜色/布局行。
     */
    private static int parseFASTA(File f, String group, List<Primer> out) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        int n = 0;
        try {
            FastaRec cur = null;
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                if (s.startsWith(">")) {
                    if (cur != null && flushFasta(cur, group, out)) n++;
                    cur = parseFastaHeader(s.substring(1).trim());
                } else if (cur != null) {
                    cur.seq.append(s.toUpperCase().replaceAll("\\s+", ""));
                }
            }
            if (cur != null && flushFasta(cur, group, out)) n++;
        } finally {
            r.close();
        }
        sanitizeEdges(out);   // 同 BED：跨染色体/跨度过大/悬空伙伴 → 剥离
        return n;
    }

    private static FastaRec parseFastaHeader(String h) {
        FastaRec rec = new FastaRec();
        // 兼容两种分隔：本插件用 '|'；野生 FASTA 多为空格
        String[] segs = h.contains("|") ? h.split("\\|") : h.split("\\s+");
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i].trim();
            if (seg.isEmpty()) continue;
            if (i == 0 && rec.name == null && !seg.matches(".+:\\d+-\\d+")) { rec.name = seg; continue; }
            // 坐标段 chr:start-end（rangeStr 口径：start 为 1-based，end 为 exclusive）
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+):(\\d+)-(\\d+)$").matcher(seg);
            if (m.matches()) {
                rec.chr = m.group(1);
                rec.start = Integer.parseInt(m.group(2)) - 1;
                rec.end = Integer.parseInt(m.group(3));
                continue;
            }
            if (seg.equals("F") || seg.equals("+")) { rec.strand = '+'; continue; }
            if (seg.equals("R") || seg.equals("-")) { rec.strand = '-'; continue; }
            if (seg.startsWith("read") && seg.length() > 4) {
                try { rec.readLen = Integer.parseInt(seg.substring(4)); } catch (Exception ignore) { }
            } else if (seg.startsWith("g") && seg.length() > 1) {
                rec.group = seg.substring(1);
            } else if (seg.startsWith("p") && seg.length() > 1) {
                for (String nm : seg.substring(1).split(";")) {
                    nm = nm.trim();
                    if (!nm.isEmpty()) rec.partners.add(nm);
                }
            } else if (seg.startsWith("c") && seg.length() > 1) {
                rec.color = seg.substring(1);
            } else if (seg.startsWith("r") && seg.length() > 1 && Character.isDigit(seg.charAt(1))) {
                try { rec.row = Integer.parseInt(seg.substring(1)); } catch (Exception ignore) { }
            } else if (i == 1 && rec.role == null || (i == 1 && !seg.contains("="))) {
                rec.role = seg;   // 第 2 段且不像 key=value → 视为角色
            }
        }
        if (rec.role == null) rec.role = "R1";
        return rec;
    }

    /** 落一条记录为 Primer，成功返回 true（无坐标 → false，跳过） */
    private static boolean flushFasta(FastaRec h, String group, List<Primer> out) {
        if (h == null || h.name == null || h.chr == null || h.end <= h.start) return false;
        Primer p = new Primer(h.name, h.chr, h.start, h.end, h.strand, h.role, h.readLen, null);
        p.group = (h.group != null && !h.group.isEmpty()) ? h.group : group;
        if (h.color != null) p.color = h.color;
        if (h.row >= 0) p.rowOverride = h.row;
        if (h.seq.length() > 0) p.seq = h.seq.toString();   // 未加载基因组时保住序列（有基因组仍以参考为准）
        out.add(p);
        if (!h.partners.isEmpty()) {
            p.pairedWith = new java.util.LinkedHashSet<String>();
            p.pairedWith.addAll(h.partners);
        }
        return true;
    }

    /** 在 out 中找到名为 ownName 的引物，把一个伙伴名 partner 记入其 pairedWith（容错：找不到则忽略）。 */
    private static void addEdgeName(List<Primer> out, String ownName, String partner) {
        for (Primer p : out) {
            if (p.name.equals(ownName)) {
                if (p.pairedWith == null) p.pairedWith = new java.util.LinkedHashSet<String>();
                p.pairedWith.add(partner);
                return;
            }
        }
    }

    /** 旧格式 |a组：同组内每条 F 与每条 R 各建一条边（仅当能形成 PCR 产物），还原多重 PCR 意图，且不产生 F-F/R-R 错连。 */
    private static void postProcessLegacyGroups(List<Primer> members) {
        if (members.isEmpty()) return;
        java.util.Map<String, List<Primer>> byGroup = new java.util.LinkedHashMap<String, List<Primer>>();
        for (Primer p : members) {
            if (p.ampliconId == null) continue;
            byGroup.computeIfAbsent(p.ampliconId, new java.util.function.Function<String, java.util.List<Primer>>() {
                public java.util.List<Primer> apply(String k) { return new java.util.ArrayList<Primer>(); }
            }).add(p);
        }
        for (List<Primer> grp : byGroup.values()) {
            for (Primer f : grp) {
                if (f.strand != '+') continue;
                for (Primer r2 : grp) {
                    if (r2.strand != '-') continue;
                    if ((r2.end - f.start) > 0) addEdgeName(grp, f.name, r2.name);
                }
            }
        }
        for (Primer p : members) p.ampliconId = null;   // 标记用毕即清
    }

    /**
     * 导入侧边 sanity 净化：真实 PCR 边跨度只有几百 bp（实测 0.2~0.5kb），若一条边跨染色体或
     * 总跨度 >10kb（只可能是旧版自动配对 bug 留下的串组）→ 剥离该边。互反边一并清理。
     * 正常的手动配对（异链 + R.end-F.start>0 + 跨度小）不受影响。
     */
    private static void sanitizeEdges(List<Primer> out) {
        java.util.Map<String, Primer> byName = new java.util.HashMap<String, Primer>();
        for (Primer p : out) byName.put(p.name, p);
        for (Primer p : out) {
            if (p.pairedWith == null) continue;
            java.util.List<String> bad = new java.util.ArrayList<String>();
            for (String nm : p.pairedWith) {
                Primer q = byName.get(nm);
                if (q == null) { bad.add(nm); continue; }                      // 悬空伙伴
                if (!p.chr.equals(q.chr)) { bad.add(nm); continue; }           // 跨染色体
                int lo = Math.min(p.start, q.start), hi = Math.max(p.end, q.end);
                if ((hi - lo) > 10000) { bad.add(nm); continue; }              // 跨度过大（串组）
                if (!((Math.max(p.end, q.end) - Math.min(p.start, q.start)) > 0)) bad.add(nm); // 无法成产物
            }
            for (String nm : bad) {
                p.pairedWith.remove(nm);
                Primer q = byName.get(nm);
                if (q != null && q.pairedWith != null) q.pairedWith.remove(p.name);  // 互反清理
            }
        }
    }
}

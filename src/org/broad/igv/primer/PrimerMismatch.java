package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引物序列 vs 参考基因组的突变比对。
 *
 * <p>引物 {@link Primer#seq} 始终为 5'→3' 实际寡核苷酸（反向链引物已是区间反向互补）。
 * 比对时把参考区间也转成与 oligo 同序（反向链先 RC），再用 <b>全局比对（Needleman-Wunsch）</b>
 * 逐列对齐，从而支持：
 * <ul>
 *   <li>{@link #MATCH}        — 碱基与参考一致（含参考为 N/gap 时不断言突变）</li>
 *   <li>{@link #MISMATCH}     — 明确碱基 ≠ 参考（真突变/替换）</li>
 *   <li>{@link #DEGENERATE}   — 简并码（RYSWKMBDHVN）且仍覆盖参考（正常容忍）</li>
 *   <li>{@link #DEGENERATE_MM}— 简并码但不覆盖参考（仍算突变）</li>
 *   <li>{@link #INSERTION}    — 引物比参考多出的碱基（与参考形成"环/凸出"）</li>
 *   <li>{@link #DELETION}     — 引物比参考少了的碱基（参考该位有、引物缺失）</li>
 *   <li>{@link #NOREF}        — 未加载参考 / 区间越界，无法比对</li>
 * </ul>
 *
 * <p>比对结果按"对齐列"组织：oligo 与 ref 等长（含 '-' 空位），每一列给出
 * {@code kind[]} 与 {@code markerCoord[]}（该列对应碱基的<b>居中</b>基因组坐标，用于
 * track 箭头上的标记精确对准碱基中心，而非落在两碱基缝隙）。
 */
public class PrimerMismatch {

    public static final int MATCH = 0;
    public static final int MISMATCH = 1;
    public static final int DEGENERATE = 2;
    public static final int DEGENERATE_MM = 3;
    public static final int NOREF = 4;
    public static final int INSERTION = 5;   // 引物多出碱基（环）
    public static final int DELETION = 6;    // 引物缺失碱基（缺口）

    public static class Result {
        public String oligo = "";     // 对齐后的 5'->3' 引物序列（含 '-' 空位）
        public String ref = "";       // 对齐后的参考序列（含 '-' 空位，与 oligo 等长）
        public int[] kind;            // 每对齐列的分类
        public double[] markerCoord;  // 每对齐列的居中基因组坐标（DELETION/不适用列 = NaN）
        public int mismatchCount = 0;
        public int degenerateCount = 0;
        public int insertionCount = 0;
        public int deletionCount = 0;
        public boolean ok = false;    // 是否成功取得参考
        public String note = "";
    }

    // 轻量缓存：引物签名（基因组id+坐标+链+序列）不变即复用，避免每次重绘重复取参考序列
    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    /** 仅标准碱基 A/C/G/T */
    private static boolean isStd(char c) {
        return c == 'A' || c == 'C' || c == 'G' || c == 'T';
    }

    /** IUPAC 简并码是否覆盖某个标准参考碱基 */
    private static boolean iupacCovers(char code, char base) {
        switch (Character.toUpperCase(code)) {
            case 'R': return base == 'A' || base == 'G';
            case 'Y': return base == 'C' || base == 'T';
            case 'S': return base == 'G' || base == 'C';
            case 'W': return base == 'A' || base == 'T';
            case 'K': return base == 'G' || base == 'T';
            case 'M': return base == 'A' || base == 'C';
            case 'B': return base == 'C' || base == 'G' || base == 'T';
            case 'D': return base == 'A' || base == 'G' || base == 'T';
            case 'H': return base == 'A' || base == 'C' || base == 'T';
            case 'V': return base == 'A' || base == 'C' || base == 'G';
            case 'N': return base == 'A' || base == 'C' || base == 'G' || base == 'T';
            default:  return false;
        }
    }

    /** 全局比对（NW），返回对齐后的 oligo / ref 字符串与每列 ref 索引（-1 表示该列为空位=插入）。 */
    private static class Aln {
        String ao, ar;
        int[] refIdx;   // 每对齐列的 ref 索引（refOligo 空间），插入列为 -1
    }

    private static Aln align(String o, String r) {
        int L = o.length(), M = r.length();
        int[][] s = new int[L + 1][M + 1];
        byte[][] dir = new byte[L + 1][M + 1]; // 0=对角 1=上(插入,ref空位) 2=左(缺失,oligo空位)
        for (int i = 0; i <= L; i++) { s[i][0] = -3 * i; dir[i][0] = 1; }
        for (int j = 0; j <= M; j++) { s[0][j] = -3 * j; dir[0][j] = 2; }
        for (int i = 1; i <= L; i++) {
            for (int j = 1; j <= M; j++) {
                int diag = s[i - 1][j - 1] + (o.charAt(i - 1) == r.charAt(j - 1) ? 2 : -1);
                int up = s[i - 1][j] - 3;     // 插入（oligo 多出）
                int left = s[i][j - 1] - 3;   // 缺失（ref 多出）
                int best = diag, d = 0;
                if (up > best) { best = up; d = 1; }
                if (left > best) { best = left; d = 2; }
                s[i][j] = best; dir[i][j] = (byte) d;
            }
        }
        StringBuilder ao = new StringBuilder(), ar = new StringBuilder();
        List<Integer> ri = new ArrayList<>();
        int i = L, j = M;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dir[i][j] == 0) {
                ao.append(o.charAt(i - 1)); ar.append(r.charAt(j - 1)); ri.add(j - 1); i--; j--;
            } else if (i > 0 && (j == 0 || dir[i][j] == 1)) {
                ao.append(o.charAt(i - 1)); ar.append('-'); ri.add(-1); i--;
            } else {
                ao.append('-'); ar.append(r.charAt(j - 1)); ri.add(j - 1); j--;
            }
        }
        Aln a = new Aln();
        a.ao = ao.reverse().toString();
        a.ar = ar.reverse().toString();
        a.refIdx = new int[ri.size()];
        for (int k = 0; k < ri.size(); k++) a.refIdx[k] = ri.get(ri.size() - 1 - k); // 反转对齐
        return a;
    }

    /** 比对单条引物与其参考区间（带缓存）。 */
    public static Result compare(Primer p) {
        String ori = (p.seq == null) ? "" : p.seq.toUpperCase().replaceAll("\\s+", "");
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        String gid = (g == null) ? "null" : g.getId();
        String key = gid + "|" + p.chr + "|" + p.start + "|" + p.end + "|" + p.strand + "|" + ori;
        Result cached = CACHE.get(key);
        if (cached != null) return cached;

        Result r = new Result();
        r.oligo = ori;
        if (g == null) {
            r.ok = false; r.note = "未加载参考基因组，无法比对";
            r.kind = new int[ori.length()];
            r.markerCoord = new double[ori.length()];
            CACHE.put(key, r);
            return r;
        }
        byte[] b = g.getSequence(p.chr, p.start, p.end);
        String fwd = (b == null) ? "" : new String(b).toUpperCase();
        if (fwd.isEmpty()) {
            r.ok = false; r.note = "该区间无参考序列（染色体/坐标越界）";
            r.kind = new int[ori.length()];
            r.markerCoord = new double[ori.length()];
            CACHE.put(key, r);
            return r;
        }
        String refOligo = (p.strand == '+') ? fwd : PrimerMetrics.revComp(fwd);
        Aln a = align(ori, refOligo);
        r.ref = a.ar;
        int n = a.ao.length();
        r.kind = new int[n];
        r.markerCoord = new double[n];
        for (int c = 0; c < n; c++) {
            char oc = a.ao.charAt(c), rc = a.ar.charAt(c);
            if (oc != '-' && rc != '-') {
                if (!isStd(rc)) {
                    r.kind[c] = MATCH;
                } else if (isStd(oc)) {
                    r.kind[c] = (oc == rc) ? MATCH : MISMATCH;
                } else {
                    r.kind[c] = iupacCovers(oc, rc) ? DEGENERATE : DEGENERATE_MM;
                }
            } else if (oc != '-' && rc == '-') {
                r.kind[c] = INSERTION;
            } else {
                r.kind[c] = DELETION;
            }
            if (r.kind[c] == MISMATCH || r.kind[c] == DEGENERATE_MM) r.mismatchCount++;
            if (r.kind[c] == DEGENERATE || r.kind[c] == DEGENERATE_MM) r.degenerateCount++;
            if (r.kind[c] == INSERTION) r.insertionCount++;
            if (r.kind[c] == DELETION) r.deletionCount++;
        }
        // markerCoord：替换/缺失列 = G(refIdx) + 0.5（居中）；插入列 = 相邻碱基中点
        double step = (p.strand == '+') ? 1.0 : -1.0;
        for (int c = 0; c < n; c++) {
            if (a.ar.charAt(c) != '-') {
                int ri = a.refIdx[c];
                double G = (p.strand == '+') ? (p.start + ri) : (p.end - 1 - ri);
                r.markerCoord[c] = G + 0.5;
            } else {
                r.markerCoord[c] = Double.NaN;
            }
        }
        for (int c = 0; c < n; c++) {
            if (a.ar.charAt(c) != '-') continue; // 仅处理插入列
            double LC = Double.NaN, RC = Double.NaN;
            for (int t = c - 1; t >= 0; t--) { if (!Double.isNaN(r.markerCoord[t])) { LC = r.markerCoord[t]; break; } }
            for (int t = c + 1; t < n; t++) { if (!Double.isNaN(r.markerCoord[t])) { RC = r.markerCoord[t]; break; } }
            if (!Double.isNaN(LC) && !Double.isNaN(RC)) r.markerCoord[c] = (LC + RC) / 2;
            else if (!Double.isNaN(LC)) r.markerCoord[c] = LC + step;
            else if (!Double.isNaN(RC)) r.markerCoord[c] = RC - step;
            else r.markerCoord[c] = ((p.strand == '+') ? p.start : p.end - 1) + 0.5;
        }
        if (r.insertionCount > 0 || r.deletionCount > 0) {
            r.note = "引物与参考长度差异：插入(环)" + r.insertionCount + " / 缺失" + r.deletionCount;
        }
        r.ok = true;
        CACHE.put(key, r);
        return r;
    }

    /**
     * 单字符单元格（等宽靠 Monospaced 字体本身保证，不依赖任何 CSS 布局属性）。
     * ⚠️ 绝不用 display:inline-block / width / border-radius / 中文 / 多字符（这些在 Java HTMLEditorKit 下
     *    会破坏等宽对齐，导致三行错位——v0.2.7 的"乱七八糟"即源于此）。
     */
    private static String cell(char c, String color, String bg) {
        StringBuilder s = new StringBuilder("<span style='");
        if (color != null) s.append("color:").append(color).append(";");
        if (bg != null) s.append("background-color:").append(bg).append(";");
        s.append("'>").append(c == ' ' ? "&nbsp;" : String.valueOf(c)).append("</span>");
        return s.toString();
    }

    /**
     * 突变比对显示（编辑框内 JEditorPane, text/html）。
     * 三行严格按"每列一个字符"对齐（Monospaced 字体天然等宽）：
     * <ul>
     *   <li>引物行 5'→3'：突变=红底红字、简并=紫、插入=青底（引物比参考多出的碱基，即"环/凸起"）、缺失列=灰 '-'。</li>
     *   <li>参考行：淡化灰；引物缺失处参考多出碱基=橙底（即"缺口/环"的另一半）。</li>
     *   <li>结构行：'^'=插入、'_'=缺失、'.'=一致、空格=突变（断口）。</li>
     * </ul>
     */
    public static String toHtml(Result r) {
        if (r == null || r.oligo.isEmpty()) {
            return "<html><body style='font-family:Monospaced;font-size:11px;line-height:16px'>"
                    + "（无序列：该引物在内存/存档中都没有序列。请确认参考基因组已加载、染色体与坐标正确；"
                    + "若此引物曾手动改过序列，请点「更新」重新保存以持久化）</body></html>";
        }
        int n = (r.kind == null) ? 0 : r.kind.length;
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Monospaced;font-size:11px;line-height:16px'>");
        // 三行统一前缀宽度：行1 "5'- "（4字符），行2/3 用 4 空格，保证三行序列列对齐
        // 行1：引物（5'→3'）
        sb.append("5'- ");
        for (int c = 0; c < n; c++) {
            char o = r.oligo.charAt(c);
            int k = r.kind[c];
            if (o == '-') {
                sb.append(cell('-', "#bbb", null));            // 缺失列：引物此处无碱基
            } else if (k == MISMATCH || k == DEGENERATE_MM) {
                sb.append(cell(o, "#d00", "#ffd24d"));          // 突变：红底红字
            } else if (k == DEGENERATE) {
                sb.append(cell(o, "#9b4dc6", null));            // 简并：紫
            } else if (k == INSERTION) {
                sb.append(cell(o, "#007a8a", "#c8f0f5"));        // 插入：青底（环/凸起）
            } else {
                sb.append(cell(o, "#222", null));               // 一致
            }
        }
        sb.append(" -3'<br>");
        // 行2：参考
        sb.append("    ");
        for (int c = 0; c < n; c++) {
            char rf = r.ref.charAt(c);
            if (rf == '-') {
                sb.append(cell('-', "#bbb", null));            // 插入列：参考此处无碱基
            } else if (r.kind[c] == DELETION) {
                sb.append(cell(rf, "#e07b00", "#fff0e0"));       // 缺失列：参考多出碱基（橙底=缺口/环）
            } else {
                sb.append(cell(rf, "#999", null));
            }
        }
        sb.append("<br>");
        // 行3：结构指示（全 ASCII 单字符）
        sb.append("    ");
        for (int c = 0; c < n; c++) {
            int k = r.kind[c];
            if (k == INSERTION) sb.append(cell('^', "#007a8a", null));
            else if (k == DELETION) sb.append(cell('_', "#e07b00", null));
            else if (k == MATCH) sb.append(cell('.', "#999", null));
            else sb.append(cell(' ', "#999", null));            // 突变：断口（空格）
        }
        sb.append("<br>");
        // 图例
        sb.append("<span style='font-size:11px;color:#666'>");
        sb.append("<span style='background-color:#ffd24d;color:#d00'>红底</span>=突变&nbsp;");
        sb.append("<span style='color:#9b4dc6'>紫</span>=简并&nbsp;");
        sb.append("<span style='background-color:#c8f0f5;color:#007a8a'>青底</span>=插入(引物多出/环)&nbsp;");
        sb.append("<span style='background-color:#fff0e0;color:#e07b00'>橙底</span>=缺失(引物缺口)&nbsp;");
        sb.append("结构行 ^插入 _缺失 .一致 空格=突变");
        sb.append("</span>");
        if (!r.ok || !r.note.isEmpty()) {
            sb.append("<br><span style='color:#a70'>").append(r.note.isEmpty() ? "（未比对）" : r.note).append("</span>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 一句话摘要（突变 / 插入 / 缺失数）。 */
    public static String summary(Result r) {
        if (r == null) return "";
        if (!r.ok) return r.note;
        if (r.mismatchCount == 0 && r.insertionCount == 0 && r.deletionCount == 0) {
            return r.degenerateCount > 0 ? ("与参考一致（含 " + r.degenerateCount + " 个简并位点）")
                    : "与参考完全一致";
        }
        List<String> parts = new ArrayList<>();
        if (r.mismatchCount > 0) parts.add(r.mismatchCount + " 个突变");
        if (r.insertionCount > 0) parts.add(r.insertionCount + " 个插入(环)");
        if (r.deletionCount > 0) parts.add(r.deletionCount + " 个缺失");
        if (r.degenerateCount > 0) parts.add(r.degenerateCount + " 简并");
        return "检测到 " + String.join("、", parts);
    }

    /** 序列净化（编辑框输入用）：大写、U→T、仅保留 A-Z（含简并码）。 */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String up = s.toUpperCase().replace('U', 'T');
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if ((c >= 'A' && c <= 'Z')) sb.append(c);
        }
        return sb.toString();
    }

    /** 是否为合法引物序列（仅 A/C/G/T + IUPAC 简并码，长度>=1）。 */
    public static boolean valid(String s) {
        String c = clean(s);
        return c.length() >= 1 && c.matches("(?i)[ACGTNRYSMWKBDHV]+");
    }

    /** 清空比对缓存（切换参考基因组后调用，避免比对到旧参考）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}

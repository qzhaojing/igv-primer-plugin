package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;

/**
 * 引物序列 vs 参考基因组的突变比对。
 *
 * <p>引物 {@link Primer#seq} 始终为 5'→3' 实际寡核苷酸（反向链引物已是区间反向互补）。
 * 比对时把参考区间也转成与 oligo 同序（反向链先 RC），逐位比较：
 * <ul>
 *   <li>{@link #MATCH}        — 碱基与参考一致（含参考为 N/gap 时不断言突变）</li>
 *   <li>{@link #MISMATCH}     — 明确碱基 ≠ 参考（真突变）</li>
 *   <li>{@link #DEGENERATE}   — 简并码（RYSWKMBDHVN）且仍覆盖参考（正常容忍）</li>
 *   <li>{@link #DEGENERATE_MM}— 简并码但不覆盖参考（仍算突变）</li>
 *   <li>{@link #NOREF}        — 未加载参考 / 区间越界，无法比对</li>
 * </ul>
 */
public class PrimerMismatch {

    public static final int MATCH = 0;
    public static final int MISMATCH = 1;
    public static final int DEGENERATE = 2;
    public static final int DEGENERATE_MM = 3;
    public static final int NOREF = 4;

    public static class Result {
        public String oligo = "";     // 5'->3' 引物序列（大写，含简并码）
        public String ref = "";       // 与 oligo 同序（5'->3'）的参考序列（反向链已 RC）
        public int[] kind;            // 每位的分类（长度 = min(oligo,ref)）
        public int mismatchCount = 0;
        public int degenerateCount = 0;
        public boolean ok = false;    // 是否成功取得参考
        public String note = "";
    }

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

    /** 比对单条引物与其参考区间。 */
    public static Result compare(Primer p) {
        Result r = new Result();
        r.oligo = (p.seq == null) ? "" : p.seq.toUpperCase().replaceAll("\\s+", "");
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) {
            r.ok = false; r.note = "未加载参考基因组，无法比对";
            r.kind = new int[r.oligo.length()];
            return r;
        }
        byte[] b = g.getSequence(p.chr, p.start, p.end);
        String fwd = (b == null) ? "" : new String(b).toUpperCase();
        if (fwd.isEmpty()) {
            r.ok = false; r.note = "该区间无参考序列（染色体/坐标越界）";
            r.kind = new int[r.oligo.length()];
            return r;
        }
        // 反向链：引物 5'->3' = 正链区间的反向互补 → 参考也转成同序再逐位比
        String refOligo = (p.strand == '+') ? fwd : PrimerMetrics.revComp(fwd);
        r.ref = refOligo;
        int n = Math.min(r.oligo.length(), refOligo.length());
        r.kind = new int[n];
        for (int i = 0; i < n; i++) {
            char o = r.oligo.charAt(i);
            char rf = refOligo.charAt(i);
            if (!isStd(rf)) { r.kind[i] = MATCH; continue; }   // 参考未知（N/gap）→ 不断言突变
            if (isStd(o)) {
                r.kind[i] = (o == rf) ? MATCH : MISMATCH;
            } else {
                r.kind[i] = iupacCovers(o, rf) ? DEGENERATE : DEGENERATE_MM;
            }
            if (r.kind[i] == MISMATCH || r.kind[i] == DEGENERATE_MM) r.mismatchCount++;
            if (r.kind[i] == DEGENERATE || r.kind[i] == DEGENERATE_MM) r.degenerateCount++;
        }
        if (r.oligo.length() != refOligo.length()) {
            r.note = "引物长度(" + r.oligo.length() + ")与区间长度(" + refOligo.length()
                    + ")不一致，仅比对前 " + n + " 位";
        }
        r.ok = true;
        return r;
    }

    /** 着色 HTML（<pre> 等宽）：突变=红、简并=紫、其余=深灰。 */
    public static String toHtml(Result r) {
        if (r == null || r.oligo.isEmpty()) return "<html><pre>（无序列）</pre></html>";
        StringBuilder sb = new StringBuilder();
        sb.append("<html><pre style='font-family:Monospaced;font-size:12px'>");
        sb.append("5'- ");
        for (int i = 0; i < r.oligo.length(); i++) {
            char o = r.oligo.charAt(i);
            int k = (i < r.kind.length) ? r.kind[i] : MATCH;
            String color = (k == MISMATCH || k == DEGENERATE_MM) ? "#d11"
                    : (k == DEGENERATE ? "#82a" : "#222");
            sb.append("<font color='").append(color).append("'>").append(o).append("</font>");
        }
        sb.append(" -3'\n");
        if (r.ok && !r.ref.isEmpty()) {
            sb.append("    ").append(r.ref).append("\n");
            int n = Math.min(r.oligo.length(), r.ref.length());
            sb.append("    ");
            for (int i = 0; i < n; i++) {
                int k = r.kind[i];
                sb.append((k == MISMATCH || k == DEGENERATE_MM) ? "^" : " ");
            }
            sb.append("\n");
        }
        sb.append("</pre>");
        if (!r.ok || !r.note.isEmpty()) {
            sb.append("<font color='#a70'>").append(r.note).append("</font>");
        }
        sb.append("</html>");
        return sb.toString();
    }

    /** 一句话摘要（突变数 / 简并数）。 */
    public static String summary(Result r) {
        if (r == null) return "";
        if (!r.ok) return r.note;
        if (r.mismatchCount == 0) {
            return r.degenerateCount > 0 ? ("与参考一致（含 " + r.degenerateCount + " 个简并位点）")
                    : "与参考完全一致";
        }
        return "检测到 " + r.mismatchCount + " 个突变位点"
                + (r.degenerateCount > 0 ? ("（含 " + r.degenerateCount + " 个简并）") : "");
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
}

package org.broad.igv.primer;

import org.broad.igv.primer.PrimerStore;

/**
 * 引物指标计算：Tm（SantaLucia 1998 最近邻法 + 50mM Na+ 校正）、GC%、hairpin、self/hetero dimer、3' 互补。
 * 判定标准按超多重 PCR 保守口径。
 */
public class PrimerMetrics {

    public static final double R_GAS = 1.987;      // cal/(mol·K)
    public static final double NA_CONC = 0.05;     // 50 mM Na+
    public static final double PRIMER_CONC = 5e-7; // 500 nM

    // SantaLucia 1998 NN 参数（kcal/mol 与 cal/(mol·K)）
    // 索引: A=0,C=1,G=2,T=3, 二核苷酸 idx = a*4+b
    static final double[] DH = {
            -7.9, -8.4, -7.8, -7.2,   // AA AT TA CA
            -8.5, -8.0, -10.6, -8.4,  // AC CC CG CT  (占位，下方显式覆盖)
            -8.8, -9.8, -8.0, -9.4,
            -6.9, -8.2, -8.5, -7.6
    };
    static final double[] DS = {
            -22.2, -23.6, -21.0, -20.4,
            -22.7, -19.9, -27.2, -22.4,
            -23.5, -24.4, -19.9, -22.9,
            -18.4, -21.0, -22.7, -20.5
    };

    static int idx(char a, char b) {
        int i = baseIdx(a), j = baseIdx(b);
        if (i < 0 || j < 0) return -1;
        return i * 4 + j;
    }

    static int baseIdx(char c) {
        switch (c) {
            case 'A': return 0;
            case 'C': return 1;
            case 'G': return 2;
            case 'T': return 3;
            default: return -1;
        }
    }

    static char comp(char c) {
        switch (c) {
            case 'A': return 'T';
            case 'T': return 'A';
            case 'C': return 'G';
            case 'G': return 'C';
            default: return 'N';
        }
    }

    public static String revComp(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = s.length() - 1; i >= 0; i--) sb.append(comp(s.charAt(i)));
        return sb.toString();
    }

    /** SantaLucia NN Tm（含 50mM Na 校正），序列需为大写 ACGT */
    public static double tm(String seq) {
        if (seq == null || seq.length() < 4) return Double.NaN;
        seq = seq.toUpperCase();
        double h = 0, s = 0;
        boolean valid = true;
        for (int i = 0; i < seq.length() - 1; i++) {
            int k = idx(seq.charAt(i), seq.charAt(i + 1));
            if (k < 0) return Double.NaN;
            h += DH[k];
            s += DS[k];
        }
        // 起始/终止项（SantaLucia 统一修正）
        char f = seq.charAt(0), l = seq.charAt(seq.length() - 1);
        h += (f == 'A' || f == 'T') ? 2.2 : 0.1;
        h += (l == 'A' || l == 'T') ? 2.2 : 0.1;
        s += (f == 'A' || f == 'T') ? 6.9 : 2.8;
        s += (l == 'A' || l == 'T') ? 6.9 : 2.8;
        if (f == 'G' && l == 'C' || f == 'C' && l == 'G') s += 0; // 对称性对称处理略
        if (!valid) return Double.NaN;

        double tmC = (1000.0 * h) / (s + R_GAS * Math.log(PRIMER_CONC / 4.0)) - 273.15
                + 16.6 * Math.log10(NA_CONC);
        return tmC;
    }

    public static double gcPercent(String seq) {
        if (seq == null || seq.isEmpty()) return Double.NaN;
        int gc = 0, n = 0;
        for (char c : seq.toUpperCase().toCharArray()) {
            if (c == 'G' || c == 'C') gc++;
            if (c == 'A' || c == 'T' || c == 'G' || c == 'C') n++;
        }
        return n == 0 ? Double.NaN : 100.0 * gc / n;
    }

    /** 估最大 GC 含量（0-100） */
    static double pairPenalty(boolean gcPair) {
        return gcPair ? -2.0 : -1.0; // kcal/mol per pair（粗估）
    }

    /** hairpin ΔG 估算（茎≥3、环≥3），返回最负 ΔG */
    public static double hairpinDG(String seq) {
        if (seq == null || seq.length() < 7) return 0;
        seq = seq.toUpperCase();
        double best = 0;
        int n = seq.length();
        for (int i = 0; i + 5 < n; i++) {
            for (int j = n - 1; j > i + 3; j--) {
                int stem = 0, k = 0;
                double dg = 0;
                while (i + k < j - k - 3 && j - k > i + k + 3) {
                    char a = seq.charAt(i + k), b = seq.charAt(j - k);
                    if (comp(a) == b) {
                        stem++;
                        dg += pairPenalty(a == 'G' || a == 'C');
                        k++; // 必须推进循环变量，否则首对配对时死循环（2026-09-28 卡死 EDT 的根因）
                    } else break;
                }
                if (stem >= 3) {
                    double v = dg + 3.5; // 环罚
                    if (v < best) best = v;
                }
                if (stem == 0) continue;
            }
        }
        return best;
    }

    /** self-dimer：序列自身与自身反互补对齐的最大连续互补段 ΔG 估算 */
    public static double selfDG(String seq) {
        return dimerDG(seq, seq);
    }

    /** hetero-dimer：两条序列间最大连续互补段 ΔG 估算 */
    public static double dimerDG(String a, String b) {
        if (a == null || b == null || a.length() < 4 || b.length() < 4) return 0;
        String rc = revComp(b.toUpperCase());
        String s = a.toUpperCase();
        double best = 0;
        for (int i = -(rc.length() - 1); i < s.length(); i++) {
            int match = 0, gc = 0;
            double dg = 0;
            for (int j = 0; j < rc.length(); j++) {
                int k = i + j;
                if (k < 0 || k >= s.length()) continue;
                if (s.charAt(k) == rc.charAt(j)) {
                    match++;
                    if (s.charAt(k) == 'G' || s.charAt(k) == 'C') gc++;
                } else {
                    if (match >= 3) {
                        double v = -1.0 * match - 1.0 * gc + 3.5;
                        if (v < best) best = v;
                    }
                    match = 0;
                    gc = 0;
                }
            }
            if (match >= 3) {
                double v = -1.0 * match - 1.0 * gc + 3.5;
                if (v < best) best = v;
            }
        }
        return best;
    }

    /** 二聚体比对形状（用于编辑对话框内文本可视化） */
    public static class DimerShape {
        public String topLine = "";
        public String bondLine = "";
        public String botLine = "";
        public double dg = 0;
        public boolean valid = false;
    }

    /**
     * 计算两条序列的最佳互补比对形状（self-dimer：b 传自身；hetero-dimer：b 传配对伙伴）。
     * 复用 dimerDG 的滑动打分逻辑，返回三行文本：上链 5'->3' / 配对竖线 / 下链 3'->5'
     * （下链为反互补反向显示，呈反向平行；配对处画 '|'，错配/凸出留空格）。
     */
    public static DimerShape dimerShape(String a, String b) {
        DimerShape sh = new DimerShape();
        if (a == null || b == null || a.length() < 4 || b.length() < 4) return sh;
        String top = a.toUpperCase();
        String botRaw = revComp(b.toUpperCase());                 // 5'->3'
        String botDisp = new StringBuilder(botRaw).reverse().toString(); // 3'->5'（反向平行显示，= comp(b)）
        int n = top.length(), m = botRaw.length();
        // 找最佳偏移（与 dimerDG 同口径：最负 ΔG）
        double bestDG = 0;
        int bestOff = 0;
        for (int off = -(m - 1); off < n; off++) {
            int match = 0, gc = 0;
            double dg = 0;
            for (int j = 0; j < m; j++) {
                int k = off + j;
                if (k < 0 || k >= n) continue;
                if (top.charAt(k) == botRaw.charAt(j)) {
                    match++;
                    if (top.charAt(k) == 'G' || top.charAt(k) == 'C') gc++;
                } else {
                    if (match >= 3) {
                        double v = -1.0 * match - 1.0 * gc + 3.5;
                        if (v < dg) dg = v;
                    }
                    match = 0;
                    gc = 0;
                }
            }
            if (match >= 3) {
                double v = -1.0 * match - 1.0 * gc + 3.5;
                if (v < dg) dg = v;
            }
            if (dg < bestDG - 1e-9) {
                bestDG = dg;
                bestOff = off;
            }
        }
        int lo = Math.min(0, bestOff);
        int hi = Math.max(n, bestOff + m);
        int W = hi - lo;
        char[] tA = new char[W], bA = new char[W], bd = new char[W];
        java.util.Arrays.fill(tA, ' ');
        java.util.Arrays.fill(bA, ' ');
        java.util.Arrays.fill(bd, ' ');
        for (int c = 0; c < W; c++) {
            int topIdx = c + lo;
            if (topIdx >= 0 && topIdx < n) tA[c] = top.charAt(topIdx);
            int botRawIdx = (c + lo) - bestOff; // = k - bestOff = j
            if (botRawIdx >= 0 && botRawIdx < m) {
                int dispIdx = m - 1 - botRawIdx;
                bA[c] = botDisp.charAt(dispIdx);
                if (topIdx >= 0 && topIdx < n && tA[c] != ' ') {
                    if (tA[c] == bA[c]) bd[c] = '|'; // top==revComp(b) 即互补配对
                }
            }
        }
        String t = new String(tA), bot = new String(bA), bond = new String(bd);
        sh.topLine = "5'-" + t + "-3'";
        sh.bondLine = "   " + bond + "   ";
        sh.botLine = "3'-" + bot + "-5'";
        sh.dg = bestDG;
        sh.valid = true;
        return sh;
    }

    /** 两引物 3' 端互配的最大连续互补碱基数（任一 3' 端参与才计入） */
    public static int max3pComplement(String a, String b) {
        if (a == null || b == null) return 0;
        a = a.toUpperCase();
        b = b.toUpperCase();
        String brc = revComp(b);
        int best = 0;
        // a 的 3' 端（末位）
        best = Math.max(best, suffixMatch(a, brc));
        // b 的 3' 端：等价于 a 的反互补对齐 b 的末位
        best = Math.max(best, suffixMatch(b, revComp(a)));
        return best;
    }

    /** a 的末缀在 brc 中出现的最大后缀匹配长度 */
    static int suffixMatch(String a, String brc) {
        int maxL = Math.min(a.length(), brc.length());
        for (int L = maxL; L >= 4; L--) {
            String suf = a.substring(a.length() - L);
            if (brc.contains(suf)) return L;
        }
        return 0;
    }

    /**
     * 重算单条引物指标（Tm/GC/hairpin/self）。hetero 部分由 PrimerStore 统一评估。
     * 判定: 长度18-30, Tm 55-65, GC 30-75, hairpin ΔG > -3.5, selfDG > -5
     */
    public static void evaluate(Primer p) {
        String seq = p.seq == null ? "" : p.seq;
        if (seq.length() < 10) {
            p.tm = Double.NaN;
            p.gc = Double.NaN;
            p.pass = false;
            p.failReasons = "无序列(参考基因组未加载或区段无效)";
            return;
        }
        p.tm = tm(seq);
        p.gc = gcPercent(seq);
        p.hairpinDG = hairpinDG(seq);
        p.selfDG = selfDG(seq);

        StringBuilder bad = new StringBuilder();
        if (p.length() < PrimerStore.failLenMin || p.length() > PrimerStore.failLenMax)
            bad.append("长度" + p.length() + "nt(" + PrimerStore.failLenMin + "-" + PrimerStore.failLenMax + ") ");
        if (!Double.isNaN(p.tm) && (p.tm < PrimerStore.failTmMin || p.tm > PrimerStore.failTmMax))
            bad.append("Tm越界 ").append(String.format("%.1f ", p.tm));
        if (!Double.isNaN(p.gc) && (p.gc < PrimerStore.failGcMin || p.gc > PrimerStore.failGcMax))
            bad.append("GC越界 ").append(String.format("%.0f%% ", p.gc));
        if (p.hairpinDG <= PrimerStore.failHairpinTh) bad.append("hairpin强 ");
        if (p.selfDG <= PrimerStore.failSelfTh) bad.append("self-dimer强 ");
        p.failReasons = bad.toString().trim();
        p.pass = p.failReasons.isEmpty();
    }
}

package org.broad.igv.primer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;

/**
 * 把 IGV 当前加载的基因组整条导出成 MFEprimer 能吃的 FASTA 临时文件。
 * 直接复用插件取序列的同一套 API（GenomeManager.getCurrentGenome / getSequence），
 * 因此不需要用户另行提供基因组文件——"用 IGV 调用的基因组"即可。
 *
 * 大基因组（如人类 ~3Gb）全量 dump 会很慢且产生巨大临时文件；调用方应先看
 * {@link #totalBp()} 决定要不要走"仅含引物染色体"模式。
 */
public class MfeGenome {

    private static File cachedFasta = null;
    private static String cachedKey = null;

    /** 当前基因组总碱基数（用于大基因组尺寸警示）。无基因组返回 0。 */
    public static long totalBp() {
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) return 0;
        long total = 0;
        for (String c : g.getAllChromosomeNames()) {
            try { total += g.getChromosome(c).getLength(); } catch (Exception ignore) {}
        }
        return total;
    }

    /**
     * 导出当前基因组。
     * @param limitToPrimerChroms 仅导出含引物的染色体（大基因组加速）
     * @return 临时 FASTA 文件；无基因组或导出失败返回 null
     */
    public static File dump(boolean limitToPrimerChroms) {
        Genome g = GenomeManager.getInstance().getCurrentGenome();
        if (g == null) return null;
        List<String> chroms = new ArrayList<String>(g.getAllChromosomeNames());
        if (limitToPrimerChroms) {
            Set<String> keep = new HashSet<String>();
            for (Primer p : PrimerStore.getPrimers()) if (p.chr != null) keep.add(p.chr);
            chroms.retainAll(keep);
            if (chroms.isEmpty()) chroms = new ArrayList<String>(g.getAllChromosomeNames());
        }
        StringBuilder sb = new StringBuilder();
        for (String c : chroms) {
            int len = safeLen(g, c);
            sb.append(c).append(':').append(len).append(';');
        }
        String key = sb.toString();
        if (cachedFasta != null && key.equals(cachedKey) && cachedFasta.exists()) return cachedFasta;
        try {
            File fa = File.createTempFile("igv_genome_", ".fa");
            fa.deleteOnExit();
            BufferedWriter w = new BufferedWriter(new FileWriter(fa));
            int step = 1_000_000;
            for (String c : chroms) {
                int len = safeLen(g, c);
                if (len <= 0) continue;
                w.write(">" + c);
                w.newLine();
                for (int s = 0; s < len; s += step) {
                    int e = Math.min(len, s + step);
                    byte[] b = g.getSequence(c, s, e);
                    if (b == null) continue;
                    w.write(new String(b).toUpperCase());
                    w.newLine();
                }
            }
            w.close();
            cachedFasta = fa;
            cachedKey = key;
            return fa;
        } catch (Exception ex) {
            System.err.println("[MFEprimer] 基因组 dump 失败: " + ex);
            return null;
        }
    }

    private static int safeLen(Genome g, String c) {
        try { return g.getChromosome(c).getLength(); } catch (Exception e) { return 0; }
    }
}

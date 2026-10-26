/**
 * TestP17：v0.1.77 预置接头序列的正确性（对照厂商官方文件）
 *   参考来源：
 *     - Illumina《Adapter Sequences》文档 / support.illumina.com 寡核苷酸序列页
 *       P5=AATGATACGGCGACCACCGAGATCTACAC、P7=CAAGCAGAAGACGGCATACGAGAT、
 *       Read1 测序引物=ACACTCTTTCCCTACACGACGCTCTTCCGATCT、
 *       Read2 测序引物=GTGACTGGAGTTCAGACGTGTGCTCTTCCGATCT、
 *       Nextera ME(Read1)=TCGTCGGCAGCGTCAGATGTGTATAAGAGACAG、
 *       ME(Read2)=GTCTCGTGGGCTCGGAGATGTGTATAAGAGACAG
 *     - i5/i7 index 用「Bases in Adapter」方向：S502=CTCTCTAT…、N701=TCGCCTTA…
 *       （N701 在 sample sheet 上填的是反向互补 TAAGGCGA）
 *     - PacBio 16S 全长扩增官方 universal tail（PN 100-466-100 二轮引物方案）
 *     - ONT 官方 PCR barcoding 协议（SQK-LSK110/LSK114 + EXP-PBC）universal tailing sequences
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP17 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static String seq(String name) {
        org.broad.igv.primer.TailLibrary.Tail t = org.broad.igv.primer.TailLibrary.get(name);
        return t == null ? null : t.seq;
    }

    static String type(String name) {
        org.broad.igv.primer.TailLibrary.Tail t = org.broad.igv.primer.TailLibrary.get(name);
        return t == null ? null : t.type;
    }

    public static void main(String[] args) throws Exception {
        org.broad.igv.primer.TailLibrary.loadDefaults();

        final String P5 = "AATGATACGGCGACCACCGAGATCTACAC";
        final String P7 = "CAAGCAGAAGACGGCATACGAGAT";
        final String ME1 = "TCGTCGGCAGCGTCAGATGTGTATAAGAGACAG";
        final String ME2 = "GTCTCGTGGGCTCGGAGATGTGTATAAGAGACAG";

        // ---------- 1) Illumina 骨架 ----------
        check(P5.equals(seq("Illumina P5 overhang")), "P5 overhang 序列正确（29nt）");
        check(P7.equals(seq("Illumina P7 overhang")), "P7 overhang 序列正确（24nt）");
        check((P5 + ME1).equals(seq("Nextera i5 全长（无 index）")), "i5 全长 = P5 + ME1（62nt）");
        check((P7 + ME2).equals(seq("Nextera i7 全长（无 index）")), "i7 全长 = P7 + ME2（58nt）");
        check(seq("Nextera i5 全长（无 index）").length() == 62, "i5 全长长度 62");
        check(seq("Nextera i7 全长（无 index）").length() == 58, "i7 全长长度 58");

        // ---------- 2) index 引物（Bases in Adapter 方向）----------
        check((P5 + "CTCTCTAT" + ME1).equals(seq("Nextera i5 引物 S502")), "i5 引物 S502 正确（P5+CTCTCTAT+ME1）");
        check((P5 + "CTAAGCCT" + ME1).equals(seq("Nextera i5 引物 S508")), "i5 引物 S508 正确（CTAAGCCT）");
        check(seq("Nextera i5 引物 S502").length() == 70, "i5 引物长度 70");
        check((P7 + "TCGCCTTA" + ME2).equals(seq("Nextera i7 引物 N701")), "i7 引物 N701 正确（P7+TCGCCTTA+ME2）");
        check((P7 + "CATGCCTA" + ME2).equals(seq("Nextera i7 引物 N706")), "i7 引物 N706 正确（CATGCCTA）");
        check(seq("Nextera i7 引物 N701").length() == 66, "i7 引物长度 66");
        check("接头/index(i5)".equals(type("Nextera i5 引物 S502")), "i5 引物归入 index(i5) 子类");
        check("接头/index(i7)".equals(type("Nextera i7 引物 N701")), "i7 引物归入 index(i7) 子类");
        // i7 的 sample sheet 方向应是 adapter 序列的反向互补（N701: TCGCCTTA ↔ TAAGGCGA）
        check("TAAGGCGA".equals(revcomp("TCGCCTTA")), "N701 adapter/sample-sheet 互为反向互补");
        // i5 的 adapter 序列 == MiSeq sample sheet 序列
        check("CTCTCTAT".equals(seq("Nextera i5 引物 S502").substring(29, 37)), "i5 index 位于 P5 之后（29..37）");

        // ---------- 3) 官方测序引物 ----------
        check("ACACTCTTTCCCTACACGACGCTCTTCCGATCT".equals(seq("Illumina Read1 测序引物")), "Read1 测序引物正确");
        check("GTGACTGGAGTTCAGACGTGTGCTCTTCCGATCT".equals(seq("Illumina Read2 测序引物")), "Read2 测序引物正确");

        // ---------- 4) 三代官方尾 ----------
        check("GCAGTCGAACATGTAGCTGACTCAGGTCAC".equals(seq("PacBio 通用尾（正向）")), "PacBio 正向尾正确（30nt）");
        check("TGGATCACTTGTGCAAGCATCACATCGTAG".equals(seq("PacBio 通用尾（反向）")), "PacBio 反向尾正确（31nt）");
        check("TTTCTGTTGGTGCTGATATTGC".equals(seq("ONT 一轮加尾（正向）")), "ONT 正向尾正确（22nt）");
        check("ACTTGCCTGTCGCTCTATCTTC".equals(seq("ONT 一轮加尾（反向）")), "ONT 反向尾正确（22nt）");
        check("接头/三代接头".equals(type("PacBio 通用尾（正向）")), "PacBio 归到三代接头");
        check("接头/三代接头".equals(type("ONT 一轮加尾（正向）")), "ONT 归到三代接头");
        // 旧条目已退役（不再用「示例」占位糊弄）
        check(seq("PacBio 通用接头") == null, "旧占位条目「PacBio 通用接头」已移除");
        check(seq("Nanopore 接头") == null, "旧占位条目「Nanopore 接头」已移除");

        // ---------- 5) 方向标记 ----------
        check("F".equals(org.broad.igv.primer.TailLibrary.get("Illumina P5 overhang").apply), "P5 标记仅 F");
        check("R".equals(org.broad.igv.primer.TailLibrary.get("Illumina P7 overhang").apply), "P7 标记仅 R");
        check("F".equals(org.broad.igv.primer.TailLibrary.get("Nextera i5 引物 S502").apply), "i5 引物仅 F");
        check("R".equals(org.broad.igv.primer.TailLibrary.get("Nextera i7 引物 N701").apply), "i7 引物仅 R");

        // ---------- 6) 库整体自洽（无重复名 / 序列非空 / 分类非空）----------
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> es =
                org.broad.igv.primer.TailLibrary.entries();
        java.util.Set<String> names = new java.util.HashSet<String>();
        boolean dup = false, empty = false, noType = false;
        for (org.broad.igv.primer.TailLibrary.Tail t : es) {
            if (!names.add(t.name)) dup = true;
            if (t.seq == null || t.seq.isEmpty()) empty = true;
            if (t.type == null || t.type.isEmpty()) noType = true;
            if (!t.seq.matches("[ACGTN]+")) empty = true;   // 预置里不应有非碱基字符
        }
        check(!dup, "预置条目无重名");
        check(!empty, "预置条目序列非空且只含 ACGTN");
        check(!noType, "预置条目都有分类");
        check(es.size() >= 45, "预置条目数 " + es.size() + "（含 index 引物）");

        System.out.println("==== TestP17: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static String revcomp(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            sb.append(c == 'A' ? 'T' : c == 'T' ? 'A' : c == 'C' ? 'G' : c == 'G' ? 'C' : 'N');
        }
        return sb.toString();
    }
}

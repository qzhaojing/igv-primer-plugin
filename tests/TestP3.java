import org.broad.igv.primer.*;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

public class TestP3 {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) throws Exception {
        // 1. exe 释放（从 classpath 资源 → ~/.igv_primer3/）
        File exe = Primer3Core.ensureExe();
        check(exe.isFile() && exe.length() > 500000, "ensureExe 释放 (" + exe + ", " + exe.length() + " bytes)");
        check(exe.getAbsolutePath().contains(".igv_primer3"), "释放路径 ~/.igv_primer3/");

        // 2. 简单模式设计（SEQUENCE_TARGET 产物落 150-250）
        java.util.Random rnd = new java.util.Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("ACGT".charAt(rnd.nextInt(4)));
        String seq = sb.toString();
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("PRIMER_NUM_RETURN", "5");
        params.put("PRIMER_MIN_SIZE", "18");
        params.put("PRIMER_OPT_SIZE", "20");
        params.put("PRIMER_MAX_SIZE", "27");
        params.put("PRIMER_MIN_TM", "57");
        params.put("PRIMER_OPT_TM", "60");
        params.put("PRIMER_MAX_TM", "63");
        params.put("PRIMER_PRODUCT_SIZE_RANGE", "100-300");
        params.put("SEQUENCE_TARGET", "150,100");
        List<Primer3Core.Pair> pairs = Primer3Core.design("t1", seq, params);
        check(pairs.size() == 5, "design 返回 5 对 (实际 " + pairs.size() + ")");
        if (!pairs.isEmpty()) {
            Primer3Core.Pair p0 = pairs.get(0);
            check(p0.leftSeq != null && p0.rightSeq != null && p0.leftLen == p0.leftSeq.length()
                    && p0.rightLen == p0.rightSeq.length(), "序列与长度一致");
            check(!Double.isNaN(p0.leftTm) && !Double.isNaN(p0.rightTm) && p0.productSize > 0, "Tm/产物字段完整");
            check(p0.leftStart < 150 && p0.leftStart + p0.leftLen <= 151, "left 落在目标区左界前 (left=" + p0.leftStart + ")");
            check(p0.rightStart + p0.rightLen >= 250, "right 3'端覆盖目标区右界 (rightEnd=" + (p0.rightStart + p0.rightLen) + ")");
        }

        // 3. 高级模式（产物区间白名单：上游区 10-80 + 下游区 300-380，双区）
        params.remove("SEQUENCE_TARGET");
        params.remove("PRIMER_PRODUCT_SIZE_RANGE");
        params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", "10,70,300,80");
        List<Primer3Core.Pair> pairs2 = Primer3Core.design("t2", seq, params);
        check(!pairs2.isEmpty(), "高级模式有解 (" + pairs2.size() + " 对)");
        if (!pairs2.isEmpty()) {
            Primer3Core.Pair p = pairs2.get(0);
            check(p.leftStart >= 10 && p.leftStart + p.leftLen <= 80 + 27, "left 落上游区侧 (" + p.leftStart + "-" + (p.leftStart + p.leftLen) + ")");
            check(p.rightStart >= 300 - 27 && p.rightStart + p.rightLen <= 400, "right 落下游区侧 (" + p.rightStart + "-" + (p.rightStart + p.rightLen) + ")");
        }

        // 4. 无解场景：产物范围超过模板长度（可靠构造）
        Map<String, String> impossible = new LinkedHashMap<String, String>();
        impossible.put("PRIMER_PRODUCT_SIZE_RANGE", "500-600");
        List<Primer3Core.Pair> none = Primer3Core.design("t3", seq, impossible);
        check(none.isEmpty(), "无解返回空列表 (size=" + none.size() + ")");

        // 5. toPrimers：坐标换算/配对/指标/去重
        if (!pairs2.isEmpty()) {
            Primer3Core.Pair p = pairs2.get(0);
            int offset = 1000;
            List<Primer> two = p.toPrimers("chr1", "p3grp", offset, null);
            Primer f = two.get(0), r = two.get(1);
            check(f.strand == '+' && r.strand == '-' && "R1".equals(f.role) && "R2".equals(r.role), "F=+/R1，R=-/R2");
            check(f.start == offset + p.leftStart && r.start == offset + p.rightStart, "基因组坐标换算 (offset=1000)");
            check(f.pairedWith.contains(r.name) && r.pairedWith.contains(f.name), "pairedWith 互链");
            check(f.seq.equals(p.leftSeq) && r.seq.equals(p.rightSeq), "序列搬运");
            check(!Double.isNaN(f.tm) && f.tm == p.leftTm && f.gc == p.leftGc, "Tm/GC 搬运");
            Set<String> used = new HashSet<String>(Arrays.asList(f.name, r.name));
            List<Primer> two2 = p.toPrimers("chr1", "p3grp", offset, used);
            check(!two2.get(0).name.equals(f.name) && two2.get(0).name.startsWith(f.name + "_"),
                    "重名自动加后缀: " + two2.get(0).name + " / " + two2.get(1).name);
            check(two2.get(0).pairedWith.contains(two2.get(1).name), "去重后配对引用同步");
        }

        // 6. parse() 模拟输出 + 空结果
        String mock = "PRIMER_PAIR_NUM_RETURNED=1\n"
                + "PRIMER_LEFT_0=138,20\nPRIMER_RIGHT_0=348,20\n"
                + "PRIMER_LEFT_0_SEQUENCE=CGTCCGTGTTACCAGACCAA\n"
                + "PRIMER_RIGHT_0_SEQUENCE=ACCTCCAGTGGCCAGGATAT\n"
                + "PRIMER_LEFT_0_TM=59.968\nPRIMER_RIGHT_0_TM=60.030\n"
                + "PRIMER_LEFT_0_GC_PERCENT=55.000\nPRIMER_RIGHT_0_GC_PERCENT=55.000\n"
                + "PRIMER_PAIR_0_PRODUCT_SIZE=211\n"
                + "PRIMER_PAIR_0_COMPL_ANY_TH=0.00\nPRIMER_PAIR_0_COMPL_END_TH=0.00\n"
                + "PRIMER_LEFT_0_HAIRPIN_TH=0.00\nPRIMER_RIGHT_0_HAIRPIN_TH=0.00\n"
                + "PRIMER_LEFT_0_SELF_ANY_TH=0.00\nPRIMER_LEFT_0_SELF_END_TH=0.00\n"
                + "PRIMER_RIGHT_0_SELF_ANY_TH=0.00\nPRIMER_RIGHT_0_SELF_END_TH=0.00\n"
                + "PRIMER_LEFT_0_END_STABILITY=5\nPRIMER_RIGHT_0_END_STABILITY=5\n"
                + "=\n";
        List<Primer3Core.Pair> mp = Primer3Core.parse(mock);
        check(mp.size() == 1 && mp.get(0).leftStart == 138 && mp.get(0).productSize == 211
                && mp.get(0).leftSeq.equals("CGTCCGTGTTACCAGACCAA") && mp.get(0).leftEndStab == 5, "parse() 模拟输出");
        check(Primer3Core.parse("PRIMER_PAIR_NUM_RETURNED=0\n=\n").isEmpty(), "parse() 空结果");

        // 7. 标准 BED 回归（v0.1.55 降级导入行为不破坏）
        Method m = ExportUtils.class.getDeclaredMethod("parseBED", File.class, String.class, List.class, boolean[].class);
        m.setAccessible(true);
        List<Primer> out = new ArrayList<Primer>();
        boolean[] plain = new boolean[1];
        int n = (Integer) m.invoke(null, new File("\\\\fs2\\d\\data\\zhaojing\\lls_\\20261008\\primers091.bed"), "primers091", out, plain);
        check(n == 181 && !plain[0] && !out.get(0).pairedWith.isEmpty(),
                "标准BED回归 (n=" + n + " plain=" + plain[0] + ")");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

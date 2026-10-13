import org.broad.igv.primer.Primer;
import org.broad.igv.primer.PrimerStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v0.1.63：修复「primer3 设计结果 pair 连线连错对象」+ Shift+拖动纯选区。
 *
 * 用户数据实锤（~/.igv_primer_autosave.bed）：
 *   primer3_P3_F 的 |p = primer3_P1_R;primer3_P2_R;primer3_P4_R（连了 3 条别的编号），
 *   而同编号的 primer3_P3_R 反而孤立。渲染层（边模型）忠实按 pairedWith 画线，
 *   所以这是数据层错误——F 被连到了非搭档的 R。
 *
 * 本测试覆盖：
 *  1) parseNumbered 命名识别（P编号_F/_R vs 手动命名不碰）；
 *  2) repairNumberedPairs 修复用户实锤数据形状（清错边、重建 P3_F↔P3_R 单边）；
 *  3) 单侧编号（单引物设计）不动；
 *  4) 非 P 编号命名（Rv0678 手动多重 PCR 1 对多）完全不碰；
 *  5) add/addAll 重名防护（同名第二个自动 _2，防按名寻址连错实例）；
 *  6) 已正确配对 → fixed=0（幂等）。
 */
public class TestP6 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    static Primer mk(String name, int start, int end, char strand) {
        Primer p = new Primer(name, "NC_000962.3", start, end, strand, strand == '+' ? "R1" : "R2",
                strand == '+' ? 20 : 18, null);
        p.group = "primer3";
        return p;
    }

    static List<String> partners(Primer p) {
        return p.pairedWith == null ? new ArrayList<String>() : new ArrayList<String>(p.pairedWith);
    }

    public static void main(String[] a) throws Exception {
        PrimerStore.autosaveEnabled = false;   // 测试不写盘

        // ================= 1) parseNumbered =================
        java.lang.reflect.Method pn = PrimerStore.class.getDeclaredMethod("parseNumbered", String.class);
        pn.setAccessible(true);
        check(Arrays.equals((int[]) pn.invoke(null, "primer3_P3_F"), new int[]{3, 1}), "primer3_P3_F → {3,+1}");
        check(Arrays.equals((int[]) pn.invoke(null, "primer3_P12_R"), new int[]{12, -1}), "primer3_P12_R → {12,-1}");
        check(pn.invoke(null, "Rv0678-6") == null, "Rv0678-6 非编号风格 → null");
        check(pn.invoke(null, "P026") == null, "P026 → null");
        check(pn.invoke(null, "primer3_P3_X") == null, "_X 结尾 → null");
        check(pn.invoke(null, "primer3_PA_F") == null, "非数字编号 → null");
        check(pn.invoke(null, (Object) null) == null, "null → null");

        // ================= 2) 修复用户实锤数据形状 =================
        PrimerStore.clear();
        Primer f3 = mk("primer3_P3_F", 2291813, 2291833, '+');
        Primer r1 = mk("primer3_P1_R", 2292592, 2292610, '-');
        Primer r2 = mk("primer3_P2_R", 2292592, 2292610, '-');
        Primer r3 = mk("primer3_P3_R", 2292592, 2292610, '-');
        Primer r4 = mk("primer3_P4_R", 2292592, 2292610, '-');
        Primer r5 = mk("primer3_P5_R", 2292592, 2292610, '-');
        // 误连状态：P3_F 连 P1_R/P2_R/P4_R，P3_R/P5_R 孤立（autosave 原样）
        f3.pairedWith.add("primer3_P1_R"); r1.pairedWith.add("primer3_P3_F");
        f3.pairedWith.add("primer3_P2_R"); r2.pairedWith.add("primer3_P3_F");
        f3.pairedWith.add("primer3_P4_R"); r4.pairedWith.add("primer3_P3_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(f3, r1, r2, r3, r4, r5)));
        check(partners(f3).size() == 3, "修复前 P3_F 持 3 条错边 " + partners(f3));

        int fixed = PrimerStore.repairNumberedPairs("primer3");
        check(fixed == 1, "repair 修复 1 对 (fixed=" + fixed + ")");
        check(partners(f3).equals(Arrays.asList("primer3_P3_R")), "P3_F 只连 P3_R " + partners(f3));
        check(partners(r3).equals(Arrays.asList("primer3_P3_F")), "P3_R 只连 P3_F " + partners(r3));
        check(partners(r1).isEmpty(), "P1_R 错边已清 " + partners(r1));
        check(partners(r2).isEmpty(), "P2_R 错边已清 " + partners(r2));
        check(partners(r4).isEmpty(), "P4_R 错边已清 " + partners(r4));
        check(partners(r5).isEmpty(), "P5_R 仍孤立 " + partners(r5));

        // 幂等：再修一次返回 0
        check(PrimerStore.repairNumberedPairs("primer3") == 0, "repair 幂等（再修 fixed=0）");

        // ================= 3) 单侧编号不动（单引物设计结果） =================
        PrimerStore.clear();
        Primer f7 = mk("primer3_P7_F", 1000, 1020, '+');   // 无 P7_R
        f7.pairedWith.add("primer3_P1_R");                  // 故意连错
        Primer r1b = mk("primer3_P1_R", 2000, 2020, '-');
        r1b.pairedWith.add("primer3_P7_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(f7, r1b)));
        int fixed3 = PrimerStore.repairNumberedPairs("primer3");
        check(fixed3 == 0, "单侧编号不修复 (fixed=" + fixed3 + ")");
        check(partners(f7).contains("primer3_P1_R"), "单侧 F 的原有边保留（单引物设计不管配对）");

        // ================= 4) 非 P 编号命名不碰（手动多重 PCR 1 对多） =================
        PrimerStore.clear();
        Primer f6 = mk("Rv0678-6", 778958, 778978, '+');
        Primer r8 = mk("Rv0678-8", 779225, 779245, '-');
        Primer r6 = mk("Rv0678-6R", 779105, 779125, '-');
        f6.pairedWith.add("Rv0678-8"); r8.pairedWith.add("Rv0678-6");
        f6.pairedWith.add("Rv0678-6R"); r6.pairedWith.add("Rv0678-6");   // 1 对 2（合法多重 PCR）
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(f6, r8, r6)));
        int fixed4 = PrimerStore.repairNumberedPairs("primers");
        check(fixed4 == 0, "非 P 编号组 repair=0 (fixed=" + fixed4 + ")");
        check(partners(f6).size() == 2, "Rv0678-6 的 1 对多两条边保留 " + partners(f6));

        // ================= 5) add/addAll 重名防护 =================
        PrimerStore.clear();
        PrimerStore.add(mk("primer3_P3_F", 2291813, 2291833, '+'));
        Primer dup = mk("primer3_P3_F", 2291813, 2291833, '+');
        PrimerStore.add(dup);
        check("primer3_P3_F_2".equals(dup.name), "重名 add 自动改名 _2 (实际 " + dup.name + ")");
        PrimerStore.clear();
        Primer d1 = mk("primer3_P1_R", 2292592, 2292610, '-');
        Primer d2 = mk("primer3_P1_R", 2292592, 2292610, '-');
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(d1, d2)));
        check("primer3_P1_R_2".equals(d2.name), "重名 addAll 自动改名 _2 (实际 " + d2.name + ")");

        // ================= 6) 成对正确数据不受影响 =================
        PrimerStore.clear();
        Primer fa = mk("primer3_P1_F", 1000, 1020, '+');
        Primer ra = mk("primer3_P1_R", 1200, 1220, '-');
        fa.pairedWith.add("primer3_P1_R"); ra.pairedWith.add("primer3_P1_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(fa, ra)));
        check(PrimerStore.repairNumberedPairs("primer3") == 0, "正确配对 fixed=0");
        check(partners(fa).equals(Arrays.asList("primer3_P1_R")), "正确配对边保留");

        PrimerStore.autosaveEnabled = true;
        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

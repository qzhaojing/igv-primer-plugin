import org.broad.igv.primer.Primer3Core;
import org.broad.igv.primer.Primer3Dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v0.1.60：修复「成对简单模式总是无解」。
 * 根因：SEQUENCE_TARGET 语义=产物必须覆盖整个 target（非重叠），
 * 最小产物 = target长 + 两侧引物长；v0.1.59 把产物上限放到 target 等长（甚至更小），必然无解。
 * 本测试端到端复刻 runDesign 的参数构造，验证新公式 needMax = tLen + 2*lenMax。
 */
public class TestP4 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    /** 复刻 Primer3Dialog.runDesign 简单成对模式的参数构造 */
    static Map<String, String> pairParams(int lenMin, int lenOpt, int lenMax,
                                          int tmMin, int tmOpt, int tmMax,
                                          int gcMin, int gcMax, int numRet,
                                          int s, int e, int prodMin, int prodMaxUser,
                                          int tmplStart) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("PRIMER_MIN_SIZE", String.valueOf(lenMin));
        params.put("PRIMER_OPT_SIZE", String.valueOf(lenOpt));
        params.put("PRIMER_MAX_SIZE", String.valueOf(lenMax));
        params.put("PRIMER_MIN_TM", String.valueOf(tmMin));
        params.put("PRIMER_OPT_TM", String.valueOf(tmOpt));
        params.put("PRIMER_MAX_TM", String.valueOf(tmMax));
        params.put("PRIMER_MIN_GC", String.valueOf(gcMin));
        params.put("PRIMER_MAX_GC", String.valueOf(gcMax));
        params.put("PRIMER_NUM_RETURN", String.valueOf(numRet));
        params.put("PRIMER_PICK_LEFT_PRIMER", "1");
        params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
        // v0.1.60 修复逻辑（与 Primer3Dialog 一致）
        int tLen = e - (s - 1);
        int prodMax = prodMaxUser;
        int needMax = tLen + 2 * lenMax;
        if (prodMax < needMax) prodMax = needMax;
        params.put("PRIMER_PRODUCT_SIZE_RANGE", prodMin + "-" + prodMax);
        params.put("SEQUENCE_TARGET", (s - 1 - tmplStart) + "," + tLen);
        return params;
    }

    public static void main(String[] a) throws Exception {
        Random rnd = new Random(42);
        StringBuilder sb = new StringBuilder();
        // 模板 = 区间2662 ± pad120 = 2902bp（与 runDesign 的 regionToTemplate 一致）
        for (int i = 0; i < 2902; i++) sb.append("ACGT".charAt(rnd.nextInt(4)));
        String seq = sb.toString();

        // ---- 场景1：用户截图（2662bp 区间，默认产物 100-300，模板=区间±120）----
        int s = 2284793, e = 2287454;           // 2662bp
                int ts1 = s - 1 - 120;
        Map<String, String> p1 = pairParams(18, 20, 27, 57, 60, 63, 30, 70, 5, s, e, 100, 300, ts1);
        // 旧公式（上限=target等长）应无解
        Map<String, String> p1old = new LinkedHashMap<String, String>(p1);
        p1old.put("PRIMER_PRODUCT_SIZE_RANGE", "100-2662");
        List<Primer3Core.Pair> oldR = Primer3Core.design("t_old", seq, p1old);
        check(oldR.isEmpty(), "旧公式(上限=target等长)无解——复现用户bug");
        // 新公式应有解
        List<Primer3Core.Pair> r1 = Primer3Core.design("t_new", seq, p1);
        check(!r1.isEmpty(), "新公式(上限=2662+54)有解 (" + r1.size() + " 对)");
        if (!r1.isEmpty()) {
            Primer3Core.Pair p = r1.get(0);
            // target 覆盖验证：left 起点 ≤ target 起、right 终点 ≥ target 止
            int tS = 120, tE = 120 + 2662;       // 模板 2770，target 起于 pad=120
            check(p.leftStart <= tS && p.rightStart + p.rightLen >= tE,
                    "产物覆盖整个 target (left=" + p.leftStart + ", rightEnd=" + (p.rightStart + p.rightLen) + ")");
            check(p.productSize >= 2662, "产物 >= target 长 (" + p.productSize + "bp)");
        }

        // ---- 场景2：上张截图（500bp 区间 < 原上限300? 不，500>300 但旧代码只在 tLen>prodMax 时放到等长仍无解）----
        int s2 = 2288000, e2 = 2288500;          // 500bp
        int ts2 = s2 - 1 - 120;
        Map<String, String> p2 = pairParams(18, 20, 27, 57, 60, 63, 30, 70, 5, s2, e2, 100, 300, ts2);
        Map<String, String> p2old = new LinkedHashMap<String, String>(p2);
        p2old.put("PRIMER_PRODUCT_SIZE_RANGE", "100-500");   // v0.1.59 旧公式放到等长
        List<Primer3Core.Pair> old2 = Primer3Core.design("t2_old", seq, p2old);
        check(old2.isEmpty(), "旧公式 500bp 场景同样无解");
        List<Primer3Core.Pair> r2 = Primer3Core.design("t2_new", seq, p2);
        check(!r2.isEmpty(), "新公式 500bp 场景有解 (" + r2.size() + " 对)");

        // ---- 场景3：区间小于原上限但 target 仍超物理最小（区间 200bp，上限 300，需 ≥254）----
        int s3 = 100, e3 = 299;                  // 200bp
        Map<String, String> p3 = pairParams(18, 20, 27, 57, 60, 63, 30, 70, 5, s3, e3, 100, 300, 0);
        List<Primer3Core.Pair> r3 = Primer3Core.design("t3", seq, p3);
        // 200+54=254 ≤ 300，公式不动上限，300 上限本身够；应有解
        check(!r3.isEmpty(), "小区间(200bp,上限300)有解 (" + r3.size() + " 对)");

        // ---- 场景4：EXPLAIN 诊断提取（真无解：产物上限卡死小于 target）----
        Map<String, String> p4 = pairParams(18, 20, 27, 57, 60, 63, 30, 70, 5, s, e, 100, 2662, ts1);
        p4.put("PRIMER_PRODUCT_SIZE_RANGE", "100-2662");
        List<Primer3Core.Pair> r4 = Primer3Core.design("t4", seq, p4);
        check(r4.isEmpty(), "无解场景返回空");
        check(Primer3Core.lastExplain != null && Primer3Core.lastExplain.contains("considered"),
                "lastExplain 含诊断统计: " + Primer3Core.lastExplain);

        // ---- 场景5：单引物模式回归（不受影响）----
        Map<String, String> p5 = pairParams(18, 20, 27, 57, 60, 63, 30, 70, 5, 101, 300, 100, 300, 0);
        p5.put("PRIMER_PICK_RIGHT_PRIMER", "0");
        p5.remove("PRIMER_PRODUCT_SIZE_RANGE");
        p5.put("SEQUENCE_INCLUDED_REGION", "100,200");
        p5.put("SEQUENCE_TARGET", "190,20");
        List<Primer3Core.Pair> r5 = Primer3Core.design("t5", seq, p5);
        check(!r5.isEmpty() && r5.get(0).rightSeq == null, "单引物模式回归 (" + r5.size() + " 条)");

        // ---- 场景6：regionToTemplate 回归（包私有，反射调）----
        java.lang.reflect.Method rtt = Primer3Dialog.class.getDeclaredMethod("regionToTemplate",
                int.class, boolean.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class);
        rtt.setAccessible(true);
        int[] rt = (int[]) rtt.invoke(null, Primer3Dialog.MODE_PAIR, false, s, e, 0, 0, 0, 0, 248956422, 18, 27);
        check(rt[0] == s - 1 - 120 && rt[1] == e + 120, "regionToTemplate 模板±120 (" + rt[0] + "," + rt[1] + ")");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

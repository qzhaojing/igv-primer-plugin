import org.broad.igv.primer.Primer3Dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.1.69：单引物误发 SEQUENCE_TARGET 修复 + 降条件自动重试 + explain 扩展 + 快速设计。
 *
 * 老板截图：选区后设计单向引物，"LEFT: considered 2, overlap target 2, ok 0"。
 *   根因：单引物模式补发了 SEQUENCE_TARGET（选区中段 ≤20bp），primer3 语义=引物不得
 *   覆盖 target，19bp 选区放 18nt 引物仅 2 个候选位且全部 overlap target → ok 0。
 *   修复：单引物只发 SEQUENCE_INCLUDED_REGION（runDesign 内改动，依赖 IGV 不便单测，
 *   由 explain/relax 的可测部分覆盖）。
 *
 * 老板需求："给出 explain 为什么选不出来"（单引物与双引物同款逻辑）：
 *   explainTips 扩展解析无 failed 后缀的统计段（"overlap target 2"）。
 *
 * 老板需求："降低条件的逻辑，先设计5-10个级别…一次选不出来则继续降低，直到选出来，
 *   但要让用户明晰降低到什么条件了，还有风险提示"：
 *   relaxAt 6 级阶梯（纯函数）；成功后弹窗逐项明示 + 风险提示（UI 部分）。
 *
 * 老板需求："很多引物无法选区的时候，想获得一个引物"：
 *   quickCenterRegion 视口中心取 100bp（纯函数）。
 *
 * 本测试覆盖：
 *  1) relaxAt 各级：L1 Tm±2 / L2 +GC±5 / L3 +引物长 / L4 删组成 / L5 删二聚体 / L6 极限+OPT；
 *  2) 区域约束与 Tm 计算基础永不动（INCLUDED_REGION/TARGET/OK_REGION_LIST/
 *     PRODUCT_SIZE_RANGE/PICK_LEFT/RIGHT/盐浓度）；
 *  3) level 边界（0 / 7 原样返回无描述）；
 *  4) explainTips 新解析：overlap target / unacceptable product size 出建议；
 *  5) quickCenterRegion：宽视野取中段 100bp、窄视野退化、奇数/偶数中点。
 */
public class TestP11 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    /** 构造一组典型参数（含区域约束 + 用户设的结构约束）。 */
    static Map<String, String> baseParams() {
        Map<String, String> p = new LinkedHashMap<String, String>();
        // 区域约束（绝不能被降级动）
        p.put("SEQUENCE_INCLUDED_REGION", "100,19");
        p.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", "0,100,200,100");
        p.put("PRIMER_PRODUCT_SIZE_RANGE", "100-500");
        p.put("PRIMER_PICK_LEFT_PRIMER", "1");
        p.put("PRIMER_PICK_RIGHT_PRIMER", "0");
        // Tm 计算基础（降级不动，否则 Tm 基准变了）
        p.put("PRIMER_SALT_MONOVALENT", "50");
        p.put("PRIMER_DNA_CONC", "50");
        // 筛选参数
        p.put("PRIMER_MIN_SIZE", "18");
        p.put("PRIMER_OPT_SIZE", "20");
        p.put("PRIMER_MAX_SIZE", "27");
        p.put("PRIMER_MIN_TM", "57");
        p.put("PRIMER_OPT_TM", "60");
        p.put("PRIMER_MAX_TM", "63");
        p.put("PRIMER_MIN_GC", "30");
        p.put("PRIMER_MAX_GC", "70");
        // 用户显式设置的结构/二聚体约束（L4/L5 应删除）
        p.put("PRIMER_MAX_POLY_X", "5");
        p.put("PRIMER_GC_CLAMP", "1");
        p.put("PRIMER_MAX_COMPL_ANY_TH", "8");
        p.put("PRIMER_MAX_HAIRPIN_TH", "47");
        return p;
    }

    static String v(Map<String, String> p, String k) {
        return p.get(k);
    }

    public static void main(String[] args) {
        // ---------- 1) relaxAt 各级 ----------
        Map<String, String> base = baseParams();

        // L1：仅 Tm ±2
        Primer3Dialog.Relaxed r1 = Primer3Dialog.relaxAt(base, 1);
        check("55".equals(v(r1.params, "PRIMER_MIN_TM")) && "65".equals(v(r1.params, "PRIMER_MAX_TM")),
                "L1 Tm 57-63 → 55-65");
        check("30".equals(v(r1.params, "PRIMER_MIN_GC")) && "70".equals(v(r1.params, "PRIMER_MAX_GC")),
                "L1 GC 不动（仍 30-70）");
        check("18".equals(v(r1.params, "PRIMER_MIN_SIZE")) && "27".equals(v(r1.params, "PRIMER_MAX_SIZE")),
                "L1 引物长不动（仍 18-27）");
        check("5".equals(v(r1.params, "PRIMER_MAX_POLY_X")) && "8".equals(v(r1.params, "PRIMER_MAX_COMPL_ANY_TH")),
                "L1 结构/二聚体约束不动");
        check(r1.descs.size() == 1 && r1.descs.get(0).contains("Tm 范围 57–63℃ → 55–65℃"),
                "L1 描述只有一条 Tm（" + r1.descs + "）");

        // L2：Tm ±4 + GC ±5
        Primer3Dialog.Relaxed r2 = Primer3Dialog.relaxAt(base, 2);
        check("53".equals(v(r2.params, "PRIMER_MIN_TM")) && "67".equals(v(r2.params, "PRIMER_MAX_TM")),
                "L2 Tm 57-63 → 53-67");
        check("25".equals(v(r2.params, "PRIMER_MIN_GC")) && "75".equals(v(r2.params, "PRIMER_MAX_GC")),
                "L2 GC 30-70 → 25-75");
        check(r2.descs.size() == 2, "L2 描述两条（Tm+GC）：" + r2.descs);

        // L3：+ 引物长 min-3 / max+3
        Primer3Dialog.Relaxed r3 = Primer3Dialog.relaxAt(base, 3);
        check("15".equals(v(r3.params, "PRIMER_MIN_SIZE")) && "30".equals(v(r3.params, "PRIMER_MAX_SIZE")),
                "L3 引物长 18-27 → 15-30");
        check(r3.descs.size() == 3, "L3 描述三条：" + r3.descs);

        // L4：+ 删组成约束（POLY_X / GC_CLAMP）
        Primer3Dialog.Relaxed r4 = Primer3Dialog.relaxAt(base, 4);
        check(v(r4.params, "PRIMER_MAX_POLY_X") == null && v(r4.params, "PRIMER_GC_CLAMP") == null,
                "L4 删除组成约束（多聚X/GC钳）");
        check("8".equals(v(r4.params, "PRIMER_MAX_COMPL_ANY_TH")) && "47".equals(v(r4.params, "PRIMER_MAX_HAIRPIN_TH")),
                "L4 二聚体约束仍在");
        boolean hasCompDesc = false;
        for (String d : r4.descs) if (d.contains("取消组成约束")) hasCompDesc = true;
        check(hasCompDesc, "L4 描述含取消组成约束：" + r4.descs);

        // L5：+ 删二聚体/发夹
        Primer3Dialog.Relaxed r5 = Primer3Dialog.relaxAt(base, 5);
        check(v(r5.params, "PRIMER_MAX_COMPL_ANY_TH") == null && v(r5.params, "PRIMER_MAX_HAIRPIN_TH") == null,
                "L5 删除二聚体/发夹阈值");
        boolean hasDimerDesc = false;
        for (String d : r5.descs) if (d.contains("取消二聚体/发夹阈值")) hasDimerDesc = true;
        check(hasDimerDesc, "L5 描述含取消二聚体阈值：" + r5.descs);

        // L6：极限（Tm±10 夹 40..80 / GC 10-90 / 长 15-40 / 删 OPT）
        Primer3Dialog.Relaxed r6 = Primer3Dialog.relaxAt(base, 6);
        check("47".equals(v(r6.params, "PRIMER_MIN_TM")) && "73".equals(v(r6.params, "PRIMER_MAX_TM")),
                "L6 Tm 57-63 → 47-73（±10 夹 40..80）");
        check("10".equals(v(r6.params, "PRIMER_MIN_GC")) && "90".equals(v(r6.params, "PRIMER_MAX_GC")),
                "L6 GC → 10-90");
        check("15".equals(v(r6.params, "PRIMER_MIN_SIZE")) && "40".equals(v(r6.params, "PRIMER_MAX_SIZE")),
                "L6 引物长 → 15-40");
        check(v(r6.params, "PRIMER_OPT_SIZE") == null && v(r6.params, "PRIMER_OPT_TM") == null,
                "L6 删除 OPT 偏好");
        boolean hasOptDesc = false;
        for (String d : r6.descs) if (d.contains("取消最优")) hasOptDesc = true;
        check(hasOptDesc, "L6 描述含取消 OPT 偏好：" + r6.descs);

        // ---------- 2) 区域约束与 Tm 计算基础永不动 ----------
        for (int lv = 1; lv <= Primer3Dialog.MAX_RELAX_LEVEL; lv++) {
            Primer3Dialog.Relaxed r = Primer3Dialog.relaxAt(base, lv);
            check("100,19".equals(v(r.params, "SEQUENCE_INCLUDED_REGION"))
                            && "0,100,200,100".equals(v(r.params, "SEQUENCE_PRIMER_PAIR_OK_REGION_LIST"))
                            && "100-500".equals(v(r.params, "PRIMER_PRODUCT_SIZE_RANGE"))
                            && "1".equals(v(r.params, "PRIMER_PICK_LEFT_PRIMER"))
                            && "50".equals(v(r.params, "PRIMER_SALT_MONOVALENT"))
                            && "50".equals(v(r.params, "PRIMER_DNA_CONC")),
                    "L" + lv + " 区域约束/Tm计算基础不动");
        }
        // base 本身不被修改
        check("57".equals(v(base, "PRIMER_MIN_TM")) && "5".equals(v(base, "PRIMER_MAX_POLY_X")),
                "relaxAt 不修改原始 base 参数");

        // ---------- 3) level 边界 ----------
        Primer3Dialog.Relaxed r0 = Primer3Dialog.relaxAt(base, 0);
        check(r0.params.equals(base) && r0.descs.isEmpty(), "level=0 原样返回无描述");
        Primer3Dialog.Relaxed r7 = Primer3Dialog.relaxAt(base, 7);
        check(r7.params.equals(base) && r7.descs.isEmpty(), "level=7（越界）原样返回无描述");
        Primer3Dialog.Relaxed rNeg = Primer3Dialog.relaxAt(base, -1);
        check(rNeg.params.equals(base) && rNeg.descs.isEmpty(), "level=-1 原样返回无描述");

        // 未设置结构约束时 L4/L5 无删除描述（不产生噪音描述）
        Map<String, String> bare = new LinkedHashMap<String, String>();
        bare.put("PRIMER_MIN_TM", "57");
        bare.put("PRIMER_MAX_TM", "63");
        bare.put("PRIMER_MIN_GC", "30");
        bare.put("PRIMER_MAX_GC", "70");
        bare.put("PRIMER_MIN_SIZE", "18");
        bare.put("PRIMER_MAX_SIZE", "27");
        Primer3Dialog.Relaxed rb5 = Primer3Dialog.relaxAt(bare, 5);
        boolean noNoise = true;
        for (String d : rb5.descs) if (d.contains("取消")) noNoise = false;
        check(noNoise && rb5.descs.size() == 3, "L5 无约束可删时不产生取消类描述：" + rb5.descs);

        // ---------- 4) explainTips 新解析 ----------
        // 老板截图原样："overlap target 2"（无 failed 后缀，旧版解析不到→建议区为空）
        String tips1 = Primer3Dialog.explainTips("LEFT: considered 2, overlap target 2, ok 0", true);
        check(tips1.contains("overlap target ×2"), "explainTips 解析 overlap target ×2：" + tips1);
        check(tips1.contains("目标区重叠"), "overlap target 给专属建议（目标区重叠）：" + tips1);
        // 双引物旧死区："unacceptable product size 1234"
        String tips2 = Primer3Dialog.explainTips("PAIR: considered 1234, unacceptable product size 1234, ok 0", false);
        check(tips2.contains("unacceptable product size ×1234"), "explainTips 解析 product size ×1234：" + tips2);
        check(tips2.contains("产物 min"), "product size 给产物范围建议：" + tips2);
        // 旧 "xxx failed N" 格式仍可用
        String tips3 = Primer3Dialog.explainTips("LEFT: considered 985, GC content failed 598, low tm 11, ok 86", false);
        check(tips3.contains("GC content ×598") && tips3.contains("low tm ×11"),
                "explainTips 旧 failed 格式回归：" + tips3);
        // considered/ok 不算淘汰原因
        String tips4 = Primer3Dialog.explainTips("LEFT: considered 985, ok 86", false);
        check(tips4.isEmpty() || !tips4.contains("considered") && !tips4.contains("ok ×"),
                "considered/ok 不计入失败统计：" + tips4);

        // ---------- 5) quickCenterRegion ----------
        int[] q1 = Primer3Dialog.quickCenterRegion(22923800, 22924000, 100);   // 200bp 视野
        check(q1[1] - q1[0] + 1 == 100, "宽视野取 100bp（长=" + (q1[1] - q1[0] + 1) + "）");
        // 视野 0-based [22923800, 22924000)，1-based 中点 = (22923800 + 22923999) / 2
        check(Math.abs((q1[0] + q1[1]) / 2 - (22923800 + 22923999) / 2) <= 1,
                "快速区居中（mid=" + ((q1[0] + q1[1]) / 2) + " 期望≈" + ((22923800 + 22923999) / 2) + "）");
        int[] q2 = Primer3Dialog.quickCenterRegion(22923800, 22923850, 100);   // 50bp 窄视野
        check(q2[0] == 22923801 && q2[1] == 22923850, "窄视野退化为整个视野：" + q2[0] + "-" + q2[1]);
        int[] q3 = Primer3Dialog.quickCenterRegion(1000, 1300, 100);           // 300bp 偶数宽
        // 100bp 偶数宽无法完美对称，允许 ±1bp 偏差；长度必须仍是 100
        check(q3[1] - q3[0] + 1 == 100
                        && Math.abs((q3[0] + q3[1]) / 2.0 - (1000 + 1 + 1300) / 2.0) <= 1,
                "偶数宽视野取整后仍 100bp 且基本居中：" + q3[0] + "-" + q3[1]);

        System.out.println("==== TestP11: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

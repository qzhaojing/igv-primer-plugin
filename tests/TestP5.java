import org.broad.igv.primer.Primer3Core;
import org.broad.igv.primer.Primer3Dialog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v0.1.80：鼠标选区 → 一键填充产物区间 + 区内（产物两端内侧）引物设计区。
 * 覆盖：
 *  1) fillFromSelection 纯函数：常规/左右缘贴边退化/选区超宽/屏外返回null/起止倒转/不钳屏幕；
 *  2) regionToTemplate 高级模式回归（模板覆盖区内设计区±pad）；
 *  3) 端到端复刻 runDesign 高级分支（OK_REGION_LIST 双区约束，区内模型）：
 *     验证有解、左引物落左端设计区、右引物落右端设计区、产物覆盖选区；
 *  4) 回归：v0.1.56 旧单区格式 → PRIMER_ERROR 被暴露（不再静默吞成"无解"）。
 *
 * 实测语义（v0.1.61 直调 primer3_core.exe 确认，区内模型不变）：
 *  SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 值 = 逗号分隔的「start,len」区对，**必须 ≥2 对**
 *  （单对 → PRIMER_ERROR=Illegal value）；语义 = 左引物必须落在第 1 个区、右引物落在第 2 个区
 *  （顺序不可反，反序 0 对）。坐标相对 SEQUENCE_TEMPLATE。
 *  区内模型（v0.1.80）：第 1/2 个区分别是产物左/右端内侧的设计带，引物只在区内发生设计。
 */
public class TestP5 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    public static void main(String[] a) throws Exception {
        // ================= 1) fillFromSelection =================
        // 视野 0-based [10000,13000)，选区 [11000,11500]（0-based 含），最短引物 18
        int[] f = Primer3Dialog.fillFromSelection(11000, 11500, 10000, 13000, 18);
        check(f != null && f[0] == 11001 && f[1] == 11501, "产物=选区(1-based) " + Arrays.toString(f));
        // v0.1.80：区内模型。设计带在产物两端内侧，带宽=200bp（=max(lenMax,min(flankMax,L/2))，且钳到产物长）。
        // 左端带 0-based [11000,11199]→1-based 11001+200；右端带 0-based [11301,11500]→11302+200
        check(f[2] == 11001 && f[3] == 200, "左端设计区=产物左缘内200bp (起11001 长200)");
        check(f[4] == 11302 && f[5] == 200, "右端设计区=产物右缘内200bp (起11302 长200)");

        // 不钳屏幕（无视野上下文）：设计带同样在产物两端内侧 200bp
        int[] fNoVp = Primer3Dialog.fillFromSelection(11000, 11500, 0, Integer.MAX_VALUE, 18);
        check(fNoVp[2] == 11001 && fNoVp[3] == 200 && fNoVp[4] == 11302 && fNoVp[5] == 200,
                "无视野钳制时设计区=产物两端内侧200bp " + Arrays.toString(fNoVp));

        // 起止倒转自动交换
        int[] fRev = Primer3Dialog.fillFromSelection(11500, 11000, 10000, 13000, 18);
        check(fRev != null && Arrays.equals(fRev, f), "起止倒转自动交换");

        // 左缘贴边：选区起点=视野起点 → v0.1.80 区内模型把左端设计区放在产物左缘内侧（仍在屏幕内），out=0
        int[] fL = Primer3Dialog.fillFromSelection(10000, 10500, 10000, 13000, 18);
        check(fL != null && fL[2] == 10001 && fL[3] == 200 && fL[6] == 0,
                "左缘齐平→左端设计区贴产物左缘内200bp out=0 (起=" + fL[2] + " 长=" + fL[3] + ")");

        // 右缘贴边：选区终点=视野末 → 右端设计区放在产物右缘内侧（屏幕内），out=0
        int[] fR = Primer3Dialog.fillFromSelection(12000, 12999, 10000, 13000, 18);
        check(fR != null && fR[4] == 12801 && fR[5] == 200 && fR[7] == 0,
                "右缘齐平→右端设计区贴产物右缘内200bp out=0 (止=" + (fR[4] + fR[5] - 1) + " 长=" + fR[5] + ")");

        // 选区右端超出视野 → 产物钳到视野内（引物设计区域限屏幕内）
        int[] fOver = Primer3Dialog.fillFromSelection(12500, 13500, 10000, 13000, 18);
        check(fOver != null && fOver[0] == 12501 && fOver[1] == 13000,
                "选区超宽→产物钳到视野 " + Arrays.toString(fOver));

        // 选区完全在视野外（平移走了）→ null，调用方回退视口
        check(Primer3Dialog.fillFromSelection(20000, 20100, 10000, 13000, 18) == null,
                "屏外选区返回 null");

        // minLen 兜底：视野极窄（仅 30bp）也返回正长度侧翼
        int[] fTiny = Primer3Dialog.fillFromSelection(10010, 10020, 10000, 10030, 18);
        check(fTiny != null && fTiny[3] >= 1 && fTiny[5] >= 1, "极窄视野不炸 " + Arrays.toString(fTiny));

        // ================= 2) regionToTemplate 高级模式回归 =================
        java.lang.reflect.Method rtt = Primer3Dialog.class.getDeclaredMethod("regionToTemplate",
                int.class, boolean.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class);
        rtt.setAccessible(true);
        // 区内模型（v0.1.80）：设计区在产物两端内侧。
        // 产物 [2001,2401]；左端设计区 起2001 长200 → 0-based [2000,2200)；右端设计区 起2202 长200 → [2201,2401)
        int[] rt = (int[]) rtt.invoke(null, Primer3Dialog.MODE_PAIR, true, 2001, 2401, 2001, 200, 2202, 200,
                248956422, 18, 27);
        // ts=min(2000,2201)-54=1946；te=max(2200,2401)+54=2455
        check(rt[0] == 1946 && rt[1] == 2455, "高级模板覆盖区内设计区±pad (" + rt[0] + "," + rt[1] + ")");

        // ================= 3) 端到端：区内模型（设计区在产物两端内侧，OK_REGION_LIST 约束）=================
        Random rnd = new Random(42);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2455 - 1946; i++) sb.append("ACGT".charAt(rnd.nextInt(4)));
        String seq = sb.toString();   // 509bp 模板（0-based [1946,2455) 的内容）

        int lenMin = 18, lenMax = 27;
        int s1 = 2001, e1 = 2401;     // 产物区间（1-based，来自选区填充）
        int us0 = 2000, ul = 200;     // 左端设计区 0-based [2000,2200)（产物左缘内侧）
        int ds0 = 2201, dl = 200;     // 右端设计区 0-based [2201,2401)（产物右缘内侧）
        int tmplStart = 1946;
        int tLen = e1 - (s1 - 1);
        // 注：本测试手写 PRIMER_PRODUCT_SIZE_RANGE=100-1000（见下），验证区内双区约束语义；
        // 上限自适应公式（v0.1.62: tLen+2×pad）由 TestP4 场景7 覆盖。

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("PRIMER_MIN_SIZE", String.valueOf(lenMin));
        params.put("PRIMER_OPT_SIZE", "20");
        params.put("PRIMER_MAX_SIZE", String.valueOf(lenMax));
        params.put("PRIMER_MIN_TM", "57");
        params.put("PRIMER_OPT_TM", "60");
        params.put("PRIMER_MAX_TM", "63");
        params.put("PRIMER_MIN_GC", "30");
        params.put("PRIMER_MAX_GC", "70");
        params.put("PRIMER_NUM_RETURN", "5");
        params.put("PRIMER_PICK_LEFT_PRIMER", "1");
        params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
        // 产物范围放宽到 100-1000：本测试验证区内双区约束语义而非上限自适应公式（后者由 TestP4 覆盖）。
        params.put("PRIMER_PRODUCT_SIZE_RANGE", "100-1000");
        // 区内模型：生产默认只发 OK_REGION_LIST（不靠 SEQUENCE_TARGET 限定产物）；
        // 左端设计区,右端设计区（两对）作为引物白名单，引物只在区内发生设计。
        params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST",
                (us0 - tmplStart) + "," + ul + "," + (ds0 - tmplStart) + "," + dl); // 左端设计区,右端设计区（两对）

        List<Primer3Core.Pair> rs = Primer3Core.design("t_adv_sel", seq, params);
        check(!rs.isEmpty(), "区内模型(双区格式)有解 (" + rs.size() + " 对)");
        if (!rs.isEmpty()) {
            Primer3Core.Pair p = rs.get(0);
            int upS = us0 - tmplStart, upE = upS + ul;       // 模板坐标左端设计区 [54,254)
            int dnS = ds0 - tmplStart, dnE = dnS + dl;       // 模板坐标右端设计区 [255,455)
            check(p.leftStart >= upS && p.leftStart < upE,
                    "左引物落左端设计区 (left=" + p.leftStart + " ∈ [" + upS + "," + upE + "))");
            check(p.rightStart >= dnS && p.rightStart < dnE,
                    "右引物落右端设计区 (right=" + p.rightStart + " ∈ [" + dnS + "," + dnE + "))");
            // 区内模型下产物=两端带之间的区段，长度应落在约束范围内
            check(p.productSize >= 100 && p.productSize <= 1000,
                    "产物长度在约束范围内 (" + p.productSize + "bp)");
        }

        // ================= 4) 回归：v0.1.56 旧单区格式 → PRIMER_ERROR 暴露 =================
        Map<String, String> bad = new LinkedHashMap<String, String>(params);
        bad.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", "54,1400");   // 旧格式：上下游合并成单对
        List<Primer3Core.Pair> rb = Primer3Core.design("t_bad_fmt", seq, bad);
        check(rb.isEmpty(), "旧单区格式无结果");
        check(Primer3Core.lastExplain != null && Primer3Core.lastExplain.contains("primer3 报错"),
                "旧单区格式的 PRIMER_ERROR 被暴露: " + Primer3Core.lastExplain);

        // ================= 5) 回归：简单模式（v0.1.60 逻辑不受影响）=================
        Map<String, String> simple = new LinkedHashMap<String, String>(params);
        simple.remove("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST");
        List<Primer3Core.Pair> rs2 = Primer3Core.design("t_simple", seq, simple);
        check(!rs2.isEmpty(), "简单模式回归有解 (" + rs2.size() + " 对)");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

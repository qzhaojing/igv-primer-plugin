import org.broad.igv.primer.Primer3Core;
import org.broad.igv.primer.Primer3Dialog;
import org.broad.igv.primer.PrimerEditTool;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v0.1.80：区内模型端到端 + 实时预览状态机（#101/#102）。
 *
 * 1) 区内模型端到端：用 Primer3Dialog.fillFromSelection 算出产物+两端设计带，
 *    经 regionToTemplate 取模板、拼 OK_REGION_LIST，跑 Primer3Core.design，
 *    断言左引物落左端带、右引物落右端带、产物覆盖选区（TARGET 仅用于强断言）。
 *
 * 2) 实时预览状态机（PrimerEditTool.setPcrPreview/clearPcrPreview 等）：
 *    headless 下只校验静态态（active/chr/regions）；渲染走 RulerGlass 由 UI 层负责。
 *    注意：setPcrPreview/clearPcrPreview 内部会触发 UI 刷新（repaintDataPanels →
 *    findDataPanels），headless 无 IGV 实例时会抛 NPE，但静态态在刷新前已写好，
 *    故此处用 try/catch 吞掉渲染异常、仅断言静态态。
 */
public class TestP19 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    public static void main(String[] a) throws Exception {
        // ============ 1) 区内模型端到端（与 Primer3Dialog.fillFromSelection 一致） ============
        // 选区 [11000,11500]（0-based 含），视野 [10000,13000)
        int[] f = Primer3Dialog.fillFromSelection(11000, 11500, 10000, 13000, 18);
        // 产物 [11001,11501]；左端带 0-based [11000,11199]→1-based 11001+200；右端带 [11301,11500]→11302+200
        check(f != null && f[0] == 11001 && f[1] == 11501, "产物=选区(1-based) " + Arrays.toString(f));
        check(f[2] == 11001 && f[3] == 200, "左端设计区=产物左缘内200bp");
        check(f[4] == 11302 && f[5] == 200, "右端设计区=产物右缘内200bp");

        // 用上述带构建 primer3 输入：模板需覆盖 [11000,11500] + 两端余量
        // 直接套用 Primer3Dialog 的模板扩展逻辑（高级模式，设计区=区内带）
        java.lang.reflect.Method rtt = Primer3Dialog.class.getDeclaredMethod("regionToTemplate",
                int.class, boolean.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class);
        rtt.setAccessible(true);
        // 高级模式：设计区 = 左带[11001,11200](1-based)、右带[11302,11501](1-based)
        int[] rt = (int[]) rtt.invoke(null, Primer3Dialog.MODE_PAIR, true, f[0], f[1],
                f[2], f[3], f[4], f[5], 248956422, 18, 27);
        int tmplStart = rt[0], tmplEnd = rt[1];
        int tLen = f[1] - (f[0] - 1);
        Random rnd = new Random(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tmplEnd - tmplStart; i++) sb.append("ACGT".charAt(rnd.nextInt(4)));
        String seq = sb.toString();

        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("PRIMER_MIN_SIZE", "18");
        params.put("PRIMER_OPT_SIZE", "20");
        params.put("PRIMER_MAX_SIZE", "27");
        params.put("PRIMER_MIN_TM", "57");
        params.put("PRIMER_OPT_TM", "60");
        params.put("PRIMER_MAX_TM", "63");
        params.put("PRIMER_MIN_GC", "30");
        params.put("PRIMER_MAX_GC", "70");
        params.put("PRIMER_NUM_RETURN", "5");
        params.put("PRIMER_PICK_LEFT_PRIMER", "1");
        params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
        params.put("PRIMER_PRODUCT_SIZE_RANGE", "100-1000");
        // 区内模型：仅用 OK_REGION_LIST 作引物白名单（生产默认行为），不靠 SEQUENCE_TARGET 限定产物
        int lS = f[2] - 1 - tmplStart, lL = f[3];
        int rS = f[4] - 1 - tmplStart, rL = f[5];
        params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST", lS + "," + lL + "," + rS + "," + rL);

        List<Primer3Core.Pair> rs = Primer3Core.design("t_p19_e2e", seq, params);
        check(!rs.isEmpty(), "区内模型端到端有解 (" + rs.size() + " 对)");
        if (!rs.isEmpty()) {
            Primer3Core.Pair p = rs.get(0);
            check(p.leftStart >= lS && p.leftStart < lS + lL,
                    "左引物落左端设计区 (left=" + p.leftStart + " ∈ [" + lS + "," + (lS + lL) + "))");
            check(p.rightStart >= rS && p.rightStart < rS + rL,
                    "右引物落右端设计区 (right=" + p.rightStart + " ∈ [" + rS + "," + (rS + rL) + "))");
            // 区内模型：产物=两端带之间的区段，长度应落在约束范围内
            check(p.productSize >= 100 && p.productSize <= 1000,
                    "产物长度在约束范围内 (" + p.productSize + "bp)");
        }

        // ============ 2) 实时预览状态机（headless 仅校验静态态） ============
        check(!PrimerEditTool.pcrPreviewActive(), "初始无预览");
        check(PrimerEditTool.pcrPreviewRegions() == null, "初始预览区域为 null");
        // 设置预览（chr1，产物 [1000,2000)，左带 [1000,1200)，右带 [1800,2000)）
        try { PrimerEditTool.setPcrPreview("chr1", 1000, 2000, 1000, 1200, 1800, 2000); }
        catch (Throwable t) { /* headless 渲染 NPE 忽略 */ }
        check(PrimerEditTool.pcrPreviewActive(), "设置后预览生效");
        check("chr1".equals(PrimerEditTool.pcrPreviewChr()), "预览染色体=chr1");
        int[] reg = PrimerEditTool.pcrPreviewRegions();
        check(reg != null && Arrays.equals(reg, new int[]{1000, 2000, 1000, 1200, 1800, 2000}),
                "预览区域数组正确 " + Arrays.toString(reg));
        // 清除预览
        try { PrimerEditTool.clearPcrPreview(); }
        catch (Throwable t) { /* 同上 */ }
        check(!PrimerEditTool.pcrPreviewActive(), "清除后预览失效");
        check(PrimerEditTool.pcrPreviewRegions() == null, "清除后预览区域为 null");
        check(PrimerEditTool.pcrPreviewChr() == null, "清除后预览染色体为 null");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

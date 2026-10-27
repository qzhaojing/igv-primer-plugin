import org.broad.igv.primer.Primer;
import org.broad.igv.primer.Primer3Dialog;
import org.broad.igv.primer.PrimerStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v0.1.65：同序列引物去重 + 1-F-多-R 结构保护 + 视口子集填充 + Shift 选区入口。
 *
 * 老板截图实锤（v0.1.64 设计结果表）：5 对结果里 P1/P2/P3/P5 共用同一条 F
 * （序列全同 CCAACAGTGTGGCGGTGATA、位置全同 2290578-2290597）——直接加入会产生
 * 4 条冗余引物（BED 多条重复；P5_F 与 P1_F 同位同序列，悬浮提示命中查找返回
 * 第一条 → 停 P5_F 显示 P1_F 信息）。
 *
 * 本测试覆盖：
 *  1) mergeDuplicatePrimers：复刻老板数据形状 → 合并 3 条重复 F、保留 5 条、
 *     被合并者的配对边重连到保留者、addAll 后边对称、幂等、跨分组不误并；
 *  2) repairNumberedPairs 新语义（v0.1.65）：1-F-多-R 合法结构不动（旧语义会错修）、
 *     同编号未互连仍修复、单侧不动；
 *  3) fillFromViewport：产物=视野子集（内缩 margin）、区内设计区=产物两端内侧 200bp、
 *     全钳视野内、窄视野退化；fillFromSelection 5 参兼容（100/200）。
 */
public class TestP7 {
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

    static Primer byName(List<Primer> list, String name) {
        for (Primer p : list) if (p.name.equals(name)) return p;
        return null;
    }

    public static void main(String[] a) throws Exception {
        PrimerStore.autosaveEnabled = false;   // 测试不写盘

        // ============ 1) mergeDuplicatePrimers：老板截图数据形状 ============
        PrimerStore.clear();
        // store 先有首次加入的 P1_F/P1_R（P1_F = 被 4 个产物共用的那条）
        Primer f1 = mk("primer3_P1_F", 2290578, 2290597, '+'); f1.seq = "CCAACAGTGTGGCGGTGATA";
        Primer r1 = mk("primer3_P1_R", 2290878, 2290896, '-'); r1.seq = "ATGGTGTTCATCCACGTGG";
        f1.pairedWith.add("primer3_P1_R"); r1.pairedWith.add("primer3_P1_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(f1, r1)));

        // 第二批（toPrimers 输出形状）：P2/P3/P5_F 与 P1_F 同序列同位，P4_F 序列不同
        List<Primer> batch = new ArrayList<Primer>();
        String[][] defs = {
                {"primer3_P2_F", "primer3_P2_R", "CAACCCCCGATATACGACGC"},
                {"primer3_P3_F", "primer3_P3_R", "GGCAACCCCGATATACGACGG"},
                {"primer3_P4_F", "primer3_P4_R", "TTGCCGGTAATGGTTGTCAC"},
                {"primer3_P5_F", "primer3_P5_R", "GCTGCCGGTAATGGTTGTCA"},
        };
        for (String[] d : defs) {
            Primer f = mk(d[0], 2290578, 2290597, '+');
            f.seq = d[0].equals("primer3_P4_F") ? "ACAGTGGTGGCGGTGATATGC" : "CCAACAGTGTGGCGGTGATA";
            Primer r = mk(d[1], 2290878, 2290896, '-');
            r.seq = d[2];
            f.pairedWith.add(d[1]); r.pairedWith.add(d[0]);
            batch.add(f); batch.add(r);
        }
        int merged = PrimerStore.mergeDuplicatePrimers(batch, "primer3");
        check(merged == 3, "合并 3 条重复 F (P2/P3/P5) (merged=" + merged + ")");
        check(batch.size() == 5, "批次剩 5 条 (P4_F + 4R) (" + batch.size() + ")");
        check(byName(batch, "primer3_P4_F") != null, "P4_F（序列不同）保留");
        for (String rn : new String[]{"primer3_P2_R", "primer3_P3_R", "primer3_P5_R"}) {
            Primer r = byName(batch, rn);
            check(r != null && partners(r).equals(Arrays.asList("primer3_P1_F")),
                    rn + " 边重连到 P1_F " + (r == null ? "null" : partners(r)));
        }
        Primer p4f = byName(batch, "primer3_P4_F");
        check(p4f != null && partners(p4f).equals(Arrays.asList("primer3_P4_R")),
                "P4_F 配对不变 " + (p4f == null ? "null" : partners(p4f)));
        // addAll 后 linkByName 补齐对称边 → P1_F 连 4 条 R（1-F-多-R 合法结构）
        PrimerStore.addAll(batch);
        check(partners(f1).containsAll(Arrays.asList(
                        "primer3_P1_R", "primer3_P2_R", "primer3_P3_R", "primer3_P5_R")),
                "P1_F 连 4 条 R（1-F-多-R） " + partners(f1));
        // 幂等：对已入 store 的同批次再调，merged=0 且批次不动（同对象保留）
        check(PrimerStore.mergeDuplicatePrimers(batch, "primer3") == 0 && batch.size() == 5,
                "幂等：merged=0 且批次不动 (" + batch.size() + ")");
        // 与 store 已有引物同指纹 → 合并（重复打开设计结果对话框防叠加）
        List<Primer> dup = new ArrayList<Primer>();
        Primer f1b = mk("primer3_P9_F", 2290578, 2290597, '+'); f1b.seq = "CCAACAGTGTGGCGGTGATA";
        dup.add(f1b);
        check(PrimerStore.mergeDuplicatePrimers(dup, "primer3") == 1 && dup.isEmpty(),
                "与已有引物同序列同位置 → 合并丢弃");
        // 跨分组不误并（同坐标不同 group 是不同轨的引物）
        PrimerStore.clear();
        Primer x = mk("other_F", 100, 120, '+'); x.seq = "AAAA"; x.group = "other";
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(x)));
        List<Primer> same = new ArrayList<Primer>();
        Primer y = mk("primer3_P1_F", 100, 120, '+'); y.seq = "AAAA";   // group=primer3（mk 默认）
        same.add(y);
        check(PrimerStore.mergeDuplicatePrimers(same, "primer3") == 0 && same.size() == 1,
                "跨分组同坐标不合并");

        // ============ 2) repairNumberedPairs 新语义 ============
        // 2a) 1-F-多-R 合法结构（去重后形状）：不动
        PrimerStore.clear();
        Primer f = mk("primer3_P1_F", 2290578, 2290597, '+'); f.seq = "CCAACAGTGTGGCGGTGATA";
        Primer q1 = mk("primer3_P1_R", 2290878, 2290896, '-');
        Primer q2 = mk("primer3_P2_R", 2290879, 2290897, '-');
        Primer q3 = mk("primer3_P3_R", 2290880, 2290898, '-');
        f.pairedWith.add("primer3_P1_R"); q1.pairedWith.add("primer3_P1_F");
        f.pairedWith.add("primer3_P2_R"); q2.pairedWith.add("primer3_P1_F");
        f.pairedWith.add("primer3_P3_R"); q3.pairedWith.add("primer3_P1_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(f, q1, q2, q3)));
        int fixed0 = PrimerStore.repairNumberedPairs("primer3");
        check(fixed0 == 0, "1-F-多-R 合法结构不动 (fixed=0)");
        check(partners(f).size() == 3, "P1_F 仍连 3 条 R " + partners(f));
        check(partners(q2).equals(Arrays.asList("primer3_P1_F")), "P2_R 仍连 P1_F " + partners(q2));

        // 2b) 同编号未互连（误连形状）：仍修复。用 P4 编号（避开 2a 已有的 P1/P2/P3）
        Primer g4 = mk("primer3_P4_F", 2291813, 2291833, '+');
        Primer g4r = mk("primer3_P4_R", 2292592, 2292610, '-');
        g4.pairedWith.add("primer3_P1_R"); q1.pairedWith.add("primer3_P4_F");
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(g4, g4r)));
        int fixed1 = PrimerStore.repairNumberedPairs("primer3");
        check(fixed1 == 1, "同编号未互连仍修复 (fixed=1)");
        check(partners(g4).equals(Arrays.asList("primer3_P4_R")), "P4_F 重建为只连 P4_R " + partners(g4));
        check(partners(g4r).equals(Arrays.asList("primer3_P4_F")), "P4_R 重建为只连 P4_F " + partners(g4r));
        check(!partners(q1).contains("primer3_P4_F"), "P1_R 错边已清 " + partners(q1));
        check(partners(q1).contains("primer3_P1_F"), "P1_R 与 P1_F 原边保留 " + partners(q1));

        // 2c) 单侧编号（单引物设计结果）不动
        PrimerStore.clear();
        Primer solo = mk("primer3_P7_F", 2290578, 2290597, '+');
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(solo)));
        check(PrimerStore.repairNumberedPairs("primer3") == 0, "单侧编号不动");
        check(partners(solo).isEmpty(), "单引物无配对");

        // ============ 3) fillFromViewport / fillFromSelection（区内模型） ============
        // 视野 [10000, 20000)（1-based 10001-20000），margin=200 → 产物内缩200，设计区=产物两端内侧200bp
        int[] v = Primer3Dialog.fillFromViewport(10000, 20000, 18, 200, 100, 200);
        // ps=10200, pe=19799, L=9600, band=200 → 产物[10201,19800], 左带[10201,10400], 右带[19601,19800]
        check(v[0] == 10201 && v[1] == 19800, "产物=视野内缩200 " + v[0] + "-" + v[1]);
        check(v[2] == 10201 && v[3] == 200, "左端设计区=产物左缘内200bp (起10201 长200)");
        check(v[4] == 19601 && v[5] == 200, "右端设计区=产物右缘内200bp (起19601 长200)");
        check(v[2] >= 10001 && v[4] + v[5] - 1 <= 20000, "产物+区内设计区全部在屏幕内");
        // margin=150 → 产物 [10151,19850]，设计区=产物两端内侧200bp
        int[] v2 = Primer3Dialog.fillFromViewport(10000, 20000, 18, 150, 100, 200);
        check(v2[0] == 10151 && v2[1] == 19850, "margin150 产物 " + v2[0] + "-" + v2[1]);
        check(v2[2] == 10151 && v2[3] == 200, "margin150 左端设计区=产物左缘内200bp");
        check(v2[4] == 19651 && v2[5] == 200, "margin150 右端设计区=产物右缘内200bp");
        check(v2[2] >= 10001 && v2[4] + v2[5] - 1 <= 20000, "区内设计区仍在屏幕内");
        // 窄视野（100bp）+margin=150 → 产物退化取中段 minLen，设计区=产物两端内侧（相遇）
        int[] n = Primer3Dialog.fillFromViewport(10000, 10100, 18, 150, 100, 200);
        check(n[1] - n[0] + 1 >= 18, "窄视野产物 ≥18bp " + n[0] + "-" + n[1]);
        check(n[3] >= 18 && n[5] >= 18, "窄视野两端设计区 ≥18bp (" + n[3] + "/" + n[5] + ")");
        // fillFromSelection 5 参兼容（默认 100/200）→ 设计区=选区两端内侧200bp
        int[] s = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18);
        check(s[0] == 10001 && s[1] == 10401, "选区=产物(401bp) " + s[0] + "-" + s[1]);
        check(s[2] == 10001 && s[3] == 200, "左端设计区=选区左缘内200bp (起10001 长200)");
        check(s[4] == 10202 && s[5] == 200, "右端设计区=选区右缘内200bp (起10202 长200)");
        check(Primer3Dialog.fillFromSelection(5000, 5100, 9000, 11000, 18) == null,
                "屏外选区 → null（回退视口）");

        // ============ 4) Shift 选区入口（headless 空转安全） ============
        PrimerStore.clear();
        PrimerEditToolProbe.ensure();   // ensureShiftSelectors 无面板空转不异常
        check(PrimerEditToolProbe.currentSelection() == null, "无选区 → null");
        check(PrimerEditToolProbe.rulerActive() == false, "无 glass 时 rulerActive=false");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    /** 反射包：避免测试类直接依赖 internal 可见性细节（保持与 TestP6 一致风格）。 */
    static class PrimerEditToolProbe {
        static void ensure() {
            try { org.broad.igv.primer.PrimerEditTool.ensureShiftSelectors(); }
            catch (Throwable t) { throw new RuntimeException(t); }
        }
        static String[] currentSelection() {
            return org.broad.igv.primer.PrimerEditTool.currentSelection();
        }
        static boolean rulerActive() {
            return org.broad.igv.primer.PrimerEditTool.rulerActive();
        }
    }
}

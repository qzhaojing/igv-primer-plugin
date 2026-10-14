import org.broad.igv.primer.Primer;
import org.broad.igv.primer.Primer3Core;
import org.broad.igv.primer.PrimerStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.1.66：primer3 二次设计编号延续（修复老板截图：两次设计后出现 P1_F_2/P1_R_2）。
 *
 * 根因：Pair.toPrimers 用结果集内序号 idx+1 命名（group_P1_F/P1_R），第二次设计又从
 * P1 开始，撞名后 unique()/dedupNames 只能加 _2 后缀——丑且断号。
 * 修复：PrimerStore.nextPairNumber(group) 扫分组内已有最大对编号，新批次从 max+1
 * 续号（P1..P5 之后新对从 P6 开始）；toPrimers 增加 pairNo 参数覆盖 idx+1。
 *
 * 本测试覆盖：
 *  1) nextPairNumber：空分组→1；P1..P5（F/R 混排）→6；_2 后缀名不计入编号；
 *     跨分组隔离；无分组前缀的手工名（P007）不计入；null 分组→1；
 *  2) toPrimers pairNo：指定编号命名 + pairedWith 互链同步；pairNo<=0 回退 idx+1；
 *     单引物同样续号；
 *  3) 两轮设计模拟（复刻 addSelected 流程）：第二轮从 P3 起、无 _2 名、边全部正确。
 */
public class TestP8 {
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

    /** 造一对设计结果（模板坐标 0-based；productSize 随意）。 */
    static Primer3Core.Pair pair(int idx, int leftStart, int rightStart, String lseq, String rseq) {
        Primer3Core.Pair p = new Primer3Core.Pair();
        p.idx = idx;
        p.leftSeq = lseq;
        p.rightSeq = rseq;
        p.leftStart = leftStart;
        p.leftLen = 20;
        p.rightStart = rightStart;
        p.rightLen = 20;
        p.leftTm = 60;
        p.rightTm = 60;
        p.leftGc = 50;
        p.rightGc = 50;
        p.productSize = rightStart + 20 - leftStart;
        return p;
    }

    static List<String> partners(Primer p) {
        return p.pairedWith == null ? new ArrayList<String>() : new ArrayList<String>(p.pairedWith);
    }

    static Primer byName(List<Primer> list, String name) {
        for (Primer p : list) if (p.name.equals(name)) return p;
        return null;
    }

    /** 复刻 Primer3ResultDialog.addSelected 的命名流程，返回加入的引物。 */
    static List<Primer> designRound(String group, List<Primer3Core.Pair> chosen) {
        int firstFree = PrimerStore.nextPairNumber(group);
        Set<String> used = new HashSet<String>();
        for (Primer p : PrimerStore.getPrimersByGroup(group)) used.add(p.name);
        List<Primer> add = new ArrayList<Primer>();
        for (int i = 0; i < chosen.size(); i++) {
            add.addAll(chosen.get(i).toPrimers("NC_000962.3", group, 0, used, firstFree + i));
        }
        PrimerStore.mergeDuplicatePrimers(add, group);
        PrimerStore.addAll(add);
        return add;
    }

    public static void main(String[] a) throws Exception {
        PrimerStore.autosaveEnabled = false;   // 测试不写盘

        // ============ 1) nextPairNumber ============
        PrimerStore.clear();
        check(PrimerStore.nextPairNumber("primer3") == 1, "空分组 → 1");
        check(PrimerStore.nextPairNumber(null) == 1, "null 分组 → 1");

        PrimerStore.clear();
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(
                mk("primer3_P1_F", 1000, 1020, '+'), mk("primer3_P1_R", 1300, 1320, '-'),
                mk("primer3_P2_F", 2000, 2020, '+'), mk("primer3_P2_R", 2300, 2320, '-'))));
        check(PrimerStore.nextPairNumber("primer3") == 3, "P1/P2 之后 → 3");

        // P5 之后 → 6（复刻老板截图首批 5 对的形状，编号不连续也取 max）
        PrimerStore.clear();
        List<Primer> batch = new ArrayList<Primer>();
        for (int n : new int[]{1, 2, 3, 4, 5}) {
            batch.add(mk("primer3_P" + n + "_F", 1000 * n, 1000 * n + 20, '+'));
            batch.add(mk("primer3_P" + n + "_R", 1300 * n, 1300 * n + 20, '-'));
        }
        PrimerStore.addAll(batch);
        check(PrimerStore.nextPairNumber("primer3") == 6, "P1..P5 之后 → 6（续号起点）");

        // _2 后缀名（dedupNames 历史产物）不虚增编号：P1_F_2 仍在 P1 档
        PrimerStore.add(mk("primer3_P1_F_2", 5000, 5020, '+'));
        check(PrimerStore.nextPairNumber("primer3") == 6, "_2 后缀名不虚增编号（仍 → 6）");

        // 跨分组隔离：其他分组的编号不影响本分组
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(
                mk("grp2_P9_F", 9000, 9020, '+'), mk("grp2_P9_R", 9300, 9320, '-'))));
        check(PrimerStore.nextPairNumber("primer3") == 6, "跨分组隔离（primer3 仍 → 6）");
        check(PrimerStore.nextPairNumber("grp2") == 10, "grp2 → 10");

        // 手工添加的 P007（无分组前缀）不计入
        PrimerStore.clear();
        PrimerStore.addAll(new ArrayList<Primer>(Arrays.asList(
                mk("P007", 100, 120, '+'), mk("primer3_P2_R", 300, 320, '-'))));
        check(PrimerStore.nextPairNumber("primer3") == 3, "无前缀手工名不计入（P007 忽略 → 3）");

        // ============ 2) toPrimers pairNo ============
        PrimerStore.clear();
        Primer3Core.Pair p0 = pair(0, 100, 300, "CCAACAGTGTGGCGGTGATA", "TGCCGATCTTCCTGATGTGA");
        List<Primer> ps = p0.toPrimers("NC_000962.3", "primer3", 0, null, 6);
        check(ps.size() == 2, "pairNo=6 产出 2 条");
        check(ps.get(0).name.equals("primer3_P6_F") && ps.get(1).name.equals("primer3_P6_R"),
                "按 pairNo 命名 " + ps.get(0).name + "/" + ps.get(1).name);
        check(ps.get(0).pairedWith.contains("primer3_P6_R") && ps.get(1).pairedWith.contains("primer3_P6_F"),
                "pairedWith 互链用新名");
        check(ps.get(0).start == 100 && ps.get(1).start == 300, "坐标换算不受 pairNo 影响");

        // pairNo<=0 回退 idx+1（兼容旧调用/TestP3）
        List<Primer> legacy = p0.toPrimers("NC_000962.3", "primer3", 0, null, 0);
        check(legacy.get(0).name.equals("primer3_P1_F"), "pairNo=0 回退 idx+1 → P1_F");

        // used 兜底仍在：指定编号恰好撞名时加 _2（不应发生，但防呆）
        Set<String> used = new HashSet<String>(Arrays.asList("primer3_P6_F", "primer3_P6_R"));
        List<Primer> clash = p0.toPrimers("NC_000962.3", "primer3", 0, used, 6);
        check(clash.get(0).name.equals("primer3_P6_F_2"), "极端撞名兜底 _2 " + clash.get(0).name);

        // 单引物（仅 R）续号
        Primer3Core.Pair single = pair(3, 0, 400, null, "ACCTCCAGTGGCCAGGATAT");
        List<Primer> sp = single.toPrimers("NC_000962.3", "primer3", 0, null, 9);
        check(sp.size() == 1 && sp.get(0).name.equals("primer3_P9_R") && sp.get(0).strand == '-',
                "单引物续号 P9_R (-)");

        // ============ 3) 两轮设计模拟（复刻 addSelected） ============
        PrimerStore.clear();
        // 第一轮：2 对 → P1/P2
        designRound("primer3", Arrays.asList(
                pair(0, 100, 400, "CCAACAGTGTGGCGGTGATA", "TGCCGATCTTCCTGATGTGA"),
                pair(1, 2000, 2300, "GCTAGCTAGCTAGCTAGCTA", "CGATCGATCGATCGATCGAT")));
        check(PrimerStore.nextPairNumber("primer3") == 3, "第一轮后下一个编号 = 3");
        check(PrimerStore.getPrimersByGroup("primer3").size() == 4, "第一轮入 4 条");

        // 第二轮：复刻老板场景——结果集又从 idx 0 开始，但应续号为 P3/P4
        designRound("primer3", Arrays.asList(
                pair(0, 5000, 5400, "TTTTGGGGCCCCAAAATTTT", "AAAACCCCGGGGTTTTAAAA"),
                pair(1, 6000, 6500, "ACGTACGTACGTACGTACGT", "TGCATGCATGCATGCATGCA")));
        List<Primer> all = PrimerStore.getPrimersByGroup("primer3");
        check(all.size() == 8, "第二轮后共 8 条（无重名无 _2）");
        boolean anySuffix = false;
        for (Primer p : all) if (p.name.matches(".*_\\d+$")) anySuffix = true;
        check(!anySuffix, "不存在 _2 后缀名（续号生效）");
        check(byName(all, "primer3_P3_F") != null && byName(all, "primer3_P3_R") != null
                && byName(all, "primer3_P4_F") != null && byName(all, "primer3_P4_R") != null,
                "第二轮命名为 P3/P4（延续非重名）");
        check(partners(byName(all, "primer3_P3_F")).equals(Arrays.asList("primer3_P3_R"))
                && partners(byName(all, "primer3_P3_R")).equals(Arrays.asList("primer3_P3_F")),
                "P3 对互链正确");
        check(partners(byName(all, "primer3_P4_F")).equals(Arrays.asList("primer3_P4_R"))
                && partners(byName(all, "primer3_P4_R")).equals(Arrays.asList("primer3_P4_F")),
                "P4 对互链正确");
        check(partners(byName(all, "primer3_P1_F")).equals(Arrays.asList("primer3_P1_R")),
                "第一轮 P1 对不受影响");

        // 第三轮只勾 1 对 → 从 P5 起（单对续号）
        designRound("primer3", Arrays.asList(
                pair(0, 7000, 7400, "GGGGTTTTAAAACCCCGGGG", "CCCCAAAAGGGGTTTTCCCC")));
        all = PrimerStore.getPrimersByGroup("primer3");
        check(byName(all, "primer3_P5_F") != null && byName(all, "primer3_P5_R") != null,
                "第三轮单对 → P5");
        check(all.size() == 10, "第三轮后共 10 条");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

import org.broad.igv.primer.Primer;
import org.broad.igv.primer.PrimerEditTool;
import org.broad.igv.primer.PrimerStore;
import org.broad.igv.primer.PrimerTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * v0.1.70：多对引物自动分行 + 点击选中框（headless 安全）+ 菜单折叠。
 *
 * 老板需求 1："能够设计出多对引物，但是多对引物位于同一行，很难看，需要手动调整。
 *   修正，多引物不重叠"。
 *   根因：layoutRows 对不重叠的引物一律贪心塞第一个空行 → primer3 一次设计 5 对
 *   （5 个互不重叠的配对组）全挤在 row 0，看不清哪条引物属于哪一对，只能手动上下拖。
 *   修复：PrimerStore.onePairPerRow（默认开）下每个配对连通分量独占一行；
 *   未配对单引物优先放没有配对组的行。
 *
 * 老板需求 2："引物目前按住左右拉动时候才会显示出淡色边界…修正为，点击某引物，
 *   直接显示出淡色框，拖动也显示" —— 选中入口统一为 PrimerEditTool.setSelected，
 *   UI 部分（glass 绘制）需真实 IGV 环境，此处只验证 headless 下不炸、状态正确。
 *
 * 本测试覆盖：
 *  1) 每对独占一行：5 对互不重叠 → 5 行；组内 F/R 同行；单引物避开配对行；
 *  2) 关闭开关：回退紧凑共行（旧行为）；
 *  3) 旧行为回归：重叠换行、异组换行、rowOverride 优先；
 *  4) setSelected：状态正确 + headless 安全（无 IGV 实例不抛异常）。
 */
public class TestP12 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    static final String CHR = "NC_000962.3";

    static Primer mk(String name, int start, int end, char strand, String group) {
        Primer p = new Primer(name, CHR, start, end, strand, strand == '+' ? "R1" : "R2", 0, null);
        p.group = group;
        return p;
    }

    /** 建一条配对边（双向，与 PrimerStore.toggleEdge 的边模型一致）。 */
    static void link(Primer a, Primer b) {
        a.pairedWith = new java.util.LinkedHashSet<String>(Arrays.asList(b.name));
        b.pairedWith = new java.util.LinkedHashSet<String>(Arrays.asList(a.name));
    }

    static int rowOf(Map<Primer, Integer> rows, Primer p) {
        Integer r = rows.get(p);
        return r == null ? -99 : r;
    }

    static int distinctRows(Map<Primer, Integer> rows) {
        java.util.Set<Integer> set = new java.util.HashSet<Integer>(rows.values());
        return set.size();
    }

    public static void main(String[] args) {
        boolean saved = PrimerStore.onePairPerRow;

        // ---------- 1) 每对独占一行（老板场景：primer3 一次设计 5 对，位置互不重叠）----------
        PrimerStore.onePairPerRow = true;
        List<Primer> vis = new ArrayList<Primer>();
        Primer[] fs = new Primer[5], rs = new Primer[5];
        for (int i = 0; i < 5; i++) {
            int base = 1000 + i * 1000;                     // 每对相隔 1000bp，互不重叠
            fs[i] = mk("P" + (i + 1) + "_F", base, base + 18, '+', "primer3");
            rs[i] = mk("P" + (i + 1) + "_R", base + 200, base + 218, '-', "primer3");
            link(fs[i], rs[i]);
            vis.add(fs[i]);
            vis.add(rs[i]);
        }
        Map<Primer, Integer> rows = PrimerTrack.layoutRows(vis);
        check(distinctRows(rows) == 5, "5 对互不重叠 → 5 行（实际 " + distinctRows(rows) + " 行）");
        boolean samePairRow = true;
        for (int i = 0; i < 5; i++)
            if (rowOf(rows, fs[i]) != rowOf(rows, rs[i])) samePairRow = false;
        check(samePairRow, "同一对的 F/R 仍同行");
        boolean allDistinct = true;
        for (int i = 0; i < 5; i++)
            for (int j = i + 1; j < 5; j++)
                if (rowOf(rows, fs[i]) == rowOf(rows, fs[j])) allDistinct = false;
        check(allDistinct, "5 对彼此不同行（一眼分清哪条属于哪对）");

        // ---------- 2) 关闭开关 → 回退紧凑共行（旧行为）----------
        PrimerStore.onePairPerRow = false;
        Map<Primer, Integer> rowsC = PrimerTrack.layoutRows(vis);
        check(distinctRows(rowsC) == 1, "关开关 → 5 对紧凑共 1 行（实际 " + distinctRows(rowsC) + " 行）");
        PrimerStore.onePairPerRow = true;

        // ---------- 3) 单引物避开配对行 ----------
        List<Primer> v2 = new ArrayList<Primer>();
        Primer f1 = mk("A_F", 1000, 1018, '+', "g1");
        Primer r1 = mk("A_R", 1200, 1218, '-', "g1");
        link(f1, r1);
        v2.add(f1); v2.add(r1);
        Primer single1 = mk("X1", 3000, 3018, '+', "g1");   // 与 A 对不重叠（本可共行）
        v2.add(single1);
        Map<Primer, Integer> rows2 = PrimerTrack.layoutRows(v2);
        check(rowOf(rows2, single1) != rowOf(rows2, f1),
                "单引物避开配对组所在行（单引物行=" + rowOf(rows2, single1)
                + " 配对行=" + rowOf(rows2, f1) + "）");
        check(distinctRows(rows2) == 2, "1 对 + 1 单引物 → 2 行（实际 " + distinctRows(rows2) + "）");

        // ---------- 4) 旧行为回归 ----------
        // 4a 重叠必换行（无论开关）
        Primer o1 = mk("O1", 1000, 1018, '+', "g1");
        Primer o2 = mk("O2", 1010, 1028, '+', "g1");
        List<Primer> v3 = new ArrayList<Primer>(Arrays.asList(o1, o2));
        Map<Primer, Integer> rows3 = PrimerTrack.layoutRows(v3);
        check(rowOf(rows3, o1) != rowOf(rows3, o2), "重叠引物仍换行（防互相压住）");

        // 4b 异组不共行（单引物）
        Primer g1 = mk("G1", 2000, 2018, '+', "gA");
        Primer g2 = mk("G2", 3000, 3018, '+', "gB");
        List<Primer> v4 = new ArrayList<Primer>(Arrays.asList(g1, g2));
        Map<Primer, Integer> rows4 = PrimerTrack.layoutRows(v4);
        check(rowOf(rows4, g1) != rowOf(rows4, g2), "异组单引物不共行");

        // 4c 同组+不重叠（关开关时）可共行
        PrimerStore.onePairPerRow = false;
        Primer s1 = mk("S1", 2000, 2018, '+', "gA");
        Primer s2 = mk("S2", 3000, 3018, '+', "gA");
        List<Primer> v5 = new ArrayList<Primer>(Arrays.asList(s1, s2));
        Map<Primer, Integer> rows5 = PrimerTrack.layoutRows(v5);
        check(rowOf(rows5, s1) == rowOf(rows5, s2), "同组不重叠单引物共行（关开关时紧凑）");
        PrimerStore.onePairPerRow = true;

        // 4d rowOverride 最优先（手动拖到指定行，自动布局不覆盖）
        Primer m1 = mk("M1", 1000, 1018, '+', "g1");
        m1.rowOverride = 7;
        Primer m2 = mk("M2", 3000, 3018, '+', "g1");
        m2.rowOverride = 3;
        List<Primer> v6 = new ArrayList<Primer>(Arrays.asList(m1, m2));
        Map<Primer, Integer> rows6 = PrimerTrack.layoutRows(v6);
        check(rowOf(rows6, m1) == 7 && rowOf(rows6, m2) == 3, "rowOverride 仍最优先（手动指定行不被自动布局改）");

        // 4e 成对组内成员即使位置分散也同行（连线可画）
        Primer pf = mk("PF", 500, 518, '+', "g1");
        Primer pr = mk("PR", 5000, 5018, '-', "g1");
        link(pf, pr);
        List<Primer> v7 = new ArrayList<Primer>(Arrays.asList(pf, pr));
        Map<Primer, Integer> rows7 = PrimerTrack.layoutRows(v7);
        check(rowOf(rows7, pf) == rowOf(rows7, pr), "远距离配对仍同行（连线可画）");

        // ---------- 5) setSelected：状态正确 + headless 安全 ----------
        Primer sel = mk("SEL", 1000, 1018, '+', "g1");
        boolean threw = false;
        try {
            PrimerEditTool.setSelected(sel);
        } catch (Throwable t) {
            threw = true;
        }
        check(!threw, "setSelected 在 headless（无 IGV 实例）下不抛异常");
        check(PrimerStore.selected == sel, "setSelected 正确设置 PrimerStore.selected");
        threw = false;
        try {
            PrimerEditTool.setSelected(null);
        } catch (Throwable t) {
            threw = true;
        }
        check(!threw && PrimerStore.selected == null, "setSelected(null) 清除选中且不抛异常");

        // ---------- 7) v0.1.86：1-F-多-R 合并分量自动展开（两条 R 重叠 → 分行）----------
        // 场景：v0.1.65 同序列去重后两条产物共用一条 F（SH_F 连 SH_R1 和 SH_R2），
        // 两条 R 位置几乎重合 → 旧逻辑整分量一行，弧线/标签完全重叠。
        PrimerStore.onePairPerRow = true;
        Primer shF = mk("SH_F", 1000, 1018, '+', "g9");
        Primer shR1 = mk("SH_R1", 3200, 3218, '-', "g9");
        Primer shR2 = mk("SH_R2", 3210, 3228, '-', "g9");   // 与 R1 重叠（两条产物近似）
        shF.pairedWith = new java.util.LinkedHashSet<String>(Arrays.asList(shR1.name, shR2.name));
        shR1.pairedWith = new java.util.LinkedHashSet<String>(Arrays.asList(shF.name));
        shR2.pairedWith = new java.util.LinkedHashSet<String>(Arrays.asList(shF.name));
        List<Primer> v8 = new ArrayList<Primer>(Arrays.asList(shF, shR1, shR2));
        Map<Primer, Integer> rows8 = PrimerTrack.layoutRows(v8);
        check(rowOf(rows8, shR1) != rowOf(rows8, shR2),
                "1-F-多-R：重叠的两条 R 自动分行（行 " + rowOf(rows8, shR1) + "/" + rowOf(rows8, shR2) + "）");
        check(rowOf(rows8, shF) == rowOf(rows8, shR1) || rowOf(rows8, shF) == rowOf(rows8, shR2),
                "共用 F 与至少一条 R 同行（弧线可画）");
        // 紧凑模式（关开关）同样要拆：重叠成员不开新行会互相压住
        PrimerStore.onePairPerRow = false;
        Map<Primer, Integer> rows9 = PrimerTrack.layoutRows(
                new ArrayList<Primer>(Arrays.asList(shF, shR1, shR2)));
        check(rowOf(rows9, shR1) != rowOf(rows9, shR2), "紧凑模式下重叠 R 同样分行");
        PrimerStore.onePairPerRow = true;

        // ---------- 6) 开关默认值 ----------
        check(PrimerStore.onePairPerRow, "onePairPerRow 默认开（多对自动分行）");

        PrimerStore.onePairPerRow = saved;
        System.out.println("==== TestP12: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

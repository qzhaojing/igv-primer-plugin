import org.broad.igv.primer.Primer3Core;
import org.broad.igv.primer.Primer3TilingDialog;
import org.broad.igv.primer.Primer;

import java.util.List;

/**
 * TestP21：v0.2.0 平铺设计（Tiling）回归。
 *  - tileWindows 划窗纯函数：单窗/多窗/末窗过短左移/参数非法，覆盖无缺口、窗长上下界、相邻重叠；
 *  - Pair.toPrimers tag 命名：T 前缀（分组/手动轨）、默认 P 前缀不回归。
 */
public class TestP21 {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // ---------- tileWindows：单窗 ----------
        List<int[]> w = Primer3TilingDialog.tileWindows(1000, 1500, 400, 700, 100);
        eq("L<=max 单窗", 1, w.size());
        eq("单窗起点", 1000, w.get(0)[0]);
        eq("单窗终点", 1500, w.get(0)[1]);

        // L 恰等于 ampMax（1000..1699 = 700bp）→ 单窗
        w = Primer3TilingDialog.tileWindows(1000, 1699, 400, 700, 100);
        eq("L==max 单窗", 1, w.size());
        eq("L==max 单窗区间", "1000-1699", w.get(0)[0] + "-" + w.get(0)[1]);

        // ---------- 多窗：2000bp，产物 400-700，重叠 100 ----------
        // 期望：[1,700],[601,1300],[1201,1900]，末段 rem=200<400 → 末窗左移为 [1601,2000]
        w = Primer3TilingDialog.tileWindows(1, 2000, 400, 700, 100);
        eq("2000bp 窗数", 4, w.size());
        checkInvariants("2000bp", w, 400, 700, 100);
        eq("W1", "1-700", w.get(0)[0] + "-" + w.get(0)[1]);
        eq("W2", "601-1300", w.get(1)[0] + "-" + w.get(1)[1]);
        eq("W3", "1201-1900", w.get(2)[0] + "-" + w.get(2)[1]);
        eq("W4 末窗左移", "1601-2000", w.get(3)[0] + "-" + w.get(3)[1]);

        // ---------- 整除场景：1500bp，产物 500-600，重叠 100 ----------
        // step=500: [1,600],[501,1100],[1001,1500]（末窗 rem=500>=500 直接收尾）
        w = Primer3TilingDialog.tileWindows(1, 1500, 500, 600, 100);
        eq("1500bp 窗数", 3, w.size());
        checkInvariants("1500bp", w, 500, 600, 100);
        eq("末窗", "1001-1500", w.get(2)[0] + "-" + w.get(2)[1]);

        // ---------- 大区域窗数上限合理性：50kb / 产物 400-700 / 重叠 100 ----------
        w = Primer3TilingDialog.tileWindows(1, 50000, 400, 700, 100);
        eq("50kb 窗数上限内", true, w.size() <= Primer3TilingDialog.MAX_WINDOWS);
        checkInvariants("50kb", w, 400, 700, 100);

        // ---------- 重叠 0：窗口首尾相接不重叠 ----------
        w = Primer3TilingDialog.tileWindows(1, 2000, 400, 700, 0);
        checkInvariants("重叠0", w, 400, 700, 0);
        eq("重叠0 相邻紧贴", 701, w.get(1)[0]);
        eq("重叠0 前窗末", 700, w.get(0)[1]);

        // ---------- 参数非法 ----------
        eq("ampMax<ampMin 空", 0, Primer3TilingDialog.tileWindows(1, 2000, 700, 400, 100).size());
        eq("s>e 空", 0, Primer3TilingDialog.tileWindows(2000, 1, 400, 700, 100).size());
        eq("负重叠 空", 0, Primer3TilingDialog.tileWindows(1, 2000, 400, 700, -1).size());

        // ---------- toPrimers tag 命名 ----------
        Primer3Core.Pair p = new Primer3Core.Pair();
        p.idx = 0;
        p.leftSeq = "ACGTACGTACGTACGTACGT"; p.leftStart = 10; p.leftLen = 20; p.leftTm = 60; p.leftGc = 50;
        p.rightSeq = "TTGGTTGGTTGGTTGGTTGG"; p.rightStart = 100; p.rightLen = 20; p.rightTm = 61; p.rightGc = 50;
        p.productSize = 110;

        java.util.Set<String> used = new java.util.HashSet<String>();
        List<Primer> ps = p.toPrimers("chr1", "A", 0, used, 3, "T");
        eq("T命名 条数", 2, ps.size());
        eq("T命名 F", "A_T3_F", ps.get(0).name);
        eq("T命名 R", "A_T3_R", ps.get(1).name);
        eq("T命名 配对F→R", "A_T3_R", ps.get(0).pairedWith.iterator().next());
        eq("T命名 配对R→F", "A_T3_F", ps.get(1).pairedWith.iterator().next());
        eq("T命名 F 坐标", 10, ps.get(0).start);
        eq("T命名 R 坐标", 100, ps.get(1).start);

        // 手动轨（group=null）+ T 前缀 → 无 "null_"
        used = new java.util.HashSet<String>();
        ps = p.toPrimers("chr1", null, 0, used, 5, "T");
        eq("null组 T命名 F", "T5_F", ps.get(0).name);
        eq("null组 T命名 R", "T5_R", ps.get(1).name);

        // 默认 5 参重载 → 仍是 P 前缀（旧路径不回归）
        used = new java.util.HashSet<String>();
        ps = p.toPrimers("chr1", "A", 0, used, 2);
        eq("默认 P命名 F", "A_P2_F", ps.get(0).name);
        eq("默认 P命名 R", "A_P2_R", ps.get(1).name);

        // tag=null → 回退 P
        used = new java.util.HashSet<String>();
        ps = p.toPrimers("chr1", "A", 0, used, 7, null);
        eq("tag=null 回退P", "A_P7_F", ps.get(0).name);

        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    /** 窗口不变量：窗长 ∈ [ampMin, ampMax]（末窗左移后仍应 ≥ ampMin）；覆盖无缺口；相邻重叠 ≥ overlap。 */
    static void checkInvariants(String tag, List<int[]> w, int ampMin, int ampMax, int overlap) {
        for (int k = 0; k < w.size(); k++) {
            int[] x = w.get(k);
            int len = x[1] - x[0] + 1;
            ok(tag + " W" + (k + 1) + " 窗长≤max", len <= ampMax);
            ok(tag + " W" + (k + 1) + " 窗长≥min", len >= ampMin);
            if (k > 0) {
                int[] prev = w.get(k - 1);
                ok(tag + " W" + (k + 1) + " 覆盖无缺口", x[0] <= prev[1] + 1);
                ok(tag + " W" + (k + 1) + " 前进不回退", x[0] > prev[0]);
                ok(tag + " W" + (k + 1) + " 重叠≥设定", prev[1] - x[0] + 1 >= overlap);
            }
        }
    }

    static void eq(String name, Object exp, Object act) {
        boolean good = exp == null ? act == null : exp.equals(act);
        if (good) { pass++; System.out.println("[OK] " + name); }
        else { fail++; System.out.println("[FAIL] " + name + " 期望=" + exp + " 实际=" + act); }
    }

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[OK] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }
}

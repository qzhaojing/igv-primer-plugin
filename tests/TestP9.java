import org.broad.igv.primer.Primer;
import org.broad.igv.primer.Primer3Dialog;
import org.broad.igv.primer.PrimerStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v0.1.67：tooltip 串显修复 + flank 窄视野无解修正。
 *
 * 老板截图 1（串显）：鼠标停在下面引物（P2_F）上，悬浮框显示上面引物（P1_F）的信息。
 *   根因：getValueStringAt 只按基因组坐标命中——不分行、正序返回先加入者；
 *   P1_F/P2_F 同在视野左侧（x 范围重叠、不同行）时必串。
 *   修复：优先用鼠标实时坐标跑行感知命中（PrimerEditTool.mousePanelXY + hitTest(x,y,chr)），
 *   bp 兜底改后画优先（PrimerStore.hitByPosition(lastFirst=true)）。
 *
 * 老板截图 2（上下游区 18bp 无解）：窄视野（~85bp）下 flank 退化成 minLen=18 且伸进产物区，
 *   18bp 装 18nt 引物零余量 → primer3 必 0 对。
 *   修复：flank 三档语义——① 理想（缘外 100~200）裁后仍 ≥ lenMax 就用；
 *   ② 否则紧贴选段缘取视野内最大宽；③ 仍 < lenMax → 伸出视野取 want 宽，out=1 警告。
 *
 * 本测试覆盖：
 *  1) flank 三档：截图场景复刻（上下游 101bp + out=1）、hug 档（视野内紧贴）、
 *     lenMax 边界（lenMax 越大越容易出界）、理想档不变、不伸进产物区；
 *  2) fillFromViewport 窄视野：hug 档与出界档；
 *  3) hitByPosition 排序：重叠区后加入者优先（tooltip 兜底修复的核心）。
 */
public class TestP9 {
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

    public static void main(String[] a) throws Exception {
        PrimerStore.autosaveEnabled = false;

        // ============ 1) flank 三档语义 ============
        // 1a) 老板截图场景复刻：视野 ~85bp，产物 56bp，lenMax=27（截图引物长上限）
        //     旧实现：上下游各 18bp 且下游伸进产物区 → 无解
        //     新实现：上下游各伸出视野取 101bp（want = max(flankMax-flankMin+1, lenMax) = 101）
        int[] f = Primer3Dialog.fillFromSelection(2290141, 2290196, 2290123, 2290208, 18, 100, 200, 27);
        check(f != null && f.length == 8, "返回 8 元素（含两个 out 标志）");
        check(f[3] == 101, "上游 101bp（伸出视野） " + f[2] + "+" + f[3]);
        check(f[5] == 101, "下游 101bp（伸出视野） " + f[4] + "+" + f[5]);
        check(f[6] == 1 && f[7] == 1, "上下游均标记超视野 out=1");
        check(f[3] >= 27 && f[5] >= 27, "上下游均 ≥ lenMax=27（不再 18bp 无解）");
        check(f[2] + f[3] - 1 < f[0], "上游不伸进产物区 (" + (f[2] + f[3] - 1) + " < " + f[0] + ")");
        check(f[4] > f[1], "下游不伸进产物区 (" + f[4] + " > " + f[1] + ")");

        // 1b) hug 档：理想全在视野外，但紧贴选段缘后视野内够 lenMax → 不出界
        //     视野 [10000,10080)，产物 [10050,10069]，lenMax=30
        //     上游 hug [10000,10049] 宽 50 ≥ 30 → 用（out=0）；下游 hug 仅 10bp < 30 → 出界
        int[] h = Primer3Dialog.fillFromSelection(10050, 10069, 10000, 10080, 18, 100, 200, 30);
        check(h[3] == 50 && h[6] == 0, "上游 hug 视野内 50bp out=0 " + h[2] + "+" + h[3]);
        check(h[5] == 101 && h[7] == 1, "下游 hug 仅 10bp<30 → 出界 101bp " + h[4] + "+" + h[5]);
        check(h[2] + h[3] - 1 < h[0] && h[4] > h[1], "hug 档也不伸进产物区");

        // 1c) lenMax 边界：同一 hug 场景，lenMax 决定档位
        int[] h40 = Primer3Dialog.fillFromSelection(10050, 10069, 10000, 10080, 18, 100, 200, 40);
        check(h40[3] == 50 && h40[6] == 0, "lenMax=40 ≤ hug 50 → 仍 hug 不出界");
        int[] h60 = Primer3Dialog.fillFromSelection(10050, 10069, 10000, 10080, 18, 100, 200, 60);
        check(h60[3] == 101 && h60[6] == 1, "lenMax=60 > hug 50 → 出界 101bp");

        // 1d) 理想档不变（大视野，与 TestP7 语义一致）：缘外 100~200
        int[] id = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18, 100, 200, 27);
        check(id[2] == 9801 && id[3] == 101 && id[6] == 0, "理想档上游 9801+101 out=0");
        check(id[4] == 10501 && id[5] == 101 && id[7] == 0, "理想档下游 10501+101 out=0");

        // 1e) 理想被裁但 ≥ lenMax → 用裁后理想（TestP7 margin=150 语义保持）
        int[] cl = Primer3Dialog.fillFromSelection(10150, 19849, 10000, 20000, 18, 100, 200, 30);
        check(cl[2] == 10001 && cl[3] == 51 && cl[6] == 0, "裁后理想 51bp ≥ lenMax → 用 " + cl[2] + "+" + cl[3]);

        // 1f) want 随 lenMax 放大：lenMax=200 → 出界宽 = max(101, 200) = 200
        int[] big = Primer3Dialog.fillFromSelection(2290141, 2290196, 2290123, 2290208, 18, 100, 200, 200);
        check(big[3] == 200 && big[5] == 200, "lenMax=200 → 出界宽 want=200");

        // 1g) 兼容重载（5 参/7 参）：lenMax 默认 max(minLen,30)，旧下标语义不变
        int[] c5 = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18);
        check(c5.length == 8 && c5[2] == 9801 && c5[3] == 101 && c5[6] == 0, "5 参兼容重载语义不变");
        int[] c7 = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18, 100, 200);
        check(c7.length == 8 && c7[4] == 10501 && c7[5] == 101, "7 参兼容重载语义不变");
        check(Primer3Dialog.fillFromSelection(5000, 5100, 9000, 11000, 18) == null, "屏外选区仍返回 null");

        // ============ 2) fillFromViewport 窄视野 ============
        // 2a) 100bp 视野（TestP7 场景）：产物 18bp 居中，上下游 hug 视野内 40/42bp，均 out=0
        int[] v = Primer3Dialog.fillFromViewport(10000, 10100, 18, 150, 100, 200, 30);
        check(v[2] == 10001 && v[3] == 40 && v[6] == 0, "窄视野上游 hug 40bp 屏内 " + v[2] + "+" + v[3]);
        check(v[4] == 10059 && v[5] == 42 && v[7] == 0, "窄视野下游 hug 42bp 屏内 " + v[4] + "+" + v[5]);
        check(v[3] >= 18 && v[5] >= 18, "上下游仍 ≥18bp（TestP7 旧断言保持）");
        // 2b) 85bp 视野 + margin=0 → 产物=全视野，上下游均出界
        int[] v2 = Primer3Dialog.fillFromViewport(2290123, 2290208, 18, 0, 100, 200, 27);
        check(v2[6] == 1 && v2[7] == 1 && v2[3] == 101 && v2[5] == 101,
                "85bp 视野 margin=0 → 上下游出界 101bp");
        // 2c) 大视野理想档不变（TestP7 margin=200 场景）
        int[] v3 = Primer3Dialog.fillFromViewport(10000, 20000, 18, 200, 100, 200, 30);
        check(v3[2] == 10001 && v3[3] == 101 && v3[4] == 19900 && v3[5] == 101
                && v3[6] == 0 && v3[7] == 0, "大视野理想档不变（TestP7 断言保持）");

        // ============ 3) hitByPosition 排序（tooltip 兜底修复核心） ============
        // 3a) x 重叠区：后加入者优先（lastFirst=true）——旧正序返回先加入的 P1_F 就是串显根因
        List<Primer> ps = new ArrayList<Primer>(Arrays.asList(
                mk("primer3_P1_F", 1000, 1020, '+'),
                mk("primer3_P2_F", 1010, 1030, '+')));
        check(PrimerStore.hitByPosition(ps, "NC_000962.3", 1015, true).name.equals("primer3_P2_F"),
                "重叠区 bp=1015 lastFirst → P2_F（后画优先）");
        check(PrimerStore.hitByPosition(ps, "NC_000962.3", 1015, false).name.equals("primer3_P1_F"),
                "重叠区 bp=1015 正序 → P1_F（旧行为，仅兼容保留）");
        // 3b) 只落一条：两种顺序都命中它
        check(PrimerStore.hitByPosition(ps, "NC_000962.3", 1005, true).name.equals("primer3_P1_F"),
                "bp=1005 仅 P1_F 覆盖 → P1_F");
        check(PrimerStore.hitByPosition(ps, "NC_000962.3", 1025, true).name.equals("primer3_P2_F"),
                "bp=1025 仅 P2_F 覆盖 → P2_F");
        // 3c) 边界与空
        check(PrimerStore.hitByPosition(ps, "NC_000962.3", 1030, true) == null, "end 不含（bp=1030 落空）");
        check(PrimerStore.hitByPosition(ps, "chrX", 1015, true) == null, "染色体不匹配 → null");
        check(PrimerStore.hitByPosition(new ArrayList<Primer>(), "NC_000962.3", 1015, true) == null, "空列表 → null");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

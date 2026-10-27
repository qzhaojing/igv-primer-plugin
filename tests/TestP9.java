import org.broad.igv.primer.Primer;
import org.broad.igv.primer.Primer3Dialog;
import org.broad.igv.primer.PrimerStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v0.1.80：区内模型回归（设计区落在产物两端内侧）+ tooltip 串显修复（hitByPosition）。
 *
 * 老板截图 1（串显）：鼠标停在下面引物（P2_F）上，悬浮框显示上面引物（P1_F）的信息。
 *   根因：getValueStringAt 只按基因组坐标命中——不分行、正序返回先加入者；
 *   P1_F/P2_F 同在视野左侧（x 范围重叠、不同行）时必串。
 *   修复：优先用鼠标实时坐标跑行感知命中（PrimerEditTool.mousePanelXY + hitTest(x,y,chr)），
 *   bp 兜底改后画优先（PrimerStore.hitByPosition(lastFirst=true)）。
 *
 * 老板截图 2（窄选区无解）：旧"区外 flank"模型下，56bp 选区把设计区退化到产物外/伸进产物，
 *   18bp 装 18nt 引物零余量 → primer3 必 0 对。
 *   v0.1.80 区内模型修复：设计区=产物两端内侧（带宽=min(产物长, max(lenMax, min(flankMax, 产物长/2)))），
 *   引物只在产物内部两端的设计带内发生设计，窄选区也能在带内放下最长引物。
 *
 * 本测试覆盖：
 *  1) 区内模型 fillFromSelection：常规/窄选区/lenMax 边界/理想档/兼容重载/屏外；
 *  2) 区内模型 fillFromViewport：窄视野退化/85bp 视野/大视野理想档；
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

        // ============ 1) 区内模型：fillFromSelection 设计带落在产物两端内侧 ============
        // 1a) 老板截图场景复刻：选区 ~56bp（[2290141,2290196]），lenMax=27
        //     ps=2290141, pe=2290196, L=56, band=min(56,max(27,min(200,28)))=28
        //     左带 [2290141,2290168] 长28 →1-based 2290142+28；右带 [2290169,2290196] 长28 →2290170+28
        int[] f = Primer3Dialog.fillFromSelection(2290141, 2290196, 2290123, 2290208, 18, 100, 200, 27);
        check(f != null && f.length == 8, "返回 8 元素");
        check(f[2] == 2290142 && f[3] == 28, "左端设计区=产物左缘内28bp (起2290142 长28)");
        check(f[4] == 2290170 && f[5] == 28, "右端设计区=产物右缘内28bp (起2290170 长28)");
        check(f[6] == 0 && f[7] == 0, "区内模型无超出屏幕标记(out=0)");
        check(f[2] >= f[0] && f[4] <= f[1], "设计区完全落在产物内部 (左端≥产物起, 右端≤产物止)");

        // 1b) lenMax 边界：band 至少装下最长引物，产物过窄时钳到产物长
        int[] h40 = Primer3Dialog.fillFromSelection(2290141, 2290196, 2290123, 2290208, 18, 100, 200, 40);
        check(h40[3] == 40 && h40[5] == 40, "lenMax=40 时设计带宽=40（≤产物长）");
        int[] h60 = Primer3Dialog.fillFromSelection(2290141, 2290196, 2290123, 2290208, 18, 100, 200, 60);
        check(h60[3] == 56 && h60[5] == 56, "lenMax=60 > 产物长 → 设计区相遇(带宽=产物长56)");

        // 1c) 大视野理想档：全区内，带宽=flankMax（200）
        int[] id = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18, 100, 200, 27);
        // ps=10000,pe=10400,L=401,band=200 → 左带[10001,10200] 右带[10202,10400]
        check(id[2] == 10001 && id[3] == 200 && id[6] == 0, "理想档左端设计区=产物左缘内200bp");
        check(id[4] == 10202 && id[5] == 200 && id[7] == 0, "理想档右端设计区=产物右缘内200bp");

        // 1d) 兼容重载（5 参/7 参）：语义不变
        int[] c5 = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18);
        // 5参默认 flankMin=100,flankMax=200,lenMax=max(18,30)=30 → band=200
        check(c5.length == 8 && c5[2] == 10001 && c5[3] == 200 && c5[6] == 0, "5 参兼容重载语义不变");
        int[] c7 = Primer3Dialog.fillFromSelection(10000, 10400, 9000, 11000, 18, 100, 200);
        // 7参默认 lenMax=max(18,30)=30 → band=200
        check(c7.length == 8 && c7[4] == 10202 && c7[5] == 200, "7 参兼容重载语义不变");
        check(Primer3Dialog.fillFromSelection(5000, 5100, 9000, 11000, 18) == null, "屏外选区仍返回 null");

        // ============ 2) 区内模型：fillFromViewport ============
        // 2a) 100bp 视野 + margin=150 → 产物退化中段 minLen，设计区=产物两端内侧相遇
        int[] v = Primer3Dialog.fillFromViewport(10000, 10100, 18, 150, 100, 200, 30);
        // ps=10150,pe=9949 → mid-clamp ps=10040,pe=10057,L=18,band=18 → 左带[10041]右带[10041]
        check(v[2] == 10041 && v[3] == 18 && v[6] == 0, "窄视野左端设计区=产物左缘内18bp 屏内 " + v[2] + "+" + v[3]);
        check(v[4] == 10041 && v[5] == 18 && v[7] == 0, "窄视野右端设计区=产物右缘内18bp 屏内 " + v[4] + "+" + v[5]);
        check(v[3] >= 18 && v[5] >= 18, "两端设计区仍 ≥18bp");
        // 2b) 85bp 视野 + margin=0 → 产物=全视野，设计区=产物两端内侧（区内，out=0）
        int[] v2 = Primer3Dialog.fillFromViewport(2290123, 2290208, 18, 0, 100, 200, 27);
        // ps=2290123,pe=2290207,L=85,band=min(85,max(27,min(200,42)))=42
        // 左带[2290124,2290165] 右带[2290167,2290207]
        check(v2[0] == 2290124 && v2[1] == 2290208, "85bp视野margin0 产物=全视野 " + v2[0] + "-" + v2[1]);
        check(v2[2] == 2290124 && v2[3] == 42 && v2[6] == 0, "左端设计区=产物左缘内42bp（区内 out=0）");
        check(v2[4] == 2290167 && v2[5] == 42 && v2[7] == 0, "右端设计区=产物右缘内42bp（区内 out=0）");
        // 2c) 大视野理想档不变
        int[] v3 = Primer3Dialog.fillFromViewport(10000, 20000, 18, 200, 100, 200, 30);
        check(v3[2] == 10201 && v3[3] == 200 && v3[4] == 19601 && v3[5] == 200
                && v3[6] == 0 && v3[7] == 0, "大视野区内理想档（左带[10201,200] 右带[19601,200]）");

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

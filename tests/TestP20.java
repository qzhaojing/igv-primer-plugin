import org.broad.igv.primer.*;

import java.io.File;
import java.util.*;

/**
 * v0.1.96 回归：跨轨移动/复制 + 手动轨(null 分组) primer3 命名。
 */
public class TestP20 {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) throws Exception {
        PrimerStore.clear();

        // ---- 1. 手动轨(null 分组) primer3 命名：不应出现 "null_" 前缀 ----
        // 用 primer3 真实设计一对，转成 null 分组引物
        File exe = Primer3Core.ensureExe();
        check(exe.isFile(), "ensureExe 释放就绪");
        StringBuilder sb = new StringBuilder();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 400; i++) sb.append("ACGT".charAt(rnd.nextInt(4)));
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("PRIMER_NUM_RETURN", "1");
        params.put("PRIMER_MIN_SIZE", "18"); params.put("PRIMER_MAX_SIZE", "27");
        params.put("PRIMER_MIN_TM", "57"); params.put("PRIMER_MAX_TM", "63");
        params.put("PRIMER_PRODUCT_SIZE_RANGE", "100-300");
        params.put("SEQUENCE_TARGET", "150,100");
        List<Primer3Core.Pair> pairs = Primer3Core.design("t20", sb.toString(), params);
        check(!pairs.isEmpty(), "design 有解");
        List<Primer> manual = pairs.get(0).toPrimers("chr1", null, 1000, null);
        check(manual.size() == 2, "null 分组产出 2 条");
        boolean noNullPrefix = true;
        for (Primer p : manual) if (p.name == null || p.name.startsWith("null_")) noNullPrefix = false;
        check(noNullPrefix, "null 分组命名无 'null_' 前缀: " + manual.get(0).name + " / " + manual.get(1).name);

        // 加入手动轨后，续号应从 P2 起（nextPairNumber(null) 扫 P{n}）
        PrimerStore.addAll(manual);
        int nxt = PrimerStore.nextPairNumber(null);
        check(nxt == 2, "nextPairNumber(null) 续号为 2 (实际 " + nxt + ")");
        List<Primer> manual2 = pairs.get(0).toPrimers("chr1", null, 2000, null, nxt);
        check(manual2.get(0).name.equals("P2_F") && manual2.get(1).name.equals("P2_R"),
                "二次设计命名 P2_F/P2_R (实际 " + manual2.get(0).name + "/" + manual2.get(1).name + ")");

        // ---- 2. 跨轨移动：整对迁移 + 命名冲突时改名并重连配对边 ----
        PrimerStore.clear();
        Primer fA = new Primer("A_P1_F", "chr1", 100, 120, '+', "R1", 20, null);
        Primer rA = new Primer("A_P1_R", "chr1", 200, 220, '-', "R2", 20, null);
        fA.pairedWith = new LinkedHashSet<String>(); rA.pairedWith = new LinkedHashSet<String>();
        fA.pairedWith.add("A_P1_R"); rA.pairedWith.add("A_P1_F");
        fA.group = "A"; rA.group = "A";
        PrimerStore.add(fA); PrimerStore.add(rA);
        // B 轨已有一个同名引物（制造冲突）：先以唯一名加入，再直接改名为 "A_P1_F" 模拟"目标轨已存在同名"，
        // 因为 add() 的全局去重本来就会阻止两个 "A_P1_F" 同时存在（命名全局唯一是设计使然），
        // 真正触发 move 内重命名的场景需绕过去重手动构造。
        Primer fB = new Primer("B_dummy", "chr1", 300, 320, '+', "R1", 20, null);
        fB.group = "B"; fB.pairedWith = new LinkedHashSet<String>();
        PrimerStore.add(fB);
        fB.name = "A_P1_F";   // 模拟 B 轨已存在同名引物

        PrimerStore.movePrimersToGroup(Collections.singletonList(fA), "B");
        check(fA.group.equals("B"), "移动后 fA.group==B");
        check(fA.name.equals("A_P1_F_2"), "冲突改名 fA.name==A_P1_F_2 (实际 " + fA.name + ")");
        check(rA.group.equals("B"), "配对伙伴 rA 一并迁移到 B");
        check(fA.pairedWith.contains("A_P1_R"), "fA 仍连 rA 旧名");
        check(rA.pairedWith.contains("A_P1_F_2"), "rA 配对边已重连到 fA 新名");
        check(PrimerStore.countGroup("A") == 0, "源轨 A 引物已清空");
        check(PrimerStore.countGroup("B") == 3, "目标轨 B 现 3 条 (原 fB + 迁移的 fA/rA，实际 " + PrimerStore.countGroup("B") + ")");
        check(fB.name.equals("A_P1_F"), "B 轨原有引物名未被破坏");

        // ---- 3. 跨轨复制：保留内部配对关系 + 名称去重 ----
        PrimerStore.clear();
        Primer f = new Primer("A_P1_F", "chr1", 100, 120, '+', "R1", 20, null);
        Primer r = new Primer("A_P1_R", "chr1", 200, 220, '-', "R2", 20, null);
        f.pairedWith = new LinkedHashSet<String>(); r.pairedWith = new LinkedHashSet<String>();
        f.pairedWith.add("A_P1_R"); r.pairedWith.add("A_P1_F");
        f.group = "A"; r.group = "A";
        PrimerStore.add(f); PrimerStore.add(r);

        PrimerStore.copyPrimersToGroup(Collections.singletonList(f), "B");
        check(PrimerStore.countGroup("A") == 2, "复制后源轨 A 仍 2 条");
        check(PrimerStore.countGroup("B") == 2, "复制目标轨 B 得 2 条 (实际 " + PrimerStore.countGroup("B") + ")");
        // 找到 B 中的副本并验证内部配对
        List<Primer> bList = PrimerStore.getPrimersByGroup("B");
        Primer cf = null, cr = null;
        for (Primer p : bList) { if (p.name.equals("A_P1_F")) cf = p; if (p.name.equals("A_P1_R")) cr = p; }
        check(cf != null && cr != null, "副本命名保留 A_P1_F / A_P1_R");
        check(cf.pairedWith.contains("A_P1_R") && cr.pairedWith.contains("A_P1_F"), "副本内部配对完整");
        check(!cf.pairedWith.contains(f.name + "_X"), "副本未带出跨集合旧边");

        System.out.println("################ TestP20 PASS=" + pass + " FAIL=" + fail + " ################");
        if (fail > 0) System.exit(1);
    }
}

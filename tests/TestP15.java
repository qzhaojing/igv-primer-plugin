/**
 * TestP15：v0.1.73 端槽位（F / R / R1 / R2 直接序列）
 *   覆盖：槽位设置/净化/清除 /
 *        v0.1.79：F/R（链方向）与 R1/R2（测序读段）二选一模式，互不混用、
 *        未启用那组的值保留但不起作用、模式随 XML 持久化 /
 *        槽位优先于库条目默认 / 逐条仍最优先 /
 *        nameFor 来源标注 / XML 保存-加载往返 / loadDefaults 清槽
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP15 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static org.broad.igv.primer.Primer p(String name, char strand, String role) {
        return new org.broad.igv.primer.Primer(name, "chr1", 100, 120, strand, role, 50, null);
    }

    public static void main(String[] args) throws Exception {
        org.broad.igv.primer.TailLibrary.setEntries(new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>());
        org.broad.igv.primer.TailLibrary.loadDefaults();   // 清空条目 + 清槽位
        // 重新塞一个干净的迷你库
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> lib =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        lib.add(new org.broad.igv.primer.TailLibrary.Tail("LibB", "AAAA", "自定义", "BOTH", ""));
        org.broad.igv.primer.TailLibrary.setEntries(lib);

        // ---------- 1) 槽位设置 / 净化 / 清除 ----------
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "初始槽位为空");
        check(!org.broad.igv.primer.TailLibrary.slotAny(), "未设槽位 → slotAny=false");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_F, " gaat tc ");
        check("GAATTC".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "槽位序列被净化");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_F, "");
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F))
                && !org.broad.igv.primer.TailLibrary.slotAny(), "空串清除槽位");

        // ---------- 2) 匹配规则（v0.1.79：F/R 与 R1/R2 是两个维度，二选一，绝不混用）----------
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_F, "GGGG");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R, "TTTT");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "CCCC");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R2, "GGCC");
        check(org.broad.igv.primer.TailLibrary.slotAny(), "四槽全设 → slotAny=true");

        org.broad.igv.primer.Primer f = p("F1", '+', "R1");
        org.broad.igv.primer.Primer r = p("R1", '-', "R2");

        // 默认 = 按链方向（F / R），只按 strand 匹配，与 role 无关
        check(org.broad.igv.primer.TailLibrary.slotMode() == org.broad.igv.primer.TailLibrary.MODE_STRAND,
                "默认模式 = 按链方向（F / R）");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(f)),
                "链方向模式：+ 链引物吃 F 端槽（role=R1 也不改判）");
        check("TTTT".equals(org.broad.igv.primer.TailLibrary.seqFor(r)),
                "链方向模式：- 链引物吃 R 端槽（role=R2 也不改判）");

        // 切到读段模式（R1 / R2）：只按 role 匹配，F/R 槽立刻失效
        org.broad.igv.primer.TailLibrary.setSlotMode(org.broad.igv.primer.TailLibrary.MODE_ROLE);
        check(org.broad.igv.primer.TailLibrary.slotMode() == org.broad.igv.primer.TailLibrary.MODE_ROLE,
                "切到按测序读段模式（R1 / R2）");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(f)),
                "读段模式：role=R1 的引物吃 R1 端槽（不是 F 端槽）");
        check("GGCC".equals(org.broad.igv.primer.TailLibrary.seqFor(r)),
                "读段模式：role=R2 的引物吃 R2 端槽（不是 R 端槽）");

        // 核心反例：F 端可能是 R1 也可能是 R2；R 端同理。两个维度不能绑死。
        check("GGCC".equals(org.broad.igv.primer.TailLibrary.seqFor(p("FasR2", '+', "R2"))),
                "反例：+ 链引物若 role=R2 → 吃 R2 端槽（F ≠ R1）");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(p("RasR1", '-', "R1"))),
                "反例：- 链引物若 role=R1 → 吃 R1 端槽（R ≠ R2）");

        // 未启用那组的值保留、但不起作用（切换模式不会被清空，也不会污染导出）
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F))
                        && "TTTT".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R)),
                "未启用那组（F/R）的值保留，不会被清空");
        check(!"GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(f))
                        && !"TTTT".equals(org.broad.igv.primer.TailLibrary.seqFor(r)),
                "未启用那组的值不影响导出");

        // role 不是 R1/R2 的引物
        org.broad.igv.primer.Primer odd = p("X", '+', "R1");
        odd.role = "manual";
        check(org.broad.igv.primer.TailLibrary.slotHit(odd) < 0,
                "读段模式下 role 非 R1/R2 → 不命中任何端槽");
        org.broad.igv.primer.TailLibrary.setSlotMode(org.broad.igv.primer.TailLibrary.MODE_STRAND);
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(odd)),
                "链方向模式下 role 非 R1/R2 的引物仍按 strand 吃 F 端槽");

        // 槽位优先于库条目默认（此时模式=链方向，f 命中 F 端槽 GGGG）
        org.broad.igv.primer.TailLibrary.setActive("LibB");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(f)),
                "槽位（F端 GGGG）优先于统一条目默认（LibB=AAAA）");
        org.broad.igv.primer.TailLibrary.setActive(null);
        // 逐条仍最优先
        f.tailName = "LibB";
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor(f)), "逐条指定压过端槽位");
        f.tailName = org.broad.igv.primer.TailLibrary.NONE;
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor(f)), "逐条「不附加」挡掉端槽位");
        f.tailName = null;
        // 逐条自定义序列最优先
        f.tailSeq = "ACGTAC";
        check("ACGTAC".equals(org.broad.igv.primer.TailLibrary.seqFor(f)), "逐条自定义序列压过端槽位");
        f.tailSeq = null;

        // ---------- 3) nameFor / scopeFor ----------
        check(org.broad.igv.primer.TailLibrary.nameFor(f).startsWith("F端:"),
                "槽位来源标注 F端: " + org.broad.igv.primer.TailLibrary.nameFor(f));
        check("F端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(f)), "scopeFor=F端槽");
        org.broad.igv.primer.TailLibrary.setSlotMode(org.broad.igv.primer.TailLibrary.MODE_ROLE);
        check("R1端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(f)), "切读段模式后 scopeFor=R1端槽");
        check(org.broad.igv.primer.TailLibrary.nameFor(f).startsWith("R1端:"),
                "槽位来源标注 R1端: " + org.broad.igv.primer.TailLibrary.nameFor(f));
        org.broad.igv.primer.TailLibrary.setSlotMode(org.broad.igv.primer.TailLibrary.MODE_STRAND);

        // ---------- 4) XML 保存-加载往返（含 v0.1.79 模式持久化）----------
        java.io.File tmp = java.io.File.createTempFile("tails73", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML 成功");
        org.broad.igv.primer.TailLibrary.loadDefaults();   // 全清
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)),
                "loadDefaults 清空槽位");
        check(org.broad.igv.primer.TailLibrary.slotMode() == org.broad.igv.primer.TailLibrary.MODE_STRAND,
                "loadDefaults 把模式复位为按链方向");
        check(org.broad.igv.primer.TailLibrary.load(tmp), "加载 XML 成功");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)),
                "往返后 R1 端槽一致");
        check("GGCC".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R2)),
                "往返后 R2 端槽一致");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "往返后 F 端槽一致");
        check("TTTT".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R)),
                "往返后 R 端槽一致");
        check(org.broad.igv.primer.TailLibrary.slotMode() == org.broad.igv.primer.TailLibrary.MODE_STRAND,
                "往返后模式一致（链方向）");
        // 换成读段模式再跑一次，确认模式真的落盘（不是读取时猜的）
        org.broad.igv.primer.TailLibrary.setSlotMode(org.broad.igv.primer.TailLibrary.MODE_ROLE);
        check(org.broad.igv.primer.TailLibrary.save(tmp), "以读段模式再保存一次");
        org.broad.igv.primer.TailLibrary.loadDefaults();
        check(org.broad.igv.primer.TailLibrary.load(tmp), "再次加载 XML 成功");
        check(org.broad.igv.primer.TailLibrary.slotMode() == org.broad.igv.primer.TailLibrary.MODE_ROLE,
                "往返后模式一致（读段）");
        tmp.delete();

        // 旧版 XML（无 slot 属性）加载 → 槽位为空，不报错
        java.io.File old = java.io.File.createTempFile("tailsOld73", ".xml");
        java.nio.file.Files.write(old.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\" active=\"LibB\">"
                        + "<tail><name>LibB</name><type>自定义</type><seq>AAAA</seq><apply>BOTH</apply><note></note></tail>"
                        + "</tailLibrary>").getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(old), "旧版 XML 加载成功");
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "旧版 XML 无槽位属性 → 槽位为空");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor(r)), "旧版 XML 统一默认照常生效");
        old.delete();

        System.out.println("==== TestP15: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

/**
 * TestP18：v0.1.79 端命名方式——F/R（链方向）与 R1/R2（测序读段）是两个正交维度
 *
 * <p>起因：老板指出「F 端可能是 R1，也可能是 R2，R 端同理，因此不能把 F 等同于 R1」。
 * 旧实现把两条规则串成一条链（R1/R2 优先于 F/R），等价于默认假设「F=R1、R=R2」，
 * 而这个假设在双端建库里并不总成立——哪一端对应正链取决于试剂盒设计。
 * 现改为两种<b>互斥</b>模式，一次只用一种；另一组的值保留但完全不起作用。
 *
 * <p>覆盖：
 * <ol>
 *   <li>模式常量 / 默认值 / activeSlots / modeLabel</li>
 *   <li>同一条引物在两种模式下命中不同槽；F≠R1、R≠R2 的反例</li>
 *   <li>未启用那组的值既不被清空、也不参与导出（BED / FASTA 标记）</li>
 *   <li>非法模式值（setSlotMode(99)、XML 里写 "9"）→ 回落按链方向</li>
 *   <li>旧库 XML（无 slotMode 属性）的推断：只设了 R1/R2 → 读段模式；
 *       两组都有 / 都没有 → 链方向模式</li>
 *   <li>loadDefaults 复位为链方向模式</li>
 * </ol>
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP18 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static org.broad.igv.primer.Primer p(String name, char strand, String role) {
        return new org.broad.igv.primer.Primer(name, "chr1", 100, 120, strand, role, 50, null);
    }

    static void setSlot(int s, String v) { org.broad.igv.primer.TailLibrary.setSlot(s, v); }
    static String slotSeq(int s) { return org.broad.igv.primer.TailLibrary.slotSeq(s); }
    static String seqFor(org.broad.igv.primer.Primer p) { return org.broad.igv.primer.TailLibrary.seqFor(p); }
    static void mode(int m) { org.broad.igv.primer.TailLibrary.setSlotMode(m); }
    static int STRAND() { return org.broad.igv.primer.TailLibrary.MODE_STRAND; }
    static int ROLE() { return org.broad.igv.primer.TailLibrary.MODE_ROLE; }
    static int m() { return org.broad.igv.primer.TailLibrary.slotMode(); }

    /** 造一个只含一条 LibB 的迷你库，并把四个槽位清干净。 */
    static void freshLib() {
        org.broad.igv.primer.TailLibrary.loadDefaults();
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> lib =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        lib.add(new org.broad.igv.primer.TailLibrary.Tail("LibB", "AAAA", "自定义", "BOTH", ""));
        org.broad.igv.primer.TailLibrary.setEntries(lib);
    }

    public static void main(String[] args) throws Exception {
        freshLib();

        // ---------- 1) 常量 / 默认 / 显示 ----------
        check(STRAND() != ROLE(), "两种模式常量不同");
        check(m() == STRAND(), "初始模式 = 按链方向（F / R）");
        int[] a1 = org.broad.igv.primer.TailLibrary.activeSlots();
        check(a1.length == 2 && a1[0] == org.broad.igv.primer.TailLibrary.SLOT_F
                        && a1[1] == org.broad.igv.primer.TailLibrary.SLOT_R,
                "链方向模式 → 生效槽位 = F 端 / R 端");
        check("按链方向（F / R）".equals(org.broad.igv.primer.TailLibrary.modeLabel()),
                "modeLabel（链方向）= " + org.broad.igv.primer.TailLibrary.modeLabel());
        mode(ROLE());
        int[] a2 = org.broad.igv.primer.TailLibrary.activeSlots();
        check(a2.length == 2 && a2[0] == org.broad.igv.primer.TailLibrary.SLOT_R1
                        && a2[1] == org.broad.igv.primer.TailLibrary.SLOT_R2,
                "读段模式 → 生效槽位 = R1 端 / R2 端");
        check("按测序读段（R1 / R2）".equals(org.broad.igv.primer.TailLibrary.modeLabel()),
                "modeLabel（读段）= " + org.broad.igv.primer.TailLibrary.modeLabel());

        // 非法值 → 回落链方向（宁可保守，不能出第三种状态）
        mode(99);
        check(m() == STRAND(), "setSlotMode(99) → 回落按链方向");
        mode(-1);
        check(m() == STRAND(), "setSlotMode(-1) → 回落按链方向");

        // ---------- 2) 同一条引物，两种模式命中不同槽 ----------
        setSlot(org.broad.igv.primer.TailLibrary.SLOT_F, "FFFF");
        setSlot(org.broad.igv.primer.TailLibrary.SLOT_R, "RRRR");
        setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "CCAA");
        setSlot(org.broad.igv.primer.TailLibrary.SLOT_R2, "AACC");

        org.broad.igv.primer.Primer fR1 = p("fR1", '+', "R1");
        org.broad.igv.primer.Primer rR2 = p("rR2", '-', "R2");
        org.broad.igv.primer.Primer fR2 = p("fR2", '+', "R2");   // 正链却是 Read2
        org.broad.igv.primer.Primer rR1 = p("rR1", '-', "R1");   // 负链却是 Read1

        mode(STRAND());
        check("FFFF".equals(seqFor(fR1)), "链方向模式：+ 链（role=R1）→ F 端槽");
        check("RRRR".equals(seqFor(rR2)), "链方向模式：- 链（role=R2）→ R 端槽");
        check("FFFF".equals(seqFor(fR2)), "链方向模式：+ 链（role=R2）也走 F 端槽（不看 role）");
        check("RRRR".equals(seqFor(rR1)), "链方向模式：- 链（role=R1）也走 R 端槽（不看 role）");

        mode(ROLE());
        check("CCAA".equals(seqFor(fR1)), "读段模式：role=R1 → R1 端槽（+ 链也一样）");
        check("AACC".equals(seqFor(rR2)), "读段模式：role=R2 → R2 端槽（- 链也一样）");
        check("AACC".equals(seqFor(fR2)), "读段模式：role=R2 → R2 端槽（F 端 ≠ R1）");
        check("CCAA".equals(seqFor(rR1)), "读段模式：role=R1 → R1 端槽（R 端 ≠ R2）");

        // ---------- 3) 未启用那组：保留值，但不生效 ----------
        check("FFFF".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F))
                        && "RRRR".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R)),
                "读段模式下 F/R 槽的值仍在（切模式不清空）");
        check(org.broad.igv.primer.TailLibrary.apply("ACGT", fR1).equals("CCAAACGT"),
                "读段模式导出拼接用 R1 端槽，不带 F 端槽（实际 "
                        + org.broad.igv.primer.TailLibrary.apply("ACGT", fR1) + "）");
        check(!org.broad.igv.primer.TailLibrary.apply("ACGT", fR1).contains("FFFF"),
                "导出结果里不含未启用那组的序列");
        // 反向同样成立
        mode(STRAND());
        check("CCAA".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)),
                "链方向模式下 R1/R2 槽的值仍在");
        check(org.broad.igv.primer.TailLibrary.apply("ACGT", fR1).equals("FFFFACGT"),
                "链方向模式导出拼接用 F 端槽，不带 R1 端槽");

        // source 标注（写 FASTA header 的 |tail=）也跟着模式走，不能出现「F端」标注读段槽
        check("F端:FFFF".equals(org.broad.igv.primer.TailLibrary.nameFor(fR1)),
                "链方向模式 nameFor = F端:FFFF（实际 " + org.broad.igv.primer.TailLibrary.nameFor(fR1) + "）");
        mode(ROLE());
        check("R1端:CCAA".equals(org.broad.igv.primer.TailLibrary.nameFor(fR1)),
                "读段模式 nameFor = R1端:CCAA（实际 " + org.broad.igv.primer.TailLibrary.nameFor(fR1) + "）");
        check("R1端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(fR1)), "scopeFor = R1端槽");
        check("R2端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(fR2)), "scopeFor（正链 R2）= R2端槽");

        // ---------- 4) XML 往返 + 非法模式值 ----------
        java.io.File tmp = java.io.File.createTempFile("tails79", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML（读段模式）");
        org.broad.igv.primer.TailLibrary.loadDefaults();
        check(org.broad.igv.primer.TailLibrary.load(tmp), "加载 XML");
        check(m() == ROLE(), "往返后仍是读段模式");
        check("CCAA".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1))
                        && "FFFF".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "往返后四槽全在（含未启用那组）");
        tmp.delete();

        // XML 里 slotMode 被写成非法值 → 回落链方向，不崩
        java.io.File bad = java.io.File.createTempFile("tails79bad", ".xml");
        java.nio.file.Files.write(bad.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\" slotMode=\"9\""
                        + " slotF=\"FFFF\" slotR1=\"R1R1\"><tail><name>LibB</name><type>自定义</type>"
                        + "<seq>AAAA</seq><apply>BOTH</apply><note></note></tail></tailLibrary>")
                        .getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(bad), "非法 slotMode 的 XML 仍能加载");
        check(m() == STRAND(), "XML 里 slotMode=9 → 回落按链方向");
        bad.delete();

        // ---------- 5) 旧库（无 slotMode 属性）的模式推断 ----------
        // 5a) 只设了 R1/R2 → 推断为读段模式（老板以前的库就是这么用的）
        java.io.File oldRole = java.io.File.createTempFile("tails79oldR", ".xml");
        java.nio.file.Files.write(oldRole.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\""
                        + " slotR1=\"CCCC\" slotR2=\"GGCC\"><tail><name>LibB</name><type>自定义</type>"
                        + "<seq>AAAA</seq><apply>BOTH</apply><note></note></tail></tailLibrary>")
                        .getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(oldRole), "旧库（只有 R1/R2）加载成功");
        check(m() == ROLE(), "旧库只设了 R1/R2 → 推断为读段模式");
        check("CCCC".equals(seqFor(p("a", '+', "R1"))), "推断后 role=R1 的引物拿到 R1 端槽");
        oldRole.delete();

        // 5b) 都没设 → 链方向
        java.io.File oldNone = java.io.File.createTempFile("tails79oldN", ".xml");
        java.nio.file.Files.write(oldNone.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\" active=\"LibB\">"
                        + "<tail><name>LibB</name><type>自定义</type><seq>AAAA</seq><apply>BOTH</apply>"
                        + "<note></note></tail></tailLibrary>").getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(oldNone), "旧库（无槽位）加载成功");
        check(m() == STRAND(), "旧库没设槽位 → 链方向模式");
        check("AAAA".equals(seqFor(p("a", '+', "R1"))), "库条目统一默认照常生效");
        oldNone.delete();

        // 5c) 两组都设了（v0.1.73~78 的库）→ 保守取链方向，并保留 R1/R2 的值供用户切回去
        java.io.File oldBoth = java.io.File.createTempFile("tails79oldB", ".xml");
        java.nio.file.Files.write(oldBoth.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\""
                        + " slotF=\"FFFF\" slotR=\"RRRR\" slotR1=\"CCCC\" slotR2=\"GGCC\">"
                        + "<tail><name>LibB</name><type>自定义</type><seq>AAAA</seq><apply>BOTH</apply>"
                        + "<note></note></tail></tailLibrary>").getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(oldBoth), "旧库（四槽全设）加载成功");
        check(m() == STRAND(), "两组都设了 → 取链方向（保守）");
        check("FFFF".equals(seqFor(p("a", '+', "R1"))), "链方向下走 F 端槽");
        check("CCCC".equals(slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)), "R1 端槽的值保留，切模式即可用");
        mode(ROLE());
        check("CCCC".equals(seqFor(p("a", '+', "R1"))), "切到读段模式即用上旧库的 R1 端槽");
        oldBoth.delete();

        // ---------- 6) loadDefaults 复位 ----------
        mode(ROLE());
        org.broad.igv.primer.TailLibrary.loadDefaults();
        check(m() == STRAND(), "loadDefaults 把模式复位为按链方向");
        check(!org.broad.igv.primer.TailLibrary.slotAny(), "loadDefaults 清空所有槽位");

        System.out.println("==== TestP18: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

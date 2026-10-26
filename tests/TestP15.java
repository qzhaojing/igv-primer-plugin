/**
 * TestP15：v0.1.73 端槽位（F / R / R1 / R2 直接序列）+ 复制引物提级辅助
 *   覆盖：槽位设置/净化/清除 / R1R2 按 role 匹配、FR 按 strand 匹配 /
 *        槽位优先于库条目默认、R1R2 优先于 FR / 逐条仍最优先 /
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

        // ---------- 2) 匹配规则 ----------
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_F, "GGGG");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R, "TTTT");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "CCCC");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R2, "GGCC");
        check(org.broad.igv.primer.TailLibrary.slotAny(), "四槽全设 → slotAny=true");

        org.broad.igv.primer.Primer f = p("F1", '+', "R1");
        org.broad.igv.primer.Primer r = p("R1", '-', "R2");
        // R1/R2 更具体，优先于 F/R（都填时）
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(f)),
                "F 引物(role=R1)：R1 端槽优先于 F 端槽");
        check("GGCC".equals(org.broad.igv.primer.TailLibrary.seqFor(r)),
                "R 引物(role=R2)：R2 端槽优先于 R 端槽");
        // 只填 F/R 时按 strand 匹配
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R2, "");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(f)), "R1 槽清空后 F 引物回落 F 端槽");
        check("TTTT".equals(org.broad.igv.primer.TailLibrary.seqFor(r)), "R2 槽清空后 R 引物回落 R 端槽");
        // role 不是 R1/R2 的引物直接按 strand
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "CCCC");
        org.broad.igv.primer.Primer odd = p("X", '+', "R1");
        odd.role = "manual";
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(odd)),
                "role 非 R1/R2 → 不吃 R1 槽，按 strand 走 F 端槽");
        // 槽位优先于库条目默认（此时 R1 槽=CCCC 仍生效，f 命中 R1 端槽）
        org.broad.igv.primer.TailLibrary.setActive("LibB");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(f)),
                "槽位（R1端 CCCC）优先于统一条目默认（LibB=AAAA）");
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
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "");   // 先清 R1 槽 → f 落到 F 端槽
        check(org.broad.igv.primer.TailLibrary.nameFor(f).startsWith("F端:"),
                "槽位来源标注 F端: " + org.broad.igv.primer.TailLibrary.nameFor(f));
        check("F端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(f)), "scopeFor=F端槽");
        org.broad.igv.primer.TailLibrary.setSlot(org.broad.igv.primer.TailLibrary.SLOT_R1, "CCCC");
        check("R1端槽".equals(org.broad.igv.primer.TailLibrary.scopeFor(f)), "设 R1 槽后 scopeFor=R1端槽");
        check(org.broad.igv.primer.TailLibrary.nameFor(f).startsWith("R1端:"),
                "槽位来源标注 R1端: " + org.broad.igv.primer.TailLibrary.nameFor(f));

        // ---------- 4) XML 保存-加载往返 ----------
        java.io.File tmp = java.io.File.createTempFile("tails73", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML 成功");
        org.broad.igv.primer.TailLibrary.loadDefaults();   // 全清
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)),
                "loadDefaults 清空槽位");
        check(org.broad.igv.primer.TailLibrary.load(tmp), "加载 XML 成功");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R1)),
                "往返后 R1 端槽一致");
        check("".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_R2)),
                "往返后 R2 端槽一致（第 2 节已清空）");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.slotSeq(org.broad.igv.primer.TailLibrary.SLOT_F)),
                "往返后 F 端槽一致");
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

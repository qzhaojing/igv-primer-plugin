/**
 * TestP14：v0.1.72 5′ 附加三级作用域（逐条 &gt; 方向默认 &gt; 统一默认）
 *   覆盖：F/R 分开设置 / 方向默认回落统一 / setActive 清空方向默认 /
 *        逐条覆盖（库条目、自定义序列、明确不附加）/ 显式指定忽略 apply 方向 /
 *        nameFor / scopeFor / BED 段往返 / XML 保留方向默认 / 删条目自愈
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP14 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static org.broad.igv.primer.TailLibrary.Tail t(String n, String s, String a) {
        return new org.broad.igv.primer.TailLibrary.Tail(n, s, "自定义", a, "");
    }

    static org.broad.igv.primer.Primer p(String name, char strand) {
        return new org.broad.igv.primer.Primer(name, "chr1", 100, 120, strand, "R1", 50, null);
    }

    public static void main(String[] args) throws Exception {
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> lib =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        lib.add(t("B", "AAAA", "BOTH"));
        lib.add(t("Fonly", "CCCC", "F"));
        lib.add(t("Ronly", "GGGG", "R"));
        org.broad.igv.primer.TailLibrary.setEntries(lib);

        // ---------- 1) 方向默认：F / R 各设一个 ----------
        org.broad.igv.primer.TailLibrary.setActive(null);
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        org.broad.igv.primer.TailLibrary.setDefault('-', "Ronly");
        check(org.broad.igv.primer.TailLibrary.split(), "设了方向默认 → split()=true");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "F 向取方向默认 Fonly=CCCC");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "R 向取方向默认 Ronly=GGGG");
        check("CCCCTTTT".equals(org.broad.igv.primer.TailLibrary.apply("TTTT", '+')), "apply(+) 拼在最前面");
        check("GGGGTTTT".equals(org.broad.igv.primer.TailLibrary.apply("TTTT", '-')), "apply(-) 拼接结果正确");
        String sum = org.broad.igv.primer.TailLibrary.summaryLine();
        check(sum.contains("F:Fonly") && sum.contains("R:Ronly"), "summaryLine 分方向显示：" + sum);

        // 只设一边：另一边回落统一默认
        org.broad.igv.primer.TailLibrary.setActive("B");
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "只设 F → F 仍用方向默认");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "只设 F → R 回落统一默认 B");

        // 设统一默认会清掉方向默认（否则界面上「改了没生效」）
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        org.broad.igv.primer.TailLibrary.setDefault('-', "Ronly");
        org.broad.igv.primer.TailLibrary.setActive("B");
        check(!org.broad.igv.primer.TailLibrary.split(), "setActive 统一 → 清空方向默认");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "统一后 F=B");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "统一后 R=B");

        // ---------- 2) 逐条覆盖 ----------
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        org.broad.igv.primer.TailLibrary.setDefault('-', "Ronly");
        org.broad.igv.primer.Primer pf = p("P1", '+');
        org.broad.igv.primer.Primer pr = p("P2", '-');
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(pf)), "未逐条设置 → F 用方向默认");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "未逐条设置 → R 用方向默认");

        pr.tailName = "B";
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "逐条指定 B → 覆盖 R 方向默认");
        // 显式指定「仅F」条目给 R 向引物：用户点名要，不再按 apply 过滤方向
        pr.tailName = "Fonly";
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "逐条显式指定忽略 apply 方向");
        check("Fonly".equals(org.broad.igv.primer.TailLibrary.nameFor(pr)), "nameFor=本条条目名");

        pr.tailName = org.broad.igv.primer.TailLibrary.NONE;
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "逐条设为不附加 → 挡掉全局默认");
        check(org.broad.igv.primer.TailLibrary.nameFor(pr) == null, "不附加时 nameFor=null");
        check("本条不加".equals(org.broad.igv.primer.TailLibrary.scopeFor(pr)), "scopeFor=本条不加");

        pr.tailName = null;
        pr.tailSeq = " acgt tt ";
        check("ACGTTT".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "自定义序列优先且被净化");
        check(org.broad.igv.primer.TailLibrary.nameFor(pr).startsWith("自定义:"), "nameFor 标为自定义");
        check("本条自定义".equals(org.broad.igv.primer.TailLibrary.scopeFor(pr)), "scopeFor=本条自定义");
        // 自定义序列 + 库条目同时存在 → 序列优先
        pr.tailName = "B";
        check("ACGTTT".equals(org.broad.igv.primer.TailLibrary.seqFor(pr)), "tailSeq 优先于 tailName");
        check("ACGTTTTTTT".equals(org.broad.igv.primer.TailLibrary.apply("TTTT", pr)), "apply(Primer) 拼接正确");

        pr.tailSeq = null;
        pr.tailName = null;
        check("方向默认(R)".equals(org.broad.igv.primer.TailLibrary.scopeFor(pr)), "回落后 scopeFor=方向默认(R)");
        org.broad.igv.primer.TailLibrary.setDefault('+', null);
        org.broad.igv.primer.TailLibrary.setDefault('-', null);
        org.broad.igv.primer.TailLibrary.setActive("B");
        check("全局".equals(org.broad.igv.primer.TailLibrary.scopeFor(pf)), "统一模式 scopeFor=全局");

        // 库中已删的同名条目 → 回落全局，不抛异常
        pf.tailName = "不存在的条目";
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor(pf)), "逐条引用失效条目 → 回落全局");
        pf.tailName = null;

        // ---------- 3) BED / FASTA 段往返 ----------
        org.broad.igv.primer.Primer b1 = p("P3", '+');
        b1.tailName = "B";
        b1.tailSeq = null;
        check(b1.bedName().contains("|tB"), "bedName 含 |t 条目名段");
        org.broad.igv.primer.Primer b2 = p("P4", '-');
        b2.tailName = org.broad.igv.primer.TailLibrary.NONE;
        b2.tailSeq = "AACCGG";
        check(b2.bedName().contains("|t-") && b2.bedName().contains("|xAACCGG"), "bedName 含 |t- 与 |x 序列段");

        String bed = "chr1\t100\t120\t" + b1.bedName() + "\t0\t+\n"
                + "chr1\t200\t220\t" + b2.bedName() + "\t0\t-\n";
        java.util.List<org.broad.igv.primer.Primer> got = new java.util.ArrayList<org.broad.igv.primer.Primer>();
        int n = org.broad.igv.primer.ExportUtils.parseBEDText(bed, "g", got);
        check(n == 2, "BED 解析出 2 条");
        check(n == 2 && "B".equals(got.get(0).tailName), "往返保留 |t 条目名");
        check(n == 2 && got.get(1).tailSeq != null && "AACCGG".equals(got.get(1).tailSeq), "往返保留 |x 自定义序列");
        check(n == 2 && org.broad.igv.primer.TailLibrary.NONE.equals(got.get(1).tailName), "往返保留「不附加」标记");
        check(n == 2 && "AACCGG".equals(org.broad.igv.primer.TailLibrary.seqFor(got.get(1))),
                "|t- 与 |x 并存 → tailSeq 优先（AACCGG）");
        got.get(1).tailSeq = null;   // 去掉自定义序列后「不附加」应生效
        check(n == 2 && "".equals(org.broad.igv.primer.TailLibrary.seqFor(got.get(1))),
                "仅 |t- 时生效为「不附加」（挡掉全局）");

        org.broad.igv.primer.Primer plain = p("P5", '+');
        check(!plain.bedName().contains("|t") && !plain.bedName().contains("|x"), "未设置时 bedName 不带附加段");

        // ---------- 4) XML 保留方向默认 ----------
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        org.broad.igv.primer.TailLibrary.setDefault('-', "Ronly");
        java.io.File tmp = java.io.File.createTempFile("tails72", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML 成功");
        org.broad.igv.primer.TailLibrary.setEntries(lib);   // 打乱
        org.broad.igv.primer.TailLibrary.setActive(null);
        check(!org.broad.igv.primer.TailLibrary.split(), "重设后方向默认已清空");
        check(org.broad.igv.primer.TailLibrary.load(tmp), "重新加载 XML 成功");
        check(org.broad.igv.primer.TailLibrary.split(), "往返后仍为分开模式");
        check("Fonly".equals(org.broad.igv.primer.TailLibrary.defaultName('+')), "往返后 F 向默认一致");
        check("Ronly".equals(org.broad.igv.primer.TailLibrary.defaultName('-')), "往返后 R 向默认一致");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "往返后 F 向序列一致");
        tmp.delete();

        // 旧版 XML（只有 active，无 defaultF/defaultR）加载后仍按统一默认工作
        java.io.File old = java.io.File.createTempFile("tailsOld", ".xml");
        java.nio.file.Files.write(old.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\" active=\"B\">"
                        + "<tail><name>B</name><type>自定义</type><seq>AAAA</seq><apply>BOTH</apply><note></note></tail>"
                        + "<tail><name>Fonly</name><type>自定义</type><seq>CCCC</seq><apply>F</apply><note></note></tail>"
                        + "</tailLibrary>").getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(old), "旧版 XML（无方向默认）加载成功");
        check(!org.broad.igv.primer.TailLibrary.split(), "旧版 XML → 非分开模式");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "旧版 XML 统一默认生效");
        old.delete();

        // ---------- 5) 删除条目时方向默认自愈 ----------
        org.broad.igv.primer.TailLibrary.setEntries(lib);
        org.broad.igv.primer.TailLibrary.setDefault('+', "Fonly");
        org.broad.igv.primer.TailLibrary.setDefault('-', "Ronly");
        check(org.broad.igv.primer.TailLibrary.remove("Fonly"), "删除 Fonly 成功");
        check(org.broad.igv.primer.TailLibrary.defaultName('+') == null, "删条目后 F 向默认自动清空");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "删条目不影响 R 向默认");
        org.broad.igv.primer.TailLibrary.setEntries(lib);

        System.out.println("==== TestP14: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

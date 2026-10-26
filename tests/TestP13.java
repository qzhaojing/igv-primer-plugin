/**
 * TestP13：v0.1.71 5′ 附加序列库（TailLibrary）
 *   覆盖：序列净化 / apply 归一化 / 按方向取附加序列 / 拼接 / 启用失效自愈 /
 *        排序 / 内置预置 / XML 保存-加载往返 / 非法 XML 拒绝
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP13 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static org.broad.igv.primer.TailLibrary.Tail t(String n, String s, String a) {
        return new org.broad.igv.primer.TailLibrary.Tail(n, s, "自定义", a, "");
    }

    public static void main(String[] args) throws Exception {

        // ---------- 1) 序列净化 ----------
        check("GAATTC".equals(org.broad.igv.primer.TailLibrary.sanitize(" gaa ttc ")),
                "sanitize 去空格转大写");
        check("GCGGCCGC".equals(org.broad.igv.primer.TailLibrary.sanitize("gcg-gccgc")),
                "sanitize 去连字符");
        check("AATGATACGGCGACCACCGAGATCTACAC".equals(
                        org.broad.igv.primer.TailLibrary.sanitize("AATGATACGGCGACCACCGAGATCTACAC 123")),
                "sanitize 去数字");
        check("".equals(org.broad.igv.primer.TailLibrary.sanitize(null)), "sanitize null → 空串");

        // ---------- 2) apply 归一化 ----------
        check("BOTH".equals(org.broad.igv.primer.TailLibrary.normApply(null)), "normApply null → BOTH");
        check("F".equals(org.broad.igv.primer.TailLibrary.normApply("f")), "normApply f → F");
        check("R".equals(org.broad.igv.primer.TailLibrary.normApply("-")), "normApply - → R");
        check("BOTH".equals(org.broad.igv.primer.TailLibrary.normApply("乱写")), "normApply 未知 → BOTH");

        // ---------- 3) 按方向取附加序列 ----------
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> lib =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        lib.add(t("B", "AAAA", "BOTH"));
        lib.add(t("Fonly", "CCCC", "F"));
        lib.add(t("Ronly", "GGGG", "R"));
        org.broad.igv.primer.TailLibrary.setEntries(lib);

        check(!org.broad.igv.primer.TailLibrary.enabled(), "初始未启用");
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "未启用 → 空序列");
        check("ACGT".equals(org.broad.igv.primer.TailLibrary.apply("ACGT", '+')), "未启用 apply 原样返回");

        org.broad.igv.primer.TailLibrary.setActive("B");
        check(org.broad.igv.primer.TailLibrary.enabled(), "启用 B 成功");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "BOTH 项 F 向取到");
        check("AAAA".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "BOTH 项 R 向取到");
        check("AAAAACGT".equals(org.broad.igv.primer.TailLibrary.apply("ACGT", '+')), "apply 拼到 5′ 端（前置）");

        org.broad.igv.primer.TailLibrary.setActive("Fonly");
        check("CCCC".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "仅F 项 正向取到");
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "仅F 项 反向不加");
        check("ACGT".equals(org.broad.igv.primer.TailLibrary.apply("ACGT", '-')), "仅F 项 反向 apply 不加");

        org.broad.igv.primer.TailLibrary.setActive("Ronly");
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "仅R 项 正向不加");
        check("GGGG".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "仅R 项 反向取到");

        // ---------- 4) 启用失效自愈 ----------
        org.broad.igv.primer.TailLibrary.setActive("不存在");
        check(!org.broad.igv.primer.TailLibrary.enabled(), "启用不存在的条目 → 自动关闭");
        org.broad.igv.primer.TailLibrary.setActive("B");
        org.broad.igv.primer.TailLibrary.remove("B");
        check(!org.broad.igv.primer.TailLibrary.enabled(), "删除启用项 → 自动关闭");
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "删除启用项后序列为空");

        // ---------- 5) 排序 ----------
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> l2 =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        l2.add(t("A1", "AA", "BOTH"));
        l2.add(t("A2", "CC", "BOTH"));
        l2.add(t("A3", "GG", "BOTH"));
        org.broad.igv.primer.TailLibrary.setEntries(l2);
        org.broad.igv.primer.TailLibrary.move("A3", -1);
        check(org.broad.igv.primer.TailLibrary.indexOf("A3") == 1, "move 上移一位（A3 → index 1）");
        org.broad.igv.primer.TailLibrary.move("A1", -1);
        check(org.broad.igv.primer.TailLibrary.indexOf("A1") == 0, "move 越界不动（A1 仍在首位）");
        org.broad.igv.primer.TailLibrary.move("A1", 5);
        check(org.broad.igv.primer.TailLibrary.indexOf("A1") == 0, "move 超出末尾不动");

        // ---------- 6) 内置预置 ----------
        org.broad.igv.primer.TailLibrary.loadDefaults();
        int n = org.broad.igv.primer.TailLibrary.entries().size();
        check(n >= 25, "内置预置条目数 " + n + " ≥ 25");
        org.broad.igv.primer.TailLibrary.Tail eco = org.broad.igv.primer.TailLibrary.get("EcoRI");
        check(eco != null && "GAATTC".equals(eco.seq), "预置含 EcoRI=GAATTC");
        check(eco != null && "酶切位点".equals(eco.type), "EcoRI 分类=酶切位点");
        org.broad.igv.primer.TailLibrary.Tail p5 = org.broad.igv.primer.TailLibrary.get("Illumina P5 overhang");
        check(p5 != null && "F".equals(p5.apply), "Illumina P5 默认仅 F 向");
        org.broad.igv.primer.TailLibrary.Tail p7 = org.broad.igv.primer.TailLibrary.get("Illumina P7 overhang");
        check(p7 != null && "R".equals(p7.apply), "Illumina P7 默认仅 R 向");
        check(org.broad.igv.primer.TailLibrary.get("Gibson 同源臂(示例占位)") != null, "预置含同源臂示例");
        check(org.broad.igv.primer.TailLibrary.get("His6") != null, "预置含 His6 标签");
        check(!org.broad.igv.primer.TailLibrary.enabled(), "loadDefaults 后处于未启用态");

        // ---------- 7) XML 保存 / 加载往返 ----------
        java.util.List<org.broad.igv.primer.TailLibrary.Tail> l3 =
                new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>();
        l3.add(new org.broad.igv.primer.TailLibrary.Tail("建库接头<测试>", "AATTCCGG", "接头", "BOTH", "含 & 特殊字符 <note>"));
        l3.add(new org.broad.igv.primer.TailLibrary.Tail("酶切 EcoRI", "GAATTC", "酶切位点", "F", "保护碱基 GC"));
        org.broad.igv.primer.TailLibrary.setEntries(l3);
        org.broad.igv.primer.TailLibrary.setActive("酶切 EcoRI");
        java.io.File tmp = java.io.File.createTempFile("tails", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML 成功: " + tmp.getName());
        String xml = new String(java.nio.file.Files.readAllBytes(tmp.toPath()), "UTF-8");
        check(xml.contains("tailLibrary"), "XML 根元素正确");
        check(xml.contains("GAATTC"), "XML 含序列");
        check(xml.contains("&amp;"), "XML 特殊字符已转义（&amp;）");

        org.broad.igv.primer.TailLibrary.loadDefaults();   // 清空
        check(org.broad.igv.primer.TailLibrary.entries().size() != 2, "加载前先清空为预置库");
        check(org.broad.igv.primer.TailLibrary.load(tmp), "加载 XML 成功");
        check(org.broad.igv.primer.TailLibrary.entries().size() == 2, "加载后条目数=2");
        org.broad.igv.primer.TailLibrary.Tail back = org.broad.igv.primer.TailLibrary.get("建库接头<测试>");
        check(back != null && "AATTCCGG".equals(back.seq), "往返后序列一致");
        check(back != null && "含 & 特殊字符 <note>".equals(back.note), "往返后备注（含特殊字符）一致");
        check("酶切 EcoRI".equals(org.broad.igv.primer.TailLibrary.activeName()), "往返后启用项一致");
        check("GAATTC".equals(org.broad.igv.primer.TailLibrary.seqFor('+')), "往返后启用项 F 向可取");
        check("".equals(org.broad.igv.primer.TailLibrary.seqFor('-')), "往返后启用项 R 向不加（apply=F）");
        tmp.delete();

        // ---------- 8) 非法 XML ----------
        java.io.File bad = java.io.File.createTempFile("bad", ".xml");
        java.nio.file.Files.write(bad.toPath(), "这不是 XML <<<".getBytes("UTF-8"));
        int before = org.broad.igv.primer.TailLibrary.entries().size();
        check(!org.broad.igv.primer.TailLibrary.load(bad), "非法 XML 加载失败（返回 false）");
        check(org.broad.igv.primer.TailLibrary.entries().size() == before, "非法 XML 不破坏现有库");
        bad.delete();
        check(!org.broad.igv.primer.TailLibrary.load(new java.io.File("不存在的文件.xml")), "不存在的文件加载失败");

        System.out.println("==== TestP13: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

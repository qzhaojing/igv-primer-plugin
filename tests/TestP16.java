/**
 * TestP16：v0.1.76 自建类别（本地库分类分级）
 *   覆盖：类别列表（内置+自建）/ 新增去重 / 删除自建（条目降为自定义、内置拒绝）/
 *        重命名（条目 type 跟随，含子类前缀）/ pathOf、leafOf、inCategory /
 *        XML 往返保留自建类别（空类别也留着）/ loadDefaults 清自建 / 预置含二代、三代接头分类
 * 纯逻辑测试（不依赖 AWT），跑法同 TestP3。
 */
public class TestP16 {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static org.broad.igv.primer.TailLibrary.Tail t(String n, String s, String type) {
        return new org.broad.igv.primer.TailLibrary.Tail(n, s, type, "BOTH", "");
    }

    public static void main(String[] args) throws Exception {
        org.broad.igv.primer.TailLibrary.loadDefaults();   // 内置预置 + 清空自建类别

        // ---------- 1) 内置类别 ----------
        java.util.List<String> cs = org.broad.igv.primer.TailLibrary.categories();
        check(cs.contains("酶切位点"), "内置含「酶切位点」");
        check(cs.contains("接头/二代接头"), "内置含二级「接头/二代接头」");
        check(cs.contains("接头/三代接头"), "内置含二级「接头/三代接头」");
        check(cs.contains("自定义"), "内置含「自定义」");

        // ---------- 2) 预置条目已按二代/三代归类 ----------
        check("接头/二代接头".equals(org.broad.igv.primer.TailLibrary.get("Illumina P5 overhang").type),
                "Illumina P5 归到二代接头");
        check("接头/三代接头".equals(org.broad.igv.primer.TailLibrary.get("PacBio 通用接头").type),
                "PacBio 归到三代接头");
        check("接头/三代接头".equals(org.broad.igv.primer.TailLibrary.get("Nanopore 接头").type),
                "Nanopore 归到三代接头");

        // ---------- 3) 路径解析 ----------
        String[] p1 = org.broad.igv.primer.TailLibrary.pathOf("接头/二代接头");
        check(p1.length == 2 && "接头".equals(p1[0]) && "二代接头".equals(p1[1]), "pathOf 两级切分");
        check("二代接头".equals(org.broad.igv.primer.TailLibrary.leafOf("接头/二代接头")), "leafOf 取末段");
        check("酶切位点".equals(org.broad.igv.primer.TailLibrary.leafOf("酶切位点")), "leafOf 单级=本身");
        check(org.broad.igv.primer.TailLibrary.inCategory("接头/二代接头", "接头"), "inCategory 子类属父类");
        check(org.broad.igv.primer.TailLibrary.inCategory("接头", "接头"), "inCategory 自身");
        check(!org.broad.igv.primer.TailLibrary.inCategory("酶切位点", "接头"), "inCategory 不相关=false");

        // ---------- 4) 自建类别 ----------
        check(org.broad.igv.primer.TailLibrary.addCategory("我的元件/启动子"), "新增自建类别成功");
        check(org.broad.igv.primer.TailLibrary.categories().contains("我的元件/启动子"), "自建类别进入列表");
        check(!org.broad.igv.primer.TailLibrary.addCategory("我的元件/启动子"), "重复新增被拒");
        check(!org.broad.igv.primer.TailLibrary.addCategory("酶切位点"), "内置同名新增被拒");
        check(!org.broad.igv.primer.TailLibrary.addCategory("  "), "空白类别被拒");

        // 删条目里用到的类别：降为自定义，不删条目
        org.broad.igv.primer.TailLibrary.setEntries(new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>());
        org.broad.igv.primer.TailLibrary.add(t("T7p", "TAATACGACTCACTATAGGG", "我的元件/启动子"));
        org.broad.igv.primer.TailLibrary.add(t("T7p2", "GGGG", "我的元件/启动子/强"));
        org.broad.igv.primer.TailLibrary.add(t("E", "GAATTC", "酶切位点"));
        int moved = org.broad.igv.primer.TailLibrary.removeCategory("我的元件/启动子");
        check(moved == 2, "删自建类别 → 2 条被改类型（实际 " + moved + "）");
        check("自定义".equals(org.broad.igv.primer.TailLibrary.get("T7p").type), "条目降为「自定义」");
        check("自定义".equals(org.broad.igv.primer.TailLibrary.get("T7p2").type), "子类条目也降为「自定义」");
        check("酶切位点".equals(org.broad.igv.primer.TailLibrary.get("E").type), "无关条目不受影响");
        check(org.broad.igv.primer.TailLibrary.get("T7p") != null, "删类别不删条目（序列还在）");
        check(org.broad.igv.primer.TailLibrary.removeCategory("酶切位点") == -1, "内置类别拒绝删除");

        // ---------- 5) 重命名类别 ----------
        org.broad.igv.primer.TailLibrary.setEntries(new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>());
        org.broad.igv.primer.TailLibrary.add(t("A1", "AAAA", "接头"));
        org.broad.igv.primer.TailLibrary.add(t("A2", "CCCC", "接头/二代接头"));
        int rn = org.broad.igv.primer.TailLibrary.renameCategory("接头", "测序接头");
        check(rn == 2, "重命名影响 2 条（实际 " + rn + "）");
        check("测序接头".equals(org.broad.igv.primer.TailLibrary.get("A1").type), "本体 type 已改名");
        check("测序接头/二代接头".equals(org.broad.igv.primer.TailLibrary.get("A2").type), "子类前缀一起改");
        check(org.broad.igv.primer.TailLibrary.renameCategory("测序接头", "") == -1, "空名拒绝重命名");
        org.broad.igv.primer.TailLibrary.renameCategory("测序接头", "接头");

        // ---------- 6) XML 往返（空类别也要留着） ----------
        org.broad.igv.primer.TailLibrary.setEntries(new java.util.ArrayList<org.broad.igv.primer.TailLibrary.Tail>());
        org.broad.igv.primer.TailLibrary.add(t("X1", "ACGT", "酶切位点"));
        org.broad.igv.primer.TailLibrary.addCategory("空类别（占位）");
        java.io.File tmp = java.io.File.createTempFile("tails76", ".xml");
        check(org.broad.igv.primer.TailLibrary.save(tmp), "保存 XML 成功");
        org.broad.igv.primer.TailLibrary.loadDefaults();
        check(!org.broad.igv.primer.TailLibrary.categories().contains("空类别（占位）"), "loadDefaults 清自建类别");
        check(org.broad.igv.primer.TailLibrary.load(tmp), "加载 XML 成功");
        check(org.broad.igv.primer.TailLibrary.categories().contains("空类别（占位）"),
                "往返后空类别仍在（长久保存）");
        check("ACGT".equals(org.broad.igv.primer.TailLibrary.get("X1").seq), "往返后条目序列一致");
        tmp.delete();

        // 旧版 XML（无 categories 节点）加载不报错；平铺「接头」自动迁移到二代/三代/其它
        java.io.File old = java.io.File.createTempFile("tailsOld76", ".xml");
        java.nio.file.Files.write(old.toPath(),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tailLibrary version=\"1\" active=\"\">"
                        + "<tail><name>Illumina P5</name><type>接头</type><seq>TTTT</seq><apply>F</apply><note></note></tail>"
                        + "<tail><name>PacBio 接头</name><type>接头</type><seq>GGGG</seq><apply>BOTH</apply><note></note></tail>"
                        + "<tail><name>某接头</name><type>接头</type><seq>CCCC</seq><apply>BOTH</apply><note></note></tail>"
                        + "</tailLibrary>").getBytes("UTF-8"));
        check(org.broad.igv.primer.TailLibrary.load(old), "旧版 XML 加载成功");
        check("接头/二代接头".equals(org.broad.igv.primer.TailLibrary.get("Illumina P5").type),
                "旧库 Illumina → 二代接头");
        check("接头/三代接头".equals(org.broad.igv.primer.TailLibrary.get("PacBio 接头").type),
                "旧库 PacBio → 三代接头");
        check("接头/其它".equals(org.broad.igv.primer.TailLibrary.get("某接头").type),
                "认不出的旧接头 → 接头/其它");
        old.delete();

        System.out.println("==== TestP16: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

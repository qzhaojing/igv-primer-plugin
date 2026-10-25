package org.broad.igv.primer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.1.71：5′ 附加序列库（5′ Tail / Add-on）。
 *
 * <p>用途：导出 FASTA 时给引物（或测序读段）的 5′ 端拼一段额外序列。同一套机制覆盖
 * 多种实验场景，故取通用名「5′ 附加序列（Tail）」而非「接头」：
 * <ul>
 *   <li>测序/建库接头（adapter、overhang、i5/i7、Barcode）</li>
 *   <li>载体构建的酶切位点（± 保护碱基）、同源臂（Gibson / In-Fusion）</li>
 *   <li>启动子 / 通用引物尾巴（T7、SP6、M13F/R）</li>
 *   <li>蛋白标签编码序列（FLAG、HA、His6、Myc）</li>
 * </ul>
 *
 * <p>库以 XML 存放（默认 <code>~/.igv_primer_tails.xml</code>），可保存 / 加载 / 修改后再保存；
 * 每次导出时按「启用条目 + 应用方向（F / R / 双向）」自动拼接到 5′ 端，并在 FASTA
 * header 写入 <code>|tail=&lt;名称&gt;</code> 以便回溯。
 *
 * <h3>v0.1.72：三级作用域（解决「R1/R2 接头不同、每条酶切位点不同」）</h3>
 * <ol>
 *   <li><b>逐条覆盖</b>：引物自带 tailSeq（自定义序列）或 tailName（库条目名，
 *       <code>"-"</code> = 明确不加）。命中即生效，且<b>不再受条目 apply 方向限制</b>
 *       ——用户显式指定优先于条目的隐含方向。</li>
 *   <li><b>方向默认</b>：defaultF（+ 链引物）/ defaultR（- 链引物），可各设一个条目，
 *       解决 R1/R2 接头不同、F/R 尾巴不同的场景。</li>
 *   <li><b>统一默认</b>：active（旧版字段），F/R 同用一个条目；仅当方向默认未设时生效。</li>
 * </ol>
 * 优先级：逐条 &gt; 方向默认 &gt; 统一默认。
 *
 * <p>纯数据类，不依赖 AWT，可 headless 单测。
 */
public class TailLibrary {

    /** 单条 5′ 附加序列。 */
    public static class Tail {
        public String name;    // 名称（唯一，工具栏显示）
        public String seq;     // 序列（5'->3'，已净化为大写字母）
        public String type;    // 分类：酶切位点 / 接头 / 通用引物 / 标签 / 同源臂 / 自定义
        public String apply;   // BOTH / F / R
        public String note;    // 备注

        public Tail() { this("", "", "自定义", "BOTH", ""); }

        public Tail(String name, String seq, String type, String apply, String note) {
            this.name = name;
            this.seq = sanitize(seq);
            this.type = (type == null || type.isEmpty()) ? "自定义" : type;
            this.apply = normApply(apply);
            this.note = note == null ? "" : note;
        }

        @Override
        public String toString() {
            return name + (seq == null || seq.isEmpty() ? "" : "  [" + seq + "]");
        }
    }

    public static final String APPLY_BOTH = "BOTH";
    public static final String APPLY_F = "F";
    public static final String APPLY_R = "R";

    /** v0.1.72：逐条设置里「明确不附加」的标记（存 Primer.tailName），用于覆盖全局默认。 */
    public static final String NONE = "-";
    /** v0.1.72：逐条设置里「继承全局/方向默认」的显示名（实际存 null）。 */
    public static final String INHERIT = null;

    /** 分类候选（管理对话框下拉用）。 */
    public static final String[] TYPES = {"酶切位点", "接头", "通用引物", "标签", "同源臂", "自定义"};

    private static final List<Tail> entries = new ArrayList<Tail>();
    private static String active = null;      // 统一默认条目名；null/""/"关" = 不附加（旧版字段，兼容保留）
    private static String defaultF = null;    // v0.1.72：F 向（+ 链引物）方向默认条目名；null=未设（回落 active）
    private static String defaultR = null;    // v0.1.72：R 向（- 链引物）方向默认条目名
    private static File currentFile = null;   // 当前库文件路径（保存到 / 另存为 用）

    // ---------- 文件 ----------

    /** 默认库文件：~/.igv_primer_tails.xml */
    public static File defaultFile() {
        return new File(System.getProperty("user.home"), ".igv_primer_tails.xml");
    }

    public static synchronized File currentFile() {
        return currentFile;
    }

    /** 首次使用：默认文件存在则加载，否则装内置预置并落盘一份（用户可直接改）。 */
    public static synchronized void init() {
        if (!entries.isEmpty()) return;
        File f = defaultFile();
        if (f.exists()) {
            if (load(f)) return;
        }
        loadDefaults();
        save(f);
    }

    // ---------- 条目访问 ----------

    public static synchronized List<Tail> entries() {
        init();
        List<Tail> out = new ArrayList<Tail>();
        for (Tail t : entries) out.add(t);
        return out;
    }

    public static synchronized Tail get(String name) {
        if (name == null) return null;
        for (Tail t : entries) if (name.equals(t.name)) return t;
        return null;
    }

    public static synchronized void setEntries(List<Tail> list) {
        entries.clear();
        if (list != null) entries.addAll(list);
        if (active != null && get(active) == null) active = null;   // 启用项被删则自动关闭
        if (defaultF != null && get(defaultF) == null) defaultF = null;
        if (defaultR != null && get(defaultR) == null) defaultR = null;
    }

    public static synchronized void add(Tail t) {
        if (t == null || t.name == null || t.name.trim().isEmpty()) return;
        entries.add(t);
    }

    public static synchronized boolean remove(String name) {
        Tail t = get(name);
        if (t == null) return false;
        entries.remove(t);
        if (name.equals(active)) active = null;
        if (name.equals(defaultF)) defaultF = null;
        if (name.equals(defaultR)) defaultR = null;
        return true;
    }

    public static synchronized void move(String name, int delta) {
        int i = indexOf(name);
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= entries.size()) return;
        Tail t = entries.remove(i);
        entries.add(j, t);
    }

    public static synchronized int indexOf(String name) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(name)) return i;
        return -1;
    }

    // ---------- 启用 / 应用 ----------

    public static synchronized String activeName() {
        return (active == null || active.isEmpty()) ? null : active;
    }

    /**
     * v0.1.72：设为「统一默认」（F / R 同用一个条目）会自动清掉方向默认，
     * 否则方向默认会一直盖住统一设置，界面上看起来「改了没生效」。
     */
    public static synchronized void setActive(String name) {
        defaultF = null;
        defaultR = null;
        if (name == null || name.isEmpty() || "关".equals(name)) { active = null; return; }
        if (get(name) == null) { active = null; return; }
        active = name;
    }

    public static synchronized boolean enabled() {
        return activeName() != null && get(activeName()) != null;
    }

    /** v0.1.72：任一方向当前实际会附加（含方向默认）。工具条高亮用。 */
    public static synchronized boolean anyEnabled() {
        return !seqFor('+').isEmpty() || !seqFor('-').isEmpty();
    }

    public static synchronized Tail activeEntry() {
        return enabled() ? get(activeName()) : null;
    }

    // ---------- v0.1.72 方向默认（F / R 可不同）----------

    /** F/R 是否已分开设置（任一方向默认非空即算分开）。 */
    public static synchronized boolean split() {
        return defaultF != null || defaultR != null;
    }

    /** 某方向的方向默认条目名（未设返回 null）。 */
    public static synchronized String defaultName(char strand) {
        return strand == '+' ? defaultF : defaultR;
    }

    /** 设某方向的方向默认；name 为空 / "关" / 库中不存在 → 视为不设（回落统一默认）。 */
    public static synchronized void setDefault(char strand, String name) {
        String v = null;
        if (name != null && !name.isEmpty() && !"关".equals(name) && get(name) != null) v = name;
        if (strand == '+') defaultF = v; else defaultR = v;
    }

    /**
     * 某方向「当前生效的默认条目名」：方向默认 &gt; 统一默认。
     */
    public static synchronized String effectiveDefaultName(char strand) {
        String d = defaultName(strand);
        if (d != null) return get(d) == null ? null : d;
        return activeName();
    }

    /** 全局设置的一句话摘要（导出提示 / 工具条 tooltip 用），如 "F:EcoRI / R:XhoI" 或 "统一:EcoRI"。 */
    public static synchronized String summaryLine() {
        if (split()) {
            String f = effectiveDefaultName('+');
            String r = effectiveDefaultName('-');
            return "F:" + (f == null ? "无" : f) + " / R:" + (r == null ? "无" : r);
        }
        String n = activeName();
        return n == null ? "无" : ("统一:" + n);
    }

    // ---------- 解析 ----------

    /**
     * 导出时实际拼到 5′ 端的序列（按方向默认 / 统一默认）。
     *
     * @param strand '+' = F 向（正向引物），'-' = R 向；按条目 apply 过滤。
     * @return 附加序列（大写）；未启用 / 方向不匹配 / 空序列时返回空串。
     */
    public static synchronized String seqFor(char strand) {
        String nm = effectiveDefaultName(strand);
        Tail t = nm == null ? null : get(nm);
        if (t == null || t.seq == null || t.seq.isEmpty()) return "";
        if (APPLY_F.equals(t.apply) && strand != '+') return "";
        if (APPLY_R.equals(t.apply) && strand != '-') return "";
        return t.seq;
    }

    /** 给一段序列拼 5′ 附加（按方向默认；未启用则原样返回）。 */
    public static String apply(String seq, char strand) {
        if (seq == null) return "";
        String t = seqFor(strand);
        return t.isEmpty() ? seq : t + seq;
    }

    // ---------- v0.1.72 逐条覆盖 ----------

    /**
     * 某条引物最终生效的 5′ 附加序列。
     * 优先级：本条自定义序列 &gt; 本条指定库条目（不看 apply 方向）&gt; 方向默认 &gt; 统一默认。
     * 本条 tailName 为 {@link #NONE} 时明确不附加（连全局默认一起挡掉）。
     */
    public static synchronized String seqFor(Primer p) {
        if (p == null) return "";
        if (p.tailSeq != null && !p.tailSeq.isEmpty()) return sanitize(p.tailSeq);
        String nm = p.tailName == null ? null : p.tailName.trim();
        if (nm != null && !nm.isEmpty()) {
            if (NONE.equals(nm)) return "";
            Tail t = get(nm);
            if (t != null && t.seq != null) return t.seq;   // 显式指定 → 不再按 apply 过滤方向
        }
        return seqFor(p.strand);
    }

    /** 某条引物附加来源的名称（写 FASTA header / 界面显示用）；无附加返回 null。 */
    public static synchronized String nameFor(Primer p) {
        if (p == null) return null;
        if (p.tailSeq != null && !p.tailSeq.isEmpty()) return "自定义:" + sanitize(p.tailSeq);
        String nm = p.tailName == null ? null : p.tailName.trim();
        if (nm != null && !nm.isEmpty()) {
            if (NONE.equals(nm)) return null;
            if (get(nm) != null) return nm;
        }
        return seqFor(p.strand).isEmpty() ? null : effectiveDefaultName(p.strand);
    }

    /** 附加来源说明：本条 / 本条自定义 / 方向默认(F|R) / 全局 / 无。 */
    public static synchronized String scopeFor(Primer p) {
        if (p == null) return "无";
        if (p.tailSeq != null && !p.tailSeq.isEmpty()) return "本条自定义";
        String nm = p.tailName == null ? null : p.tailName.trim();
        if (nm != null && !nm.isEmpty()) {
            if (NONE.equals(nm)) return "本条不加";
            if (get(nm) != null) return "本条";
        }
        if (seqFor(p.strand).isEmpty()) return "无";
        return defaultName(p.strand) != null ? ("方向默认" + (p.strand == '+' ? "(F)" : "(R)")) : "全局";
    }

    /** 给某条引物的序列拼 5′ 附加（含逐条覆盖）。 */
    public static String apply(String seq, Primer p) {
        if (seq == null) return "";
        String t = seqFor(p);
        return t.isEmpty() ? seq : t + seq;
    }

    // ---------- 净化 / 归一化 ----------

    /** 序列净化：去所有非字母字符、转大写（空格、连字符、数字、小写一并处理）。 */
    public static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z]", "").toUpperCase();
    }

    /** apply 归一化：F/R（含中文「正/反」）之外一律 BOTH。 */
    public static String normApply(String a) {
        if (a == null) return APPLY_BOTH;
        String u = a.trim().toUpperCase();
        if (u.equals("F") || u.equals("+") || u.equals("正") || u.equals("正向") || u.equals("F向")) return APPLY_F;
        if (u.equals("R") || u.equals("-") || u.equals("反") || u.equals("反向") || u.equals("R向")) return APPLY_R;
        return APPLY_BOTH;
    }

    // ---------- 内置预置 ----------

    /** 内置预置库（酶切位点 / 接头 / 通用引物 / 标签 / 同源臂示例）。 */
    public static synchronized void loadDefaults() {
        entries.clear();
        active = null;
        defaultF = null;
        defaultR = null;
        // —— 酶切位点（纯位点；实际使用时建议在 5′ 端再补 2–6 个保护碱基）——
        add(new Tail("EcoRI", "GAATTC", "酶切位点", APPLY_BOTH, "G^AATTC；建议前置保护碱基 GC/C"));
        add(new Tail("BamHI", "GGATCC", "酶切位点", APPLY_BOTH, "G^GATCC；建议前置保护碱基 CGC"));
        add(new Tail("HindIII", "AAGCTT", "酶切位点", APPLY_BOTH, "A^AGCTT；建议前置保护碱基 CC/CCC"));
        add(new Tail("XhoI", "CTCGAG", "酶切位点", APPLY_BOTH, "C^TCGAG；常与 SalI 配对（同尾）"));
        add(new Tail("SalI", "GTCGAC", "酶切位点", APPLY_BOTH, "G^TCGAC；与 XhoI 同尾，可互换连接"));
        add(new Tail("NheI", "GCTAGC", "酶切位点", APPLY_BOTH, "G^CTAGC；与 SpeI/XbaI 同尾"));
        add(new Tail("SpeI", "ACTAGT", "酶切位点", APPLY_BOTH, "A^CTAGT；BioBrick 常用"));
        add(new Tail("XbaI", "TCTAGA", "酶切位点", APPLY_BOTH, "T^CTAGA；BioBrick 常用"));
        add(new Tail("KpnI", "GGTACC", "酶切位点", APPLY_BOTH, "GGTAC^C"));
        add(new Tail("SacI", "GAGCTC", "酶切位点", APPLY_BOTH, "GAGCT^C"));
        add(new Tail("PstI", "CTGCAG", "酶切位点", APPLY_BOTH, "CTGCA^G；3′ 突出端"));
        add(new Tail("NotI", "GCGGCCGC", "酶切位点", APPLY_BOTH, "GC^GGCCGC；8 bp 稀有位点，建议前置保护碱基"));
        add(new Tail("NdeI", "CATATG", "酶切位点", APPLY_BOTH, "CA^TATG；含 ATG，常用于 N 端融合表达"));
        add(new Tail("AgeI", "ACCGGT", "酶切位点", APPLY_BOTH, "A^CCGGT"));
        add(new Tail("MluI", "ACGCGT", "酶切位点", APPLY_BOTH, "A^CGCGT"));
        add(new Tail("BglII", "AGATCT", "酶切位点", APPLY_BOTH, "A^GATCT；与 BamHI 同尾"));
        add(new Tail("ClaI", "ATCGAT", "酶切位点", APPLY_BOTH, "AT^CGAT"));
        add(new Tail("EcoRV", "GATATC", "酶切位点", APPLY_BOTH, "GAT^ATC；平末端"));
        add(new Tail("SmaI", "CCCGGG", "酶切位点", APPLY_BOTH, "CCC^GGG；平末端，GC 含量高"));
        add(new Tail("ApaI", "GGGCCC", "酶切位点", APPLY_BOTH, "GGGCC^C；3′ 突出端"));
        // —— 建库 / 测序接头（overhang 形式，二轮 PCR 用）——
        add(new Tail("Illumina P5 overhang", "AATGATACGGCGACCACCGAGATCTACAC", "接头", APPLY_F,
                "Illumina 二轮 PCR 正向端（P5 侧）overhang；后面接 i5 index"));
        add(new Tail("Illumina P7 overhang", "CAAGCAGAAGACGGCATACGAGAT", "接头", APPLY_R,
                "Illumina 二轮 PCR 反向端（P7 侧）overhang；后面接 i7 index"));
        add(new Tail("PacBio 通用接头", "GCAGTCGAACATGTAGCTGACTCAGGTCAC", "接头", APPLY_BOTH, "PacBio 建库通用接头示例"));
        add(new Tail("Nanopore 接头", "TTTCTGTTGGTGCTGATATTGC", "接头", APPLY_BOTH, "ONT 建库接头示例（按需替换）"));
        // —— 通用引物 / 启动子尾巴 ——
        add(new Tail("M13F(-21)", "TGTAAAACGACGGCCAGT", "通用引物", APPLY_F, "M13 正向通用测序引物"));
        add(new Tail("M13R", "CAGGAAACAGCTATGACC", "通用引物", APPLY_R, "M13 反向通用测序引物"));
        add(new Tail("T7 promoter", "TAATACGACTCACTATAGGG", "通用引物", APPLY_F, "T7 RNA 聚合酶启动子（体外转录）"));
        add(new Tail("SP6 promoter", "ATTTAGGTGACACTATAG", "通用引物", APPLY_F, "SP6 RNA 聚合酶启动子"));
        add(new Tail("T3 promoter", "AATTAACCCTCACTAAAGGG", "通用引物", APPLY_F, "T3 RNA 聚合酶启动子"));
        // —— 蛋白标签编码序列 ——
        add(new Tail("FLAG", "GACTACAAGGACGACGATGACAAG", "标签", APPLY_BOTH, "FLAG 标签（DYKDDDDK），常加在 C/N 端"));
        add(new Tail("HA", "TACCCATACGATGTTCCAGATTACGCT", "标签", APPLY_BOTH, "HA 标签（YPYDVPDYA）"));
        add(new Tail("His6", "CACCACCACCACCACCAC", "标签", APPLY_BOTH, "6×His 标签（HHHHHH）"));
        add(new Tail("Myc", "GAACAAAAACTCATCTCAGAAGAGGATCTG", "标签", APPLY_BOTH, "Myc 标签（EQKLISEEDL）"));
        // —— 同源臂示例 ——
        add(new Tail("Gibson 同源臂(示例)", "ACGTACGTACGTACGTACGT", "同源臂", APPLY_BOTH,
                "示例占位：改成与载体末端同源的 20–30 bp（无缝克隆）"));
    }

    // ---------- XML ----------

    /** 当前库序列化为 XML 文本（测试/预览用）。 */
    public static synchronized String toXml() {
        try {
            Document doc = newDoc();
            Element root = doc.createElement("tailLibrary");
            root.setAttribute("version", "1");
            root.setAttribute("active", active == null ? "" : active);
            root.setAttribute("defaultF", defaultF == null ? "" : defaultF);
            root.setAttribute("defaultR", defaultR == null ? "" : defaultR);
            doc.appendChild(root);
            for (Tail t : entries) {
                Element e = doc.createElement("tail");
                e.appendChild(kid(doc, "name", t.name));
                e.appendChild(kid(doc, "type", t.type));
                e.appendChild(kid(doc, "seq", t.seq));
                e.appendChild(kid(doc, "apply", t.apply));
                e.appendChild(kid(doc, "note", t.note));
                root.appendChild(e);
            }
            Transformer tr = TransformerFactory.newInstance().newTransformer();
            tr.setOutputProperty(OutputKeys.INDENT, "yes");
            tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            tr.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 保存到指定文件（同时记为当前库文件）。 */
    public static synchronized boolean save(File f) {
        String xml = toXml();
        if (f == null || xml == null) return false;
        FileOutputStream out = null;
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            out = new FileOutputStream(f);
            out.write(xml.getBytes("UTF-8"));
            out.close();
            out = null;
            currentFile = f;
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignore) { }
        }
    }

    /** 保存到当前库文件（无则默认文件）。 */
    public static synchronized boolean save() {
        return save(currentFile != null ? currentFile : defaultFile());
    }

    /** 从 XML 加载（覆盖当前库）。 */
    public static synchronized boolean load(File f) {
        if (f == null || !f.exists()) return false;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) { }
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(f);
            doc.getDocumentElement().normalize();
            Element root = doc.getDocumentElement();
            if (root == null || !"tailLibrary".equals(root.getTagName())) return false;
            List<Tail> got = new ArrayList<Tail>();
            NodeList ns = root.getElementsByTagName("tail");
            for (int i = 0; i < ns.getLength(); i++) {
                Node n = ns.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String nm = text(e, "name");
                if (nm == null || nm.trim().isEmpty()) continue;
                got.add(new Tail(nm, text(e, "seq"), text(e, "type"), text(e, "apply"), text(e, "note")));
            }
            entries.clear();
            entries.addAll(got);
            active = root.getAttribute("active");
            if (active != null) {
                if (active.trim().isEmpty() || get(active) == null) active = null;
            }
            currentFile = f;
            // v0.1.72：方向默认（缺省=未设；库中已无此名则回落统一默认）
            String df = root.getAttribute("defaultF");
            String dr = root.getAttribute("defaultR");
            defaultF = (df == null || df.trim().isEmpty() || get(df) == null) ? null : df;
            defaultR = (dr == null || dr.trim().isEmpty() || get(dr) == null) ? null : dr;
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String text(Element parent, String tag) {
        NodeList ns = parent.getElementsByTagName(tag);
        if (ns == null || ns.getLength() == 0) return "";
        Node n = ns.item(0);
        return n == null ? "" : n.getTextContent();
    }

    // ---------- DOM 辅助 ----------

    private static Document newDoc() throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    }

    private static Element kid(Document doc, String tag, String val) {
        Element e = doc.createElement(tag);
        e.setTextContent(val == null ? "" : val);
        return e;
    }
}

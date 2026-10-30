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

    /** 分类候选（管理对话框下拉用）。v0.1.76：支持用 "/" 分级，如「接头/二代接头」。 */
    public static final String[] TYPES = {"酶切位点", "接头/二代接头", "接头/三代接头", "通用引物", "标签",
            "同源臂", "自定义"};

    /** v0.1.76：用户自建类别（存 XML，可长久保留；与内置类别合并展示）。 */
    private static final List<String> extraCats = new ArrayList<String>();

    private static final List<Tail> entries = new ArrayList<Tail>();
    private static String active = null;      // 统一默认条目名；null/""/"关" = 不附加（旧版字段，兼容保留）
    private static String defaultF = null;    // v0.1.72：F 向（+ 链引物）方向默认条目名；null=未设（回落 active）
    private static String defaultR = null;    // v0.1.72：R 向（- 链引物）方向默认条目名
    // v0.1.73：四个「端槽位」——直接存序列（管理对话框右侧文本框），优先于库条目默认。
    // R1/R2 按 role 匹配（建库场景：read1/read2 两端接头不同），F/R 按 strand 匹配（克隆场景）。
    // R1 通常对应 F 向引物、R2 对应 R 向；两类都填时 R1/R2 更具体、优先。
    private static String slotF = "";
    private static String slotR = "";
    private static String slotR1 = "";
    private static String slotR2 = "";

    /** 端槽位编号（TailManagerDialog 右侧四个文本框）。 */
    public static final int SLOT_F = 0, SLOT_R = 1, SLOT_R1 = 2, SLOT_R2 = 3;

    /**
     * v0.1.79：端槽位模式。F/R（链方向）与 R1/R2（测序读段编号）是**两个正交维度**，
     * 不能互相等同，也不能同时生效——F 端既可能是 R1 也可能是 R2（取决于建库方案：
     * 常规 PCR / 克隆只谈 F、R；双端测序才分 R1、R2，且谁对应正链看试剂盒设计）。
     * 因此做成两种互斥模式，一次只用一种，另一组的值保留但不起作用。
     */
    public static final int MODE_STRAND = 0;   // 按链方向：F 端（+ 链）/ R 端（- 链）
    public static final int MODE_ROLE = 1;     // 按测序读段：R1 端 / R2 端
    private static int slotMode = MODE_STRAND;
    /** 槽位显示名（预览 / header 来源标注用）。 */
    public static final String[] SLOT_NAMES = {"F端", "R端", "R1端", "R2端"};
    private static File currentFile = null;   // 当前库文件路径（保存到 / 另存为 用）

    // ---------- v0.1.76 类别（可自建，持久化）----------

    /** 全部类别：内置 + 用户自建（去重保序）。 */
    public static synchronized List<String> categories() {
        List<String> out = new ArrayList<String>();
        for (String c : TYPES) if (!out.contains(c)) out.add(c);
        for (String c : extraCats) if (c != null && !c.trim().isEmpty() && !out.contains(c)) out.add(c);
        // 条目里用到的类别也算（兼容旧库 / 手改 XML）
        for (Tail t : entries) {
            if (t != null && t.type != null && !t.type.trim().isEmpty() && !out.contains(t.type)) out.add(t.type);
        }
        return out;
    }

    /** 新增自建类别（空 / 已存在返回 false）。 */
    public static synchronized boolean addCategory(String c) {
        if (c == null) return false;
        String v = c.trim();
        if (v.isEmpty() || categories().contains(v)) return false;
        extraCats.add(v);
        return true;
    }

    /**
     * 删除自建类别（内置类别删不掉）。同时把该类别及其子类下的条目 type 降级为「自定义」，
     * 不删条目本身——只清分类，避免误删老板攒的序列。
     *
     * @return 被改类型的条目数（-1 = 拒绝删除）
     */
    public static synchronized int removeCategory(String c) {
        if (c == null) return -1;
        if (!extraCats.contains(c)) return -1;
        extraCats.remove(c);
        int n = 0;
        for (Tail t : entries) {
            if (t != null && inCategory(t.type, c)) { t.type = "自定义"; n++; }
        }
        return n;
    }

    /** 重命名类别（内置也能改）：把该类别及子类下条目的 type 前缀一起改。 */
    public static synchronized int renameCategory(String oldC, String newC) {
        if (oldC == null || newC == null) return -1;
        String nv = newC.trim();
        if (nv.isEmpty() || oldC.equals(nv)) return -1;
        int n = 0;
        for (Tail t : entries) {
            if (t == null || t.type == null) continue;
            if (t.type.equals(oldC)) { t.type = nv; n++; }
            else if (t.type.startsWith(oldC + "/")) { t.type = nv + t.type.substring(oldC.length()); n++; }
        }
        int i = extraCats.indexOf(oldC);
        if (i >= 0) extraCats.set(i, nv);
        else if (categories().indexOf(nv) < 0) extraCats.add(nv);
        return n;
    }

    /** type 是否属于某类别（含子类）。 */
    public static boolean inCategory(String type, String cat) {
        if (type == null || cat == null) return false;
        return type.equals(cat) || type.startsWith(cat + "/");
    }

    /** 分类路径按 "/" 切分（用于树形展示）。 */
    public static String[] pathOf(String type) {
        String t = (type == null || type.trim().isEmpty()) ? "自定义" : type.trim();
        return t.split("/");
    }

    /**
     * v0.1.76：加载旧库后，把平铺的「接头」按名称启发式归到二代 / 三代子类，
     * 认不出的进「接头/其它」。（不删条目，只改分类。）
     */
    private static void migrateLegacyCategory() {
        for (Tail t : entries) {
            if (t == null || !"接头".equals(t.type)) continue;
            String n = t.name == null ? "" : t.name.toLowerCase();
            if (n.contains("illumina") || n.contains("miseq") || n.contains("nextseq")
                    || n.contains("novaseq") || n.contains("hiseq") || n.contains("i5")
                    || n.contains("i7") || n.contains("bgi") || n.contains("mgiseq")) {
                t.type = "接头/二代接头";
            } else if (n.contains("pacbio") || n.contains("nanopore") || n.contains("minion")
                    || n.contains("promethion") || n.contains("ont")) {
                t.type = "接头/三代接头";
            } else {
                t.type = "接头/其它";
            }
        }
    }

    /** 分类的最后一段（叶子显示名）。 */
    public static String leafOf(String type) {
        String[] p = pathOf(type);
        return p[p.length - 1];
    }

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

    // ---------- v0.1.73 端槽位（直接序列，F / R / R1 / R2）----------

    /** 某端槽位当前序列（未设返回空串）。 */
    public static synchronized String slotSeq(int slot) {
        String s = slotField(slot);
        return s == null ? "" : s;
    }

    /** 设某端槽位序列（自动净化；null/空 = 清除该槽）。 */
    public static synchronized void setSlot(int slot, String seq) {
        String v = sanitize(seq);
        if (slot == SLOT_F) slotF = v;
        else if (slot == SLOT_R) slotR = v;
        else if (slot == SLOT_R1) slotR1 = v;
        else if (slot == SLOT_R2) slotR2 = v;
    }

    /** 是否设置了任一端槽位。 */
    public static synchronized boolean slotAny() {
        return !slotF.isEmpty() || !slotR.isEmpty() || !slotR1.isEmpty() || !slotR2.isEmpty();
    }

    /** 按引物 role（R1/R2）取槽序列（仅供界面显示；是否生效取决于当前模式）。 */
    public static synchronized String slotSeqForRole(String role) {
        if ("R1".equals(role) && !slotR1.isEmpty()) return slotR1;
        if ("R2".equals(role) && !slotR2.isEmpty()) return slotR2;
        return "";
    }

    /** 按引物 strand（F/R）取槽序列（仅供界面显示；是否生效取决于当前模式）。 */
    public static synchronized String slotSeqForStrand(char strand) {
        if (strand == '+' && !slotF.isEmpty()) return slotF;
        if (strand == '-' && !slotR.isEmpty()) return slotR;
        return "";
    }

    // ---------- v0.1.79 槽位模式（F/R 与 R1/R2 二选一）----------

    /** 当前模式：{@link #MODE_STRAND} 或 {@link #MODE_ROLE}。 */
    public static synchronized int slotMode() {
        return slotMode;
    }

    /** 切换模式（只接受 MODE_STRAND / MODE_ROLE，其余按 MODE_STRAND 处理）。 */
    public static synchronized void setSlotMode(int m) {
        slotMode = (m == MODE_ROLE) ? MODE_ROLE : MODE_STRAND;
    }

    /** 当前模式下真正生效的两个槽位。 */
    public static synchronized int[] activeSlots() {
        return slotMode == MODE_ROLE ? new int[]{SLOT_R1, SLOT_R2} : new int[]{SLOT_F, SLOT_R};
    }

    /**
     * 实际生效的模式：当某一组端槽位有值、另一组全空时，自动采用「有值的那组」，
     * 避免「填了 R1/R2 却停在 F/R 模式」导致附加序列既不显示、导出也被静默丢弃的坑
     * （v0.2.4 修复：用户截图即此 —— R1/R2 端已填序列，但模式停在 F/R，引物结构预览
     * 只画 F/R 空端，导出也因 slotHit 按链方向落到空 F/R 槽而丢了接头）。
     * 两组都有值或都空 → 沿用用户显式选择的 slotMode（保留其明确意图）。
     */
    public static synchronized int effectiveMode() {
        boolean role = !slotR1.isEmpty() || !slotR2.isEmpty();
        boolean strand = !slotF.isEmpty() || !slotR.isEmpty();
        if (role && !strand) return MODE_ROLE;
        if (strand && !role) return MODE_STRAND;
        return slotMode;
    }

    /** 模式显示名。 */
    public static synchronized String modeLabel() {
        return slotMode == MODE_ROLE ? "按测序读段（R1 / R2）" : "按链方向（F / R）";
    }

    /**
     * 某条引物在当前模式下命中的槽位（返回槽位编号；-1 = 未命中，调用方继续回落到库条目默认）。
     * 只按当前模式的维度匹配，绝不跨维度（“F 就是 R1”这种假设不成立）。
     *
     * <p><b>注意「空槽 = 未命中」</b>：链方向模式下每条引物都能对上一个槽，若空槽也算命中，
     * 库条目默认（统一默认 / F、R 向默认）就永远轮不上——这是 v0.1.79 重写时踩的坑，
     * 现象是「在库里点了设为统一默认，导出却不附加」。
     */
    public static synchronized int slotHit(Primer p) {
        if (p == null) return -1;
        int s = -1;
        if (slotMode == MODE_ROLE) {
            if ("R1".equals(p.role)) s = SLOT_R1;
            else if ("R2".equals(p.role)) s = SLOT_R2;
        } else {
            s = (p.strand == '+') ? SLOT_F : SLOT_R;
        }
        if (s < 0 || slotSeq(s).isEmpty()) return -1;   // 空槽视为未命中
        return s;
    }

    private static String slotField(int slot) {
        if (slot == SLOT_F) return slotF;
        if (slot == SLOT_R) return slotR;
        if (slot == SLOT_R1) return slotR1;
        if (slot == SLOT_R2) return slotR2;
        return "";
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

    /**
     * 某引物端槽解析：角色槽(R1/R2)优先于链向槽(F/R)，哪个槽有值用哪个；都不填返回 -1。
     * v0.2.4 修复：原先 {@link #slotHit} 只按当前 mode 拨盘取一个维度，导致 R1/R2 已填但 mode 在 F/R 时
     * 该适配器被完全忽略、导出与结构预览都丢尾巴。改为角色维度优先、链向维度兜底，与优先级注释一致。
     */
    private static int endSlotFor(Primer p) {
        if (p == null) return -1;
        int roleSlot = "R1".equals(p.role) ? SLOT_R1 : "R2".equals(p.role) ? SLOT_R2 : -1;
        if (roleSlot >= 0 && !slotSeq(roleSlot).isEmpty()) return roleSlot;
        int strandSlot = (p.strand == '+') ? SLOT_F : SLOT_R;
        if (!slotSeq(strandSlot).isEmpty()) return strandSlot;
        return -1;
    }

    // ---------- v0.1.72 逐条覆盖 ----------

    /**
     * 某条引物最终生效的 5′ 附加序列。
     * 优先级：本条自定义序列 &gt; 本条指定库条目（不看 apply 方向）&gt; R1/R2 端槽 &gt; F/R 端槽
     * &gt; 方向默认条目 &gt; 统一默认条目。
     * 本条 tailName 为 {@link #NONE} 时明确不附加（连下面的默认一起挡掉）。
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
        int slot = endSlotFor(p);
        if (slot >= 0 && !slotSeq(slot).isEmpty()) return slotSeq(slot);
        return seqFor(p.strand);   // 空槽 / 未命中 → 回落库条目默认
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
        int slot = endSlotFor(p);
        if (slot >= 0 && !slotSeq(slot).isEmpty()) {
            return SLOT_NAMES[slot] + ":" + shortSeq8(slotSeq(slot));
        }
        return seqFor(p.strand).isEmpty() ? null : effectiveDefaultName(p.strand);
    }

    /** 附加来源说明：本条 / 本条自定义 / 端槽(R1端|R2端|F端|R端) / 方向默认(F|R) / 全局 / 无。 */
    public static synchronized String scopeFor(Primer p) {
        if (p == null) return "无";
        if (p.tailSeq != null && !p.tailSeq.isEmpty()) return "本条自定义";
        String nm = p.tailName == null ? null : p.tailName.trim();
        if (nm != null && !nm.isEmpty()) {
            if (NONE.equals(nm)) return "本条不加";
            if (get(nm) != null) return "本条";
        }
        int slot = endSlotFor(p);
        if (slot >= 0 && !slotSeq(slot).isEmpty()) return SLOT_NAMES[slot] + "槽";
        if (seqFor(p.strand).isEmpty()) return "无";
        return defaultName(p.strand) != null ? ("方向默认" + (p.strand == '+' ? "(F)" : "(R)")) : "全局";
    }

    private static String shortSeq8(String s) {
        if (s == null) return "";
        return s.length() <= 8 ? s : s.substring(0, 8) + "…";
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
        extraCats.clear();
        slotMode = MODE_STRAND;
        active = null;
        defaultF = null;
        defaultR = null;
        slotF = "";
        slotR = "";
        slotR1 = "";
        slotR2 = "";
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
        // —— 建库 / 测序接头：序列取自厂商官方文件（v0.1.77）——
        // Illumina：P5 / P7 流动池序列 + Nextera mosaic end（ME）见 Illumina《Adapter Sequences》文档
        // 与 support.illumina.com "Oligonucleotide Sequences" 页；i5/i7 index 用「Bases in Adapter」
        // 方向（即合成进引物的实际序列；与 sample sheet 填写方向不同，i7 恰好是它的反向互补）。
        final String P5 = "AATGATACGGCGACCACCGAGATCTACAC";
        final String P7 = "CAAGCAGAAGACGGCATACGAGAT";
        final String ME1 = "TCGTCGGCAGCGTCAGATGTGTATAAGAGACAG";   // Read1 侧 ME = Read1 测序引物
        final String ME2 = "GTCTCGTGGGCTCGGAGATGTGTATAAGAGACAG";   // Read2 侧 ME = Read2 测序引物
        add(new Tail("Illumina P5 overhang", P5, "接头/二代接头", APPLY_F,
                "Illumina 官方：P5 流动池结合序列（二轮 PCR 正向端，后面接 i5 index）"));
        add(new Tail("Illumina P7 overhang", P7, "接头/二代接头", APPLY_R,
                "Illumina 官方：P7 流动池结合序列（二轮 PCR 反向端，后面接 i7 index）"));
        add(new Tail("Nextera i5 全长（无 index）", P5 + ME1, "接头/二代接头", APPLY_F,
                "P5 + Read1 测序引物（ME 全长，62nt）；单样本/无需 index 时用"));
        add(new Tail("Nextera i7 全长（无 index）", P7 + ME2, "接头/二代接头", APPLY_R,
                "P7 + Read2 测序引物（ME 全长，58nt）；单样本/无需 index 时用"));
        // 带 index 的完整 index 引物（Nextera XT 常用 6+6，序列取自 Illumina Adapter Sequences 文档）
        String[] i5n = {"S502", "S503", "S505", "S506", "S507", "S508"};
        String[] i5s = {"CTCTCTAT", "TATCCTCT", "GTAAGGAG", "ACTGCATA", "AAGGAGTA", "CTAAGCCT"};
        for (int i = 0; i < i5n.length; i++) {
            add(new Tail("Nextera i5 引物 " + i5n[i], P5 + i5s[i] + ME1, "接头/index(i5)", APPLY_F,
                    "P5 + i5 index " + i5s[i] + " + ME（70nt）；Illumina Adapter Sequences「Bases in Adapter」方向"));
        }
        String[] i7n = {"N701", "N702", "N703", "N704", "N705", "N706"};
        String[] i7s = {"TCGCCTTA", "CTAGTACG", "TTCTGCCT", "GCTCAGGA", "AGGAGTCC", "CATGCCTA"};
        for (int i = 0; i < i7n.length; i++) {
            add(new Tail("Nextera i7 引物 " + i7n[i], P7 + i7s[i] + ME2, "接头/index(i7)", APPLY_R,
                    "P7 + i7 index " + i7s[i] + " + ME（66nt）；sample sheet 上填的是它的反向互补（如 N701=TAAGGCGA）"));
        }
        // —— 三代（PacBio / ONT）——
        // PacBio：16S 全长扩增方案官方「universal tail」，二轮用 Barcoded Universal Primers（PN 100-466-100）
        add(new Tail("PacBio 通用尾（正向）", "GCAGTCGAACATGTAGCTGACTCAGGTCAC", "接头/三代接头", APPLY_F,
                "PacBio 16S 全长扩增官方通用尾（30nt）；一轮 PCR 加在正向引物 5′"));
        add(new Tail("PacBio 通用尾（反向）", "TGGATCACTTGTGCAAGCATCACATCGTAG", "接头/三代接头", APPLY_R,
                "PacBio 16S 全长扩增官方通用尾（31nt）；一轮 PCR 加在反向引物 5′"));
        // ONT：官方 PCR barcoding 协议（SQK-LSK110/LSK114 + EXP-PBC096/PBC001）的「universal tailing sequences」
        add(new Tail("ONT 一轮加尾（正向）", "TTTCTGTTGGTGCTGATATTGC", "接头/三代接头", APPLY_F,
                "ONT 官方 PCR barcoding 通用尾（22nt，正向）"));
        add(new Tail("ONT 一轮加尾（反向）", "ACTTGCCTGTCGCTCTATCTTC", "接头/三代接头", APPLY_R,
                "ONT 官方 PCR barcoding 通用尾（22nt，反向）"));
        // —— 通用引物 / 启动子尾巴 ——
        add(new Tail("M13F(-21)", "TGTAAAACGACGGCCAGT", "通用引物", APPLY_F, "M13 正向通用测序引物"));
        add(new Tail("M13R", "CAGGAAACAGCTATGACC", "通用引物", APPLY_R, "M13 反向通用测序引物"));
        add(new Tail("T7 promoter", "TAATACGACTCACTATAGGG", "通用引物", APPLY_F, "T7 RNA 聚合酶启动子（体外转录）"));
        add(new Tail("SP6 promoter", "ATTTAGGTGACACTATAG", "通用引物", APPLY_F, "SP6 RNA 聚合酶启动子"));
        add(new Tail("T3 promoter", "AATTAACCCTCACTAAAGGG", "通用引物", APPLY_F, "T3 RNA 聚合酶启动子"));
        // Illumina 官方寡核苷酸序列（TruSeq 文库测序引物；也可用于 Sanger 验证/测序）
        add(new Tail("Illumina Read1 测序引物", "ACACTCTTTCCCTACACGACGCTCTTCCGATCT", "通用引物", APPLY_F,
                "Illumina 官方 Read1 sequencing primer（33nt）"));
        add(new Tail("Illumina Read2 测序引物", "GTGACTGGAGTTCAGACGTGTGCTCTTCCGATCT", "通用引物", APPLY_R,
                "Illumina 官方 Read2 sequencing primer（34nt）"));
        // —— 蛋白标签编码序列 ——
        add(new Tail("FLAG", "GACTACAAGGACGACGATGACAAG", "标签", APPLY_BOTH, "FLAG 标签（DYKDDDDK），常加在 C/N 端"));
        add(new Tail("HA", "TACCCATACGATGTTCCAGATTACGCT", "标签", APPLY_BOTH, "HA 标签（YPYDVPDYA）"));
        add(new Tail("His6", "CACCACCACCACCACCAC", "标签", APPLY_BOTH, "6×His 标签（HHHHHH）"));
        add(new Tail("Myc", "GAACAAAAACTCATCTCAGAAGAGGATCTG", "标签", APPLY_BOTH, "Myc 标签（EQKLISEEDL）"));
        // —— 同源臂示例 ——
        add(new Tail("Gibson 同源臂(示例占位)", "ACGTACGTACGTACGTACGT", "同源臂", APPLY_BOTH,
                "占位模板：改成与载体末端同源的 20–30 bp（Gibson / In-Fusion / HiFi 无缝克隆）。"
                        + "要点：与载体末端完全同源、GC 40–60%、避免与引物 3′ 端互补、同源臂自身不形成发夹"));
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
            root.setAttribute("slotF", slotF);
            root.setAttribute("slotR", slotR);
            root.setAttribute("slotR1", slotR1);
            root.setAttribute("slotR2", slotR2);
            root.setAttribute("slotMode", String.valueOf(slotMode));
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
            // v0.1.76：用户自建类别（空类别也能长久保存）
            Element cats = doc.createElement("categories");
            for (String c : extraCats) cats.appendChild(kid(doc, "category", c));
            root.appendChild(cats);
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
            // v0.1.76：自建类别（缺省为空）
            List<String> cats = new ArrayList<String>();
            NodeList cn = root.getElementsByTagName("category");
            for (int i = 0; i < cn.getLength(); i++) {
                Node n = cn.item(i);
                if (n == null) continue;
                String c = n.getTextContent();
                if (c != null && !c.trim().isEmpty() && !cats.contains(c)) cats.add(c.trim());
            }
            extraCats.clear();
            extraCats.addAll(cats);
            migrateLegacyCategory();   // v0.1.76：旧库的平铺「接头」→ 二代/三代子类
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
            // v0.1.73：端槽位序列（缺省=未设）
            slotF = sanitize(root.getAttribute("slotF"));
            slotR = sanitize(root.getAttribute("slotR"));
            slotR1 = sanitize(root.getAttribute("slotR1"));
            slotR2 = sanitize(root.getAttribute("slotR2"));
            // v0.1.79：槽位模式。旧库无该属性时自动推断：R1/R2 有值而 F/R 为空 → 读段模式
            String sm = root.getAttribute("slotMode");
            if (sm != null && sm.trim().equals("1")) slotMode = MODE_ROLE;
            else if (sm != null && sm.trim().equals("0")) slotMode = MODE_STRAND;
            else slotMode = (!slotR1.isEmpty() || !slotR2.isEmpty())
                    && slotF.isEmpty() && slotR.isEmpty() ? MODE_ROLE : MODE_STRAND;
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

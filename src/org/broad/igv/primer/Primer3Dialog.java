package org.broad.igv.primer;

import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.ui.IGV;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.1.56：primer3 设计引物对话框（设计入口）。
 * v0.1.57：支持三种设计模式——
 *  - 成对（MODE_PAIR）：简单=指定产物区间；高级=左/右端双设计区（区内模式，见 v0.1.80）；
 *    均走 SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 白名单（左引物落左端带、右引物落右端带）；
 *  - 单引物 F 向（MODE_LEFT）/ R 向（MODE_RIGHT）：指定一个区域，引物必须落在选区内
 *    （SEQUENCE_INCLUDED_REGION 限定搜索范围；v0.1.69 起不再发 SEQUENCE_TARGET）。
 * 打开时自动读取 PrimerEditTool 的鼠标选区（空白处拖拽掠过区间）预填 chr 与起止。
 * 坐标输入均为 IGV 惯例 1-based inclusive。
 *
 * v0.1.59：无鼠标选区时回退自动填充当前 IGV 视口（chr+range）；起止倒转自动交换；
 * 产物区间长于产物上限时自动放大上限（否则 primer3 必无解）；新增可折叠「高级参数」
 * 面板（盐浓度/dNTP/引物浓度、二聚体/发夹阈值、多聚X/N/GC钳，留空=primer3 默认）。
 *
 * v0.1.61：鼠标选区 → 一键填充「产物区间 + 左/右端引物设计区」。
 * v0.1.80：区内模型——引物设计区固定在「产物区间两端内侧」（F 向=左端、R 向=右端），
 *   引物只在这两个设计区内发生设计；runDesign 统一走 OK_REGION_LIST，不再发 SEQUENCE_TARGET
 *   （TARGET 会把引物 5' 强制顶到选区两端，过于受限且历史上常 0 对）。
 *
 * v0.1.62：修复大面积产物区间必无解（unacceptable product size 全灭）：
 *  - 产物上限自适应从"物理最小 tLen+2×lenMax"改为"模板可达范围 tLen+2×pad"
 *    （pad=max(2×最长引物,120)）：前者要求引物双双贴住选区边缘，候选位置漂几个 bp 即全灭；
 *    后者允许引物在选区两侧各外漂 pad，产物仍覆盖整个选区，primer3 自动挑最贴近的；
 *  - 模板容量检查 need 同步重算（旧公式 prodMax+2×lenMax 会在新 prodMax 下误报）；
 *  - 无解弹窗按 EXPLAIN 统计动态生成建议（GC/tm/产物范围/二聚体哪个失败多优先建议哪个）。
 *
 * v0.1.69：
 *  - 单引物误发 SEQUENCE_TARGET 修复：primer3 语义=引物不得覆盖 target，窄选区（如 19bp）
 *    下 18nt 候选仅 2 个且全部 overlap target → ok 0（老板截图实锤）。单引物只发
 *    SEQUENCE_INCLUDED_REGION；
 *  - 降条件自动重试（relaxAt 阶梯，默认开）：primer3 返回 0 对时逐级放宽筛选参数重试，
 *    6 级阶梯（Tm±2 → Tm±4+GC±5 → 放宽引物长 → 删组成约束 → 删二聚体约束 → 极限），
 *    成功后弹窗逐项明示"原始条件 → 降级后条件"+ 风险提示；区域约束（INCLUDED_REGION/
 *    TARGET/OK_REGION_LIST）永不动，引物必须落在选区内这条不变；
 *  - explainTips 扩展解析 "overlap target 2" 这类无 failed 后缀的统计词；
 *  - 无选区快捷设计：open(mode, quick) 无视选区按视口中心取 100bp 作引物区
 *    （"很多引物无法选区时想获得一个引物"的兜底路径）。
 *
 * 参数只暴露常用项（长度/Tm/GC/产物范围/返回对数），预设一键填充；高级参数走默认值。
 */
public class Primer3Dialog {

    public static final int MODE_PAIR = 0;    // 成对
    public static final int MODE_LEFT = 1;    // 仅 F 向（+）
    public static final int MODE_RIGHT = 2;   // 仅 R 向（-）

    /** v0.1.69："reason N" 统计段（primer3 EXPLAIN 无 failed 后缀的淘汰计数）。 */
    private static final java.util.regex.Pattern PLAIN_STAT =
            java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z ]*?)\\s+(\\d+)$");

    /** v0.1.69：降级阶梯最大级数（1..6；0=不降级）。 */
    public static final int MAX_RELAX_LEVEL = 6;

    /** 入口：打开设计对话框（默认成对）。 */
    public static void open() {
        open(MODE_PAIR);
    }

    /** 入口：打开设计对话框；有鼠标选区时自动预填。 */
    public static void open(int mode) {
        open(mode, false);
    }

    /**
     * v0.1.69：quick=true 无视鼠标选区，按当前视口中心取 100bp 作引物区/产物区
     * （"很多引物无法选区时想获得一个引物"的兜底路径；配合降级阶梯保证有产出）。
     */
    public static void open(int mode, boolean quick) {
        if (!Primer3Core.available()) {
            JOptionPane.showMessageDialog(IGV.getMainFrame(),
                    "primer3_core.exe 不可用（jar 资源缺失或无法释放到 ~/.igv_primer3/）。",
                    "primer3 设计", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Dlg(mode, quick).setVisible(true);
    }

    /**
     * v0.1.69：快速设计区域——视口中段取 wantLen bp（0-based 视野，返回 1-based inclusive 起止）。
     * 纯计算无 IO，便于测试；视野窄于 wantLen 时退化为整个视野。
     */
    public static int[] quickCenterRegion(int vpS0, int vpE0, int wantLen) {
        if (vpE0 - vpS0 <= wantLen) return new int[]{vpS0 + 1, vpE0};
        int mid = (vpS0 + vpE0 - 1) / 2;
        int qs = Math.max(vpS0, mid - wantLen / 2);
        int qe = qs + wantLen - 1;
        if (qe > vpE0 - 1) { qe = vpE0 - 1; qs = qe - wantLen + 1; }
        return new int[]{qs + 1, qe + 1};
    }

    /**
     * 区域 → 模板范围 + primer3 区域参数（纯计算，无 IO，便于测试）。
     *
     * @param mode   MODE_PAIR / MODE_LEFT / MODE_RIGHT
     * @param adv    true=高级模式（仅成对支持；单引物忽略）
     * @param s,e    简单模式区间（1-based inclusive；单引物=引物区域，成对=产物区间）
     * @param us,ul  高级模式左端设计区起(1-based)/长
     * @param ds,dl  高级模式右端设计区起(1-based)/长
     * @return {tmplStart0, tmplEnd0}（0-based，end exclusive）
     */
    static int[] regionToTemplate(int mode, boolean adv, int s, int e,
                                  int us, int ul, int ds, int dl,
                                  int chrLen, int lenMin, int lenMax) throws Exception {
        boolean single = (mode == MODE_LEFT || mode == MODE_RIGHT);
        if (single || !adv) {
            // 简单/单引物：区间 ± padding 作模板（容纳最长引物 + primer3 边界）
            int s0 = s - 1;
            if (s0 < 0 || e > chrLen || s0 >= e)
                throw new Exception("区域非法（应在 1-" + chrLen + " 内且起<止）");
            if (single && e - s0 < lenMin)
                throw new Exception("引物区域长度（" + (e - s0) + "bp）小于最短引物长（" + lenMin + "）");
            int pad = Math.max(lenMax * 2, 120);
            int ts = Math.max(0, s0 - pad);
            int te = Math.min(chrLen, e + pad);
            return new int[]{ts, te};
        }
        // 高级模式（成对）：模板覆盖左/右端设计区并各扩一个引物长度
        int us0 = us - 1;
        int ds0 = ds - 1;
        if (us0 < 0 || us0 + ul > chrLen || ds0 < 0 || ds0 + dl > chrLen)
            throw new Exception("左/右端设计区超出染色体范围");
        if (ul < lenMin || dl < lenMin)
            throw new Exception("左/右端设计区长度不能小于最短引物长（" + lenMin + "）");
        // v0.1.57：修复 v0.1.56 校验条件写反的 bug（原条件把"两区分离"这一正常场景误判为非法）。
        // 正确语义：左端设计区起点必须不晚于右端设计区起点（允许部分重叠；禁止左端整体在右侧）。
        if (us0 > ds0)
            throw new Exception("左端设计区应在右端设计区左侧（允许部分重叠）");
        int pad = lenMax * 2;
        int ts = Math.max(0, Math.min(us0, ds0) - pad);
        int te = Math.min(chrLen, Math.max(us0 + ul, ds0 + dl) + pad);
        return new int[]{ts, te};
    }

    /**
     * v0.1.59：当前 IGV 视口（主 frame）的 chr + 0-based [start, end)。
     * 无基因组/无实例时返回 null（headless 或未初始化场景安全）。
     */
    public static String[] currentViewRange() {
        try {
            org.broad.igv.ui.panel.ReferenceFrame f =
                    IGV.getInstance().getSession().getReferenceFrame();
            if (f == null || f.getChrName() == null) return null;
            int s = (int) f.getOrigin();
            int e = (int) Math.ceil(f.getEnd());
            if (e <= s || s < 0) return null;
            return new String[]{f.getChrName(), String.valueOf(s), String.valueOf(e)};
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * v0.1.59：区间超长时取中段截断（0-based，end exclusive）。
     * 用于把整条染色体级的视口缩成可设计的区间；不超过 maxLen 原样返回。
     */
    public static int[] clampMid(int s0, int e0, int maxLen) {
        if (e0 - s0 <= maxLen) return new int[]{s0, e0};
        int mid = (s0 + e0) / 2;
        int ns = Math.max(s0, mid - maxLen / 2);
        return new int[]{ns, ns + maxLen};
    }

    // ---- v0.1.69：降条件自动重试（relax ladder） ----

    /** 组成类约束（L4 起删除）：多聚X / GC钳 / 3'端GC数 / 允许N数。 */
    private static final String[] COMPOSITION_KEYS = {
            "PRIMER_MAX_POLY_X", "PRIMER_GC_CLAMP", "PRIMER_MAX_END_GC", "PRIMER_MAX_NS_ACCEPTED"};
    /** 二聚体/发夹类约束（L5 起删除）。 */
    private static final String[] DIMER_KEYS = {
            "PRIMER_MAX_COMPL_ANY_TH", "PRIMER_MAX_COMPL_END_TH", "PRIMER_MAX_HAIRPIN_TH",
            "PRIMER_MAX_SELF_ANY_TH", "PRIMER_MAX_SELF_END_TH"};

    /** 约束 tag → 中文名（降级明示用）。 */
    private static final java.util.Map<String, String> CN_NAME =
            new java.util.LinkedHashMap<String, String>();
    static {
        CN_NAME.put("PRIMER_MAX_POLY_X", "多聚X上限");
        CN_NAME.put("PRIMER_GC_CLAMP", "3' GC钳位");
        CN_NAME.put("PRIMER_MAX_END_GC", "3'端GC数上限");
        CN_NAME.put("PRIMER_MAX_NS_ACCEPTED", "允许N个数");
        CN_NAME.put("PRIMER_MAX_COMPL_ANY_TH", "引物二聚体(ANY)阈值");
        CN_NAME.put("PRIMER_MAX_COMPL_END_TH", "引物二聚体(3'端)阈值");
        CN_NAME.put("PRIMER_MAX_HAIRPIN_TH", "发夹阈值");
        CN_NAME.put("PRIMER_MAX_SELF_ANY_TH", "自互补(ANY)阈值");
        CN_NAME.put("PRIMER_MAX_SELF_END_TH", "自互补(3'端)阈值");
        CN_NAME.put("PRIMER_OPT_SIZE", "最优引物长偏好");
        CN_NAME.put("PRIMER_OPT_TM", "最优Tm偏好");
    }

    /** v0.1.69：一级降级结果（放宽后的参数副本 + 人类可读的放宽描述列表）。 */
    public static class Relaxed {
        public final Map<String, String> params;
        public final List<String> descs;

        Relaxed(Map<String, String> params, List<String> descs) {
            this.params = params;
            this.descs = descs;
        }
    }

    private static int intOf(Map<String, String> p, String k, int def) {
        try { return Integer.parseInt(p.get(k).trim()); } catch (Exception e) { return def; }
    }

    /**
     * v0.1.69：降级阶梯——计算第 level 级（1..{@link #MAX_RELAX_LEVEL}）放宽后的参数。
     * 每级相对**原始**参数定义（非递进解析，纯函数可测）；level 无效时原样返回、无描述。
     *
     * <p>只放宽筛选参数（Tm/GC/长度/组成约束/二聚体约束）；区域约束
     * （SEQUENCE_INCLUDED_REGION / SEQUENCE_TARGET / SEQUENCE_PRIMER_PAIR_OK_REGION_LIST /
     * PRIMER_PRODUCT_SIZE_RANGE / PRIMER_PICK_*）与 Tm 计算基础（盐浓度等）永不动——
     * “引物必须落在选区内”这条不变。</p>
     *
     * <p>阶梯（相对原始值）：L1 Tm±2℃；L2 Tm±4℃+GC±5%；L3 +引物长 min-3(≥15)/max+3；
     * L4 +删组成约束；L5 +删二聚体/发夹约束；L6 极限（Tm±10 限40..80 / GC 10-90 /
     * 长 15-40 / 删全部约束+删 OPT 偏好）。</p>
     */
    public static Relaxed relaxAt(Map<String, String> base, int level) {
        Map<String, String> p = new LinkedHashMap<String, String>(base);
        List<String> descs = new ArrayList<String>();
        if (level < 1 || level > MAX_RELAX_LEVEL) return new Relaxed(p, descs);

        int tmMin = intOf(p, "PRIMER_MIN_TM", 57), tmMax = intOf(p, "PRIMER_MAX_TM", 63);
        int gcMin = intOf(p, "PRIMER_MIN_GC", 30), gcMax = intOf(p, "PRIMER_MAX_GC", 70);
        int lenMin = intOf(p, "PRIMER_MIN_SIZE", 18), lenMax = intOf(p, "PRIMER_MAX_SIZE", 27);

        // --- Tm（L1 起 ±2，L2 起 ±4，L6 极限 ±10，夹在 40..80）---
        int tmDn = level >= 2 ? 4 : 2, tmUp = tmDn;
        if (level >= MAX_RELAX_LEVEL) { tmDn = 10; tmUp = 10; }
        int nTmMin = Math.max(40, tmMin - tmDn), nTmMax = Math.min(80, tmMax + tmUp);
        if (nTmMin != tmMin || nTmMax != tmMax) {
            p.put("PRIMER_MIN_TM", String.valueOf(nTmMin));
            p.put("PRIMER_MAX_TM", String.valueOf(nTmMax));
            descs.add("Tm 范围 " + tmMin + "–" + tmMax + "℃ → " + nTmMin + "–" + nTmMax + "℃");
        }
        // --- GC（L2 起 ±5，L6 极限 10-90）---
        if (level >= 2) {
            int nGcMin = Math.max(10, gcMin - 5), nGcMax = Math.min(90, gcMax + 5);
            if (level >= MAX_RELAX_LEVEL) { nGcMin = 10; nGcMax = 90; }
            if (nGcMin != gcMin || nGcMax != gcMax) {
                p.put("PRIMER_MIN_GC", String.valueOf(nGcMin));
                p.put("PRIMER_MAX_GC", String.valueOf(nGcMax));
                descs.add("GC% 范围 " + gcMin + "–" + gcMax + " → " + nGcMin + "–" + nGcMax);
            }
        }
        // --- 引物长（L3 起 min-3 限≥15 / max+3，L6 极限 15-40）---
        if (level >= 3) {
            int nLenMin = Math.max(15, lenMin - 3), nLenMax = Math.min(40, lenMax + 3);
            if (level >= MAX_RELAX_LEVEL) { nLenMin = 15; nLenMax = 40; }
            if (nLenMin != lenMin || nLenMax != lenMax) {
                p.put("PRIMER_MIN_SIZE", String.valueOf(nLenMin));
                p.put("PRIMER_MAX_SIZE", String.valueOf(nLenMax));
                descs.add("引物长 " + lenMin + "–" + lenMax + "nt → " + nLenMin + "–" + nLenMax + "nt");
            }
        }
        // --- 组成约束（L4 起删）---
        if (level >= 4) {
            for (String k : COMPOSITION_KEYS) {
                String v = p.remove(k);
                if (v != null) descs.add("取消组成约束：" + CN_NAME.get(k) + "（原=" + v + "）");
            }
        }
        // --- 二聚体/发夹约束（L5 起删）---
        if (level >= 5) {
            for (String k : DIMER_KEYS) {
                String v = p.remove(k);
                if (v != null) descs.add("取消二聚体/发夹阈值：" + CN_NAME.get(k) + "（原=" + v + "）");
            }
        }
        // --- L6 极限：删 OPT 偏好 ---
        if (level >= MAX_RELAX_LEVEL) {
            for (String k : new String[]{"PRIMER_OPT_SIZE", "PRIMER_OPT_TM"}) {
                if (p.remove(k) != null) descs.add("取消" + CN_NAME.get(k));
            }
        }
        return new Relaxed(p, descs);
    }

    /**
     * v0.1.61：鼠标选区 → 设计框填充值（产物区间 + 左/右端引物设计区）。
     * v0.1.65：侧翼默认改为两端内侧 100~200bp（老板："左右留出100-200bp作为引物设计区间"）。
     * v0.1.67：侧翼语义重写（flank 三档）——裁窄后保 lenMax 而非 minLen，仍不够则伸出视野。
     *
     * 语义（老板需求 v0.1.80）：
     *  - 产物区间 = 选区，钳到当前视野内；
     *  - F 向设计区 = 产物左端内侧 flankMin~flankMax bp，R 向设计区 = 产物右端内侧 flankMin~flankMax bp；
     *  - 引物只在这两个设计区内发生设计（区内模式，不再伸到选区外）。产物过窄时两段向中间扩张至相遇。
     *
     * @param selS0,selE0 选区（0-based，end 含；起止可倒）
     * @param vpS0,vpE0   当前视野（0-based，end 不含）；传 0/Integer.MAX_VALUE = 不钳
     * @param minLen      最短引物长
     * @return {产物起, 产物止, 左端带起, 左端带长, 右端带起, 右端带长, 保留0, 保留0}
     *         （显示就绪：1-based inclusive + 长度；末两位保留为 0，区内模式不再有"超出屏幕"场景）；
     *         选区完全在视野外返回 null（调用方回退视口填充）
     */
    public static int[] fillFromSelection(int selS0, int selE0, int vpS0, int vpE0, int minLen) {
        return fillFromSelection(selS0, selE0, vpS0, vpE0, minLen, 100, 200);
    }

    /** 同上，侧翼范围可指定（测试/高级参数用；lenMax 默认 max(minLen,30)）。 */
    public static int[] fillFromSelection(int selS0, int selE0, int vpS0, int vpE0, int minLen,
                                          int flankMin, int flankMax) {
        return fillFromSelection(selS0, selE0, vpS0, vpE0, minLen, flankMin, flankMax,
                Math.max(minLen, 30));
    }

    /**
     * v0.1.67：lenMax=引物长上限——侧翼至少要装得下最长引物，否则 primer3 无解
     * （旧版只保 minLen=18，18bp 区对 18nt 引物零余量，老板截图实锤）。
     */
    public static int[] fillFromSelection(int selS0, int selE0, int vpS0, int vpE0, int minLen,
                                          int flankMin, int flankMax, int lenMax) {
        int s0 = Math.min(selS0, selE0), e0 = Math.max(selS0, selE0);
        int ps = Math.max(s0, vpS0);
        int pe = Math.min(e0, vpE0 - 1);
        if (pe < ps) return null;                       // 选区已不在屏幕内（平移/缩放走了）
        // v0.1.80：区内模式——F 向设计区放在产物左端内侧、R 向设计区放在产物右端内侧
        // （选中区域两端各取一段作为引物白名单，引物只在这两段内设计；不再伸到选区外）。
        int L = pe - ps + 1;
        // 带宽：至少装下最长引物、至多半选区长；产物过窄（L < band）时钳到 L，两段设计区相遇（不再形成负宽/反序）。
        int band = Math.min(L, Math.max(lenMax, Math.min(flankMax, L / 2)));
        int la = ps, lb = Math.min(pe, ps + band - 1);            // 左(前)设计区：产物左端内
        int ra = Math.max(ps, pe - band + 1), rb = pe;            // 右(后)设计区：产物右端内
        return new int[]{ps + 1, pe + 1, la + 1, lb - la + 1, ra + 1, rb - ra + 1, 0, 0};
    }

    /**
     * v0.1.65：无鼠标选区 → 按当前视口填充。
     * v0.1.80：区内模式——产物区间 = 视野向内收缩 margin 的子集；设计区在产物两端内侧。
     *
     * 语义：
     *  - 产物区间 = 视野向内收缩 margin（默认 150，范围 100~200）的子集；
     *  - F 向设计区 = 产物左端内侧 flankMin~flankMax bp，R 向设计区 = 产物右端内侧 flankMin~flankMax bp；
     *  - 引物只在这两个设计区内发生设计；产物过窄时两段向中间扩张至相遇。
     *
     * @param vpS0,vpE0 当前视野（0-based，end 不含）
     * @param minLen    最短引物长（兜底）
     * @param margin    视口边距（产物距视野左右缘的距离）
     * @return {产物起, 产物止, 左端带起, 左端带长, 右端带起, 右端带长, 保留0, 保留0}
     */
    public static int[] fillFromViewport(int vpS0, int vpE0, int minLen, int margin,
                                         int flankMin, int flankMax) {
        return fillFromViewport(vpS0, vpE0, minLen, margin, flankMin, flankMax,
                Math.max(minLen, 30));
    }

    /** v0.1.67：lenMax=引物长上限（侧翼至少装得下最长引物）。 */
    public static int[] fillFromViewport(int vpS0, int vpE0, int minLen, int margin,
                                         int flankMin, int flankMax, int lenMax) {
        int ps = vpS0 + margin;
        int pe = vpE0 - 1 - margin;
        if (pe - ps + 1 < minLen) {   // 视野太窄：取中段 minLen 兜底（仍钳在视野内）
            int mid = (vpS0 + vpE0 - 1) / 2;
            ps = Math.max(vpS0, mid - minLen / 2);
            pe = ps + minLen - 1;
            if (pe > vpE0 - 1) { pe = vpE0 - 1; ps = Math.max(vpS0, pe - minLen + 1); }
        }
        // v0.1.80：区内模式——设计区在产物两端内侧（同 fillFromSelection）
        int L = pe - ps + 1;
        int band = Math.min(L, Math.max(lenMax, Math.min(flankMax, L / 2)));
        int la = ps, lb = Math.min(pe, ps + band - 1);
        int ra = Math.max(ps, pe - band + 1), rb = pe;
        return new int[]{ps + 1, pe + 1, la + 1, lb - la + 1, ra + 1, rb - ra + 1, 0, 0};
    }

    /**
     * v0.1.67：选段单侧侧翼引物设计区（0-based inclusive，长度含两端）。
     *
     * 老板截图实锤的 bug：窄视野（~85bp）下旧实现把设计区退化成 18bp（=minLen）
     * 且伸进产物区——18bp 装 18nt 引物零余量，primer3 必无解。
     * 新语义（按优先级）：
     *  1) 理想 = 选段边缘外 flankMin~flankMax bp；被视野裁窄后仍 ≥ lenMax（最长引物长）
     *     → 直接用（大视野场景不变，保持"缘外 100~200"原语义）；
     *  2) 裁后 < lenMax → **紧贴选段缘**取视野内最大宽度（上游 [vpS0..edge0-1]、
     *     下游 [edge0+1..vpE0-1]），≥lenMax 即可用（不再伸进产物区）；
     *  3) 紧贴仍 < lenMax（视野缘离选段太近）→ **伸出视野**取 want 宽
     *     （want = max(flankMax-flankMin+1, lenMax)），out=1 让调用方警告
     *     "引物区已超出屏幕，设计结果需缩放查看"——保设计能力优先于可视性。
     *
     * @return {a, b, out}（0-based inclusive；out=1 表示该侧翼超出视野）
     */
    private static int[] flank(int edge0, int dir, int vpS0, int vpE0, int minLen, int lenMax,
                               int flankMin, int flankMax) {
        int want = Math.max(flankMax - flankMin + 1, lenMax);   // 目标宽度：至少装下最长引物
        int a, b;
        if (dir < 0) { b = edge0 - flankMin; a = edge0 - flankMax; }
        else         { a = edge0 + flankMin; b = edge0 + flankMax; }
        int ca = Math.max(a, vpS0), cb = Math.min(b, vpE0 - 1);
        if (cb >= ca && cb - ca + 1 >= lenMax) return new int[]{ca, cb, 0};   // 1) 理想（裁后仍装得下最长引物）
        // 2) 紧贴选段缘，取视野内最大宽度（不再伸进产物区）
        int ha, hb;
        if (dir < 0) { hb = edge0 - 1; ha = Math.max(vpS0, hb - want + 1); }
        else         { ha = edge0 + 1; hb = Math.min(vpE0 - 1, ha + want - 1); }
        if (hb >= ha && hb - ha + 1 >= lenMax) return new int[]{ha, hb, 0};
        // 3) 视野内连最长引物都放不下 → 伸出视野（保设计能力），宽 want
        if (dir < 0) { b = edge0 - 1; a = Math.max(0, b - want + 1); }
        else         { a = edge0 + 1; b = a + want - 1; }
        return new int[]{a, b, 1};
    }

    /** v0.1.59：高级参数收集——文本框非空才写入（空 = 用 primer3 自己的默认值）。 */
    public static void putIfPresent(Map<String, String> params, String tag, JTextField f) {
        String v = f.getText().trim();
        if (!v.isEmpty()) params.put(tag, v);
    }

    /** v0.1.65：读一个整数框，非法/空 → 默认值，并钳到 [lo, hi]。 */
    private static int readBounded(JTextField f, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(f.getText().trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (Exception ignore) {
            return def;
        }
    }

    /**
     * v0.1.62：解析 primer3 EXPLAIN 统计，按失败数量降序给出可操作建议。
     * 输入形如 "LEFT: considered 985, GC content failed 598, low tm 11, ok 86"。
     * 只统计 "xxx failed N" 段；无统计时返回空串（调用方回落到通用建议）。
     *
     * v0.1.69：扩展解析无 failed 后缀的统计段——primer3 单引物 explain 用
     * "LEFT: considered 2, overlap target 2, ok 0"（overlap target 是计数不是 failed），
     * 成对有 "unacceptable product size 1234"。这些段同样反映淘汰原因，
     * 按 "reason N" 解析（跳过 considered/ok 这两个非淘汰统计）。
     */
    public static String explainTips(String explain, boolean single) {
        java.util.Map<String, Long> fail = new java.util.LinkedHashMap<String, Long>();
        for (String ln : explain.split("\n")) {
            for (String seg : ln.split(",")) {
                seg = seg.trim();
                int idx = seg.indexOf(" failed ");
                if (idx >= 0) {
                    String reason = seg.substring(0, idx).trim();
                    int c = reason.indexOf(':');
                    if (c >= 0) reason = reason.substring(c + 1).trim();
                    try {
                        long n = Long.parseLong(seg.substring(idx + 8).trim());
                        Long old = fail.get(reason);
                        fail.put(reason, old == null ? n : old + n);
                    } catch (NumberFormatException ignore) { }
                    continue;
                }
                // v0.1.69："reason N" 统计段（无 failed 后缀），如 "overlap target 2"
                java.util.regex.Matcher m = PLAIN_STAT.matcher(seg);
                if (!m.matches()) continue;
                String reason = m.group(1).trim();
                int c = reason.indexOf(':');
                if (c >= 0) reason = reason.substring(c + 1).trim();
                if (reason.equalsIgnoreCase("considered") || reason.equalsIgnoreCase("ok")) continue;
                try {
                    long n = Long.parseLong(m.group(2));
                    Long old = fail.get(reason);
                    fail.put(reason, old == null ? n : old + n);
                } catch (NumberFormatException ignore) { }
            }
        }
        if (fail.isEmpty()) return "";
        java.util.List<java.util.Map.Entry<String, Long>> es =
                new java.util.ArrayList<java.util.Map.Entry<String, Long>>(fail.entrySet());
        java.util.Collections.sort(es, new java.util.Comparator<java.util.Map.Entry<String, Long>>() {
            public int compare(java.util.Map.Entry<String, Long> a, java.util.Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        StringBuilder sb = new StringBuilder("<b>失败原因（按数量降序）：</b>");
        int shown = 0;
        for (java.util.Map.Entry<String, Long> en : es) {
            if (en.getValue() <= 0) continue;
            String tip = tipFor(en.getKey(), single);
            sb.append("<br>· ").append(en.getKey()).append(" ×").append(en.getValue());
            if (tip != null) sb.append(" → ").append(tip);
            if (++shown >= 3) break;
        }
        return sb.toString();
    }

    /** v0.1.62：primer3 EXPLAIN 原因词 → 对话框里的可操作建议（无匹配返回 null）。 */
    private static String tipFor(String reason, boolean single) {
        if (reason.contains("product size")) return "减小「产物 min」/ 增大「产物 max」（上限已自动放到模板可达范围）";
        // v0.1.69：单引物窄选区 ok 0 的典型 explain（引物不得覆盖 SEQUENCE_TARGET）
        if (reason.contains("overlap target")) return "引物与目标区重叠：缩小/去掉目标区，或扩大引物区域让引物有处可放（v0.1.69 起单引物已不再发 SEQUENCE_TARGET）";
        if (reason.contains("GC content")) return "放宽 GC 范围（如 20%-80%）";
        if (reason.contains("low tm")) return "提高 Tm 下限（如 55℃）";
        if (reason.contains("high tm")) return "提高 Tm 上限（如 65℃）";
        if (reason.contains("complementarity") || reason.contains("self"))
            return "放宽二聚体/自互补阈值（高级参数，默认 ANY=8 / END=3）";
        if (reason.contains("hairpin")) return "放宽发夹阈值（高级参数，默认 47）";
        if (reason.contains("poly X")) return "放宽多聚 X 上限（高级参数，默认 5）";
        if (reason.contains("stability")) return "放宽 3' 端稳定性阈值（高级参数）";
        if (reason.contains("GC clamp")) return "放宽 3' GC 钳位（高级参数）";
        if (reason.contains("no valid") || reason.contains("template")) return single ? "扩大引物区域" : "扩大产物区间";
        return null;
    }

    private static class Dlg extends JDialog {
        private final int mode;   // MODE_PAIR / MODE_LEFT / MODE_RIGHT
        private final boolean quick;   // v0.1.69：快速设计（无视选区，视口中心取 100bp）
        private final JComboBox modeBox = new JComboBox(new String[]{"简单：指定产物区间", "高级：分别指定左/右端设计区"});
        private final JTextField chrF = new JTextField(6);
        private final JTextField groupF = new JTextField("primer3", 10);
        // 简单模式
        private final JTextField tStartF = new JTextField(8);
        private final JTextField tEndF = new JTextField(8);
        // 高级模式
        private final JTextField uStartF = new JTextField(8);
        private final JTextField uLenF = new JTextField(6);
        private final JTextField dStartF = new JTextField(8);
        private final JTextField dLenF = new JTextField(6);
        // v0.1.65：无选区时「产物=视野子集」的边距 + 设计区侧翼范围（打开对话框时读取一次）
        private final JTextField vpMarginF = new JTextField("150", 4);
        private final JTextField flankMinF = new JTextField("100", 4);
        private final JTextField flankMaxF = new JTextField("200", 4);
        // 参数
        private final JTextField lenMinF = new JTextField("18", 4);
        private final JTextField lenOptF = new JTextField("20", 4);
        private final JTextField lenMaxF = new JTextField("27", 4);
        private final JTextField tmMinF = new JTextField("57", 4);
        private final JTextField tmOptF = new JTextField("60", 4);
        private final JTextField tmMaxF = new JTextField("63", 4);
        private final JTextField gcMinF = new JTextField("30", 4);
        private final JTextField gcMaxF = new JTextField("70", 4);
        private final JTextField prodMinF = new JTextField("100", 6);
        private final JTextField prodMaxF = new JTextField("300", 6);
        private final JTextField numRetF = new JTextField("5", 4);
        // v0.1.59：高级参数（均可空=primer3 默认值；实战中最常导致无解/结果漂移的项）
        private final JTextField saltMonoF = new JTextField("50", 5);    // PRIMER_SALT_MONOVALENT mM
        private final JTextField saltDiF = new JTextField("1.5", 5);     // PRIMER_SALT_DIVALENT mM
        private final JTextField dntpF = new JTextField("0.6", 5);       // PRIMER_DNTP_CONC mM
        private final JTextField dnaConcF = new JTextField("50", 5);     // PRIMER_DNA_CONC nM
        private final JTextField complAnyF = new JTextField("", 5);      // PRIMER_MAX_COMPL_ANY_TH
        private final JTextField complEndF = new JTextField("", 5);      // PRIMER_MAX_COMPL_END_TH
        private final JTextField hairpinF = new JTextField("", 5);       // PRIMER_MAX_HAIRPIN_TH
        private final JTextField selfAnyF = new JTextField("", 5);       // PRIMER_MAX_SELF_ANY_TH
        private final JTextField selfEndF = new JTextField("", 5);       // PRIMER_MAX_SELF_END_TH
        private final JTextField polyXF = new JTextField("", 5);         // PRIMER_MAX_POLY_X
        private final JTextField nsF = new JTextField("", 5);            // PRIMER_MAX_NS_ACCEPTED
        private final JTextField gcClampF = new JTextField("", 5);       // PRIMER_GC_CLAMP 0/1
        private final JTextField maxEndGcF = new JTextField("", 5);      // PRIMER_MAX_END_GC

        private final JPanel simplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel advPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        private final JPanel advParams = new JPanel(new GridLayout(3, 10, 4, 2));  // v0.1.59 高级参数（默认折叠）
        private final JLabel statusL = new JLabel(" ");
        // v0.1.69：0 对时自动降级重试（默认开；区域约束不动，成功后明示放宽内容+风险）
        private final JCheckBox relaxChk = new JCheckBox("0 对时自动降级重试（最多 6 级，逐级放宽并明示）", true);

        Dlg(int mode) {
            this(mode, false);
        }

        Dlg(int mode, boolean quick) {
            super(IGV.getMainFrame(), titleOf(mode), true);
            this.mode = mode;
            this.quick = quick;
            boolean single = (mode != MODE_PAIR);
            setLayout(new BorderLayout(6, 6));

            // ---- 顶部：模式/chr/分组 ----
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            top.add(new JLabel("模式:"));
            top.add(modeBox);
            top.add(new JLabel("染色体:"));
            // v0.1.61：优先鼠标选区；选区已平移出当前染色体 → 作废，回退当前 IGV 视口
            String[] sel = PrimerEditTool.currentSelection();
            String[] vp = currentViewRange();
            if (sel != null && vp != null && !sel[0].equals(vp[0])) sel = null;
            if (quick) sel = null;   // v0.1.69：快速设计无视选区，按视口中心取区域
            String[] chrSrc = sel != null ? sel : vp;
            chrF.setText(chrSrc != null ? chrSrc[0] : (PrimerStore.lastChr == null ? "" : PrimerStore.lastChr));
            top.add(chrF);
            top.add(new JLabel("分组名:"));
            top.add(groupF);

            // ---- 区域面板 ----
            JLabel simpleLbl = new JLabel(single ? "引物区域:" : "产物区间:");
            simplePanel.add(simpleLbl);
            simplePanel.add(tStartF);
            simplePanel.add(new JLabel("-"));
            simplePanel.add(tEndF);
            simplePanel.add(new JLabel(single
                    ? "bp（引物必须落在该区域内，primer3 按参数挑最优）"
                    : "bp（简单：两端设计区由 primer3 自动选择；高级：可留空=不强制覆盖）"));
            advPanel.add(new JLabel("左端设计区:"));
            advPanel.add(uStartF);
            advPanel.add(new JLabel("起 + 长"));
            advPanel.add(uLenF);
            advPanel.add(new JLabel("bp    右端设计区:"));
            advPanel.add(dStartF);
            advPanel.add(new JLabel("起 + 长"));
            advPanel.add(dLenF);
            advPanel.add(new JLabel("bp"));
            advPanel.add(new JLabel(" ← 引物设计区：左引物落左端设计区、右引物落右端设计区（默认=产物两端内侧100–200bp）"));

            // v0.1.65：无选区时按视口填充的边距/侧翼（打开时读取；有鼠标选区时以选区为准）
            JPanel fillPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            fillPanel.add(new JLabel("视口边距:"));
            fillPanel.add(vpMarginF);
            fillPanel.add(new JLabel("bp   引物区侧翼:"));
            fillPanel.add(flankMinF);
            fillPanel.add(new JLabel("-"));
            fillPanel.add(flankMaxF);
            fillPanel.add(new JLabel("bp（无选区时：产物=视野内缩边距，设计区=产物两端内侧侧翼；"
                    + "视口过窄时设计区将超出屏幕并提示，不会压成无解小区间）"));

            JPanel regionPanel = new JPanel();
            regionPanel.setLayout(new BoxLayout(regionPanel, BoxLayout.Y_AXIS));
            regionPanel.add(simplePanel);
            regionPanel.add(advPanel);
            regionPanel.add(fillPanel);
            advPanel.setVisible(false);
            if (single) {
                // 单引物只有一种区域模式：禁用模式框并换成正确文案（避免显示"产物区间"误导）
                modeBox.setModel(new DefaultComboBoxModel(new String[]{"简单：指定引物区域"}));
                modeBox.setEnabled(false);
                prodMinF.setEnabled(false);  // 单引物无产物概念
                prodMaxF.setEnabled(false);
            }

            // v0.1.61：鼠标选区 → 产物区间(=选区,钳屏幕) + 设计区(=选区两端内侧100~500bp,钳屏幕)，切高级模式；
            //           选区不在屏幕 → 回退视口填充（v0.1.59 逻辑）。均 1-based inclusive 显示。
            // 注意：选区 end 为 0-based 含（currentSelection 语义），视口 end 为 0-based 不含，分别处理。
            boolean selOffscreen = false;
            if (sel != null) {
                int lenMin0 = 18;
                try { lenMin0 = Integer.parseInt(lenMinF.getText().trim()); } catch (Exception ignore) {}
                int lenMax0 = readBounded(lenMaxF, 27, 1, 1000);   // v0.1.67：侧翼至少装得下最长引物
                int vpS = vp != null ? Integer.parseInt(vp[1]) : 0;
                int vpE = vp != null ? Integer.parseInt(vp[2]) : Integer.MAX_VALUE;
                int[] f = fillFromSelection(Integer.parseInt(sel[1]), Integer.parseInt(sel[2]),
                        vpS, vpE, lenMin0, 100, 200, lenMax0);
                if (f != null) {
                    int maxLen = single ? 2000 : 19000;
                    if (f[1] - f[0] + 1 > maxLen) {   // 选区超长（缩到染色体级）：取中段后重算侧翼
                        int[] c = clampMid(f[0] - 1, f[1], maxLen);
                        f = fillFromSelection(c[0], c[1] - 1, vpS, vpE, lenMin0, 100, 200, lenMax0);
                    }
                    tStartF.setText(String.valueOf(f[0]));
                    tEndF.setText(String.valueOf(f[1]));
                    uStartF.setText(String.valueOf(f[2])); uLenF.setText(String.valueOf(f[3]));
                    dStartF.setText(String.valueOf(f[4])); dLenF.setText(String.valueOf(f[5]));
                    boolean outView = f.length > 6 && (f[6] != 0 || f[7] != 0);   // v0.1.67：设计区超出屏幕
                    if (!single) {
                        // 高级模式：显示设计区框（modeBox 监听器稍后才注册，不会同步触发，需手动设可见性）
                        modeBox.setSelectedIndex(1);
                        simplePanel.setVisible(true);
                        advPanel.setVisible(true);
                        statusL.setText("<html><font color='gray'>已按选定区域填充：产物 " + f[0] + "-" + f[1]
                                + "；左端设计区 " + f[2] + "+" + f[3] + "bp；右端设计区 " + f[4] + "+" + f[5]
                                + "bp（默认两端内侧 100–200bp 可改；均已限制在屏幕内）</font></html>");
                    } else {
                        statusL.setText("<html><font color='gray'>已按选定区域填充引物区域 "
                                + f[0] + "-" + f[1] + "</font></html>");
                    }
                    if (outView) {
                        statusL.setText("<html><font color='#B35C00'>⚠ 视口过窄：引物设计区已超出当前屏幕"
                                + "（为保住可设计性未强行压成 minLen 小区间）。设计出的引物可能在屏幕外——"
                                + "缩放即可见；或放大视野后重新打开本对话框。</font></html>");
                    }
                } else {
                    selOffscreen = true;
                    sel = null;   // 回退视口
                }
            }
            if (sel == null && vp != null) {
                // v0.1.65：无选区 → 产物=视野子集（左右各留 margin，默认150），设计区=产物两端内侧
                // flankMin~flankMax（默认100~200）——产物+引物全在屏幕内，设计结果直接可视。
                // v0.1.67：窄视野下设计区可能超出屏幕（out=1），弹警告。
                int s0 = Integer.parseInt(vp[1]);
                int e0 = Integer.parseInt(vp[2]);
                if (s0 > e0) { int t = s0; s0 = e0; e0 = t; }
                // v0.1.69：快速设计——视口中心取 100bp 作引物区/产物区（不内缩、不侧翼），
                // 成对时设计区=产物两端内侧 100~200bp；随后跳过视口子集填充。
                boolean quickFilled = false;
                if (quick) {
                    int qMin = 18;
                    try { qMin = Integer.parseInt(lenMinF.getText().trim()); } catch (Exception ignore) {}
                    int[] q = quickCenterRegion(s0, e0, 100);
                    tStartF.setText(String.valueOf(q[0]));
                    tEndF.setText(String.valueOf(q[1]));
                    if (!single) {
                        int[] f = fillFromSelection(q[0] - 1, q[1] - 1, 0, Integer.MAX_VALUE,
                                qMin, 100, 200, 27);
                        uStartF.setText(String.valueOf(f[2])); uLenF.setText(String.valueOf(f[3]));
                        dStartF.setText(String.valueOf(f[4])); dLenF.setText(String.valueOf(f[5]));
                        modeBox.setSelectedIndex(1);   // 成对：显示设计区框
                        simplePanel.setVisible(true);
                        advPanel.setVisible(true);
                    }
                    statusL.setText("<html><font color='gray'>⚡ 快速设计：已取视口中心 100bp 作"
                            + (single ? "引物区" : "产物区") + "（" + q[0] + "-" + q[1] + "）"
                            + (single ? "" : "，设计区=两端内侧 100–200bp")
                            + "；若 0 对将自动降级条件重试</font></html>");
                    quickFilled = true;
                }
                if (!quickFilled) {
                int lenMin0 = 18;
                try { lenMin0 = Integer.parseInt(lenMinF.getText().trim()); } catch (Exception ignore) {}
                int lenMax0 = readBounded(lenMaxF, 27, 1, 1000);
                int margin = readBounded(vpMarginF, 150, 0, 2000);
                int fMin = readBounded(flankMinF, 100, 0, 5000);
                int fMax = readBounded(flankMaxF, 200, fMin, 5000);
                int[] f = fillFromViewport(s0, e0, lenMin0, margin, fMin, fMax, lenMax0);
                tStartF.setText(String.valueOf(f[0]));
                tEndF.setText(String.valueOf(f[1]));
                uStartF.setText(String.valueOf(f[2])); uLenF.setText(String.valueOf(f[3]));
                dStartF.setText(String.valueOf(f[4])); dLenF.setText(String.valueOf(f[5]));
                if (!single) {
                    modeBox.setSelectedIndex(1);   // 成对：显示设计区框（监听器未注册，手动设可见性）
                    simplePanel.setVisible(true);
                    advPanel.setVisible(true);
                }
                boolean outView = f.length > 6 && (f[6] != 0 || f[7] != 0);
                if (selOffscreen) {
                    statusL.setText("<html><font color='gray'>选区已不在当前屏幕，已按当前视图填充</font></html>");
                } else {
                    statusL.setText("<html><font color='gray'>已按当前视图填充：产物 " + f[0] + "-" + f[1]
                            + "（视野内缩 " + margin + "bp）；左端设计区 " + f[2] + "+" + f[3] + "bp；右端设计区 "
                            + f[4] + "+" + f[5] + "bp——产物与引物设计区均在屏幕内，设计结果直接可视</font></html>");
                }
                if (outView) {
                    statusL.setText("<html><font color='#B35C00'>⚠ 视口过窄：引物设计区已超出当前屏幕"
                            + "（为保住可设计性未强行压成 minLen 小区间）。设计出的引物可能在屏幕外——"
                            + "缩放即可见；或放大视野后重新打开本对话框。</font></html>");
                }
                }
            }

            // ---- 参数网格 ----
            JPanel pg = new JPanel(new GridLayout(2, 6, 4, 2));
            pg.add(new JLabel("引物长 min/opt/max:", SwingConstants.RIGHT));
            pg.add(lenMinF);
            pg.add(lenOptF);
            pg.add(lenMaxF);
            pg.add(new JLabel("Tm min/opt/max °C:", SwingConstants.RIGHT));
            pg.add(tmMinF);
            pg.add(tmOptF);
            pg.add(tmMaxF);
            pg.add(new JLabel("GC% min/max:", SwingConstants.RIGHT));
            pg.add(gcMinF);
            pg.add(gcMaxF);
            pg.add(new JLabel(single ? "（单引物无产物范围）" : "产物 bp min/max:", SwingConstants.RIGHT));
            pg.add(prodMinF);
            pg.add(prodMaxF);
            pg.add(new JLabel(single ? "返回条数:" : "返回对数:", SwingConstants.RIGHT));
            pg.add(numRetF);
            pg.add(new JLabel(""));

            // ---- 预设 ----
            JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            presets.add(new JLabel("预设:"));
            presets.add(btn("默认", new String[]{"18", "20", "27", "57", "60", "63", "30", "70", "100", "300", "5"}));
            presets.add(btn("qPCR", new String[]{"18", "20", "25", "59", "60", "62", "40", "60", "80", "150", "5"}));
            presets.add(btn("Sanger", new String[]{"18", "22", "27", "56", "60", "64", "35", "65", "300", "800", "5"}));
            presets.add(btn("克隆", new String[]{"20", "25", "30", "58", "62", "66", "40", "60", "500", "1500", "5"}));

            // ---- v0.1.59：高级参数（默认折叠；留空=primer3 默认值） ----
            final JButton advToggle = new JButton("高级参数 ▸（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）");
            advToggle.setMargin(new Insets(2, 6, 2, 6));
            // 行1：Tm 计算基础（盐/dNTP/引物浓度）
            advParams.add(new JLabel("盐 mono mM:", SwingConstants.RIGHT)); advParams.add(saltMonoF);
            advParams.add(new JLabel("盐 di mM:", SwingConstants.RIGHT));  advParams.add(saltDiF);
            advParams.add(new JLabel("dNTP mM:", SwingConstants.RIGHT));   advParams.add(dntpF);
            advParams.add(new JLabel("引物 nM:", SwingConstants.RIGHT));   advParams.add(dnaConcF);
            advParams.add(new JLabel("")); advParams.add(new JLabel(""));
            // 行2：二聚体/发夹阈值（调低更严，无解时可适当调高）
            advParams.add(new JLabel("二聚ANY:", SwingConstants.RIGHT));  advParams.add(complAnyF);
            advParams.add(new JLabel("二聚END:", SwingConstants.RIGHT));  advParams.add(complEndF);
            advParams.add(new JLabel("发卡:", SwingConstants.RIGHT));     advParams.add(hairpinF);
            advParams.add(new JLabel("自连ANY:", SwingConstants.RIGHT));  advParams.add(selfAnyF);
            advParams.add(new JLabel("自连END:", SwingConstants.RIGHT));  advParams.add(selfEndF);
            // 行3：组成约束
            advParams.add(new JLabel("多聚X:", SwingConstants.RIGHT));    advParams.add(polyXF);
            advParams.add(new JLabel("N个数:", SwingConstants.RIGHT));    advParams.add(nsF);
            advParams.add(new JLabel("GC钳0/1:", SwingConstants.RIGHT));  advParams.add(gcClampF);
            advParams.add(new JLabel("3'GC数:", SwingConstants.RIGHT));   advParams.add(maxEndGcF);
            JButton advReset = new JButton("恢复默认");
            advReset.setMargin(new Insets(2, 6, 2, 6));
            advReset.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    saltMonoF.setText("50"); saltDiF.setText("1.5"); dntpF.setText("0.6"); dnaConcF.setText("50");
                    complAnyF.setText(""); complEndF.setText(""); hairpinF.setText("");
                    selfAnyF.setText(""); selfEndF.setText(""); polyXF.setText("");
                    nsF.setText(""); gcClampF.setText(""); maxEndGcF.setText("");
                }
            });
            advParams.add(advReset);
            advParams.setVisible(false);
            advToggle.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    boolean show = !advParams.isVisible();
                    advParams.setVisible(show);
                    advToggle.setText(show
                            ? "高级参数 ▾（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）"
                            : "高级参数 ▸（盐浓度 / 二聚体阈值 / 组成约束，留空=primer3 默认）");
                    pack();
                }
            });
            JPanel advWrap = new JPanel(new BorderLayout(0, 2));
            advWrap.add(advToggle, BorderLayout.NORTH);
            advWrap.add(advParams, BorderLayout.CENTER);

            JPanel form = new JPanel();
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.add(top);
            form.add(regionPanel);
            form.add(presets);
            form.add(pg);
            form.add(advWrap);
            form.add(statusL);
            add(form, BorderLayout.CENTER);

            // ---- 按钮 ----
            JButton go = new JButton("设计");
            JButton cancel = new JButton("取消");
            relaxChk.setToolTipText("<html>primer3 返回 0 对时，按 6 级阶梯自动放宽筛选条件重试：<br>"
                    + "L1 Tm±2℃ → L2 +GC±5% → L3 +放宽引物长 → L4 +取消组成约束 → L5 +取消二聚体阈值 → L6 极限。<br>"
                    + "区域约束（引物必须落在选区内）永远不变；成功后弹窗逐项明示放宽内容与风险。</html>");
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            btns.add(relaxChk);
            btns.add(go);
            btns.add(cancel);
            add(btns, BorderLayout.SOUTH);

            modeBox.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    boolean adv = modeBox.getSelectedIndex() == 1;
                    // v0.1.61：高级模式同时显示产物区间行（留空=不强制覆盖）；简单模式只显示产物区间
                    simplePanel.setVisible(true);
                    advPanel.setVisible(adv);
                    if (adv && !single) autoFillFlanks();   // 设计区为空 → 按产物区间两端内侧补全
                    updatePreview();   // v0.1.81：切换模式后设计带可能变化，同步预览
                    pack();
                }
            });
            // v0.1.81：实时预览——任意字段变动即把产物区/设计区高亮到 IGV 面板
            addPreviewListener(chrF);
            addPreviewListener(tStartF);
            addPreviewListener(tEndF);
            addPreviewListener(uStartF);
            addPreviewListener(uLenF);
            addPreviewListener(dStartF);
            addPreviewListener(dLenF);
            addPreviewListener(flankMaxF);
            addPreviewListener(lenMaxF);
            updatePreview();
            cancel.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    dispose();
                }
            });
            go.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    runDesign();
                }
            });

            pack();
            setLocationRelativeTo(IGV.getMainFrame());
        }

        /**
         * v0.1.61：切到高级模式时左/右端设计区为空 → 按产物区间两端内侧 100~500bp 自动补全。
         * 无视野上下文（不钳屏幕）；产物区间也为空则不动，交由 runDesign 报友好错误。
         */
        private void autoFillFlanks() {
            try {
                if (!uStartF.getText().trim().isEmpty() || !dStartF.getText().trim().isEmpty()) return;
                if (tStartF.getText().trim().isEmpty() || tEndF.getText().trim().isEmpty()) return;
                int s1 = Integer.parseInt(tStartF.getText().trim());
                int e1 = Integer.parseInt(tEndF.getText().trim());
                if (s1 > e1) { int t = s1; s1 = e1; e1 = t; }
                int lenMin0 = 18;
                try { lenMin0 = Integer.parseInt(lenMinF.getText().trim()); } catch (Exception ignore) {}
                int[] f = fillFromSelection(s1 - 1, e1 - 1, 0, Integer.MAX_VALUE, lenMin0);
                uStartF.setText(String.valueOf(f[2])); uLenF.setText(String.valueOf(f[3]));
                dStartF.setText(String.valueOf(f[4])); dLenF.setText(String.valueOf(f[5]));
                statusL.setText("<html><font color='gray'>已按产物区间自动补全左/右端设计区（两端内侧 100–200bp，可改）</font></html>");
            } catch (Exception ignore) {
            }
        }

        /**
         * v0.1.81：实时预览——把产物区/设计区换算成 0-based 坐标，推给 PrimerEditTool 高亮到 IGV 面板。
         * 成对：设计带取「高级字段」或「产物两端内侧」(简单模式)；单引物：设计区=整个引物区域。
         */
        private void updatePreview() {
            try {
                String chr = chrF.getText().trim();
                if (chr.isEmpty() || tStartF.getText().trim().isEmpty() || tEndF.getText().trim().isEmpty()) {
                    PrimerEditTool.clearPcrPreview();
                    return;
                }
                int s = Integer.parseInt(tStartF.getText().trim());
                int e = Integer.parseInt(tEndF.getText().trim());
                if (s > e) { int t = s; s = e; e = t; }
                int s0 = s - 1, e0 = e;   // 0-based [s0, e0)
                int lenMax = readBounded(lenMaxF, 27, 1, 1000);
                int fMax = readBounded(flankMaxF, 200, 1, 5000);
                int L = e - (s - 1);
                int band = Math.max(lenMax, Math.min(fMax, L / 2));
                int a0, a1, b0, b1;
                if (mode == MODE_PAIR) {
                    String us = uStartF.getText().trim(), ul = uLenF.getText().trim(),
                           ds = dStartF.getText().trim(), dl = dLenF.getText().trim();
                    if (!us.isEmpty() && !ul.isEmpty() && !ds.isEmpty() && !dl.isEmpty()) {
                        a0 = Integer.parseInt(us) - 1; a1 = a0 + Integer.parseInt(ul);
                        b0 = Integer.parseInt(ds) - 1; b1 = b0 + Integer.parseInt(dl);
                    } else {
                        a0 = s0; a1 = s0 + band; b0 = e0 - band; b1 = e0;
                    }
                } else {
                    a0 = s0; a1 = e0; b0 = s0; b1 = e0;   // 单引物：设计区=整个引物区域
                }
                PrimerEditTool.setPcrPreview(chr, s0, e0, a0, a1, b0, b1);
            } catch (Exception ignore) {
                PrimerEditTool.clearPcrPreview();
            }
        }

        /** v0.1.81：给文本框挂「改动即刷新预览」的监听器。 */
        private void addPreviewListener(JTextField f) {
            f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { updatePreview(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { updatePreview(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { updatePreview(); }
            });
        }

        private static String titleOf(int mode) {
            switch (mode) {
                case MODE_LEFT:  return "primer3 设计 F 向单引物（+）";
                case MODE_RIGHT: return "primer3 设计 R 向单引物（-）";
                default:         return "primer3 设计成对引物";
            }
        }

        private JButton btn(String name, final String[] vals) {
            JButton b = new JButton(name);
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    lenMinF.setText(vals[0]);
                    lenOptF.setText(vals[1]);
                    lenMaxF.setText(vals[2]);
                    tmMinF.setText(vals[3]);
                    tmOptF.setText(vals[4]);
                    tmMaxF.setText(vals[5]);
                    gcMinF.setText(vals[6]);
                    gcMaxF.setText(vals[7]);
                    if (mode == MODE_PAIR) {
                        prodMinF.setText(vals[8]);
                        prodMaxF.setText(vals[9]);
                    }
                    numRetF.setText(vals[10]);
                }
            });
            return b;
        }

        private int i(JTextField f, String what) throws Exception {
            try {
                return Integer.parseInt(f.getText().trim());
            } catch (NumberFormatException nfe) {
                throw new Exception(what + " 不是有效整数: " + f.getText().trim());
            }
        }

        private void runDesign() {
            statusL.setText(" ");
            try {
                // ---- 参数 ----
                int lenMin = i(lenMinF, "引物长 min"), lenMax = i(lenMaxF, "引物长 max");
                int tmMin = i(tmMinF, "Tm min"), tmMax = i(tmMaxF, "Tm max");
                int gcMin = i(gcMinF, "GC min"), gcMax = i(gcMaxF, "GC max");
                int prodMin = i(prodMinF, "产物 min"), prodMax = i(prodMaxF, "产物 max");
                int numRet = i(numRetF, "返回条数");
                String chr = chrF.getText().trim();
                String group = groupF.getText().trim();
                if (chr.isEmpty()) throw new Exception("染色体不能为空");
                if (group.isEmpty()) group = "primer3";
                if (lenMin > lenMax) throw new Exception("引物长 min > max");
                if (tmMin > tmMax) throw new Exception("Tm min > max");
                if (gcMin > gcMax) throw new Exception("GC min > max");
                if (mode == MODE_PAIR && prodMin > prodMax) throw new Exception("产物 min > max");
                if (numRet < 1 || numRet > 20) throw new Exception("返回条数需在 1-20");

                Genome g = GenomeManager.getInstance().getCurrentGenome();
                if (g == null) throw new Exception("未加载参考基因组。请先在 IGV 顶部基因组下拉框选择/加载基因组（需要含序列的 .fasta/.2bit/genome），否则取不到模板序列。");
                if (g.getChromosome(chr) == null) throw new Exception("染色体「" + chr + "」不在当前基因组中");
                int chrLen = g.getChromosome(chr).getLength();

                // ---- 区域 ----
                Map<String, String> params = new LinkedHashMap<String, String>();
                params.put("PRIMER_MIN_SIZE", String.valueOf(lenMin));
                params.put("PRIMER_OPT_SIZE", lenOptF.getText().trim());
                params.put("PRIMER_MAX_SIZE", String.valueOf(lenMax));
                params.put("PRIMER_MIN_TM", String.valueOf(tmMin));
                params.put("PRIMER_OPT_TM", tmOptF.getText().trim());
                params.put("PRIMER_MAX_TM", String.valueOf(tmMax));
                params.put("PRIMER_MIN_GC", String.valueOf(gcMin));
                params.put("PRIMER_MAX_GC", String.valueOf(gcMax));
                params.put("PRIMER_NUM_RETURN", String.valueOf(numRet));

                // v0.1.59：高级参数（留空=primer3 默认，不写入）
                putIfPresent(params, "PRIMER_SALT_MONOVALENT", saltMonoF);
                putIfPresent(params, "PRIMER_SALT_DIVALENT", saltDiF);
                putIfPresent(params, "PRIMER_DNTP_CONC", dntpF);
                putIfPresent(params, "PRIMER_DNA_CONC", dnaConcF);
                putIfPresent(params, "PRIMER_MAX_COMPL_ANY_TH", complAnyF);
                putIfPresent(params, "PRIMER_MAX_COMPL_END_TH", complEndF);
                putIfPresent(params, "PRIMER_MAX_HAIRPIN_TH", hairpinF);
                putIfPresent(params, "PRIMER_MAX_SELF_ANY_TH", selfAnyF);
                putIfPresent(params, "PRIMER_MAX_SELF_END_TH", selfEndF);
                putIfPresent(params, "PRIMER_MAX_POLY_X", polyXF);
                putIfPresent(params, "PRIMER_MAX_NS_ACCEPTED", nsF);
                putIfPresent(params, "PRIMER_GC_CLAMP", gcClampF);
                putIfPresent(params, "PRIMER_MAX_END_GC", maxEndGcF);

                // ---- 区域 ----
                // v0.1.61：产物区间在高级模式改为可选（留空=不设 SEQUENCE_TARGET，仅按设计区约束）；
                // 简单/单引物模式必填（空则给友好提示，不再是"区域起 不是有效整数"）。
                boolean adv = modeBox.getSelectedIndex() == 1;
                boolean single = (mode != MODE_PAIR);
                String tp = tStartF.getText().trim(), tq = tEndF.getText().trim();
                boolean hasProduct = !tp.isEmpty() && !tq.isEmpty();
                if ((single || !adv) && !hasProduct)
                    throw new Exception("请填写" + (single ? "引物区域" : "产物区间")
                            + "（可在 IGV 中拖选区域自动填充，或手工输入起止）");
                int s = 0, e = 0;
                if (hasProduct) {
                    s = i(tStartF, "区域起"); e = i(tEndF, "区域止");
                    // v0.1.59：起止倒转自动交换（手工输入/反向拖选常见，原来直接报"区域非法"）
                    if (s > e) {
                        int t = s; s = e; e = t;
                        tStartF.setText(String.valueOf(s));
                        tEndF.setText(String.valueOf(e));
                        statusL.setText("提示: 起止已倒转，自动交换为 " + s + "-" + e);
                    }
                }
                if (single) {
                    // 单引物：只 pick 一侧；无产物范围
                    params.put("PRIMER_PICK_LEFT_PRIMER", mode == MODE_LEFT ? "1" : "0");
                    params.put("PRIMER_PICK_RIGHT_PRIMER", mode == MODE_RIGHT ? "1" : "0");
                } else {
                    params.put("PRIMER_PICK_LEFT_PRIMER", "1");
                    params.put("PRIMER_PICK_RIGHT_PRIMER", "1");
                    // v0.1.80：区内模式——产物长度范围在下方依据两端设计带的可达产物长度计算，
                    // 此处不再预置（避免把引物推到选区外）。
                }

                // 仅高级+成对才读左/右端设计区字段：单引物时 modeBox 禁用（永远简单模式），
                // advPanel 隐藏且字段为空，无条件解析会抛「左端设计区起 不是有效整数」（v0.1.58）。
                // v0.1.61：高级模式字段为空 → 友好错误（引导切回简单模式或补全）。
                int us = 0, ul = 0, ds = 0, dl = 0;
                if (adv && !single) {
                    if (uStartF.getText().trim().isEmpty() || uLenF.getText().trim().isEmpty()
                            || dStartF.getText().trim().isEmpty() || dLenF.getText().trim().isEmpty())
                        throw new Exception("高级模式需填写左/右端设计区（起+长）；或切回简单模式只指定产物区间");
                    us = i(uStartF, "左端设计区起"); ul = i(uLenF, "左端设计区长");
                    ds = i(dStartF, "右端设计区起"); dl = i(dLenF, "右端设计区长");
                } else if (!single && hasProduct) {
                    // v0.1.80：简单成对也把设计区放在产物两端内侧（不再用 SEQUENCE_TARGET 把引物推到区外）。
                    // 默认设计带宽度 = min(200, 产物长/2)，至少装下最长引物长；并写回字段便于用户查看。
                    int L = e - (s - 1);
                    int band = Math.min(L, Math.max(lenMax, Math.min(readBounded(flankMaxF, 200, 1, 5000), L / 2)));
                    us = s; ul = band; ds = e - band + 1; dl = band;
                    uStartF.setText(String.valueOf(us)); uLenF.setText(String.valueOf(ul));
                    dStartF.setText(String.valueOf(ds)); dLenF.setText(String.valueOf(dl));
                }

                int[] tmpl = regionToTemplate(mode, adv && !single, s, e, us, ul, ds, dl,
                        chrLen, lenMin, lenMax);
                int tmplStart = tmpl[0], tmplEnd = tmpl[1];

                if (single) {
                    // 单引物：引物必须落在选区内（SEQUENCE_INCLUDED_REGION 限定搜索范围）
                    int s0 = s - 1;
                    params.put("SEQUENCE_INCLUDED_REGION", (s0 - tmplStart) + "," + (e - s0));
                } else {
                    // v0.1.80：区内模式（简单/高级成对统一）——左引物落左端带、右引物落右端带
                    // （SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 白名单）。不再发 SEQUENCE_TARGET：
                    // TARGET 要求产物覆盖整个选区会把引物 5' 强制顶到选区两端，过于受限且历史上常 0 对。
                    if (us <= 0 || ul < lenMin || ds <= 0 || dl < lenMin)
                        throw new Exception("引物设计区非法（两端带宽度需 ≥ 最短引物长 " + lenMin + "）");
                    if (us > ds) throw new Exception("左设计区应在右设计区左侧（允许部分重叠）");
                    params.put("SEQUENCE_PRIMER_PAIR_OK_REGION_LIST",
                            (us - 1 - tmplStart) + "," + ul + "," + (ds - 1 - tmplStart) + "," + dl);
                    // 产物长度范围：可达区间 = [L - ul - dl + 1, L]（两带最内侧/最外侧时），
                    // 与用户设定取交（尊重用户收窄，但不允许溢出可达范围导致 0 对）。
                    int L = e - (s - 1);
                    int aMin = Math.max(1, L - ul - dl + 1);
                    int aMax = L;
                    int uMin = Math.max(prodMin, aMin), uMax = Math.min(prodMax, aMax);
                    if (uMin > uMax) { uMin = aMin; uMax = aMax; prodMin = aMin; prodMax = aMax; }
                    prodMinF.setText(String.valueOf(prodMin));
                    prodMaxF.setText(String.valueOf(prodMax));
                    params.put("PRIMER_PRODUCT_SIZE_RANGE", uMin + "-" + uMax);
                }
                // v0.1.62/0.1.80：模板容量下限重算。区内模式下产物 ≤ 选区长 L，模板需 ≥ L + 2×lenMax
                // （覆盖产物 + 两端引物）；简单/高级路径模板均为选区 ±2×lenMax，恒满足。
                int need;
                if (single) need = 2 * lenMax;
                else if (hasProduct) need = (e - (s - 1)) + 2 * lenMax;
                else need = prodMax;
                if (tmplEnd - tmplStart > 20000) throw new Exception("模板区过大（>20kb），primer3 会很慢；请缩小区间或分块设计");
                if (tmplEnd - tmplStart < need)
                    throw new Exception("模板区（" + (tmplEnd - tmplStart) + "bp）装不下" +
                            (single ? "两侧引物长度，请扩大区域" : "最长产物（" + prodMax + "bp）+ 两侧引物，请扩大区间或减小产物上限"));

                // ---- 取模板序列 ----
                byte[] sb = g.getSequence(chr, tmplStart, tmplEnd);
                if (sb == null || sb.length == 0) throw new Exception("取不到序列：" + chr + ":" + (tmplStart + 1) + "-" + tmplEnd + "（该区间可能不在基因组内）");
                String seq = new String(sb).toUpperCase();
                int nCount = 0;
                for (int k = 0; k < seq.length(); k++) if (seq.charAt(k) == 'N') nCount++;
                if (nCount * 10 > seq.length() * 3)
                    statusL.setText("警告: 模板含 N 比例 " + (nCount * 100 / seq.length()) + "%，设计结果可能不理想");

                // ---- 调 primer3 ----
                String p3id = group + "_" + chr;
                List<Primer3Core.Pair> pairs = Primer3Core.design(p3id, seq, params);
                // v0.1.69：降条件自动重试——原始条件 0 对时逐级放宽筛选参数
                // （区域约束不动），直到出结果或 6 级用尽。
                int relaxUsed = 0;
                List<String> relaxDescs = null;
                if (pairs.isEmpty() && relaxChk.isSelected()) {
                    for (int lv = 1; lv <= MAX_RELAX_LEVEL && pairs.isEmpty(); lv++) {
                        Primer3Dialog.Relaxed r = relaxAt(params, lv);
                        pairs = Primer3Core.design(p3id, seq, r.params);
                        if (!pairs.isEmpty()) { relaxUsed = lv; relaxDescs = r.descs; }
                    }
                }
                if (pairs.isEmpty()) {
                    // v0.1.60：附带 primer3 EXPLAIN 诊断（considered/ok 统计），不再只说"放宽参数"
                    StringBuilder tip = new StringBuilder();
                    tip.append("<html>primer3 未找到合适").append(single ? "引物" : "引物对").append("。<br>");
                    if (relaxChk.isSelected())
                        tip.append("（已自动降级重试至第 ").append(MAX_RELAX_LEVEL).append(" 级条件仍未找到）<br>");
                    if (Primer3Core.lastExplain != null) {
                        for (String ln : Primer3Core.lastExplain.split("\n"))
                            tip.append(ln).append("<br>");
                        tip.append("<br>");
                        // v0.1.62：按 EXPLAIN 统计动态生成建议（哪个原因失败最多就优先建议哪个），
                        // 不再是无差别的一句"放宽参数"
                        String tips = explainTips(Primer3Core.lastExplain, single);
                        if (!tips.isEmpty()) tip.append(tips).append("<br>");
                    }
                    tip.append("可尝试：放宽 Tm/GC 范围、")
                       .append(single ? "扩大区域" : "增大产物范围、扩大区域")
                       .append("、调整高级参数（盐浓度/二聚体阈值）。</html>");
                    JOptionPane.showMessageDialog(this, tip.toString(),
                            "primer3 设计", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                dispose();
                // v0.1.69：降级成功——逐项明示"原始条件 → 降级后条件"+ 风险提示
                // （老板要求"让用户明晰自己选择的时候是降低到什么条件了，还有风险提示"）
                if (relaxUsed > 0) {
                    StringBuilder w = new StringBuilder();
                    w.append("<html><b>⚠ 原始条件未找到合适").append(single ? "引物" : "引物对")
                     .append("，已自动降级到第 ").append(relaxUsed).append(" 级条件后找到。</b><br><br>")
                     .append("<b>第 ").append(relaxUsed).append(" 级放宽内容（相对原始条件）：</b><br>");
                    if (relaxDescs != null) {
                        for (String d : relaxDescs) w.append("· ").append(d).append("<br>");
                    }
                    w.append("<br><b>⚠ 风险提示：</b>降级条件的引物可能 Tm 偏离最优、引物长度变化，")
                     .append("且已取消/放宽了二聚体、发夹等安全约束——非特异扩增与二聚体风险升高。")
                     .append("合成前请核对结果列表中的 Tm/GC/序列信息，正式实验前建议做梯度 PCR")
                     .append("或 BLAST 特异性验证。<br>")
                     .append("<font color='gray'>（如不希望自动降级：取消本对话框「0 对时自动降级重试」勾选后重新设计）</font></html>");
                    JOptionPane.showMessageDialog(IGV.getMainFrame(), w.toString(),
                            "⚠ 已自动降级条件（第 " + relaxUsed + " 级）", JOptionPane.WARNING_MESSAGE);
                }
                Primer3ResultDialog.show(chr, group, pairs, tmplStart);
            } catch (Exception ex) {
                statusL.setText("<html><font color='red'>" + escape(ex.getMessage()) + "</font></html>");
            }
        }

        private static String escape(String s) {
            return s == null ? "未知错误" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        @Override
        public void dispose() {
            PrimerEditTool.clearPcrPreview();   // v0.1.81：关闭对话框即撤下实时预览高亮
            super.dispose();
        }
    }
}

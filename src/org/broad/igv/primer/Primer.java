package org.broad.igv.primer;

import java.awt.Color;

/**
 * 引物数据模型。
 * 坐标系与 IGV 一致：start 为 0-based inclusive，end 为 0-based exclusive（即 IGV 显示的 primer span）。
 */
public class Primer {

    public String name;      // 引物名，如 P001
    public String chr;
    public int start;
    public int end;
    public char strand;      // '+' 或 '-'
    public String role;      // "R1" 或 "R2"
    public int readLen;      // NGS 测序长度 nt（从引物 3' 端延伸）
    public String ampliconId;// 【已废弃】原组模型字段，保留仅为兼容旧 BED/构造函数，不再用于配对逻辑
    public String group;     // 来源分组（导入的 BED 文件名），同组才可共用一行
    public String pairWith;  // 【已废弃】原单伙伴字段，保留仅为兼容旧 BED，配对统一用 pairedWith 边集合
    // v0.1.41 边模型：每条 F-R 连线是一条独立的边，存对端引物名。一个引物可同时持有多条边（1 对多），
    // 取消/新增只作用于单条边，不影响该引物的其他边。渲染/布局/保存全部基于此集合。
    public java.util.Set<String> pairedWith = new java.util.LinkedHashSet<String>();
    public String seq;       // 实际引物序列 5'->3'（反向链引物=正链区间序列的反向互补，写入时即转好；随编辑自动更新）
    public String color;     // 自定义颜色（hex RRGGBB，null=按链默认色；右键色块设置）
    public Integer rowOverride; // 手动指定行号（上下拖动设置；null=自动布局）
    // v0.1.72：5′ 附加（酶切位点/接头/标签…）逐条覆盖。tailSeq 优先于 tailName；
    // tailName = "-" 表示「本条明确不附加」（连全局默认一起挡掉）；两者都空 = 继承方向/全局默认。
    public String tailName;
    public String tailSeq;
    public boolean seqLocked = false;   // v0.2.4：序列已被用户编辑/导入锁定，刷新时不被参考基因组覆盖

    // 实时计算指标
    public double tm = Double.NaN;
    public double gc = Double.NaN;
    public double hairpinDG = 0;    // ΔG，越负越危险
    public double selfDG = 0;
    public double heteroDG = 0;     // 与配对引物的异源二聚体
    public int max3pComp = 0;       // 与配对引物的最大 3' 互补碱基数
    public String dimerReason = "";  // 二聚体失败原因（独立于 failReasons，便于按需评估后单独清除）
    public boolean pass = true;
    public String failReasons = "";  // 长度/Tm/GC/hairpin/self 等非二聚体原因（PrimerMetrics.evaluate 设置）

    // 全体系异源二聚体 Top-5（与其他所有引物相比，按 ΔG 最负排序）
    public java.util.List<HeteroHit> topHetero = new java.util.ArrayList<HeteroHit>();

    public Primer() {
    }

    public Primer(String name, String chr, int start, int end, char strand, String role,
                  int readLen, String ampliconId) {
        this.name = name;
        this.chr = chr;
        this.start = start;
        this.end = end;
        this.strand = strand;
        this.role = role;
        this.readLen = readLen;
        this.ampliconId = ampliconId;
    }

    /** 3' 端基因组坐标（正向链=右端，反向链=左端） */
    public int threePrimeEnd() {
        return strand == '+' ? end : start;
    }

    /** 测序读段覆盖区间 [a,b)（0-based，正链方向语义） */
    public int[] readRegion() {
        if (strand == '+') {
            return new int[]{end, end + readLen};
        } else {
            return new int[]{start - readLen, start};
        }
    }

    public int length() {
        return end - start;
    }

    public String rangeStr() {
        return chr + ":" + (start + 1) + "-" + end;
    }

    /** BED name 字段：名字|role|readNN[|g分组][|a配对组ID][|c颜色][|t附加名][|x自定义附加序列][|r行号] */
    public String bedName() {
        return bedName(null);
    }

    /** 同上；pinRow 非 null 时追加 |r行号（导出时把当前布局行写死，导入可原样恢复） */
    public String bedName(Integer pinRow) {
        return name + "|" + role + "|read" + readLen
                + (group == null ? "" : "|g" + group)
                + (pairedWith.isEmpty() ? "" : "|p" + String.join(";", pairedWith))
                + (color == null ? "" : "|c" + color)
                + tailBedSegs()
                + seqBedSegs()
                + (pinRow == null ? "" : "|r" + pinRow);
    }

    /** v0.1.72：5′ 附加的两个标记段（|t 条目名 / |x 自定义序列）；缺省为空串。 */
    public String tailBedSegs() {
        StringBuilder sb = new StringBuilder();
        if (tailName != null && !tailName.isEmpty()) {
            sb.append("|t").append(tailName.replace("|", "_"));
        }
        if (tailSeq != null && !tailSeq.isEmpty()) {
            sb.append("|x").append(TailLibrary.sanitize(tailSeq));
        }
        return sb.toString();
    }

    /** v0.2.4：序列锁定（用户编辑/导入）时把实际寡核苷酸序列写入 BED（|s...），重载原样恢复，不再被参考覆盖。 */
    public String seqBedSegs() {
        if (!seqLocked || seq == null || seq.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seq.length(); i++) {
            char c = seq.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) sb.append(Character.toUpperCase(c));
        }
        return sb.length() == 0 ? "" : "|s" + sb;
    }

    public String colorHex() {
        if (color != null && !color.isEmpty()) return color.toUpperCase();
        if (!pass) return "FF0000";
        return strand == '+' ? "008000" : "0000FF";
    }

    /** 渲染主体色：自定义色 > 链默认色（选中时提亮） */
    public Color bodyColor() {
        Color c = (color != null && !color.isEmpty())
                ? new Color(Integer.parseInt(color, 16)) : null;
        if (c == null) c = strand == '+' ? new Color(0, 150, 0) : new Color(0, 80, 220);
        return PrimerStore.selected == this ? c.brighter() : c;
    }

    public String metricsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tm=").append(Double.isNaN(tm) ? "-" : String.format("%.1f", tm));
        sb.append("  GC=").append(Double.isNaN(gc) ? "-" : String.format("%.1f%%", gc));
        sb.append("  hairpinDG=").append(String.format("%.1f", hairpinDG));
        sb.append("  selfDG=").append(String.format("%.1f", selfDG));
        sb.append("  heteroDG=").append(String.format("%.1f", heteroDG));
        sb.append("  3'comp=").append(max3pComp);
        if (!failReasons.isEmpty()) sb.append("  FAIL: ").append(failReasons);
        if (!dimerReason.isEmpty()) sb.append("  ").append(dimerReason);
        return sb.toString();
    }

    public Primer copy() {
        Primer p = new Primer(name, chr, start, end, strand, role, readLen, null);
        p.group = group;
        p.pairedWith = new java.util.LinkedHashSet<String>(pairedWith);
        p.color = color;
        p.rowOverride = rowOverride;
        p.tailName = tailName;
        p.tailSeq = tailSeq;
        p.seqLocked = seqLocked;
        p.seq = seq;
        p.tm = tm;
        p.gc = gc;
        p.hairpinDG = hairpinDG;
        p.selfDG = selfDG;
        p.heteroDG = heteroDG;
        p.max3pComp = max3pComp;
        p.dimerReason = dimerReason;
        p.pass = pass;
        p.failReasons = failReasons;
        p.topHetero = new java.util.ArrayList<HeteroHit>(topHetero);
        return p;
    }

    /** 一条异源二聚体命中记录（与其他引物相比） */
    public static class HeteroHit {
        public String partnerName;
        public double dg;   // ΔG，越负越危险
        public int c3;      // 3' 互补碱基数
        public HeteroHit(String n, double d, int c) { partnerName = n; dg = d; c3 = c; }
    }
}

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
    public String ampliconId;// 同一扩增子的 R1/R2 共用一个 id
    public String group;     // 来源分组（导入的 BED 文件名），同组才可共用一行
    public String pairWith;  // 配对引物名称（编辑框填写，按名自动 pair）
    public String seq;       // 当前覆盖区序列（正链方向，随编辑自动更新）
    public String color;     // 自定义颜色（hex RRGGBB，null=按链默认色；右键色块设置）
    public Integer rowOverride; // 手动指定行号（上下拖动设置；null=自动布局）

    // 实时计算指标
    public double tm = Double.NaN;
    public double gc = Double.NaN;
    public double hairpinDG = 0;    // ΔG，越负越危险
    public double selfDG = 0;
    public double heteroDG = 0;     // 与配对引物的异源二聚体
    public int max3pComp = 0;       // 与配对引物的最大 3' 互补碱基数
    public boolean pass = true;
    public String failReasons = "";

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

    /** BED name 字段：名字|role|readNN[|g分组][|a配对组ID][|c颜色][|r行号] */
    public String bedName() {
        return bedName(null);
    }

    /** 同上；pinRow 非 null 时追加 |r行号（导出时把当前布局行写死，导入可原样恢复） */
    public String bedName(Integer pinRow) {
        return name + "|" + role + "|read" + readLen
                + (group == null ? "" : "|g" + group)
                + (ampliconId == null ? "" : "|a" + ampliconId)
                + (color == null ? "" : "|c" + color)
                + (pinRow == null ? "" : "|r" + pinRow);
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
        if (!pass) sb.append("  FAIL: ").append(failReasons);
        return sb.toString();
    }

    public Primer copy() {
        Primer p = new Primer(name, chr, start, end, strand, role, readLen, ampliconId);
        p.group = group;
        p.pairWith = pairWith;
        p.color = color;
        p.rowOverride = rowOverride;
        p.seq = seq;
        p.tm = tm;
        p.gc = gc;
        p.hairpinDG = hairpinDG;
        p.selfDG = selfDG;
        p.heteroDG = heteroDG;
        p.max3pComp = max3pComp;
        p.pass = pass;
        p.failReasons = failReasons;
        return p;
    }
}

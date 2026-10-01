package org.broad.igv.primer;

import org.broad.igv.track.AbstractTrack;
import org.broad.igv.track.RenderContext;
import org.broad.igv.track.TrackClickEvent;
import org.broad.igv.ui.panel.IGVPopupMenu;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.GeneralPath;
import java.util.List;

/**
 * 引物轨渲染：
 *  - 前向(+) 绿色箭头向右，反向(-) 蓝色箭头向左
 *  - 测序延长区半透明带（从 3' 端延伸 readLen）
 *  - 同一扩增子 R1-R2 之间画扩增子连线
 *  - fail 红色外框
 *  - 拖拽时由 PrimerEditTool 设置 guide 状态，overlay() 画竖线参考线
 */
public class PrimerTrack extends AbstractTrack {

    public PrimerTrack() {
        super("primer_designer");
        setName("Primers 引物");
        setColor(new Color(0, 128, 0));
        setHeight(60);
        PrimerStore.setTrack(this);
    }

    public void update() {
        // 通知面板高度等变化（简单起见高度固定）
    }

    @Override
    public void render(RenderContext ctx, Rectangle rect) {
        Graphics2D g = ctx.getGraphics();
        String chr = ctx.getChr();
        if (chr == null) return;
        // 首次渲染自动进入编辑模式（免手动切换，空白拖动仍可平移视图）
        if (!PrimerEditTool.autoTried()) PrimerEditTool.autoEnter();
        PrimerStore.lastChr = chr;
        // 当前染色体可见引物，按起点排序后做行布局（重叠/不同分组 → 上下分行）
        List<Primer> vis = new java.util.ArrayList<Primer>();
        for (Primer p : PrimerStore.getPrimers()) if (chr.equals(p.chr)) vis.add(p);
        java.util.Collections.sort(vis, new java.util.Comparator<Primer>() {
            public int compare(Primer a, Primer b) { return a.start - b.start; }
        });
        PrimerStore.screenRects.clear();   // 重新记录当前染色体可见引物的屏幕矩形
        PrimerStore.screenRows.clear();
        java.util.Map<Primer, Integer> rows = layoutRows(vis);
        int nRows = 0;
        for (Integer r : rows.values()) nRows = Math.max(nRows, r + 1);
        int needH = Math.max(60, nRows * ROW_H + 14);
        if (getHeight() < needH) setHeight(needH);

        // 1) 配对连线：size==2 同排水平线；size>2 hub-spoke 放射线（不同色区分每条配对）
        java.util.Set<String> drawnGroups = new java.util.HashSet<String>();
        for (Primer p : vis) {
            if (p.ampliconId == null || drawnGroups.contains(p.ampliconId)) continue;
            java.util.List<Primer> grp = new java.util.ArrayList<Primer>();
            for (Primer q : vis) if (p.ampliconId.equals(q.ampliconId)) grp.add(q);
            if (grp.size() < 2) continue;
            drawnGroups.add(p.ampliconId);
            boolean sel = PrimerStore.selected != null
                    && p.ampliconId.equals(PrimerStore.selected.ampliconId);
            if (grp.size() == 2) {
                Primer a = grp.get(0), b = grp.get(1);
                int lo = Math.min(a.start, b.start), hi = Math.max(a.end, b.end);
                int x1 = ctx.bpToScreenPixel(lo), x2 = ctx.bpToScreenPixel(hi);
                int ya = rowY(rect, rows.get(a)) + ARROW_H / 2;
                int yb = rowY(rect, rows.get(b)) + ARROW_H / 2;
                g.setColor(sel ? new Color(80, 80, 80) : new Color(150, 150, 150));
                if (sel) g.setStroke(new BasicStroke(2f));
                // v0.1.10：恢复二次贝塞尔拱形连线（与多对一汇聚风格一致），控制点在两锚点中点上方拱起
                int cx = (x1 + x2) / 2;
                int cy = Math.min(ya, yb) - Math.max(14, Math.abs(ya - yb) / 3);
                g.draw(new java.awt.geom.QuadCurve2D.Float(x1, ya, cx, cy, x2, yb));
                if (sel) g.setStroke(new BasicStroke(1f));
                boolean aLeft = a.start <= b.start;
                Color ca = a.strand == '+' ? new Color(0, 150, 0) : new Color(0, 80, 220);
                Color cb = b.strand == '+' ? new Color(0, 150, 0) : new Color(0, 80, 220);
                drawDirArrow(g, x1, ya, aLeft ? 1 : -1, ca);
                drawDirArrow(g, x2, yb, aLeft ? -1 : 1, cb);
                int len = hi - lo;
                g.setColor(Color.DARK_GRAY);
                g.setFont(g.getFont().deriveFont(11f));
                // v0.1.15：PCR 产物长度放大并移到曲线下方（原在拱顶上方易被轨道顶边裁掉），水平居中
                String lenTxt = formatLen(len);
                g.drawString(lenTxt, cx - g.getFontMetrics(g.getFont()).stringWidth(lenTxt) / 2,
                        Math.max(ya, yb) + ARROW_H + 2);
                g.setFont(g.getFont().deriveFont(10f));
            } else {
                // v8 曲线汇聚：hub = R2 角色（没有则取居中成员）；其余成员用二次贝塞尔曲线平滑汇聚到 hub
                java.util.List<Primer> sorted = new java.util.ArrayList<Primer>(grp);
                java.util.Collections.sort(sorted, new java.util.Comparator<Primer>() {
                    public int compare(Primer x, Primer y) { return x.start - y.start; }
                });
                Primer hub = null;
                for (Primer m : sorted) if ("R2".equals(m.role)) { hub = m; break; }
                if (hub == null) hub = sorted.get(sorted.size() / 2);
                int xHub = (ctx.bpToScreenPixel(hub.start) + ctx.bpToScreenPixel(hub.end)) / 2;
                int yHub = rowY(rect, rows.get(hub)) + ARROW_H / 2;
                int idx = 0;
                for (Primer s : grp) {
                    if (s == hub) continue;
                    Color c = SPOKE_COLORS[idx % SPOKE_COLORS.length];
                    int xS = (ctx.bpToScreenPixel(s.start) + ctx.bpToScreenPixel(s.end)) / 2;
                    int yS = rowY(rect, rows.get(s)) + ARROW_H / 2;
                    // 二次贝塞尔曲线：控制点取两锚点中点并向上/外拱起，形成平滑汇聚弧
                    int cx = (xS + xHub) / 2;
                    int cy = Math.min(yS, yHub) - Math.max(14, Math.abs(yS - yHub) / 3);
                    g.setColor(c);
                    g.setStroke(new BasicStroke(sel ? 2.5f : 1.4f));
                    g.draw(new java.awt.geom.QuadCurve2D.Float(xS, yS, cx, cy, xHub, yHub));
                    g.setStroke(new BasicStroke(1f));
                    // v0.1.15：每条 spoke 标注 PCR 产物长度，放大并移到曲线下方，水平居中
                    int len = Math.max(s.end, hub.end) - Math.min(s.start, hub.start);
                    g.setFont(g.getFont().deriveFont(11f));
                    g.setColor(c.darker());
                    String slen = formatLen(len);
                    g.drawString(slen, cx - g.getFontMetrics(g.getFont()).stringWidth(slen) / 2,
                            Math.max(yS, yHub) + ARROW_H + 2);
                    // 箭头指向 hub 侧（曲线终点附近）
                    drawDirArrow(g, xHub + (xS < xHub ? -6 : 6), yHub + (yS < yHub ? -4 : 4),
                            xS < xHub ? -1 : 1, c);
                    g.setFont(g.getFont().deriveFont(8f));
                    g.setColor(c.darker());
                    g.drawString("P" + (idx + 1), xS + (xS <= xHub ? 4 : -16), yS - 4);
                    g.setFont(g.getFont().deriveFont(10f));
                    idx++;
                }
                // hub 标记：文字 + 小圆点
                g.setColor(Color.DARK_GRAY);
                g.setFont(g.getFont().deriveFont(8f));
                g.drawString("hub×" + grp.size(), xHub - 12, yHub - 5);
                g.setFont(g.getFont().deriveFont(10f));
                g.setColor(new Color(60, 60, 60));
                g.fillOval(xHub - 2, yHub - 2, 4, 4);
            }
        }

        // 2) 每条引物（按各自行 y 渲染）
        for (Primer p : vis) {
            drawPrimer(ctx, g, rect, p, rows.get(p));
        }
    }

    public static final int ROW_H = 30;    // 行高（压缩，引物条更窄）
    private static final int ARROW_H = 12;

    /** 右键菜单最大宽度：与 IGV 原生右键菜单宽度一致，避免命中信息（二聚体/Tm/GC 等）过多时菜单被撑成大片白板 */
    private static final int MENU_MAX_W = 320;

    // 一对多配对中每条 spoke 连线的区分色（避开红=失败、绿/蓝=链向）
    private static final Color[] SPOKE_COLORS = {
            new Color(255, 140, 0), new Color(150, 0, 200), new Color(0, 150, 160),
            new Color(200, 0, 120), new Color(130, 110, 0), new Color(0, 110, 200)
    };

    /** v0.1.3：长度格式化 —— <1000 显示 "N bp"，否则 "x.xx kb" */
    static String formatLen(int len) {
        return len < 1000 ? len + " bp" : String.format("%.2f kb", len / 1000.0);
    }

    /** v0.1.14：测序长度输入对话框，返回 {R1, R2, 含引物长度(1/0)}；取消返回 null。 */
    private static int[] askReadLen(String title) {
        JSpinner sp1 = new JSpinner(new SpinnerNumberModel(PrimerStore.defaultReadF, 0, 1000, 1));
        JSpinner sp2 = new JSpinner(new SpinnerNumberModel(PrimerStore.defaultReadR, 0, 1000, 1));
        JCheckBox cb = new JCheckBox("包含引物长度", PrimerStore.defaultIncludeLen);
        JPanel pnl = new JPanel(new GridLayout(0, 2, 8, 6));
        pnl.add(new JLabel("R1 测序长度 (nt)"));
        pnl.add(sp1);
        pnl.add(new JLabel("R2 测序长度 (nt)"));
        pnl.add(sp2);
        pnl.add(new JLabel("口径"));
        pnl.add(cb);
        int r = JOptionPane.showConfirmDialog(null, pnl, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return null;
        return new int[]{(Integer) sp1.getValue(), (Integer) sp2.getValue(), cb.isSelected() ? 1 : 0};
    }

    /** v0.1.15：失败判定阈值设置对话框（右键菜单进入；应用到全部引物并持久化） */
    private static void showFailConfigDialog() {
        java.util.function.Function<String, JTextField> tf = (t) -> {
            JTextField f = new JTextField(t);
            f.setColumns(7);
            return f;
        };
        JTextField lenMin = tf.apply(String.valueOf(PrimerStore.failLenMin));
        JTextField lenMax = tf.apply(String.valueOf(PrimerStore.failLenMax));
        JTextField tmMin = tf.apply(String.valueOf(PrimerStore.failTmMin));
        JTextField tmMax = tf.apply(String.valueOf(PrimerStore.failTmMax));
        JTextField gcMin = tf.apply(String.valueOf(PrimerStore.failGcMin));
        JTextField gcMax = tf.apply(String.valueOf(PrimerStore.failGcMax));
        JTextField hair = tf.apply(String.valueOf(PrimerStore.failHairpinTh));
        JTextField self = tf.apply(String.valueOf(PrimerStore.failSelfTh));
        JTextField h3 = tf.apply(String.valueOf(PrimerStore.failHetero3pTh));
        JTextField hdg = tf.apply(String.valueOf(PrimerStore.failHeteroDgTh));
        JPanel p = new JPanel(new java.awt.GridLayout(0, 2, 6, 4));
        p.add(new JLabel("长度下限 (nt)"));            p.add(lenMin);
        p.add(new JLabel("长度上限 (nt)"));            p.add(lenMax);
        p.add(new JLabel("Tm 下限 (℃)"));             p.add(tmMin);
        p.add(new JLabel("Tm 上限 (℃)"));             p.add(tmMax);
        p.add(new JLabel("GC 下限 (%)"));              p.add(gcMin);
        p.add(new JLabel("GC 上限 (%)"));              p.add(gcMax);
        p.add(new JLabel("hairpin ΔG 阈值 (≤即失败)")); p.add(hair);
        p.add(new JLabel("self-dimer ΔG 阈值 (≤即失败)")); p.add(self);
        p.add(new JLabel("配对 3' 互补阈值 (≥即失败)")); p.add(h3);
        p.add(new JLabel("配对二聚体 ΔG 阈值 (≤即失败)")); p.add(hdg);
        int r = JOptionPane.showConfirmDialog(null, p, "设置失败判定条件",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            PrimerStore.failLenMin = Integer.parseInt(lenMin.getText().trim());
            PrimerStore.failLenMax = Integer.parseInt(lenMax.getText().trim());
            PrimerStore.failTmMin = Double.parseDouble(tmMin.getText().trim());
            PrimerStore.failTmMax = Double.parseDouble(tmMax.getText().trim());
            PrimerStore.failGcMin = Double.parseDouble(gcMin.getText().trim());
            PrimerStore.failGcMax = Double.parseDouble(gcMax.getText().trim());
            PrimerStore.failHairpinTh = Double.parseDouble(hair.getText().trim());
            PrimerStore.failSelfTh = Double.parseDouble(self.getText().trim());
            PrimerStore.failHetero3pTh = Integer.parseInt(h3.getText().trim());
            PrimerStore.failHeteroDgTh = Double.parseDouble(hdg.getText().trim());
            PrimerStore.saveFailConfig();
            PrimerStore.refreshAll();
            JOptionPane.showMessageDialog(null, "已更新失败判定条件，并重新评估全部引物。");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null, "输入格式错误，未保存：" + ex.getMessage(),
                    "格式错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int rowY(Rectangle rect, int row) {
        return rect.y + 4 + Math.max(0, row) * ROW_H;
    }

    /**
     * v0.1.17：不绘制、仅按当前染色体计算引物轨所需高度（行布局与 render 一致），
     * 供 PrimerStore.refresh() 判断"是否需要全量刷新"——仅当行数（高度）变化时才有必要调用昂贵的 doRefresh()。
     */
    public static int computeNeededHeight(String chr) {
        if (chr == null) return 60;
        java.util.List<Primer> vis = new java.util.ArrayList<Primer>();
        for (Primer p : PrimerStore.getPrimers()) if (chr.equals(p.chr)) vis.add(p);
        java.util.Collections.sort(vis, new java.util.Comparator<Primer>() {
            public int compare(Primer a, Primer b) { return a.start - b.start; }
        });
        java.util.Map<Primer, Integer> rows = layoutRows(vis);
        int nRows = 0;
        for (Integer r : rows.values()) nRows = Math.max(nRows, r + 1);
        return Math.max(60, nRows * ROW_H + 14);
    }

    /**
     * 配对感知行布局（v8）：
     *  - 重叠判定用【含测序延长区的扩展区间】（引物本体 ∪ readRegion），延长区占位、不被其他引物压住。
     *  - rowOverride（上下拖动手动指定行）最优先。
     *  - 配对组 size==2 强制同一行；size>2 各占独立行（曲线汇聚渲染）。
     *  - 未配对引物按"不重叠 + 同组"放同一行；重叠或异组 → 换行。
     */
    private static java.util.Map<Primer, Integer> layoutRows(List<Primer> vis) {
        java.util.Map<Primer, Integer> map = new java.util.HashMap<Primer, Integer>();
        java.util.List<java.util.List<Primer>> rows = new java.util.ArrayList<java.util.List<Primer>>();

        // 0) 手动指定行的引物最优先固定
        for (Primer p : vis) {
            if (p.rowOverride == null) continue;
            ensureRows(rows, p.rowOverride);
            rows.get(p.rowOverride).add(p);
            map.put(p, p.rowOverride);
        }

        // 按 ampliconId 分组（仅 size>=2 视为配对组）
        java.util.Map<String, java.util.List<Primer>> groups =
                new java.util.HashMap<String, java.util.List<Primer>>();
        for (Primer p : vis) {
            if (p.ampliconId != null && !map.containsKey(p)) {
                groups.computeIfAbsent(p.ampliconId, new java.util.function.Function<String, java.util.List<Primer>>() {
                    public java.util.List<Primer> apply(String k) { return new java.util.ArrayList<Primer>(); }
                });
                groups.get(p.ampliconId).add(p);
            }
        }

        // 1) 配对组
        for (java.util.List<Primer> grp : groups.values()) {
            if (grp.size() < 2) continue;
            java.util.List<Primer> members = new java.util.ArrayList<Primer>();
            for (Primer p : grp) if (!map.containsKey(p)) members.add(p);
            if (members.isEmpty()) continue;
            java.util.Collections.sort(members, new java.util.Comparator<Primer>() {
                public int compare(Primer x, Primer y) { return x.start - y.start; }
            });
            if (members.size() == 1) {
                // 组内其他成员已被手动固定行 → 剩余成员放同行
                Primer only = members.get(0);
                Integer r0 = map.get(grp.get(0));
                if (r0 == null) r0 = 0;
                ensureRows(rows, r0);
                rows.get(r0).add(only);
                map.put(only, r0);
                continue;
            }
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            for (Primer m : members) {
                long[] sp = spanOf(m);
                lo = Math.min(lo, sp[0]);
                hi = Math.max(hi, sp[1]);
            }
            int t = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (!rowOverlaps(rows.get(i), lo, hi)) { t = i; break; }
            }
            if (t < 0) { rows.add(new java.util.ArrayList<Primer>()); t = rows.size() - 1; }
            for (Primer m : members) { rows.get(t).add(m); map.put(m, t); }
        }

        // 2) 未配对（单条）引物
        for (Primer p : vis) {
            if (map.containsKey(p)) continue;
            String grp = p.group == null ? "" : p.group;
            long[] sp = spanOf(p);
            int t = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (!rowOverlaps(rows.get(i), sp[0], sp[1])
                        && (rows.get(i).isEmpty() || grp.equals(rowGroupOf(rows.get(i))))) { t = i; break; }
            }
            if (t < 0) { rows.add(new java.util.ArrayList<Primer>()); t = rows.size() - 1; }
            rows.get(t).add(p);
            map.put(p, t);
        }

        return map;
    }

    private static void ensureRows(java.util.List<java.util.List<Primer>> rows, int n) {
        while (rows.size() <= n) rows.add(new java.util.ArrayList<Primer>());
    }

    /** 引物占位区间（含测序延长区）：延长区也占行，不被其他引物重叠 */
    private static long[] spanOf(Primer p) {
        int[] rr = p.readRegion();
        return new long[]{Math.min(p.start, rr[0]), Math.max(p.end, rr[1])};
    }

    private static String rowGroupOf(java.util.List<Primer> row) {
        for (Primer r : row) {
            if (r.group != null && !r.group.isEmpty()) return r.group;
        }
        return "";
    }

    private static boolean rowOverlaps(java.util.List<Primer> row, long s, long e) {
        for (Primer r : row) {
            long[] sp = spanOf(r);
            if (sp[0] < e && sp[1] > s) return true;
        }
        return false;
    }

    private void drawPrimer(RenderContext ctx, Graphics2D g, Rectangle rect, Primer p, int row) {
        int x0 = ctx.bpToScreenPixel(p.start);
        int x1 = ctx.bpToScreenPixel(p.end);
        int w = Math.max(3, x1 - x0);
        int y = rowY(rect, row);
        int h = ARROW_H;
        int midY = y + h / 2;

        Color body = p.bodyColor();

        // 测序延长区：仅作覆盖标注，画在引物下方、浅色虚线框，明确与引物本体分开（不合并进引物）
        int[] rr = p.readRegion();
        int rx0 = ctx.bpToScreenPixel(rr[0]);
        int rx1 = ctx.bpToScreenPixel(rr[1]);
        if (rx1 != rx0) {
            int ex = Math.min(rx0, rx1);
            int ew = Math.abs(rx1 - rx0);
            int ey = y + h + 2;
            g.setColor(new Color(body.getRed(), body.getGreen(), body.getBlue(), 28));
            g.fillRect(ex, ey, ew, 4);
            Graphics2D dg = (Graphics2D) g;
            dg.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{3f, 3f}, 0f));
            g.setColor(new Color(body.getRed(), body.getGreen(), body.getBlue(), 130));
            g.drawRect(ex, ey, ew, 4);
            dg.setStroke(new BasicStroke(1f));
            if (PrimerStore.selected == p) {
                g.setColor(Color.GRAY);
                g.setFont(g.getFont().deriveFont(8f));
                g.drawString((p.strand == '+' ? "R1+" : "R2-") + p.readLen + "nt", ex, ey + 13);
                g.setFont(g.getFont().deriveFont(10f));
            }
        }

        // 引物箭头本体（仅引物本身长度，不含测序延伸）
        GeneralPath arrow = new GeneralPath();
        if (p.strand == '+') {
            int head = Math.min(7, w - 1);
            arrow.moveTo(x0, y);
            arrow.lineTo(x0 + w - head, y);
            arrow.lineTo(x0 + w - head, y - 3);
            arrow.lineTo(x0 + w, y + h / 2);
            arrow.lineTo(x0 + w - head, y + h + 3);
            arrow.lineTo(x0 + w - head, y + h);
            arrow.lineTo(x0, y + h);
        } else {
            int head = Math.min(7, w - 1);
            arrow.moveTo(x1, y);
            arrow.lineTo(x0 + head, y);
            arrow.lineTo(x0 + head, y - 3);
            arrow.lineTo(x0, y + h / 2);
            arrow.lineTo(x0 + head, y + h + 3);
            arrow.lineTo(x0 + head, y + h);
            arrow.lineTo(x1, y + h);
        }
        arrow.closePath();
        g.setColor(body);
        g.fill(arrow);

        // 失败标记：仅把引物自身轮廓（箭头主体）描红，不画额外方框/椭圆环
        if (!p.pass) {
            g.setColor(Color.RED);
            g.setStroke(new BasicStroke(2f));
            g.draw(arrow);
            g.setStroke(new BasicStroke(1f));
        }
        // 选中高亮橙框（与失败红描边共存不冲突）
        if (PrimerStore.selected == p) {
            g.setColor(Color.ORANGE);
            g.drawRect(x0 - 2, y - 3, w + 4, h + 8);
        }

        // 单行星号标签（引物下方，节省纵向空间）：名称 + 方向 + Tm/GC + 测序长度
        g.setColor(p.pass ? Color.DARK_GRAY : Color.RED);
        g.setFont(g.getFont().deriveFont(11f));
        String label = p.name + " " + (p.strand == '+' ? "F" : "R")
                + " Tm" + String.format("%.0f", p.tm) + " GC" + String.format("%.0f", p.gc)
                + (p.readLen > 0 ? " " + (p.strand == '+' ? "R1+" : "R2-") + p.readLen + "nt" : "");
        g.drawString(label, x0, y + h + 13);
        g.setFont(g.getFont().deriveFont(10f));

        // v8：显示碱基序列（右键开关；5'->3' 方向；缩放足够时才画，避免糊成一团）
        if (PrimerStore.showSeq && p.seq != null && !p.seq.isEmpty()) {
            String s = p.strand == '+' ? p.seq : PrimerMetrics.revComp(p.seq);
            if (w >= s.length() * 3) {
                g.setColor(new Color(120, 40, 140));
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
                g.drawString(s, x0, y + h + 23);
                g.setFont(g.getFont().deriveFont(10f));
            }
        }

        // 端点手柄（选中时显示两个小方块，提示可拖拽缩放）
        if (PrimerStore.selected == p) {
            g.setColor(Color.ORANGE);
            g.fillRect(x0 - 3, midY - 3, 6, 6);
            g.fillRect(x1 - 3, midY - 3, 6, 6);
        }

        // 记录屏幕矩形（含 y）与行号，供编辑工具命中检测 / 上下拖动换行
        PrimerStore.screenRects.put(p, new Rectangle(x0, y, w, h));
        PrimerStore.screenRows.put(p, Math.max(0, row));
    }

    /** 在 (x,y) 画实心方向三角：dir=+1 尖端指向右，dir=-1 尖端指向左（尖端位于 x）。 */
    private void drawDirArrow(Graphics2D g, int x, int y, int dir, Color c) {
        int s = 5;
        int[] xs = {x, x - dir * s, x - dir * s};
        int[] ys = {y, y - 3, y + 3};
        g.setColor(c);
        g.fillPolygon(xs, ys, 3);
    }

    /** 拖拽参考线（竖线 + 坐标），由工具设置状态 */
    @Override
    public void overlay(RenderContext ctx, Rectangle rect) {
        int[] guides = PrimerEditTool.guidePositions(ctx.getChr());
        if (guides == null) return;
        Graphics2D g = ctx.getGraphics();
        g.setColor(new Color(255, 60, 60));
        Graphics2D gg = (Graphics2D) g;
        gg.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 4f}, 0f));
        for (int gp : guides) {
            int x = ctx.bpToScreenPixel(gp);
            g.drawLine(x, rect.y, x, rect.y + rect.height);
            g.drawString(String.valueOf(gp + 1), x + 2, rect.y + 12);
        }
        gg.setStroke(new BasicStroke(1f));
    }

    /** 右键菜单（IGV 原生 track 菜单扩展点） */
    @Override
    public IGVPopupMenu getPopupMenu(TrackClickEvent e) {
        BoundedPopupMenu menu = new BoundedPopupMenu();
        final int clickBp = (int) e.getChromosomePosition();
        // v8 行感知命中：优先用鼠标屏幕坐标 + screenRects（上下行可区分），异常时退化为 bp 命中
        Primer h = null;
        try {
            java.awt.event.MouseEvent me = e.getMouseEvent();
            h = hitTest(me.getX(), me.getY(), e.getFrame().getChrName());
        } catch (Throwable t) {
            h = null;
        }
        if (h == null) h = hitTest(clickBp, e.getFrame().getChrName());
        final Primer hit = h;

        if (hit != null) {
            // 命中信息可能很长（含二聚体/Tm/GC 等），截断后展示，避免撑宽菜单
            String full = "选中: " + hit.bedName() + "  " + hit.metricsSummary();
            String shown = full.length() > 84 ? full.substring(0, 81) + "…" : full;
            JMenuItem info = new JMenuItem(shown);
            info.setEnabled(false);
            menu.add(info);
            menu.addSeparator();
        }

        // v8：命中引物 → "编辑引物"；空白 → "在此添加引物"
        menu.add(item(hit != null ? "编辑引物..." : "在此添加引物...", new Runnable() {
            public void run() {
                AddPrimerDialog.show(clickBp, hit);
            }
        }));

        JMenuItem editMode = new JMenuItem("进入引物编辑模式（拖拽/拖边）");
        editMode.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                PrimerEditTool.enterEditMode();
            }
        });
        menu.add(editMode);

        if (hit != null) {
            menu.add(item("删除选中引物", new Runnable() {
                public void run() {
                    PrimerStore.remove(hit);
                }
            }));
            menu.add(item("重新计算该引物参数", new Runnable() {
                public void run() {
                    PrimerStore.refreshSequence(hit);
                    PrimerStore.evaluatePairs();
                    PrimerStore.refresh();
                }
            }));
            // v0.1.16：复制选定引物——原引物正下方生成同款，名称末位数字+1
            menu.add(item("复制选定引物（下方生成）", new Runnable() {
                public void run() {
                    PrimerStore.duplicate(hit);
                }
            }));
            // v8：颜色一行 12 常用色，点击即换该引物颜色
            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
            colorRow.setOpaque(false);
            colorRow.add(new JLabel("颜色:"));
            final String[][] PALETTE = {
                    {"红", "E53935"}, {"橙", "FB8C00"}, {"黄", "FDD835"}, {"绿", "43A047"},
                    {"青", "00ACC1"}, {"蓝", "1E88E5"}, {"紫", "8E24AA"}, {"品红", "D81B60"},
                    {"棕", "795548"}, {"灰", "757575"}, {"黑", "37474F"}, {"粉", "F48FB1"}
            };
            for (final String[] c : PALETTE) {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(16, 16));
                b.setBackground(new Color(Integer.parseInt(c[1], 16)));
                b.setToolTipText(c[0]);
                b.setMargin(new Insets(0, 0, 0, 0));
                b.setContentAreaFilled(true);
                b.setBorderPainted(false);
                b.setOpaque(true);
                b.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        hit.color = c[1];
                        PrimerStore.refresh();
                    }
                });
                colorRow.add(b);
            }
            JButton auto = new JButton("默认");
            auto.setFont(auto.getFont().deriveFont(9f));
            auto.setMargin(new Insets(0, 2, 0, 2));
            auto.setToolTipText("恢复按链默认色（绿=+/蓝=-）");
            auto.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    hit.color = null;
                    PrimerStore.refresh();
                }
            });
            colorRow.add(auto);
            menu.add(colorRow);
        }

        // v8：显示碱基序列开关（全局）
        JCheckBoxMenuItem seqItem = new JCheckBoxMenuItem("显示序列", PrimerStore.showSeq);
        seqItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                PrimerStore.showSeq = seqItem.isSelected();
                PrimerStore.refresh();
            }
        });
        menu.add(seqItem);

        // v0.1.15：失败判定阈值设置入口
        menu.add(item("设置失败判定阈值...", new Runnable() {
            public void run() {
                showFailConfigDialog();
            }
        }));

        // v9：自动保存开关 + 恢复（IGV session 不保存插件轨，用它兜底防丢失）
        final JCheckBoxMenuItem asItem = new JCheckBoxMenuItem("自动保存引物（防丢失）", PrimerStore.autosaveEnabled);
        asItem.setToolTipText("每次改动自动写到 ~/.igv_primer_autosave.bed");
        asItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                PrimerStore.setAutosave(asItem.isSelected());
            }
        });
        menu.add(asItem);
        menu.add(item("恢复上次编辑（自动保存）", new Runnable() {
            public void run() {
                int n = ExportUtils.restoreAutosave();
                JOptionPane.showMessageDialog(null, n > 0
                        ? "已从自动保存恢复 " + n + " 条引物（配对/颜色/测序长度完整还原，可继续编辑）"
                        : "没有可恢复的自动保存文件（" + ExportUtils.autosaveFile().getAbsolutePath() + "）");
            }
        }));

        // v0.1.8 批量操作子菜单
        JMenu batchMenu = new JMenu("批量操作");
        int failCnt = 0;
        for (Primer p : PrimerStore.getPrimers()) if (!p.pass) failCnt++;
        final int fCnt = failCnt;
        batchMenu.add(item("删除全部失败引物 (" + failCnt + ")", new Runnable() {
            public void run() {
                if (fCnt == 0) {
                    JOptionPane.showMessageDialog(null, "当前没有失败引物，无需删除。");
                    return;
                }
                int r = JOptionPane.showConfirmDialog(null,
                        "确认删除全部 " + fCnt + " 条失败引物？\n（此操作不可撤销，建议先导出 BED 备份）",
                        "批量删除失败引物", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION) {
                    int removed = PrimerStore.removeFailed();
                    JOptionPane.showMessageDialog(null, "已删除 " + removed + " 条失败引物，孤立配对已同步清理。");
                }
            }
        }));
        batchMenu.add(item("解除全部配对", new Runnable() {
            public void run() {
                boolean hasPair = false;
                for (Primer p : PrimerStore.getPrimers()) if (p.ampliconId != null) { hasPair = true; break; }
                if (!hasPair) {
                    JOptionPane.showMessageDialog(null, "当前没有已配对的引物。");
                    return;
                }
                int r = JOptionPane.showConfirmDialog(null, "确认解除全部引物配对？",
                        "解除全部配对", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION) {
                    PrimerStore.clearAllPairs();
                    JOptionPane.showMessageDialog(null, "已解除全部配对。");
                }
            }
        }));
        // v0.1.14：按「套」设置——右键命中引物时只作用于该引物所属 BED 套，其他套不受影响
        final String grp = hit != null ? hit.group : null;
        final String grpLabel = (hit != null && hit.group != null) ? hit.group : "未分组(手动添加)";
        batchMenu.add(item("设置本套测序长度 (R1/R2)... [" + grpLabel + "]", new Runnable() {
            public void run() {
                int[] v = askReadLen("设置本套测序长度 - " + grpLabel);
                if (v == null) return;
                int n = PrimerStore.setReadLenForGroup(grp, v[0], v[1], v[2] == 1);
                JOptionPane.showMessageDialog(null, n > 0
                        ? "已将「" + grpLabel + "」共 " + n + " 条引物设为 R1=" + v[0] + " nt, R2=" + v[1]
                        + " nt（含引物长度=" + (v[2] == 1) + "）。其他 BED 套不受影响。"
                        : "未找到属于「" + grpLabel + "」的引物。");
            }
        }));
        batchMenu.add(item("设置【全部引物】测序长度 (R1/R2)...", new Runnable() {
            public void run() {
                int[] v = askReadLen("设置全部引物测序长度");
                if (v == null) return;
                PrimerStore.setReadLenByRole(v[0], v[1], v[2] == 1);
                JOptionPane.showMessageDialog(null, "已将全部引物测序长度设为 R1=" + v[0] + " nt, R2=" + v[1]
                        + " nt（含引物长度=" + (v[2] == 1) + "），并写入默认配置，新增引物自动沿用。");
            }
        }));
        menu.add(batchMenu);

        menu.addSeparator();
        menu.add(item("导出 BED（方向+颜色+测序长度）", new Runnable() {
            public void run() {
                ExportUtils.exportBED();
            }
        }));
        menu.add(item("导出引物序列 FASTA（按方向）", new Runnable() {
            public void run() {
                ExportUtils.exportPrimerFasta();
            }
        }));
        menu.add(item("导出测序读段 FASTA（含测序长度区域）", new Runnable() {
            public void run() {
                ExportUtils.exportReadFasta();
            }
        }));
        menu.add(item("导入 BED（恢复编辑）", new Runnable() {
            public void run() {
                ExportUtils.importBED();
            }
        }));
        menu.addSeparator();
        menu.add(item("重算全部参数", new Runnable() {
            public void run() {
                PrimerStore.refreshAll();
            }
        }));
        menu.add(item("清空全部引物", new Runnable() {
            public void run() {
                PrimerStore.clear();
            }
        }));
        menu.addSeparator();
        menu.add(item("退出引物编辑模式", new Runnable() {
            public void run() {
                PrimerEditTool.exitEditMode();
            }
        }));
        return menu;
    }

    private static JMenuItem item(String text, final Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
        return mi;
    }

    /**
     * 限制最大宽度的右键菜单：IGVPopupMenu 宽度由其最宽子项决定，
     * 命中信息（二聚体等指标）较多时单个 JMenuItem 文本极长会把整张菜单撑成大片白板。
     * 此处覆写 getPreferredSize 把宽度封顶为 MENU_MAX_W，与 IGV 原生右键菜单宽度一致。
     */
    private static class BoundedPopupMenu extends IGVPopupMenu {
        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (d.width > MENU_MAX_W) d = new Dimension(MENU_MAX_W, d.height);
            return d;
        }
    }

    /** 命中测试（基因组坐标，退化用） */
    public static Primer hitTest(int bp, String chr) {
        for (Primer p : PrimerStore.getPrimers()) {
            if (chr.equals(p.chr) && bp >= p.start && bp < p.end) return p;
        }
        return null;
    }

    /** v8 行感知命中测试（屏幕坐标，两轮：先精确引物条 ±3px，再含延长区 ±16px；后画优先=上层优先） */
    public static Primer hitTest(int x, int y, String chr) {
        if (chr == null) return null;
        List<Primer> ps = PrimerStore.getPrimers();
        for (int round = 0; round < 2; round++) {
            int yLo = round == 0 ? -3 : -6;
            int yHi = round == 0 ? 3 : 16;
            for (int i = ps.size() - 1; i >= 0; i--) {
                Primer p = ps.get(i);
                if (!chr.equals(p.chr)) continue;
                Rectangle r = PrimerStore.screenRects.get(p);
                if (r == null) continue;
                if (y >= r.y + yLo && y <= r.y + r.height + yHi
                        && x >= r.x - 6 && x <= r.x + r.width + 6) {
                    return p;
                }
            }
        }
        return null;
    }
}

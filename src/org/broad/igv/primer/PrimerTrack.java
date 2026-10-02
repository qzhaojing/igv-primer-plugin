package org.broad.igv.primer;

import org.broad.igv.track.AbstractTrack;
import org.broad.igv.track.RenderContext;
import org.broad.igv.track.TrackClickEvent;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.IGVPopupMenu;
import org.broad.igv.ui.panel.TrackPanel;

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
 *
 * v0.1.36 多轨：每条 PrimerTrack 绑定一个来源分组（group = 导入的 BED 文件名，null=手动添加轨），
 * 仅渲染该分组的引物；各轨独立增删改/导出/关闭。screenRects/screenRows/工具条状态均为实例级，避免多轨互相覆盖。
 */
public class PrimerTrack extends AbstractTrack {

    /** 所有存活的引物轨（供多轨刷新/命中聚合/守护使用） */
    public static final java.util.List<PrimerTrack> ALL = new java.util.ArrayList<PrimerTrack>();

    /** 本轨绑定的来源分组（BED 文件名）；null 表示手动添加轨 */
    private String group;

    /** 本轨渲染时记录每条引物的屏幕矩形/行号（实例级，多轨互不覆盖） */
    public final java.util.Map<Primer, Rectangle> screenRects = new java.util.HashMap<Primer, Rectangle>();
    public final java.util.Map<Primer, Integer> screenRows = new java.util.HashMap<Primer, Integer>();

    public PrimerTrack() { this(null); }

    public PrimerTrack(String group) {
        super("primer_designer");
        // v0.1.33：轨名必须用纯 ASCII——AbstractTrack.name 被 JAXB 序列化进 session XML，
        // 中文名在 Windows 上会被写成非 UTF-8 字节，open session 时解析报 MalformedByteSequenceException 并丢失本轨。
        // 来源文件名（可能含中文）只画在轨内 caption，不写进轨名。
        this.group = group;
        setName("Primers");
        setColor(new Color(0, 128, 0));
        setHeight(60);
        ALL.add(this);
    }

    /** 把本轨重新绑定到一个来源分组（首次导入认领空轨时使用） */
    void bindGroup(String g) { this.group = g; }

    /** 本轨是否承载该引物（按分组过滤） */
    private boolean inGroup(Primer p) {
        return group == null ? (p.group == null) : group.equals(p.group);
    }

    /** 本轨来源分组（caption 显示用） */
    public String groupLabel() {
        return group == null ? "手动添加" : group;
    }

    /** 本轨绑定的分组（null=手动添加轨）；供 PrimerPlugin 注册表查询 */
    public String getGroup() { return group; }

    public void update() {
        // 通知面板高度等变化（简单起见高度固定）
    }

    @Override
    public void render(RenderContext ctx, Rectangle rect) {
        Graphics2D g = ctx.getGraphics();
        String chr = ctx.getChr();
        if (chr == null) return;
        // 默认不进入编辑模式（防止误触修改引物）；用户点工具栏/右键「进入引物编辑模式」才激活
        PrimerStore.lastChr = chr;
        // 当前染色体可见引物（仅本轨分组），按起点排序后做行布局（重叠/不同分组 → 上下分行）
        List<Primer> vis = new java.util.ArrayList<Primer>();
        for (Primer p : PrimerStore.getPrimers()) if (chr.equals(p.chr) && inGroup(p)) vis.add(p);
        java.util.Collections.sort(vis, new java.util.Comparator<Primer>() {
            public int compare(Primer a, Primer b) { return a.start - b.start; }
        });
        this.screenRects.clear();   // 重新记录当前染色体可见引物的屏幕矩形（实例级，互不覆盖）
        this.screenRows.clear();
        java.util.Map<Primer, Integer> rows = layoutRows(vis);
        int nRows = 0;
        for (Integer r : rows.values()) nRows = Math.max(nRows, r + 1);
        // v0.1.29 卡死根因修复：render 的高度公式必须与 computeNeededHeight() 完全一致（含 TOOLBAR_H），
        // 且允许收缩（!= 而非 <）。此前 render 漏算 TOOLBAR_H 且只增不减，导致 refresh() 中
        // newH 恒 = oldH + 28 ≠ oldH → 每次点击/按键都触发全量 doRefresh()（重绘 14 轨 + 重载数据）→ 严重卡死。
        int needH = Math.max(60, TOOLBAR_H + nRows * ROW_H + 14);
        if (getHeight() != needH) setHeight(needH);

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
                // v0.1.26：长度标签置于曲线中点(t=0.5)下方 5px——多条连线时各中点沿曲线天然错开，不拥挤
                String lenTxt = formatLen(len);
                java.awt.FontMetrics fm = g.getFontMetrics(g.getFont());
                double mx2 = 0.25 * x1 + 0.5 * cx + 0.25 * x2;
                double my2 = 0.25 * ya + 0.5 * cy + 0.25 * yb;
                g.drawString(lenTxt, (int) (mx2 - fm.stringWidth(lenTxt) / 2),
                        (int) (my2) + fm.getAscent() + 5);
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
                    // v0.1.26：同 size==2，标签置于曲线中点(t=0.5)下方 5px，多条 spoke 天然错开
                    java.awt.FontMetrics fmS = g.getFontMetrics(g.getFont());
                    double mx = 0.25 * xS + 0.5 * cx + 0.25 * xHub;
                    double my = 0.25 * yS + 0.5 * cy + 0.25 * yHub;
                    g.drawString(slen, (int) (mx - fmS.stringWidth(slen) / 2),
                            (int) (my) + fmS.getAscent() + 5);
                    // 箭头指向 hub 侧（曲线终点附近）
                    drawDirArrow(g, xHub + (xS < xHub ? -6 : 6), yHub + (yS < yHub ? -4 : 4),
                            xS < xHub ? -1 : 1, c);
                    // v0.1.31：去掉曲线端点/汇聚处的 "P1"、"hub×N" 小字标注——画面更干净
                    idx++;
                }
                g.setColor(new Color(60, 60, 60));
                g.fillOval(xHub - 2, yHub - 2, 4, 4);
            }
        }

        // 2) 每条引物（按各自行 y 渲染）
        for (Primer p : vis) {
            drawPrimer(ctx, g, rect, p, rows.get(p));
        }

        // 3) 顶部参数工具条（常驻按钮栏 + 两个圆角参数框）：等价于右键部分常用项，且不受编辑模式开关影响
        Rectangle bar = new Rectangle(rect.x, rect.y, rect.width, TOOLBAR_H);
        toolbarRect = bar;
        toolbarChr = chr;
        toolbarBtns.clear();
        toolbarBoxes.clear();
        toolbarFields.clear();
        // 安装全局键盘分发器：仅当某文本框聚焦时拦截按键用于编辑（首次渲染安装一次）
        if (!kbdInstalled) {
            try {
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(primerKbd);
                kbdInstalled = true;
            } catch (Throwable ignore) { }
        }
        java.util.List<ToolbarBtn> btns = toolbarLayout(bar);   // 同时写入 toolbarBtns 映射与 toolbarBtnsEndX
        layoutBoxes(bar);                                      // 计算两个圆角参数框（依赖 toolbarBtnsEndX）
        g.setColor(new Color(238, 238, 238));
        g.fillRect(bar.x, bar.y, bar.width, bar.height);
        g.setColor(new Color(180, 180, 180));
        g.drawLine(bar.x, bar.y + bar.height - 1, bar.x + bar.width, bar.y + bar.height - 1);
        g.setFont(g.getFont().deriveFont(10f));
        for (ToolbarBtn b : btns) {
            toolbarBtns.put(b.id, b.rect);
            g.setColor(b.on ? new Color(200, 235, 200) : new Color(248, 248, 248));
            g.fillRect(b.rect.x, b.rect.y, b.rect.width, b.rect.height);
            g.setColor(b.on ? new Color(60, 140, 60) : new Color(120, 120, 120));
            g.drawRect(b.rect.x, b.rect.y, b.rect.width, b.rect.height);
            g.setColor(Color.DARK_GRAY);
            int tw = g.getFontMetrics(g.getFont()).stringWidth(b.label);
            g.drawString(b.label, b.rect.x + (b.rect.width - tw) / 2, b.rect.y + b.rect.height - 4);
        }
        drawToolbarBoxes(g);   // 绘制两个圆角参数框（含文本输入框与设置按钮）
        // v0.1.36：工具条右侧画本轨来源 caption（分组名/手动添加），用于区分多条独立引物轨
        g.setFont(g.getFont().deriveFont(10f));
        String cap = groupLabel();
        int capW = g.getFontMetrics(g.getFont()).stringWidth(cap);
        g.setColor(new Color(90, 90, 90));
        g.drawString(cap, bar.x + bar.width - capW - 8, bar.y + bar.height - 9);
    }

    /** 计算工具条（左侧 5 个动作按钮）布局；同时把按钮矩形写入 toolbarBtns 映射并在此记录按钮区结束 x（供参数框定位）。 */
    private java.util.List<ToolbarBtn> toolbarLayout(Rectangle bar) {
        java.util.List<ToolbarBtn> out = new java.util.ArrayList<ToolbarBtn>();
        int x = bar.x + 4;
        int y = bar.y + 4;
        int h = TOOLBAR_H - 8;
        java.util.Map<String, String> defs = new java.util.LinkedHashMap<String, String>();
        boolean inEdit = PrimerEditTool.inEditMode();
        defs.put("edit", inEdit ? "退出编辑" : "进入编辑");
        defs.put("seq", "序列:" + (PrimerStore.showSeq ? "开" : "关"));
        defs.put("auto", "自动存:" + (PrimerStore.autosaveEnabled ? "开" : "关"));
        defs.put("exp", "导出BED");
        defs.put("imp", "导入BED");
        defs.put("close", "关闭");
        for (java.util.Map.Entry<String, String> e : defs.entrySet()) {
            String id = e.getKey();
            String label = e.getValue();
            boolean on = id.equals("edit") ? inEdit
                    : id.equals("seq") ? PrimerStore.showSeq
                    : id.equals("auto") ? PrimerStore.autosaveEnabled : false;
            int w = 14 + label.length() * 7;
            out.add(new ToolbarBtn(id, label, on, new Rectangle(x, y, w, h)));
            x += w + 4;
        }
        toolbarBtnsEndX = x;
        return out;
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

    /** v0.1.19：track 顶部参数工具条高度（一条常驻按钮栏，等价于右键部分常用项，且不受编辑模式开关影响） */
    public static final int TOOLBAR_H = 28;
    private static final int FLD_W = 34;     // 文本框宽度（容纳 3-4 位数字）
    private static final int FLD_H = 18;     // 文本框高度
    private static final int SET_W = 38;     // “设置”按钮宽度
    private Rectangle toolbarRect = null;          // 工具条背景矩形（屏幕坐标，随渲染更新，实例级）
    private String toolbarChr = null;             // 该矩形对应的染色体
    private final java.util.Map<String, Rectangle> toolbarBtns =
            new java.util.HashMap<String, Rectangle>();   // 按钮 id -> 屏幕矩形

    /** 工具条按钮（内部记录 id/文案/是否激活/矩形） */
    private static class ToolbarBtn {
        String id, label;
        boolean on;
        Rectangle rect;
        ToolbarBtn(String id, String label, boolean on, Rectangle rect) {
            this.id = id; this.label = label; this.on = on; this.rect = rect;
        }
    }

    // ---- v0.1.21：顶部两个圆角参数框（文本框 + 设置按钮） ----
    /** 圆角框内单个文本框（带左侧标签） */
    private static class TBField {
        String id, label;
        Rectangle rect;
        TBField(String id, String label, Rectangle rect) {
            this.id = id; this.label = label; this.rect = rect;
        }
    }
    /** 一个圆角参数框：含若干文本框 + 一个“设置”按钮 */
    private static class TBBox {
        Rectangle rect;            // 圆角框整体矩形
        java.util.List<TBField> fields;   // 框内文本框（含标签）
        Rectangle setBtn;          // “设置”按钮矩形
        String setAction;          // runToolbar 动作 id
        TBBox(Rectangle rect, java.util.List<TBField> fields, Rectangle setBtn, String setAction) {
            this.rect = rect; this.fields = fields; this.setBtn = setBtn; this.setAction = setAction;
        }
    }
    private final java.util.List<TBBox> toolbarBoxes = new java.util.ArrayList<TBBox>();
    private final java.util.Map<String, Rectangle> toolbarFields = new java.util.HashMap<String, Rectangle>();
    private static final java.util.Map<String, String> fieldValues = new java.util.LinkedHashMap<String, String>();
    private static boolean fieldValuesInit = false;
    private static String focusedFieldId = null;
    private int toolbarBtnsEndX = 0;
    private static boolean kbdInstalled = false;

    /** 全局键盘分发器：仅当某个文本框聚焦时拦截按键用于编辑；其余情况放行（return false）。 */
    private static final java.awt.KeyEventDispatcher primerKbd = new java.awt.KeyEventDispatcher() {
        public boolean dispatchKeyEvent(java.awt.event.KeyEvent e) {
            if (focusedFieldId == null) return false;
            int id = e.getID();
            if (id == java.awt.event.KeyEvent.KEY_PRESSED) {
                int code = e.getKeyCode();
                if (code == java.awt.event.KeyEvent.VK_ESCAPE) { focusedFieldId = null; PrimerStore.refresh(); return true; }
                if (code == java.awt.event.KeyEvent.VK_ENTER) { focusedFieldId = null; PrimerStore.refresh(); return true; }
                if (code == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    String v = fieldValues.get(focusedFieldId);
                    if (v != null && v.length() > 0) fieldValues.put(focusedFieldId, v.substring(0, v.length() - 1));
                    PrimerStore.refresh();
                    return true;
                }
                return true;   // 聚焦期间吞掉其余按键（避免误触发 IGV 快捷键）
            }
            if (id == java.awt.event.KeyEvent.KEY_TYPED) {
                char c = e.getKeyChar();
                if (c == '\b') return true;
                if (Character.isDigit(c) || c == '.' || c == '-') {
                    String v = fieldValues.get(focusedFieldId);
                    if (v == null) v = "";
                    boolean ok = v.length() < 7 && !(c == '.' && v.contains(".")) && !(c == '-' && v.length() > 0);
                    if (ok) fieldValues.put(focusedFieldId, v + c);
                    PrimerStore.refresh();
                    return true;
                }
                return true;   // 吞掉非数字字符
            }
            return false;
        }
    };

    /** 首次渲染时把文本框初值设为当前 PrimerStore 默认/阈值 */
    private static void initFieldValues() {
        fieldValues.put("r1", String.valueOf(PrimerStore.defaultReadF));
        fieldValues.put("r2", String.valueOf(PrimerStore.defaultReadR));
        fieldValues.put("gcMin", String.valueOf(PrimerStore.failGcMin));
        fieldValues.put("gcMax", String.valueOf(PrimerStore.failGcMax));
        fieldValues.put("tmMin", String.valueOf(PrimerStore.failTmMin));
        fieldValues.put("tmMax", String.valueOf(PrimerStore.failTmMax));
        fieldValues.put("lenMin", String.valueOf(PrimerStore.failLenMin));
        fieldValues.put("lenMax", String.valueOf(PrimerStore.failLenMax));
    }

    /** 计算两个圆角参数框的布局（矩形），写入 toolbarBoxes 与 toolbarFields；依赖 toolbarBtnsEndX。 */
    private void layoutBoxes(Rectangle bar) {
        if (!fieldValuesInit) { initFieldValues(); fieldValuesInit = true; }
        int baseY = bar.y + (bar.height - FLD_H) / 2;
        // ---- 框1：测序长度（R1 / R2 + 设置） ----
        int x = toolbarBtnsEndX + 8;
        int pad = 6;
        int ix = x + pad;
        int lblW = 14;
        Rectangle r1 = new Rectangle(ix + lblW + 2, baseY, FLD_W, FLD_H);
        Rectangle r2 = new Rectangle(r1.x + FLD_W + 4 + lblW + 2, baseY, FLD_W, FLD_H);
        Rectangle s1 = new Rectangle(r2.x + FLD_W + 6, baseY, SET_W, FLD_H);
        int box1W = (s1.x + SET_W + pad) - x;
        java.util.List<TBField> f1 = new java.util.ArrayList<TBField>();
        f1.add(new TBField("r1", "R1", r1));
        f1.add(new TBField("r2", "R2", r2));
        toolbarBoxes.add(new TBBox(new Rectangle(x, bar.y + 2, box1W, bar.height - 4), f1, s1, "setRead"));
        // ---- 框2：失败阈值（GC / Tm / 长度，各 下限+上限 + 设置） ----
        int x2 = x + box1W + 8;
        int ix2 = x2 + pad;
        int lblW2 = 16;
        Rectangle gcMin = new Rectangle(ix2 + lblW2 + 2, baseY, FLD_W, FLD_H);
        Rectangle gcMax = new Rectangle(gcMin.x + FLD_W + 2, baseY, FLD_W, FLD_H);
        Rectangle tmMin = new Rectangle(gcMax.x + FLD_W + 4 + lblW2 + 2, baseY, FLD_W, FLD_H);
        Rectangle tmMax = new Rectangle(tmMin.x + FLD_W + 2, baseY, FLD_W, FLD_H);
        Rectangle lenMin = new Rectangle(tmMax.x + FLD_W + 4 + 14 + 2, baseY, FLD_W, FLD_H);
        Rectangle lenMax = new Rectangle(lenMin.x + FLD_W + 2, baseY, FLD_W, FLD_H);
        Rectangle s2 = new Rectangle(lenMax.x + FLD_W + 6, baseY, SET_W, FLD_H);
        int box2W = (s2.x + SET_W + pad) - x2;
        java.util.List<TBField> f2 = new java.util.ArrayList<TBField>();
        f2.add(new TBField("gcMin", "GC", gcMin));
        f2.add(new TBField("gcMax", "", gcMax));
        f2.add(new TBField("tmMin", "Tm", tmMin));
        f2.add(new TBField("tmMax", "", tmMax));
        f2.add(new TBField("lenMin", "长", lenMin));
        f2.add(new TBField("lenMax", "", lenMax));
        toolbarBoxes.add(new TBBox(new Rectangle(x2, bar.y + 2, box2W, bar.height - 4), f2, s2, "setFail"));
        for (TBBox b : toolbarBoxes) for (TBField f : b.fields) toolbarFields.put(f.id, f.rect);
    }

    /** 绘制两个圆角参数框（背景框 + 文本框 + 设置按钮） */
    private void drawToolbarBoxes(Graphics2D g) {
        for (TBBox box : toolbarBoxes) {
            g.setColor(new Color(232, 236, 244));
            g.fillRoundRect(box.rect.x, box.rect.y, box.rect.width, box.rect.height, 8, 8);
            g.setColor(new Color(150, 160, 185));
            g.drawRoundRect(box.rect.x, box.rect.y, box.rect.width, box.rect.height, 8, 8);
            for (TBField f : box.fields) {
                if (f.label != null && !f.label.isEmpty()) {
                    g.setColor(Color.DARK_GRAY);
                    g.setFont(g.getFont().deriveFont(9f));
                    int lw = g.getFontMetrics(g.getFont()).stringWidth(f.label);
                    g.drawString(f.label, f.rect.x - lw - 2, f.rect.y + f.rect.height - 4);
                }
                g.setColor(Color.WHITE);
                g.fillRect(f.rect.x, f.rect.y, f.rect.width, f.rect.height);
                g.setColor(focusedFieldId != null && focusedFieldId.equals(f.id)
                        ? new Color(110, 150, 255) : new Color(170, 170, 170));
                g.drawRect(f.rect.x, f.rect.y, f.rect.width, f.rect.height);
                g.setColor(Color.BLACK);
                g.setFont(g.getFont().deriveFont(11f));
                String v = fieldValues.get(f.id); if (v == null) v = "";
                g.drawString(v, f.rect.x + 3, f.rect.y + f.rect.height - 4);
                if (focusedFieldId != null && focusedFieldId.equals(f.id)) {
                    int cw = g.getFontMetrics(g.getFont()).stringWidth(v);
                    g.drawLine(f.rect.x + 3 + cw + 1, f.rect.y + 2, f.rect.x + 3 + cw + 1, f.rect.y + f.rect.height - 3);
                }
                g.setFont(g.getFont().deriveFont(10f));
            }
            Rectangle sb = box.setBtn;
            g.setColor(new Color(200, 220, 255));
            g.fillRect(sb.x, sb.y, sb.width, sb.height);
            g.setColor(new Color(60, 100, 180));
            g.drawRect(sb.x, sb.y, sb.width, sb.height);
            g.setColor(Color.DARK_GRAY);
            g.setFont(g.getFont().deriveFont(10f));
            String t = "设置";
            int tw = g.getFontMetrics(g.getFont()).stringWidth(t);
            g.drawString(t, sb.x + (sb.width - tw) / 2, sb.y + sb.height - 4);
        }
    }

    private static int parseIntField(String id, int dflt) {
        String v = fieldValues.get(id);
        if (v == null || v.trim().isEmpty()) return dflt;
        return Integer.parseInt(v.trim());
    }
    private static double parseDblField(String id, double dflt) {
        String v = fieldValues.get(id);
        if (v == null || v.trim().isEmpty()) return dflt;
        return Double.parseDouble(v.trim());
    }

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
        return rect.y + TOOLBAR_H + 4 + Math.max(0, row) * ROW_H;
    }

    /**
     * v0.1.17：不绘制、仅按当前染色体计算引物轨所需高度（行布局与 render 一致），
     * 供 PrimerStore.refresh() 判断"是否需要全量刷新"——仅当行数（高度）变化时才有必要调用昂贵的 doRefresh()。
     */
    public int computeNeededHeight(String chr) {
        if (chr == null) return 60;
        java.util.List<Primer> vis = new java.util.ArrayList<Primer>();
        for (Primer p : PrimerStore.getPrimers()) if (chr.equals(p.chr) && inGroup(p)) vis.add(p);
        java.util.Collections.sort(vis, new java.util.Comparator<Primer>() {
            public int compare(Primer a, Primer b) { return a.start - b.start; }
        });
        java.util.Map<Primer, Integer> rows = layoutRows(vis);
        int nRows = 0;
        for (Integer r : rows.values()) nRows = Math.max(nRows, r + 1);
        return Math.max(60, TOOLBAR_H + nRows * ROW_H + 14);
    }

    // ---------- v0.1.36 多轨刷新 / 命中聚合（供 PrimerStore.refresh 与编辑工具跨轨使用） ----------

    /** 逐轨重算高度：任一轨高度变化 → 全量 doRefresh；否则仅各自重绘所在面板（多轨互不拖累）。 */
    static void refreshAllTracks(String chr) {
        if (ALL.isEmpty()) {
            runOnEDT(new Runnable() {
                public void run() { IGV.getInstance().doRefresh(); }
            });
            return;
        }
        boolean heightChanged = false;
        for (PrimerTrack t : ALL) {
            int oldH = t.getHeight();
            int newH = t.computeNeededHeight(chr);
            t.update();
            if (newH != oldH) heightChanged = true;
        }
        if (heightChanged) {
            runOnEDT(new Runnable() {
                public void run() { IGV.getInstance().doRefresh(); }
            });
        } else {
            runOnEDT(new Runnable() {
                public void run() { for (PrimerTrack t : ALL) repaintTrackOnly(t); }
            });
        }
    }

    private static void runOnEDT(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private static void repaintTrackOnly(PrimerTrack t) {
        try {
            TrackPanel tp = TrackPanel.getParentPanel(t);
            if (tp != null) { tp.repaint(); return; }
        } catch (Throwable ignore) { }
        IGV.getInstance().repaintDataPanels();
    }

    /** 聚合所有轨的屏幕矩形（编辑工具命中检测用） */
    static java.util.Map<Primer, Rectangle> allScreenRects() {
        java.util.Map<Primer, Rectangle> m = new java.util.HashMap<Primer, Rectangle>();
        for (PrimerTrack t : ALL) m.putAll(t.screenRects);
        return m;
    }

    /** 聚合所有轨的屏幕行号 */
    static java.util.Map<Primer, Integer> allScreenRows() {
        java.util.Map<Primer, Integer> m = new java.util.HashMap<Primer, Integer>();
        for (PrimerTrack t : ALL) m.putAll(t.screenRows);
        return m;
    }

    /** 跨所有轨命中测试（屏幕坐标） */
    static Primer hitTestAny(int x, int y, String chr) {
        for (PrimerTrack t : ALL) {
            Primer p = t.hitTest(x, y, chr);
            if (p != null) return p;
        }
        return null;
    }

    /** 跨所有轨命中测试（基因组坐标，退化用） */
    static Primer hitTestAny(int bp, String chr) {
        for (PrimerTrack t : ALL) {
            Primer p = t.hitTest(bp, chr);
            if (p != null) return p;
        }
        return null;
    }

    /** 跨所有轨判断坐标是否落在某条轨的工具条内 */
    static boolean isInToolbarAny(int x, int y, String chr) {
        for (PrimerTrack t : ALL) if (t.isInToolbar(x, y, chr)) return true;
        return false;
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
            // v0.1.31：fail 轮廓改细+浅红，不再喧宾夺主
            g.setColor(new Color(0xE8, 0x9A, 0x9A));   // 浅红
            g.setStroke(new BasicStroke(1f));
            g.draw(arrow);
            g.setStroke(new BasicStroke(1f));
        }
        // 选中高亮橙框（与失败红描边共存不冲突）
        if (PrimerStore.selected == p) {
            g.setColor(Color.ORANGE);
            g.drawRect(x0 - 2, y - 3, w + 4, h + 8);
        }

        // v0.1.31：标签只显示引物名——Tm/GC/测序长度等详细信息移入悬浮提示框（getValueStringAt），保持画面清爽
        // v0.1.31：名称统一冷静的深蓝灰（fail 状态由浅红轮廓+悬停框表达，名称不再用红色）
        g.setColor(new Color(0x2E, 0x4A, 0x62));
        g.setFont(g.getFont().deriveFont(11f));
        String label = p.name;
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

        // 记录屏幕矩形（含 y）与行号，供编辑工具命中检测 / 上下拖动换行（本轨实例级）
        this.screenRects.put(p, new Rectangle(x0, y, w, h));
        this.screenRows.put(p, Math.max(0, row));
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

    /**
     * v0.1.31：鼠标悬停信息框（IGV tooltip 钩子，需开启 View → Show details on hover）。
     * 返回 HTML 片段，由 DataPanel.updateTooltipText 包上 <html> 拼接显示，<br> 换行。
     * 引物名称标签已精简为只显示名称，全部详细信息（Tm/GC/二聚体/序列/状态）集中在此悬浮框。
     */
    @Override
    public String getValueStringAt(String chr, double position, int mouseX, org.broad.igv.ui.panel.ReferenceFrame frame) {
        try {
            Primer p = hitTest((int) position, chr);
            if (p == null) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("<b>").append(p.name).append("</b>")
              .append("  ").append(p.strand == '+' ? "F (+)" : "R (−)")
              .append("  角色: ").append(p.role == null ? "-" : p.role);
            if (p.ampliconId != null) sb.append("  |  配对组: ").append(p.ampliconId);
            sb.append("<br>位置: ").append(p.chr).append(":").append(p.start + 1).append("-").append(p.end)
              .append("  (").append(p.length()).append(" bp)");
            sb.append("<br>Tm: ").append(fmtD(p.tm)).append(" ℃   GC: ").append(fmtD(p.gc)).append(" %");
            sb.append("<br>hairpin ΔG: ").append(fmtD(p.hairpinDG))
              .append("   self-dimer ΔG: ").append(fmtD(p.selfDG));
            sb.append("<br>异源二聚体 ΔG: ").append(fmtD(p.heteroDG))
              .append("   3' 互补: ").append(p.max3pComp).append(" bp");
            if (p.readLen > 0) sb.append("<br>测序: ").append(p.strand == '+' ? "R1+" : "R2-")
                    .append(p.readLen).append(" nt");
            if (p.group != null && !p.group.isEmpty()) sb.append("<br>来源: ").append(p.group);
            sb.append("<br>状态: ");
            if (p.pass) sb.append("<font color='green'>✔ 通过</font>");
            else {
                sb.append("<font color='red'>✗ ");
                if (p.failReasons != null && !p.failReasons.isEmpty()) sb.append(p.failReasons).append(" ");
                if (p.dimerReason != null && !p.dimerReason.isEmpty()) sb.append(p.dimerReason);
                sb.append("</font>");
            }
            if (p.seq != null && !p.seq.isEmpty()) {
                String s = p.strand == '+' ? p.seq : PrimerMetrics.revComp(p.seq);
                sb.append("<br>序列 5'→3': <font face='monospaced'>").append(s).append("</font>");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;   // 悬停信息绝不影响正常渲染
        }
    }

    /** 数值格式化：NaN 显示 "-"，否则保留 1 位小数 */
    private static String fmtD(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.1f", v);
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
                AddPrimerDialog.show(clickBp, hit, PrimerTrack.this.group);
            }
        }));

        // v0.1.19：进入/退出编辑模式合并为单一切换项（文案随当前状态变化）
        final boolean inEdit = PrimerEditTool.inEditMode();
        JMenuItem editMode = new JMenuItem(inEdit ? "退出引物编辑模式（拖拽/拖边）" : "进入引物编辑模式（拖拽/拖边）");
        editMode.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (PrimerEditTool.inEditMode()) PrimerEditTool.exitEditMode();
                else PrimerEditTool.enterEditMode();
            }
        });
        menu.add(editMode);

        if (hit != null) {
            menu.add(item("删除选中引物", new Runnable() {
                public void run() {
                    PrimerStore.remove(hit);
                }
            }));
            menu.add(item("评估二聚体（重算序列+全局）", new Runnable() {
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
            // v0.1.18：取消该引物配对——只断开与之相关的连线；同组其余成员间连线（>=2 条时）保留
            menu.add(item("取消该引物配对（仅断开相关连线）", new Runnable() {
                public void run() {
                    if (hit.ampliconId == null) {
                        JOptionPane.showMessageDialog(null, "该引物未参与任何配对。");
                        return;
                    }
                    PrimerStore.unpairForSelected(hit);
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
        menu.add(item("导出本轨 BED（方向+颜色+测序长度）", new Runnable() {
            public void run() {
                ExportUtils.exportBED(PrimerTrack.this.group);   // 仅导出本轨分组
            }
        }));
        menu.add(item("导出全部 BED", new Runnable() {
            public void run() {
                ExportUtils.exportBED(null);   // 全部引物
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
        menu.add(item("导入 BED（新建独立轨）", new Runnable() {
            public void run() {
                ExportUtils.importBED();
            }
        }));
        menu.add(item("关闭本轨（删除该来源引物）", new Runnable() {
            public void run() {
                int n = PrimerStore.countGroup(PrimerTrack.this.group);
                int r = JOptionPane.showConfirmDialog(null,
                        "确认关闭本引物轨「" + groupLabel() + "」？\n将删除其下 " + n + " 条引物（建议先「导出本轨 BED」备份）。",
                        "关闭引物轨", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION) {
                    PrimerStore.removeGroup(PrimerTrack.this.group);
                    PrimerPlugin.closeTrack(PrimerTrack.this);
                }
            }
        }));
        menu.addSeparator();
        menu.add(item("重算全部参数", new Runnable() {
            public void run() {
                PrimerStore.refreshAll();
            }
        }));
        menu.add(item("清空本轨引物", new Runnable() {
            public void run() {
                int n = PrimerStore.countGroup(PrimerTrack.this.group);
                if (n == 0) { JOptionPane.showMessageDialog(null, "本轨没有引物。"); return; }
                int r = JOptionPane.showConfirmDialog(null,
                        "确认删除本轨「" + groupLabel() + "」的全部 " + n + " 条引物？",
                        "清空本轨引物", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION) PrimerStore.removeGroup(PrimerTrack.this.group);
            }
        }));
        menu.add(item("清空全部轨道引物", new Runnable() {
            public void run() {
                int r = JOptionPane.showConfirmDialog(null,
                        "确认删除所有引物轨的全部引物？此操作不可撤销。",
                        "清空全部引物", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r == JOptionPane.YES_OPTION) PrimerStore.clear();
            }
        }));
        menu.addSeparator();
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
    public Primer hitTest(int bp, String chr) {
        for (Primer p : PrimerStore.getPrimers()) {
            if (chr.equals(p.chr) && inGroup(p) && bp >= p.start && bp < p.end) return p;
        }
        return null;
    }

    /** v8 行感知命中测试（屏幕坐标，两轮：先精确引物条 ±3px，再含延长区 ±16px；后画优先=上层优先） */
    public Primer hitTest(int x, int y, String chr) {
        if (chr == null) return null;
        List<Primer> ps = PrimerStore.getPrimers();
        for (int round = 0; round < 2; round++) {
            int yLo = round == 0 ? -3 : -6;
            int yHi = round == 0 ? 3 : 16;
            for (int i = ps.size() - 1; i >= 0; i--) {
                Primer p = ps.get(i);
                if (!chr.equals(p.chr) || !inGroup(p)) continue;
                Rectangle r = this.screenRects.get(p);
                if (r == null) continue;
                if (y >= r.y + yLo && y <= r.y + r.height + yHi
                        && x >= r.x - 6 && x <= r.x + r.width + 6) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * v0.1.19：顶部工具条左键点击处理（IGV 会把 track 数据区左键点击派发到此，与编辑模式无关）。
     * 命中某个按钮则执行对应动作并返回 true；否则交给 IGV 默认行为。
     */
    @Override
    public boolean handleDataClick(org.broad.igv.track.TrackClickEvent e) {
        if (toolbarRect == null || !e.getFrame().getChrName().equals(toolbarChr)) return false;
        java.awt.event.MouseEvent me = e.getMouseEvent();
        int x = me.getX(), y = me.getY();
        // 1) 圆角框内文本框：点击聚焦，进入键盘输入模式
        for (TBBox box : toolbarBoxes) {
            for (TBField f : box.fields) {
                if (f.rect.contains(x, y)) {
                    focusedFieldId = f.id;
                    PrimerStore.refresh();
                    return true;
                }
            }
            // 2) 圆角框“设置”按钮
            if (box.setBtn.contains(x, y)) {
                runToolbar(box.setAction);
                focusedFieldId = null;
                return true;
            }
        }
        // 3) 左侧动作按钮
        for (java.util.Map.Entry<String, Rectangle> en : toolbarBtns.entrySet()) {
            if (en.getValue().contains(x, y)) {
                focusedFieldId = null;
                runToolbar(en.getKey());
                return true;
            }
        }
        // 4) 点工具条空白处：取消文本框聚焦（不再误吞按键），并消费该点击（不触发平移）
        if (toolbarRect.contains(x, y)) {
            focusedFieldId = null;
            PrimerStore.refresh();
            return true;
        }
        focusedFieldId = null;   // 点轨道其它区域也取消聚焦
        return false;
    }

    /** 工具条按钮动作（与右键菜单对应项一致） */
    private void runToolbar(String id) {
        if ("edit".equals(id)) {
            if (PrimerEditTool.inEditMode()) PrimerEditTool.exitEditMode();
            else PrimerEditTool.enterEditMode();
        } else if ("seq".equals(id)) {
            PrimerStore.showSeq = !PrimerStore.showSeq;
            PrimerStore.refresh();
        } else if ("auto".equals(id)) {
            PrimerStore.setAutosave(!PrimerStore.autosaveEnabled);
            PrimerStore.refresh();
        } else if ("exp".equals(id)) {
            ExportUtils.exportBED(this.group);   // 仅导出本轨分组的引物
        } else if ("imp".equals(id)) {
            ExportUtils.importBED();
        } else if ("close".equals(id)) {
            // v0.1.36：关闭本轨 = 删除该分组全部引物 + 移除轨（带确认）
            int n = PrimerStore.countGroup(this.group);
            int r = JOptionPane.showConfirmDialog(null,
                    "确认关闭本引物轨「" + groupLabel() + "」？\n将同时删除其下 " + n + " 条引物（建议先点「导出BED」备份）。\n此操作不可撤销。",
                    "关闭引物轨", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                PrimerStore.removeGroup(this.group);
                PrimerPlugin.closeTrack(this);
            }
        } else if ("setRead".equals(id)) {
            try {
                int r1 = Math.max(0, parseIntField("r1", PrimerStore.defaultReadF));
                int r2 = Math.max(0, parseIntField("r2", PrimerStore.defaultReadR));
                PrimerStore.setReadLenByRole(r1, r2, PrimerStore.defaultIncludeLen);
                JOptionPane.showMessageDialog(null, "已设置全部引物测序长度：R1=" + r1 + " nt, R2=" + r2
                        + " nt（含引物长度=" + PrimerStore.defaultIncludeLen + "），并写入默认配置。");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null, "测序长度输入格式错误：" + ex.getMessage(),
                        "格式错误", JOptionPane.ERROR_MESSAGE);
            }
        } else if ("setFail".equals(id)) {
            try {
                PrimerStore.failGcMin = parseDblField("gcMin", PrimerStore.failGcMin);
                PrimerStore.failGcMax = parseDblField("gcMax", PrimerStore.failGcMax);
                PrimerStore.failTmMin = parseDblField("tmMin", PrimerStore.failTmMin);
                PrimerStore.failTmMax = parseDblField("tmMax", PrimerStore.failTmMax);
                PrimerStore.failLenMin = (int) parseDblField("lenMin", PrimerStore.failLenMin);
                PrimerStore.failLenMax = (int) parseDblField("lenMax", PrimerStore.failLenMax);
                PrimerStore.saveFailConfig();
                PrimerStore.refreshAll();
                JOptionPane.showMessageDialog(null, "已更新失败判定阈值（GC/Tm/长度 的上下限）并重新评估全部引物。");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null, "阈值输入格式错误：" + ex.getMessage(),
                        "格式错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** 屏幕坐标 (x,y) 是否落在当前染色体的工具条区域内（供编辑工具在工具条上做点击时跳过平移/拖拽） */
    public boolean isInToolbar(int x, int y, String chr) {
        if (toolbarRect == null || !chr.equals(toolbarChr)) return false;
        return toolbarRect.contains(x, y);
    }
}

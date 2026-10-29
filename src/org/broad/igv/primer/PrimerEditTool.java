package org.broad.igv.primer;

import org.broad.igv.ui.AbstractDataPanelTool;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.DataPanel;
import org.broad.igv.ui.panel.PanTool;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.ui.panel.TrackPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 引物编辑工具 v2：
 *  - 悬停引物左右边缘 ±6px = 双向箭头光标，可拖动改长度
 *  - 悬停/按住引物本体 = 移动光标，固定长度左右平移
 *  - 空白处按住拖动 = 平移视图 + 记录掠过的区间为鼠标选区（松手保留；右键「primer3 设计*」
 *    自动填充产物区间+上下游区，v0.1.61）；单击（位移<5bp）清除选区
 *  - v0.1.63：Shift+空白拖拽 = 纯选区（视图不动，只画红线；松手落定）——解决
 *    "按住 shift 选区域视图跟着滚走"；currentTool 独占事件分发，不进 panning 即不平移
 *  - v0.1.65：Shift+拖动**无需进入编辑模式**——普通模式下 Shift+左键按下即把
 *    DataPanel.currentTool 换成 ShiftTool（实测 PanTool.mousePressed 零视觉副作用），
 *    后续 dragged/released 全走 ShiftTool 只画选区不动视图，松手还原原 tool；
 *    选区显示为淡色半透明区域（RulerGlass 全窗 + overlay 轨内兜底）
 *  - 拖拽参考线/选区可视化（起止坐标）
 *  - 单击 = 选中（出现橙色端点手柄）
 *  - 坐标换算统一走 frame.getChromosomePosition()（IGV 权威，避免 scale 单位踩坑）
 */
public class PrimerEditTool extends AbstractDataPanelTool {

    private static final int EDGE_PX = 6;

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE_L = 2;
    private static final int MODE_RESIZE_R = 3;

    private static PrimerEditTool instance;
    private static RulerGlass rulerGlass;   // 全窗拖拽 ruler 叠加层（glass pane）
    private static DataPanel activePanel;  // 当前拖拽所在 DataPanel

    private int mode = MODE_NONE;
    private boolean panning = false;
    private boolean selecting = false;   // v0.1.63：Shift+拖拽纯选区（不平移）
    private static boolean shiftOn = false;   // v0.1.65：Shift 选区可视化生效中（非编辑模式也画）
    private static boolean selBox = false;    // v0.1.70：选中引物框可视化生效中（点击即显示）
    private static long selBoxKey = 0;       // v0.1.70：上次同步的位置指纹（变化检测用）
    private Primer dragging;
    private int dragAnchorBp;
    private int origStart, origEnd;
    private int pressY;      // 按下时屏幕 y（上下拖动换行用）
    private int origRow;     // 按下时引物所在行

    private final Map<DataPanel, PanTool> panTools = new HashMap<DataPanel, PanTool>();
    private final Map<DataPanel, MouseMotionAdapter> hoverListeners = new HashMap<DataPanel, MouseMotionAdapter>();

    private PrimerEditTool(DataPanel panel) {
        super(panel, Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setName("Primer Edit");
    }

    // ---------- 编辑模式进出 ----------

    public static synchronized void enterEditMode() {
        List<DataPanel> panels = findDataPanels();
        if (panels.isEmpty()) {
            JOptionPane.showMessageDialog(IGV.getMainFrame(), "未找到数据面板（请先加载数据）");
            return;
        }
        setupGlass();
        if (instance == null) {
            instance = new PrimerEditTool(panels.get(0));
        }
        for (DataPanel dp : panels) {
            dp.setCurrentTool(instance);
            instance.installHover(dp);
        }
        ensureShiftSelectors();   // v0.1.65：新建面板也补挂 Shift 选区
        PrimerStore.refresh();
        installKeyboard();
    }

    public static synchronized void exitEditMode() {
        List<DataPanel> panels = findDataPanels();
        for (DataPanel dp : panels) {
            instance.removeHover(dp);
            dp.setCurrentTool(null); // null = 恢复 IGV 默认 PanTool
        }
        if (rulerGlass != null) {
            rulerGlass.active = false;
            rulerGlass.xs = null;
            rulerGlass.repaint();
        }
        shiftOn = false;      // v0.1.65：连 Shift 选区的可视化标记一起清
        clearGuides();
        PrimerStore.refresh();
        removeKeyboard();
        clearSelection();   // v0.1.61：选区随编辑模式一起消失，避免右键菜单显示已看不见的选区
        instance = null;   // v0.1.20：退出后必须置 null，否则 inEditMode() 永久为真，菜单/按钮卡在"退出编辑"
    }

    /** 安装全窗 glass pane（拖拽 ruler 叠加层）；非交互、事件穿透 */
    private static void setupGlass() {
        try {
            java.awt.Window w = IGV.getMainFrame();
            if (w instanceof JFrame) {
                JFrame f = (JFrame) w;
                if (!(f.getGlassPane() instanceof RulerGlass)) {
                    rulerGlass = new RulerGlass();
                    f.setGlassPane(rulerGlass);
                    rulerGlass.setVisible(true);
                    // v0.1.84：定时器必须在 glass 装好时就常驻启动——不能等 setPcrPreview。
                    //   否则纯 Shift 选区（不开弹窗）时定时器不跑，缩放/平移后选中高亮脱节。
                    startViewSyncTimer();
                    // v0.1.48：装完必须 validate 生效，否则 glass 不显示 → 只剩轨内局部红线
                    try { f.getRootPane().revalidate(); f.getRootPane().repaint(); } catch (Exception ignore2) { }
                }
            }
        } catch (Exception ignore) {
        }
    }

    public static boolean inEditMode() {
        return instance != null;
    }

    /** 遍历所有 TrackPanel -> DataPanelContainer 的子组件树，收集 DataPanel */
    private static List<DataPanel> findDataPanels() {
        List<DataPanel> out = new ArrayList<DataPanel>();
        List<TrackPanel> tps = IGV.getInstance().getMainPanel().getTrackPanels();
        for (TrackPanel tp : tps) {
            collect(tp.getDataPanelContainer(), out);
        }
        return out;
    }

    private static void collect(Component c, List<DataPanel> out) {
        if (c instanceof DataPanel) {
            out.add((DataPanel) c);
            return;
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) collect(k, out);
        }
    }

    // ---------- 悬停光标（DataPanel 不分发 mouseMoved 给 tool，需自挂监听） ----------

    private void installHover(final DataPanel dp) {
        if (hoverListeners.containsKey(dp)) return;
        MouseMotionAdapter ml = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                dp.setCursor(hoverCursor(dp, e));
            }
        };
        dp.addMouseMotionListener(ml);
        hoverListeners.put(dp, ml);
    }

    private void removeHover(DataPanel dp) {
        MouseMotionAdapter ml = hoverListeners.remove(dp);
        if (ml != null) dp.removeMouseMotionListener(ml);
    }

    private Cursor hoverCursor(DataPanel dp, MouseEvent e) {
        ReferenceFrame f = dp.getFrame();
        if (f == null || mode != MODE_NONE) return getCursor();
        String chr = f.getChrName();
        if (chr == null) return getCursor();
        Primer hit = findPrimerAt(e.getX(), e.getY(), chr);
        if (hit != null) {
            java.awt.Rectangle r = PrimerTrack.allScreenRects().get(hit);
            boolean edge = Math.abs(e.getX() - r.x) <= EDGE_PX
                    || Math.abs(e.getX() - (r.x + r.width)) <= EDGE_PX;
            return Cursor.getPredefinedCursor(edge ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR);
        }
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    // ---------- 拖拽参考线（供 PrimerTrack.overlay 读取） ----------

    private static String guideChr;
    private static int[] guides;

    // ---------- v0.1.57：鼠标选区（空白处拖拽=平移+画红线，松手后保留为选区） ----------
    // 右键「primer3 设计*」读它预填区域；单击（位移<5bp）视为取消选区。
    // v0.1.61：Shift+拖动同样有效（不检查修饰键，普通空白拖拽即选区）；退出编辑模式时清除。
    private static String selChr;
    private static int selStart, selEnd;      // 0-based，selStart <= selEnd
    private static int pressBp;               // 空白拖拽按下时的 bp（平移起点）
    private static String pressChr;

    /** 当前鼠标选区：{chr, start0, end0} 或 null（无选区/跨染色体）。0-based，end 含。 */
    public static String[] currentSelection() {
        if (selChr == null) return null;
        return new String[]{selChr, String.valueOf(selStart), String.valueOf(selEnd)};
    }

    private static void setSelection(String chr, int a, int b) {
        selChr = chr;
        selStart = Math.min(a, b);
        selEnd = Math.max(a, b);
    }

    private static void clearSelection() {
        selChr = null;
        shiftOn = false;   // v0.1.65：选区撤销时，Shift 参考线/淡色区域一并撤下
    }

    public static int[] guidePositions(String chr) {
        if ((!inEditMode() && !shiftOn) || guides == null) return null;
        return chr != null && chr.equals(guideChr) ? guides : null;
    }

    private static void setGuides(ReferenceFrame frame, int a, int b) {
        guideChr = frame.getChrName();
        guides = new int[]{a, b};
        updateRuler(activePanel, frame, a, b);
    }

    /** 在 glass pane 上画贯穿 DataPanel 全高的 ruler 线（对齐参考轨/其他 track） */
    private static void updateRuler(DataPanel dp, ReferenceFrame frame, int a, int b) {
        if (dp == null || frame == null) return;
        if (rulerGlass == null) setupGlass();
        if (rulerGlass == null) return;
        startViewSyncTimer();   // v0.1.84：兜底保证视图同步常驻（setupGlass 里也会启动）
        try {
            int x1 = (int) Math.round(frame.getScreenPosition(a));
            int x2 = (int) Math.round(frame.getScreenPosition(b));
            java.awt.Point p1 = SwingUtilities.convertPoint(dp, x1, 0, rulerGlass);
            java.awt.Point p2 = SwingUtilities.convertPoint(dp, x2, 0, rulerGlass);
            // v0.1.48：纵向范围 = 所有数据面板「可见区」的并集 → 红线像 ruler line 一样贯穿全部面板上下，
            // 而不是只画在本条引物轨里。用 getVisibleRect() 自动处理滚动/被滚出可视区的情况。
            int top = Integer.MAX_VALUE, bot = Integer.MIN_VALUE;
            boolean found = false;
            for (DataPanel cand : findDataPanels()) {
                java.awt.Rectangle vr = cand.getVisibleRect();
                if (vr.width <= 0 || vr.height <= 0) continue;
                java.awt.Point t = SwingUtilities.convertPoint(cand, vr.x, vr.y, rulerGlass);
                top = Math.min(top, t.y);
                bot = Math.max(bot, t.y + vr.height);
                found = true;
            }
            if (!found) {   // 退化：拿不到并集时至少覆盖当前面板
                java.awt.Point pT = SwingUtilities.convertPoint(dp, 0, 0, rulerGlass);
                java.awt.Point pB = SwingUtilities.convertPoint(dp, 0, dp.getHeight(), rulerGlass);
                top = pT.y; bot = pB.y;
            }
            // v0.1.70：选中引物框也走同一套淡色区间绘制（点击即显示）
            rulerGlass.active = inEditMode() || shiftOn || selBox;
            rulerGlass.xs = new int[]{p1.x, p2.x};
            rulerGlass.yTop = top;
            rulerGlass.yBot = bot;
            rulerGlass.guideBps = new int[]{a, b};
            rulerGlass.setVisible(true);
            rulerGlass.repaint();
        } catch (Exception ignore) {
        }
    }

    /** v0.1.48：全窗 ruler 是否正在生效（生效时 PrimerTrack 不再重复画局部红线，避免两条重叠） */
    public static boolean rulerActive() {
        return (inEditMode() || shiftOn || selBox) && rulerGlass != null && rulerGlass.active && rulerGlass.xs != null;
    }

    private static void clearGuides() {
        guides = null;
        selBox = false;   // v0.1.70：选中框标志一并清
        if (rulerGlass != null) {
            rulerGlass.active = false;
            rulerGlass.xs = null;
            rulerGlass.repaint();
        }
    }

    // ---------- v0.1.81：PCR 设计实时预览（对话框改产物区/设计区 → IGV 面板同步高亮） ----------
    // v0.1.82：像素坐标不再缓存（旧法在缩放/平移后高亮与 bp 脱节）——绘制时经
    //   computePcrPreviewCoords() 从 bp 按当前视图实时换算；另用 150ms 轻量 Swing Timer
    //   监视视图变化（缩放/平移/滚动/布局），坐标变了才重绘，空闲时一次比较即返回。
    private static String pcrPrevChr;
    // {prodS0, prodE0, ba0, ba1, bb0, bb1}（0-based：产物 [S0,E0)，左设计区 [ba0,ba1)，右设计区 [bb0,bb1)）
    private static int[] pcrPrevRegions;
    private static boolean pcrPreviewOn = false;
    private static javax.swing.Timer pcrViewSyncTimer;   // 视图同步节拍（常驻，tick 极轻）
    private static int[] lastPcrCoords;                  // 上次应用的坐标（变更检测，免无谓重绘）

    /** 预览是否生效（同一染色体才允许画）。 */
    public static boolean pcrPreviewActive() { return pcrPreviewOn && pcrPrevChr != null; }
    public static String pcrPreviewChr() { return pcrPrevChr; }
    /** {prodS0, prodE0, ba0, ba1, bb0, bb1}（0-based），未生效返回 null。 */
    public static int[] pcrPreviewRegions() { return pcrPreviewOn ? pcrPrevRegions : null; }

    /** 设置预览区域并刷新 IGV 面板（对话框字段变化时调用）。 */
    public static void setPcrPreview(String chr, int prodS0, int prodE0, int ba0, int ba1, int bb0, int bb1) {
        pcrPrevChr = chr;
        pcrPrevRegions = new int[]{prodS0, prodE0, ba0, ba1, bb0, bb1};
        pcrPreviewOn = true;
        startViewSyncTimer();
        refreshPcrPreview();
    }

    /** 清除预览（对话框关闭或字段无效时调用）。 */
    public static void clearPcrPreview() {
        pcrPreviewOn = false;
        pcrPrevChr = null;
        pcrPrevRegions = null;
        lastPcrCoords = null;
        if (rulerGlass != null) rulerGlass.repaint();   // 立即抹掉旧高亮
        repaintDataPanels();
    }

    /** 视图同步节拍（常驻 150ms，EDT）：空闲时仅做两次布尔判断即返回，开销可忽略。 */
    private static void startViewSyncTimer() {
        if (pcrViewSyncTimer == null) {
            pcrViewSyncTimer = new javax.swing.Timer(150, new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    syncOnViewChange();
                }
            });
            pcrViewSyncTimer.setRepeats(true);
        }
        pcrViewSyncTimer.start();
    }

    /** 缩放/平移/滚动/布局变化检测：坐标与上次不同才重绘（glass 高亮 + 轨内 overlay）。 */
    private static void syncOnViewChange() {
        if (pcrPreviewOn) {
            int[] c = computePcrPreviewCoords();
            if (!java.util.Arrays.equals(c, lastPcrCoords)) {
                lastPcrCoords = c;
                if (rulerGlass != null) rulerGlass.repaint();
                repaintDataPanels();
            }
        }
        syncSelectionRuler();   // v0.1.84：无条件跑（原来挂在"无预览"分支下，弹窗开着时选区不跟随）
    }

    private static void refreshPcrPreview() {
        if (rulerGlass == null) setupGlass();
        int[] c = computePcrPreviewCoords();
        if (java.util.Arrays.equals(c, lastPcrCoords)) return;
        lastPcrCoords = c;
        if (rulerGlass != null) { rulerGlass.setVisible(true); rulerGlass.repaint(); }
        repaintDataPanels();
    }

    /**
     * v0.1.82：按当前视图把预览 bp 实时换算成 glass 坐标（每次绘制/节拍调用，绝不缓存）。
     * @return null（未生效/染色体不符/无面板/headless）或
     *         {prodX1, prodX2, bandAX1, bandAX2, bandBX1, bandBX2, yTop, yBot}（glass 坐标，x 未排序）
     */
    static int[] computePcrPreviewCoords() {
        if (!pcrPreviewOn || pcrPrevChr == null || pcrPrevRegions == null) return null;
        try {
            if (rulerGlass == null) return null;
            org.broad.igv.ui.panel.ReferenceFrame frame = IGV.getInstance().getSession().getReferenceFrame();
            if (frame == null || !frame.getChrName().equals(pcrPrevChr)) return null;
            java.util.List<DataPanel> panels = findDataPanels();
            if (panels.isEmpty()) return null;
            DataPanel dp = panels.get(0);
            // 纵向范围 = 所有数据面板可见区并集（与 updateRuler 同款）
            int top = Integer.MAX_VALUE, bot = Integer.MIN_VALUE; boolean found = false;
            for (DataPanel cand : panels) {
                java.awt.Rectangle vr = cand.getVisibleRect();
                if (vr.width <= 0 || vr.height <= 0) continue;
                java.awt.Point t = SwingUtilities.convertPoint(cand, vr.x, vr.y, rulerGlass);
                top = Math.min(top, t.y); bot = Math.max(bot, t.y + vr.height); found = true;
            }
            if (!found) { java.awt.Point pT = SwingUtilities.convertPoint(dp, 0, 0, rulerGlass); java.awt.Point pB = SwingUtilities.convertPoint(dp, 0, dp.getHeight(), rulerGlass); top = pT.y; bot = pB.y; }
            int[] r = pcrPrevRegions;
            int[] px = new int[]{
                    (int) Math.round(frame.getScreenPosition(r[0])), (int) Math.round(frame.getScreenPosition(r[1])),
                    (int) Math.round(frame.getScreenPosition(r[2])), (int) Math.round(frame.getScreenPosition(r[3])),
                    (int) Math.round(frame.getScreenPosition(r[4])), (int) Math.round(frame.getScreenPosition(r[5])) };
            int[] xs = new int[6];
            for (int k = 0; k < 6; k++) xs[k] = SwingUtilities.convertPoint(dp, px[k], 0, rulerGlass).x;
            return new int[]{ xs[0], xs[1], xs[2], xs[3], xs[4], xs[5], top, bot };
        } catch (Exception ignore) { return null; }
    }

    /** 供 RulerGlass 绘制调用：预览绘制坐标（x 已 min/max 排序）或 null。 */
    public static int[] pcrPreviewPaintCoords() {
        int[] c = computePcrPreviewCoords();
        if (c == null) return null;
        return new int[]{
                Math.min(c[0], c[1]), Math.max(c[0], c[1]),
                Math.min(c[2], c[3]), Math.max(c[2], c[3]),
                Math.min(c[4], c[5]), Math.max(c[4], c[5]),
                c[6], c[7] };
    }

    /** v0.1.82：选中/编辑高亮（rulerGlass.xs，bp 记在 guideBps）也随视图实时重算位置。 */
    private static void syncSelectionRuler() {
        if (rulerGlass == null || !rulerGlass.active
                || rulerGlass.guideBps == null || rulerGlass.guideBps.length < 2) return;
        try {
            org.broad.igv.ui.panel.ReferenceFrame frame = IGV.getInstance().getSession().getReferenceFrame();
            java.util.List<DataPanel> panels = findDataPanels();
            if (frame == null || panels.isEmpty()) return;
            DataPanel dp = panels.get(0);
            int[] gb = rulerGlass.guideBps;
            int[] nx = new int[gb.length];
            for (int k = 0; k < gb.length; k++)
                nx[k] = SwingUtilities.convertPoint(dp,
                        (int) Math.round(frame.getScreenPosition(gb[k])), 0, rulerGlass).x;
            int top = Integer.MAX_VALUE, bot = Integer.MIN_VALUE; boolean found = false;
            for (DataPanel cand : panels) {
                java.awt.Rectangle vr = cand.getVisibleRect();
                if (vr.width <= 0 || vr.height <= 0) continue;
                java.awt.Point t = SwingUtilities.convertPoint(cand, vr.x, vr.y, rulerGlass);
                top = Math.min(top, t.y); bot = Math.max(bot, t.y + vr.height); found = true;
            }
            if (!found) return;
            boolean changed = rulerGlass.yTop != top || rulerGlass.yBot != bot || rulerGlass.xs == null;
            for (int k = 0; k < gb.length && !changed; k++)
                changed = rulerGlass.xs == null || rulerGlass.xs[k] != nx[k];
            if (!changed) return;
            rulerGlass.xs = nx;
            rulerGlass.yTop = top; rulerGlass.yBot = bot;
            rulerGlass.repaint();
        } catch (Exception ignore) { }
    }

    private static void repaintDataPanels() {
        for (DataPanel dp : findDataPanels()) dp.repaint();
    }

    // ---------- 键盘快捷键：选中引物后方向键精确移动（避免鼠标拖动乱跑） ----------
    // v0.1.51：两条分发路径并用。原先只有全局 AWTEventListener，一旦焦点落在
    //   会自己吞掉方向键的组件上（顶栏搜索框/JTable/JTree 等有自身绑定），AKW listener 虽收到事件
    //   却被 isTextInputFocused() 挡掉，功能就整体失灵。
    //   ① 全局 AWTEventListener：焦点在任何地方都能兜底收到（务必保留）
    //   ② 主窗体 rootPane 上的 WHEN_IN_FOCUSED_WINDOW 键绑定：Swing 正规路径，
    //      配合「选中引物即 requestFocus 到 DataPanel」，从搜索框等场景切回来也能生效
    // 两条路径对同一物理按键会各触发一次 → 用极短窗口去重（远小于系统按键重复间隔，不影响长按连发）
    private static java.awt.event.AWTEventListener shortcutListener;   // v0.1.68：常驻快捷键（Del/N/E/F/V/Esc + 方向键）
    private static final java.util.List<KeyStroke> boundStrokes = new java.util.ArrayList<KeyStroke>();
    private static final java.util.List<String> boundActionKeys = new java.util.ArrayList<String>();
    private static long lastMoveKeyAt = 0;
    private static int lastMoveKeyCode = -1;

    /** 真正执行一次方向键移动；返回是否已处理（用于决定是否 consume，防止 IGV 原生平移双重响应） */
    private static synchronized boolean handleMoveKey(int keyCode, boolean shift) {
        long now = System.nanoTime();
        if (keyCode == lastMoveKeyCode && (now - lastMoveKeyAt) < 4000000L) return true; // <4ms：同一按键重复分发
        lastMoveKeyCode = keyCode;
        lastMoveKeyAt = now;

        if (!PrimerEditTool.inEditMode() && PrimerStore.selected == null) return false;
        // v0.1.93：方向键不再强制编辑模式——普通模式选中引物即可移动（帮助面板「无需进入编辑模式」兑现）
        Primer sel = PrimerStore.selected;
        if (sel == null) return false;
        if (focusInOtherWindow()) return false;   // 焦点在别处窗口（如引物编辑对话框）不响应
        if (isTextInputFocused()) return false;   // 在输入框打字时不拦截

        int step = shift ? 10 : 1;
        int horiz = 0, dRow = 0;
        switch (keyCode) {
            case java.awt.event.KeyEvent.VK_LEFT:  horiz = -step; break;
            case java.awt.event.KeyEvent.VK_RIGHT: horiz =  step; break;
            case java.awt.event.KeyEvent.VK_UP:    dRow = -1; break;
            case java.awt.event.KeyEvent.VK_DOWN:  dRow =  1; break;
            default: return false;
        }

        if (horiz != 0) {
            int delta = horiz;
            if (sel.start + delta < 0) delta = -sel.start;   // 防止越界到负坐标
            if (delta != 0) {
                sel.start += delta;
                sel.end += delta;
                PrimerStore.refreshSequence(sel);
            }
        }
        if (dRow != 0) {
            Integer cur = PrimerTrack.allScreenRows().get(sel);
            int base = sel.rowOverride != null ? sel.rowOverride : (cur != null ? cur : 0);
            sel.rowOverride = Math.max(0, base + dRow);
        }
        PrimerStore.refresh();
        refreshTooltip(activePanel);   // v0.1.51：改完立即刷新悬浮提示（长度等随键盘移动变化）
        return true;
    }

    /**
     * v0.1.68：常驻快捷键监听（幂等；PrimerPlugin.init 与 keeper 定时器调用）。
     * 一条 AWTEventListener 兼顾：
     *  - 功能快捷键 Del/N/E/F/V/Esc（全局，选中引物即用，无需进编辑模式）；
     *  - 方向键精确移动（沿用 v0.1.51 handleMoveKey，仅编辑模式生效）。
     * 文本输入聚焦 / 焦点在其它窗口（对话框）时不拦截。
     */
    public static synchronized void ensureShortcutKeys() {
        if (shortcutListener != null) return;
        shortcutListener = new java.awt.event.AWTEventListener() {
            public void eventDispatched(java.awt.AWTEvent ev) {
                if (!(ev instanceof java.awt.event.KeyEvent)) return;
                java.awt.event.KeyEvent ke = (java.awt.event.KeyEvent) ev;
                if (ke.getID() != java.awt.event.KeyEvent.KEY_PRESSED) return;
                int c = ke.getKeyCode();
                switch (c) {
                    case java.awt.event.KeyEvent.VK_DELETE:
                    case java.awt.event.KeyEvent.VK_BACK_SPACE:
                    case java.awt.event.KeyEvent.VK_N:
                    case java.awt.event.KeyEvent.VK_E:
                    case java.awt.event.KeyEvent.VK_F:
                    case java.awt.event.KeyEvent.VK_V:
                    case java.awt.event.KeyEvent.VK_ESCAPE:
                        if (handleShortcutKey(c)) ke.consume();
                        return;
                    default:
                }
                if (c == java.awt.event.KeyEvent.VK_LEFT || c == java.awt.event.KeyEvent.VK_RIGHT
                        || c == java.awt.event.KeyEvent.VK_UP || c == java.awt.event.KeyEvent.VK_DOWN) {
                    if (handleMoveKey(c, ke.isShiftDown())) ke.consume();  // 阻止 IGV 原生方向键平移
                }
            }
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                shortcutListener, java.awt.AWTEvent.KEY_EVENT_MASK);
    }

    private static void installKeyboard() {
        ensureShortcutKeys();   // v0.1.68：快捷键监听常驻（进入编辑模式只是补挂 rootPane 绑定）
        installKeyBindings();
    }

    /** 主窗体 rootPane 上挂方向键绑定（仅编辑模式期间存在，退出即摘掉） */
    private static void installKeyBindings() {
        try {
            java.awt.Window w = IGV.getMainFrame();
            if (!(w instanceof JFrame)) return;
            JRootPane rp = ((JFrame) w).getRootPane();
            InputMap im = rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = rp.getActionMap();
            int[] codes = {java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.VK_RIGHT,
                    java.awt.event.KeyEvent.VK_UP, java.awt.event.KeyEvent.VK_DOWN};
            for (int c : codes) {
                bindKey(im, am, c, false);
                bindKey(im, am, c, true);
            }
        } catch (Exception ignore) {
        }
    }

    private static void bindKey(InputMap im, ActionMap am, final int code, final boolean shift) {
        KeyStroke ks = KeyStroke.getKeyStroke(code, shift ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0);
        if (ks == null) return;
        String key = "primer.move." + code + (shift ? ".shift" : "");
        im.put(ks, key);
        am.put(key, new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { handleMoveKey(code, shift); }
        });
        if (!boundStrokes.contains(ks)) boundStrokes.add(ks);
        if (!boundActionKeys.contains(key)) boundActionKeys.add(key);
    }

    /** v0.1.68：退出编辑模式只摘 rootPane 方向键绑定；快捷键监听（shortcutListener）常驻不摘。 */
    private static void removeKeyboard() {
        try {
            java.awt.Window w = IGV.getMainFrame();
            if (w instanceof JFrame) {
                InputMap im = ((JFrame) w).getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
                ActionMap am = ((JFrame) w).getRootPane().getActionMap();
                for (KeyStroke ks : boundStrokes) im.remove(ks);
                for (String k : boundActionKeys) am.remove(k);
            }
            boundStrokes.clear();
            boundActionKeys.clear();
        } catch (Exception ignore) {
        }
    }

    /** 焦点是否落在主窗体以外的窗口（引物编辑对话框等模态窗口） */
    private static boolean focusInOtherWindow() {
        java.awt.Component f =
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f == null) return false;
        java.awt.Window win = SwingUtilities.getWindowAncestor(f);
        return win != null && win != IGV.getMainFrame();
    }

    /** 焦点是否在文本输入组件上（对话框输入框/下拉框/数字框等），是则跳过快捷键 */
    private static boolean isTextInputFocused() {
        java.awt.Component f =
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f == null) return false;
        return f instanceof javax.swing.text.JTextComponent
                || f instanceof javax.swing.JComboBox
                || f instanceof javax.swing.JSpinner;
    }

    // ---------- 坐标换算（IGV 权威 API；scale = 碱基/像素） ----------

    private static ReferenceFrame frameOf(MouseEvent e) {
        Object s = e.getSource();
        return (s instanceof DataPanel) ? ((DataPanel) s).getFrame() : null;
    }

    private static int bpAt(MouseEvent e, ReferenceFrame frame) {
        return (int) Math.round(frame.getChromosomePosition(e.getX()));
    }

    private static int xOf(int bp, ReferenceFrame frame) {
        double scale = frame.getScale(); // bases per pixel
        if (scale <= 0) return 0;
        return (int) Math.round((bp - frame.getOrigin()) / scale);
    }

    private PanTool panTool(DataPanel dp) {
        PanTool t = panTools.get(dp);
        if (t == null) {
            t = new PanTool(dp);
            panTools.put(dp, t);
        }
        return t;
    }

    // ---------- 鼠标事件 ----------

    @Override
    public void mousePressed(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        // 防重入：拖动/平移/选区进行中忽略重复 press，否则拖动经过其他引物时目标会被切换
        if (dragging != null || panning || selecting) return;
        ReferenceFrame frame = frameOf(e);
        if (frame == null) return;
        final DataPanel dp = (DataPanel) e.getSource();
        activePanel = dp;
        String chr = frame.getChrName();
        int bp = bpAt(e, frame);
        int x = e.getX();
        int y = e.getY();

        // v0.1.19：点击落在 track 顶部工具条上 → 交给 PrimerTrack.handleDataClick 处理，不平移/不拖拽
        if (PrimerTrack.isInToolbarAny(x, y, chr)) return;

        dragging = null;
        mode = MODE_NONE;
        panning = false;

        Primer hit = findPrimerAt(x, y, chr);

        // Ctrl+点击 = 配对切换（toggle，v0.1.27 起；v0.1.28 移除原 Ctrl+Shift 清整组功能，避免误触）：
        //  先普通点击选定一个锚点引物 → 再 Ctrl+点击 另一条 → 二者未连则建立 pair 连线、已连则取消这两条之间的连线。
        //  锚点在 toggle 后保持选中，便于连续操作。Ctrl+Shift+点击 现等同 Ctrl+点击（不再清整组）。
        // 命中引物时拦截，不进入拖拽流程。
        if (hit != null && e.isControlDown()) {
            Primer sel = PrimerStore.selected;
            if (sel == null || sel == hit) {
                setSelected(hit);       // 首次/再次点击：定为锚点（配对基准）
            } else {
                // v0.1.41 边模型：Ctrl+点击 切换 sel 与 hit「这一条」边的连线——
                // 已连则只删这一条（不影响 sel/hit 的其他边）；未连则只加这一条（天然支持一引物多连）。
                PrimerStore.toggleEdge(sel, hit);
                setSelected(sel);        // 锚点保持选中，便于连续操作
            }
            dp.requestFocusInWindow();             // v0.1.51：同普通选中，焦点回收
            e.consume();                              // 阻止 IGV 原生 pan/zoom 对 Ctrl 点击的响应
            dp.repaint();
            return;
        }

        if (hit != null) {
            java.awt.Rectangle r = PrimerTrack.allScreenRects().get(hit);
            int xl = r.x;
            int xr = r.x + r.width;
            boolean edge = Math.abs(x - xl) <= EDGE_PX || Math.abs(x - xr) <= EDGE_PX;
            dragging = hit;
            origStart = hit.start;
            origEnd = hit.end;
            mode = edge ? (Math.abs(x - xl) <= EDGE_PX ? MODE_RESIZE_L : MODE_RESIZE_R) : MODE_MOVE;
            dragAnchorBp = bp;
            pressY = y;
            Integer r0 = PrimerTrack.allScreenRows().get(hit);
            origRow = r0 == null ? 0 : r0;
            setSelected(hit);
            // v0.2.2：点中引物即画出贯穿选区框（与非编辑模式一致），并同步 guides 区间供轨道内选区绘制读取
            setGuides(frame, hit.start, hit.end);
            // v0.1.51：选中后把焦点抢回数据面板，否则焦点滞留搜索框等组件，方向键被其自身绑定吞掉
            dp.requestFocusInWindow();
            dp.repaint();
            return;
        }
        // 空白：清除选中 + 委托原生 PanTool 平移视图
        if (PrimerStore.selected != null) {
            setSelected(null);
            PrimerStore.refresh();
        }
        // v0.1.57：空白拖拽=平移+顺带记录选区（松手后右键「设计*」可预填）；单击不留选区
        // v0.1.63：Shift+拖拽=纯选区（视图不动）——此前只有菜单文案没有实现，Shift 拖动
        // 照样平移视图，选的区间跟着滚走。currentTool 独占 DataPanel 事件分发，
        // 不进 panning 分支即不会平移，无需 consume。
        pressBp = bp;
        pressChr = chr;
        clearSelection();
        clearGuides();          // 清掉上次松手保留的选区红线（若接着拖拽会重新画）
        if (e.isShiftDown()) {
            selecting = true;
            setGuides(frame, bp, bp);   // 起点先画一个点，拖动时展开
            ((DataPanel) e.getSource()).repaint();
            return;
        }
        panning = true;
        panTool(dp).mousePressed(e);
    }

    /**
     * 命中检测（v8 两轮）：
     *  第一轮精确命中——只在引物条矩形 ±3px 内，上下相邻行不会误命中；
     *  第二轮扩展命中——含下方测序延长区（±16px），宽容空白区点击。
     *  每轮内后画的（列表靠后）优先，模拟"上层优先"。
     */
    private Primer findPrimerAt(int x, int y, String chr) {
        List<Primer> ps = PrimerStore.getPrimers();
        for (int round = 0; round < 2; round++) {
            int yLo = round == 0 ? -3 : -6;
            int yHi = round == 0 ? 3 : 16;
            for (int i = ps.size() - 1; i >= 0; i--) {
                Primer p = ps.get(i);
                if (!chr.equals(p.chr)) continue;
                java.awt.Rectangle r = PrimerTrack.allScreenRects().get(p);
                if (r == null) continue;
                if (y >= r.y + yLo && y <= r.y + r.height + yHi
                        && x >= r.x - EDGE_PX && x <= r.x + r.width + EDGE_PX) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 双击引物 → 直接弹出编辑框 */
    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
            ReferenceFrame frame = frameOf(e);
            if (frame == null) return;
            Primer hit = findPrimerAt(e.getX(), e.getY(), frame.getChrName());
            if (hit != null) {
                AddPrimerDialog.show((int) Math.round(frame.getChromosomePosition(e.getX())), hit, hit.group);
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (selecting) {
            // v0.1.63：Shift+拖拽——只展开选区红线，视图不动
            ReferenceFrame f = frameOf(e);
            if (f != null && pressChr != null && pressChr.equals(f.getChrName())) {
                setGuides(f, pressBp, bpAt(e, f));
            }
            ((DataPanel) e.getSource()).repaint();
            return;
        }
        if (panning) {
            panTool((DataPanel) e.getSource()).mouseDragged(e);
            // v0.1.57：平移的同时把「按下点到当前点」画成红线参考线（松手保留为选区）
            ReferenceFrame f = frameOf(e);
            if (f != null && pressChr != null && pressChr.equals(f.getChrName())) {
                setGuides(f, pressBp, bpAt(e, f));
            }
            return;
        }
        if (dragging == null || mode == MODE_NONE) return;
        ReferenceFrame frame = frameOf(e);
        if (frame == null) return;
        int bp = bpAt(e, frame);
        int delta = bp - dragAnchorBp;

        if (mode == MODE_MOVE) {
            dragging.start = origStart + delta;
            dragging.end = origEnd + delta;
            // v8：垂直拖动 → 换行（手动指定行号，布局最优先）
            int dr = Math.round((e.getY() - pressY) / (float) PrimerTrack.ROW_H);
            Integer newRow = Math.max(0, origRow + dr);
            if (!newRow.equals(dragging.rowOverride)) {
                dragging.rowOverride = newRow;
            }
            setGuides(frame, dragging.start, dragging.end);
        } else if (mode == MODE_RESIZE_L) {
            dragging.start = Math.min(origStart + delta, origEnd - 5);
            setGuides(frame, dragging.start, origEnd);
        } else {
            dragging.end = Math.max(origEnd + delta, origStart + 5);
            setGuides(frame, origStart, dragging.end);
        }
        ((DataPanel) e.getSource()).repaint();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (selecting) {
            // v0.1.63：Shift+拖拽松手——落定选区（位移<5bp 视为单击，清除）
            selecting = false;
            if (pressChr != null) {
                ReferenceFrame f = frameOf(e);
                if (f != null && pressChr.equals(f.getChrName())) {
                    int bp = bpAt(e, f);
                    if (Math.abs(bp - pressBp) >= 5) setSelection(pressChr, pressBp, bp);
                    else clearSelection();
                }
            }
            ((DataPanel) e.getSource()).repaint();
            return;
        }
        if (panning) {
            panning = false;
            panTool((DataPanel) e.getSource()).mouseReleased(e);
            // v0.1.57：松手落定选区（按下→松开掠过的区间）；位移<5bp 视为单击不留选区。
            // 红线参考线保留显示（不 clearGuides），让选区可见；下次按/拖时清除。
            if (pressChr != null) {
                ReferenceFrame f = frameOf(e);
                if (f != null && pressChr.equals(f.getChrName())) {
                    int bp = bpAt(e, f);
                    if (Math.abs(bp - pressBp) >= 5) setSelection(pressChr, pressBp, bp);
                    else clearSelection();
                }
            }
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        // v0.2.2：编辑模式下单击/拖拽引物后选区「常驻」（不再松手即清），与非编辑模式一致。
        //        用最终位置重画 guides，使其保留在引物区间；空白点击仍会经 mousePressed 的 clearGuides 清除。
        if (dragging != null && mode != MODE_NONE) {
            ReferenceFrame rf = frameOf(e);
            if (rf != null) setGuides(rf, dragging.start, dragging.end);   // 重画到最终位置，保持常驻
            // v0.1.96：跨轨拖拽——松手落在另一条"有可见引物"的轨的纵向带内 → 整对迁移到该轨
            if (mode == MODE_MOVE) {
                PrimerTrack tgt = PrimerTrack.trackAtScreenY(e.getY());
                if (tgt != null && !java.util.Objects.equals(tgt.getGroup(), dragging.group)) {
                    PrimerStore.movePrimersToGroup(java.util.Collections.singletonList(dragging), tgt.getGroup());
                    dragging = null;
                    mode = MODE_NONE;
                    PrimerStore.refresh();
                    refreshTooltip((DataPanel) e.getSource());
                    return;
                }
            }
            boolean changed = dragging.start != origStart || dragging.end != origEnd;
            if (changed) {
                PrimerStore.refreshSequence(dragging);
                PrimerStore.resetDimerFlags();   // 序列可能变化：清陈旧二聚体标记（按需评估才重算）
            }
            dragging = null;
            mode = MODE_NONE;
            PrimerStore.refresh();
            // v0.1.48：鼠标松手后往往停在原地不动 → Swing 不会重新取 tooltip 文本，
            // 于是悬浮框还显示改之前的信息。这里补发一次 MOUSE_MOVED 强制刷新。
            refreshTooltip((DataPanel) e.getSource());
        }
    }

    /** v0.1.48：在当前鼠标位置向 DataPanel 补发一个 MOUSE_MOVED，强制刷新悬浮提示内容。 */
    private static void refreshTooltip(DataPanel dp) {
        if (dp == null) return;
        try {
            java.awt.Point ml = java.awt.MouseInfo.getPointerInfo().getLocation();
            java.awt.Point local = new java.awt.Point(ml);
            SwingUtilities.convertPointFromScreen(local, dp);
            if (local.x < 0 || local.y < 0 || local.x > dp.getWidth() || local.y > dp.getHeight()) return;
            dp.dispatchEvent(new java.awt.event.MouseEvent(dp, java.awt.event.MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, local.x, local.y, 0, false));
        } catch (Throwable ignore) {
        }
    }

    // ---------- v0.1.65：全局 Shift+拖动选区（无需进入编辑模式） ----------
    // 根因（老板两次反馈"Shift 拖动还是只能滚屏"）：选区全链挂在编辑模式上——
    //   enterEditMode 才 setCurrentTool(PrimerEditTool)，guidePositions/updateRuler/rulerActive
    //   又全带 inEditMode() 门禁。普通模式下 currentTool 是 IGV 原生 PanTool，Shift 拖动必平移。
    // 本方案不动 listener 顺序（Swing 先注册先收，DataPanel 自带的 adapter 抢不过），
    // 而是利用实测事实：PanTool.mousePressed 只记录坐标、零视觉副作用。因此 Shift+左键
    // 按下时把 currentTool 换成 ShiftTool 即可——后续 dragged/released 由 DataPanelMouseAdapter
    // 转给 currentTool（=ShiftTool），只画选区不动视图；松手后还原原 tool。
    private static final ShiftTool shiftTool = new ShiftTool();
    private static final java.util.Map<DataPanel, AbstractDataPanelTool> savedShiftTools =
            new java.util.IdentityHashMap<DataPanel, AbstractDataPanelTool>();
    private static final java.util.Set<DataPanel> shiftAdapters =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<DataPanel, Boolean>());
    private static volatile DataPanel lastPanel;   // v0.1.67：最近交互的 DataPanel（tooltip 取鼠标坐标用）

    /**
     * v0.1.67：当前鼠标在 DataPanel 内的坐标（实时，MouseInfo 取指针屏幕位置转换）。
     * tooltip 钩子 getValueStringAt 没有 mouseY 参数，IGV 也不暴露 DataPanel——
     * 用这个坐标跑行感知命中，修复"鼠标在下面引物却显示上面引物信息"的串显。
     * 鼠标不在任何数据面板上 / 拿不到 → 返回 null（调用方回退 bp 命中）。
     */
    public static int[] mousePanelXY() {
        try {
            DataPanel dp = lastPanel;
            if (dp == null) {
                java.util.List<DataPanel> ps = findDataPanels();
                if (ps.isEmpty()) return null;
                dp = ps.get(0);
            }
            java.awt.Point ml = java.awt.MouseInfo.getPointerInfo().getLocation();
            if (ml == null) return null;
            java.awt.Point local = new java.awt.Point(ml);
            SwingUtilities.convertPointFromScreen(local, dp);
            if (local.x < 0 || local.y < 0 || local.x > dp.getWidth() || local.y > dp.getHeight()) return null;
            return new int[]{local.x, local.y};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射读取 DataPanel.currentTool（私有字段，无 getter）。setCurrentTool 只收 AbstractDataPanelTool。 */
    private static AbstractDataPanelTool currentToolOf(DataPanel dp) {
        try {
            java.lang.reflect.Field f = DataPanel.class.getDeclaredField("currentTool");
            f.setAccessible(true);
            Object t = f.get(dp);
            return (t instanceof AbstractDataPanelTool) ? (AbstractDataPanelTool) t : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 给一个 DataPanel 挂 Shift 选区监听（幂等）。v0.1.65 起兼管：N 布防放置 / Ctrl+配对 / 点击选中 / 虚影跟手。 */
    public static synchronized void installShiftSelector(final DataPanel dp) {
        if (dp == null || shiftAdapters.contains(dp)) return;
        lastPanel = dp;   // v0.1.67：tooltip 行感知命中要用（拿鼠标坐标转 panel 坐标）
        javax.swing.event.MouseInputAdapter ma = new javax.swing.event.MouseInputAdapter() {
            private int pressX, pressY;
            private boolean pressed;

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                // 编辑模式下 PrimerEditTool 自身已有全套分支（DataPanelMouseAdapter 会转给它），
                // 这里不接管，避免两条路径叠加；普通模式才接管。
                if (inEditMode()) return;
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                // v0.1.68：N 布防态——点击放置新引物（引物中心对齐点击点）
                if (newArm) { placeNewPrimer(e); return; }
                // v0.1.68：Ctrl+点击 = 配对（无锚点则设为锚点；有锚点则与命中者连/断边）
                if (e.isControlDown()) { ctrlPairClick(e); return; }
                // v0.1.65：Shift+左键按下即换 ShiftTool（PanTool.mousePressed 零视觉副作用）
                if (e.isShiftDown()) {
                    if (shiftTool.busy) return;
                    AbstractDataPanelTool saved = currentToolOf(dp);
                    savedShiftTools.put(dp, saved);
                    try { dp.setCurrentTool(shiftTool); } catch (Throwable ignore) { return; }
                    shiftTool.busy = true;
                    shiftTool.mousePressed(e);
                    return;
                }
                pressX = e.getX(); pressY = e.getY(); pressed = true;   // 单击选中判定（released 用）
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // v0.1.93：普通模式双击引物 = 直接编辑（此前只有编辑模式有此功能，帮助面板宣称却未兑现）
                if (inEditMode()) return;
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2) return;
                if (newArm) return;   // 布防态点击归 placeNewPrimer，不弹编辑框
                ReferenceFrame frame = frameOf(e);
                if (frame == null) return;
                Primer hit = PrimerTrack.hitTestAny(e.getX(), e.getY(), frame.getChrName());
                if (hit != null) {
                    AddPrimerDialog.show((int) Math.round(frame.getChromosomePosition(e.getX())), hit, hit.group);
                    e.consume();
                }
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                if (newArm) updateGhost(e);   // v0.1.68：虚影跟手
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (newArm) updateGhost(e);   // 布防态按住拖动也更新虚影
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (inEditMode() || !pressed) return;
                pressed = false;
                if (newArm) return;   // 布防点击在 pressed 已处理
                if (!SwingUtilities.isLeftMouseButton(e) || e.isShiftDown() || e.isControlDown()) return;
                // 位移小 = 单击：选中/取消选中引物（拖动仍归 PanTool 平移，两不误）
                if (Math.abs(e.getX() - pressX) > 4 || Math.abs(e.getY() - pressY) > 4) return;
                ReferenceFrame frame = frameOf(e);
                if (frame == null) return;
                Primer hit = PrimerTrack.hitTestAny(e.getX(), e.getY(), frame.getChrName());
                setSelected(hit);   // 命中=选中（兼配对锚点）；空白=取消选中
                ((DataPanel) e.getSource()).repaint();
            }
        };
        dp.addMouseListener(ma);
        dp.addMouseMotionListener(ma);   // v0.1.68：虚影跟手要 mouseMoved
        shiftAdapters.add(dp);
    }

    /** 对所有数据面板确保挂上 Shift 选区监听（keeper 定时器/进入编辑模式/右键菜单时调用）。 */
    public static synchronized void ensureShiftSelectors() {
        try {
            for (DataPanel dp : findDataPanels()) installShiftSelector(dp);
        } catch (Throwable ignore) {
        }
    }

    // ---------- v0.1.68：快捷键套件（Del 删 / N 新建虚影放置 / Ctrl+配对 / E 编辑 / F 翻转 / V 定位 / Esc 取消） ----------

    private static boolean newArm = false;   // N 布防：虚影新建态

    /** N 键：进入新建布防（虚影跟手，点击放置；可连续放置，Esc 退出）。 */
    public static synchronized void armNewPrimer() {
        if (inEditMode()) exitEditMode();   // 布防依赖普通模式的全局输入监听（编辑模式会被 PrimerEditTool 接管）
        java.util.List<DataPanel> panels;
        try {
            panels = findDataPanels();
        } catch (Throwable t) {
            panels = java.util.Collections.emptyList();   // IGV 未就绪（headless 测试）
        }
        if (panels.isEmpty()) {
            try {
                JOptionPane.showMessageDialog(IGV.getMainFrame(), "未找到数据面板（请先加载数据）");
            } catch (Throwable ignore) {
            }
            return;
        }
        ensureShiftSelectors();   // 保证全局输入监听已挂
        newArm = true;
        if (rulerGlass == null) setupGlass();
        if (rulerGlass != null) rulerGlass.setVisible(true);
    }

    public static boolean newPrimerArmed() { return newArm; }

    /**
     * v0.1.70：选中/取消引物的统一入口——顺便维护「选中引物淡色框」。
     * 老板需求："引物目前按住左右拉动时候才会显示出淡色边界，实际使用上如果想查看某个引物
     * 和 ref 的位置但是不想拖动，无法看到淡色边界框很不方便。修正为，点击某引物，
     * 直接显示出淡色框，拖动也显示，这样就很方便。"
     */
    public static void setSelected(Primer p) {
        PrimerStore.selected = p;   // 直接赋值（勿改成 setSelected，会无限递归）
        markSelectedBox(p);
    }

    /**
     * v0.1.70：把选中引物所占区间画成贯穿数据面板的淡色框（与 Shift 选区同款外观）。
     * 点击即显示（selBox 标志），拖动/Shift 选区照旧；取消选中时清除（拖动中的框优先保留）。
     */
    /**
     * v0.1.70：render 时同步选中框位置——视图平移/缩放后框要跟着目标引物走，
     * 否则玻璃层上的框会留在旧的屏幕坐标上（点击后平移视图，框就和引物错位了）。
     * 用「引物位置+视图起点」指纹做变化检测，静止时不做无用重算。
     */
    public static void syncSelectedBox(Primer sel) {
        if (!selBox || sel == null || rulerGlass == null) return;
        try {
            ReferenceFrame f = IGV.getInstance() == null ? null
                    : IGV.getInstance().getSession().getReferenceFrame();
            if (f == null) return;
            long key = ((long) sel.start * 31L + sel.end) * 131L + (long) f.getOrigin();
            if (key == selBoxKey) return;
            selBoxKey = key;
            markSelectedBox(sel);
        } catch (Throwable ignore) {
        }
    }

    private static void markSelectedBox(Primer p) {
        try {
            if (p == null) {
                if (inEditMode() || shiftOn) return;   // 别把正在拖动的选区框清掉
                selBox = false;
                clearGuides();
                return;
            }
            if (IGV.getInstance() == null || IGV.getInstance().getSession() == null) return;
            ReferenceFrame f = IGV.getInstance().getSession().getReferenceFrame();
            if (f == null || f.getChrName() == null || !f.getChrName().equals(p.chr)) {
                selBox = false;
                clearGuides();
                return;
            }
            DataPanel dp = lastPanel;
            if (dp == null) {
                java.util.List<DataPanel> ps = findDataPanels();
                if (ps.isEmpty()) return;
                dp = ps.get(0);
            }
            selBox = true;
            updateRuler(dp, f, p.start, p.end);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 布防态点击：以点击点为中心放一条默认长度引物（"一一对应"），弹编辑框微调。
     * v0.1.94：两处修正（老板实测"出现虚影后无法取消、点击也没法更新原引物"）——
     *  1) 点击落在已有引物上 → 直接弹该引物的编辑框（此前弹的是"添加新引物"框，
     *     新引物叠在原引物上且改不到原引物，看起来就是"没办法更新"）；
     *  2) 编辑框取消/Esc 关闭 → 立即解除布防并清虚影（模态框开着时全局 Esc 监听
     *     因焦点在对话框被跳过，关框后仍留在布防态 → 反复弹框的死循环）。
     *  确认提交则保持布防（连续放置多条，Esc 退出）。
     */
    private static void placeNewPrimer(java.awt.event.MouseEvent e) {
        ReferenceFrame frame = frameOf(e);
        if (frame == null) return;
        String chr = frame.getChrName();
        if (PrimerTrack.isInToolbarAny(e.getX(), e.getY(), chr)) return;   // 工具条上不放置
        clearGhost();   // 模态框打开期间虚影冻结，先收掉避免遮挡
        boolean ok;
        Primer hit = PrimerTrack.hitTestAny(e.getX(), e.getY(), chr);
        if (hit != null) {
            setSelected(hit);                              // 命中已有引物 → 编辑它
            ok = AddPrimerDialog.show(hit.start, hit, hit.group);
        } else {
            int bp = bpAt(e, frame);
            int len = PrimerStore.defaultLen;
            int start = Math.max(0, bp - len / 2);   // 引物中心对齐点击点
            ok = AddPrimerDialog.show(start, null, null);  // 手动轨(group=null)，名称自动 nextName
        }
        if (!ok) {   // v0.1.94：取消/Esc → 退出布防，不再反复弹框
            newArm = false;
            clearGhost();
        }
    }

    /** 虚影跟手：更新 glass 上的半透明新引物位置。 */
    private static void updateGhost(java.awt.event.MouseEvent e) {
        try {
            ReferenceFrame frame = frameOf(e);
            if (frame == null) { clearGhost(); return; }
            int bp = bpAt(e, frame);
            int len = PrimerStore.defaultLen;
            int x1 = xOf(bp - len / 2, frame), x2 = xOf(bp + len / 2 + 1, frame);
            if (rulerGlass == null) setupGlass();
            if (rulerGlass == null) return;
            java.awt.Point p = SwingUtilities.convertPoint((DataPanel) e.getSource(), x1, e.getY(), rulerGlass);
            rulerGlass.ghostActive = true;
            rulerGlass.ghostX = p.x;
            rulerGlass.ghostY = p.y;
            rulerGlass.ghostW = Math.max(4, x2 - x1);
            rulerGlass.ghostLen = len;
            rulerGlass.setVisible(true);
            rulerGlass.repaint();
        } catch (Throwable ignore) {
        }
    }

    private static void clearGhost() {
        if (rulerGlass != null && rulerGlass.ghostActive) {
            rulerGlass.ghostActive = false;
            rulerGlass.repaint();
        }
    }

    /**
     * v0.1.68：Ctrl+点击两步配对（普通模式，编辑模式走 PrimerEditTool.mousePressed 同款逻辑）。
     * 老板原话："点击一个引物按住ctrl点击另一个对向引物可以创建pair"。
     *  - 普通单击引物 = 选中（兼锚点，见 installShiftSelector 的 released）；
     *  - Ctrl+点击第二条 = 与锚点连/断边（toggleEdge 内含同链拒绝 + PCR 产物校验）；
     *  - Ctrl+点空白 = 取消锚点（不消费事件，视图平移不受影响）。
     */
    private static void ctrlPairClick(java.awt.event.MouseEvent e) {
        ReferenceFrame frame = frameOf(e);
        if (frame == null) return;
        String chr = frame.getChrName();
        Primer hit = PrimerTrack.hitTestAny(e.getX(), e.getY(), chr);
        final DataPanel dp = (DataPanel) e.getSource();
        if (hit == null) {
            if (PrimerStore.selected != null) {   // 取消锚点
                setSelected(null);
                dp.repaint();
            }
            return;   // 不消费：Ctrl+空白仍归 IGV 原生
        }
        Primer sel = PrimerStore.selected;
        if (sel == null || sel == hit) {
            setSelected(hit);            // 首个锚点
        } else {
            PrimerStore.toggleEdge(sel, hit);      // 连/断这一条边（同向会弹窗拒绝）
            setSelected(sel);            // 锚点保持，便于连续配对
        }
        e.consume();
        dp.repaint();
    }

    /** Del：删除选中引物（确认框；配对连线由 remove→cleanOrphanPairs 一并处理）。 */
    private static void deleteSelected() {
        Primer sel = PrimerStore.selected;
        if (sel == null) return;
        int r = JOptionPane.showConfirmDialog(IGV.getMainFrame(),
                "删除引物 " + sel.name + "（" + sel.chr + ":" + (sel.start + 1) + "-" + sel.end + "）？"
                        + ((sel.pairedWith != null && !sel.pairedWith.isEmpty())
                        ? "\n该引物的配对连线将一并解除。" : ""),
                "删除引物（Del）", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        PrimerStore.remove(sel);
        setSelected(null);
    }

    /** E：编辑选中引物。 */
    private static void editSelected() {
        Primer sel = PrimerStore.selected;
        if (sel == null) return;
        AddPrimerDialog.show(sel.start, sel, sel.group);
    }

    /** F：翻转选中引物方向（F↔R，位置不动；二聚体标记清空按需重算）。public 供测试。 */
    public static void flipSelected() {
        Primer sel = PrimerStore.selected;
        if (sel == null) return;
        sel.strand = sel.strand == '+' ? '-' : '+';
        sel.role = sel.strand == '+' ? "R1" : "R2";
        PrimerStore.refreshSequence(sel);
        PrimerStore.resetDimerFlags();
        PrimerStore.refresh();
    }

    /** V：视图定位到选中引物（前后各留 max(100, 5×引物长) bp）。 */
    private static void zoomToSelected() {
        Primer sel = PrimerStore.selected;
        if (sel == null) return;
        try {
            DataPanel dp = lastPanel;
            if (dp == null) {
                java.util.List<DataPanel> ps = findDataPanels();
                if (ps.isEmpty()) return;
                dp = ps.get(0);
            }
            ReferenceFrame frame = dp.getFrame();
            if (frame == null) return;
            int pad = Math.max(100, (sel.end - sel.start) * 5);
            frame.jumpTo(sel.chr, Math.max(0, sel.start - pad), sel.end + pad);
        } catch (Throwable ignore) {
        }
    }

    /** Esc：取消一切临时状态（新建虚影 / 选中锚点 / Shift 选区）。 */
    public static synchronized void cancelTempStates() {
        boolean wasArmed = newArm;
        newArm = false;
        clearGhost();
        clearSelection();   // 含 shiftOn / ruler 参考线
        if (wasArmed || PrimerStore.selected != null) {
            setSelected(null);
            PrimerStore.refresh();
        }
    }

    /** v0.1.68 快捷键分发（文本输入/其它窗口聚焦时不拦截）。public 供测试。 */
    public static boolean handleShortcutKey(int keyCode) {
        if (focusInOtherWindow()) return false;   // 焦点在对话框等别处窗口
        if (isTextInputFocused()) return false;   // 输入框打字中
        switch (keyCode) {
            case java.awt.event.KeyEvent.VK_DELETE:
            case java.awt.event.KeyEvent.VK_BACK_SPACE:
                deleteSelected(); return true;
            case java.awt.event.KeyEvent.VK_N:
                armNewPrimer(); return true;
            case java.awt.event.KeyEvent.VK_E:
                editSelected(); return true;
            case java.awt.event.KeyEvent.VK_F:
                flipSelected(); return true;
            case java.awt.event.KeyEvent.VK_V:
                zoomToSelected(); return true;
            case java.awt.event.KeyEvent.VK_ESCAPE:
                cancelTempStates(); return true;
            default:
                return false;
        }
    }

    /** Shift+拖动专用 tool：只记录/绘制选区，绝不动视图。 */
    private static final class ShiftTool extends AbstractDataPanelTool {
        private boolean busy = false;
        private String chr;
        private int bp0;

        ShiftTool() {
            super(null, Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        public String getName() { return "Primer Shift Select"; }
        public java.awt.Cursor getCursor() { return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR); }

        public void mousePressed(java.awt.event.MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            ReferenceFrame frame = frameOf(e);
            if (frame == null) return;
            int x = e.getX(), y = e.getY();
            if (PrimerTrack.isInToolbarAny(x, y, frame.getChrName())) return;   // 工具条点击不选区
            chr = frame.getChrName();
            bp0 = bpAt(e, frame);
            activePanel = (DataPanel) e.getSource();
            shiftOn = true;
            clearSelection();
            clearGuides();
            setGuides(frame, bp0, bp0);
            ((DataPanel) e.getSource()).repaint();
        }

        public void mouseDragged(java.awt.event.MouseEvent e) {
            ReferenceFrame f = frameOf(e);
            if (f != null && chr != null && chr.equals(f.getChrName())) {
                setGuides(f, bp0, bpAt(e, f));
            }
            ((DataPanel) e.getSource()).repaint();
        }

        public void mouseReleased(java.awt.event.MouseEvent e) {
            busy = false;
            if (chr != null) {
                ReferenceFrame f = frameOf(e);
                if (f != null && chr.equals(f.getChrName())) {
                    int bp = bpAt(e, f);
                    if (Math.abs(bp - bp0) >= 5) setSelection(chr, bp0, bp);
                    else clearSelection();
                }
            }
            final DataPanel dp = (DataPanel) e.getSource();
            // 还原被替换的 tool。延后到本事件链结束：DataPanelMouseAdapter 随后还会把
            // 这个 released 分发给 currentTool，彼时必须是 ShiftTool 本身。
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    AbstractDataPanelTool saved = savedShiftTools.remove(dp);
                    try { dp.setCurrentTool(saved); } catch (Throwable ignore) { }
                }
            });
            dp.repaint();
        }

        public void mouseClicked(java.awt.event.MouseEvent e) { }
    }
}

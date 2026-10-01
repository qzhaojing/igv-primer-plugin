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
 *  - 空白处按住拖动 = 正常平移视图（委托 IGV 原生 PanTool）
 *  - 拖拽中两条红竖线参考线（起止坐标）
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
        PrimerStore.refresh();
        removeKeyboard();
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
            java.awt.Rectangle r = PrimerStore.screenRects.get(hit);
            boolean edge = Math.abs(e.getX() - r.x) <= EDGE_PX
                    || Math.abs(e.getX() - (r.x + r.width)) <= EDGE_PX;
            return Cursor.getPredefinedCursor(edge ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR);
        }
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    // ---------- 拖拽参考线（供 PrimerTrack.overlay 读取） ----------

    private static String guideChr;
    private static int[] guides;

    public static int[] guidePositions(String chr) {
        if (!inEditMode() || guides == null) return null;
        return chr != null && chr.equals(guideChr) ? guides : null;
    }

    private void setGuides(ReferenceFrame frame, int a, int b) {
        guideChr = frame.getChrName();
        guides = new int[]{a, b};
        updateRuler(activePanel, frame, a, b);
    }

    /** 在 glass pane 上画贯穿 DataPanel 全高的 ruler 线（对齐参考轨/其他 track） */
    private void updateRuler(DataPanel dp, ReferenceFrame frame, int a, int b) {
        if (dp == null || rulerGlass == null || frame == null) return;
        try {
            int x1 = (int) Math.round(frame.getScreenPosition(a));
            int x2 = (int) Math.round(frame.getScreenPosition(b));
            java.awt.Point p1 = SwingUtilities.convertPoint(dp, x1, 0, rulerGlass);
            java.awt.Point p2 = SwingUtilities.convertPoint(dp, x2, 0, rulerGlass);
            java.awt.Point pT = SwingUtilities.convertPoint(dp, 0, 0, rulerGlass);
            java.awt.Point pB = SwingUtilities.convertPoint(dp, 0, dp.getHeight(), rulerGlass);
            rulerGlass.active = inEditMode();
            rulerGlass.xs = new int[]{p1.x, p2.x};
            rulerGlass.yTop = pT.y;
            rulerGlass.yBot = pB.y;
            rulerGlass.guideBps = new int[]{a, b};
            rulerGlass.repaint();
        } catch (Exception ignore) {
        }
    }

    private void clearGuides() {
        guides = null;
        if (rulerGlass != null) {
            rulerGlass.active = false;
            rulerGlass.xs = null;
            rulerGlass.repaint();
        }
    }

    // ---------- 键盘快捷键：选中引物后方向键精确移动（避免鼠标拖动乱跑） ----------
    // 用全局 AWTEventListener 捕获方向键：规避 DataPanel 焦点不确定导致的快捷键失灵。
    private static java.awt.event.AWTEventListener keyListener;

    private static void installKeyboard() {
        if (keyListener != null) return;
        keyListener = new java.awt.event.AWTEventListener() {
            public void eventDispatched(java.awt.AWTEvent ev) {
                if (!(ev instanceof java.awt.event.KeyEvent)) return;
                java.awt.event.KeyEvent ke = (java.awt.event.KeyEvent) ev;
                if (ke.getID() != java.awt.event.KeyEvent.KEY_PRESSED) return;
                if (!PrimerEditTool.inEditMode()) return;
                Primer sel = PrimerStore.selected;
                if (sel == null) return;
                if (isTextInputFocused()) return;   // 在对话框/输入框打字时不拦截

                int step = ke.isShiftDown() ? 10 : 1;
                int horiz = 0, dRow = 0;
                switch (ke.getKeyCode()) {
                    case java.awt.event.KeyEvent.VK_LEFT:  horiz = -step; break;
                    case java.awt.event.KeyEvent.VK_RIGHT: horiz =  step; break;
                    case java.awt.event.KeyEvent.VK_UP:    dRow = -1; break;
                    case java.awt.event.KeyEvent.VK_DOWN:  dRow =  1; break;
                    default: return;
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
                    int base = sel.rowOverride != null ? sel.rowOverride
                            : (PrimerStore.screenRows.get(sel) != null ? PrimerStore.screenRows.get(sel) : 0);
                    sel.rowOverride = Math.max(0, base + dRow);
                }
                PrimerStore.evaluatePairs();
                PrimerStore.refresh();
                ke.consume();   // 阻止 IGV 原生方向键平移，避免双重响应
            }
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                keyListener, java.awt.AWTEvent.KEY_EVENT_MASK);
    }

    private static void removeKeyboard() {
        if (keyListener == null) return;
        java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(keyListener);
        keyListener = null;
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
        // 防重入：拖动/平移进行中忽略重复 press，否则拖动经过其他引物时目标会被切换
        if (dragging != null || panning) return;
        ReferenceFrame frame = frameOf(e);
        if (frame == null) return;
        final DataPanel dp = (DataPanel) e.getSource();
        activePanel = dp;
        String chr = frame.getChrName();
        int bp = bpAt(e, frame);
        int x = e.getX();
        int y = e.getY();

        // v0.1.19：点击落在 track 顶部工具条上 → 交给 PrimerTrack.handleDataClick 处理，不平移/不拖拽
        if (PrimerTrack.isInToolbar(x, y, chr)) return;

        dragging = null;
        mode = MODE_NONE;
        panning = false;

        Primer hit = findPrimerAt(x, y, chr);

        // v0.1.7/v0.1.11 Ctrl+点击 = 增量配对（1对1 或 1对多）；Ctrl+Shift+点击 = 仅解除所点击引物的配对
        // 命中引物时拦截，不进入拖拽流程
        if (hit != null && e.isControlDown()) {
            if (e.isShiftDown()) {
                // 只清点击引物所在的配对组（其伙伴同清），其余引物配对完全不受影响
                PrimerStore.unpairAllFor(hit);
                PrimerStore.selected = hit;
            } else {
                Primer sel = PrimerStore.selected;
                if (sel == null || sel == hit) {
                    PrimerStore.selected = hit;       // 首次点击只选定为锚点
                } else {
                    // 把点击引物并入锚点所在组（可连续 Ctrl+点击 多条 → 1v多，已配对的不会丢失）
                    PrimerStore.mergePair(sel, hit);
                    // 锚点保持选中，便于继续叠加
                }
            }
            e.consume();                              // 阻止 IGV 原生 pan/zoom 对 Ctrl 点击的响应
            dp.repaint();
            return;
        }

        if (hit != null) {
            java.awt.Rectangle r = PrimerStore.screenRects.get(hit);
            int xl = r.x;
            int xr = r.x + r.width;
            boolean edge = Math.abs(x - xl) <= EDGE_PX || Math.abs(x - xr) <= EDGE_PX;
            dragging = hit;
            origStart = hit.start;
            origEnd = hit.end;
            mode = edge ? (Math.abs(x - xl) <= EDGE_PX ? MODE_RESIZE_L : MODE_RESIZE_R) : MODE_MOVE;
            dragAnchorBp = bp;
            pressY = y;
            Integer r0 = PrimerStore.screenRows.get(hit);
            origRow = r0 == null ? 0 : r0;
            PrimerStore.selected = hit;
            dp.repaint();
            return;
        }
        // 空白：清除选中 + 委托原生 PanTool 平移视图
        if (PrimerStore.selected != null) {
            PrimerStore.selected = null;
            PrimerStore.refresh();
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
                java.awt.Rectangle r = PrimerStore.screenRects.get(p);
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
                AddPrimerDialog.show((int) Math.round(frame.getChromosomePosition(e.getX())), hit);
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (panning) {
            panTool((DataPanel) e.getSource()).mouseDragged(e);
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
        if (panning) {
            panning = false;
            panTool((DataPanel) e.getSource()).mouseReleased(e);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        clearGuides();
        if (dragging != null && mode != MODE_NONE) {
            boolean changed = dragging.start != origStart || dragging.end != origEnd;
            if (changed) {
                PrimerStore.refreshSequence(dragging);
                PrimerStore.evaluatePairs();
            }
            dragging = null;
            mode = MODE_NONE;
            PrimerStore.refresh();
        }
    }
}

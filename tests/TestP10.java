import org.broad.igv.primer.Primer;
import org.broad.igv.primer.PrimerEditTool;
import org.broad.igv.primer.PrimerStore;

import java.lang.reflect.Field;

/**
 * v0.1.68：快捷键套件（Del 删 / N 新建虚影放置 / Ctrl+配对 / E 编辑 / F 翻转 / V 定位 / Esc 取消）。
 *
 * GUI 路径（布防点击放置、Ctrl+点击配对、虚影跟手绘制）依赖 DataPanel/鼠标事件，headless
 * 无法直测；本测试覆盖可单测的核心逻辑：
 *  1) handleShortcutKey 分发：无选中时各键安全空转（不抛异常）、Esc 清选中；
 *  2) flipSelected：方向/角色翻转（F↔R、R1↔R2）；
 *  3) armNewPrimer headless 安全（无 DataPanel 时不进入布防、不抛异常）；
 *  4) cancelTempStates：清布防态 + 清选中；
 *  5) zoomToSelected：无面板时安全空转。
 */
public class TestP10 {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String msg) {
        if (ok) { pass++; System.out.println("PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }

    static Primer mk(String name, int start, int end, char strand) {
        Primer p = new Primer(name, "NC_000962.3", start, end, strand, strand == '+' ? "R1" : "R2",
                strand == '+' ? 20 : 18, null);
        p.group = "primer3";
        return p;
    }

    static void setNewArm(boolean v) throws Exception {
        Field f = PrimerEditTool.class.getDeclaredField("newArm");
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    static boolean newArm() throws Exception {
        Field f = PrimerEditTool.class.getDeclaredField("newArm");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    public static void main(String[] a) throws Exception {
        PrimerStore.autosaveEnabled = false;
        PrimerStore.clear();
        PrimerStore.selected = null;

        // ============ 1) 快捷键分发：无选中时安全空转 ============
        int[] keys = {
                java.awt.event.KeyEvent.VK_DELETE,
                java.awt.event.KeyEvent.VK_BACK_SPACE,
                java.awt.event.KeyEvent.VK_N,
                java.awt.event.KeyEvent.VK_E,
                java.awt.event.KeyEvent.VK_F,
                java.awt.event.KeyEvent.VK_V,
        };
        boolean allOk = true;
        for (int k : keys) {
            try {
                if (!PrimerEditTool.handleShortcutKey(k)) allOk = false;
            } catch (Throwable t) {
                allOk = false;
                System.out.println("  (exception on key " + k + ": " + t + ")");
            }
        }
        check(allOk, "无选中时 Del/N/E/F/V 全部安全空转且返回已处理");
        check(!PrimerEditTool.newPrimerArmed(), "headless 下 N 不进入布防（无数据面板）");
        check(PrimerEditTool.handleShortcutKey(java.awt.event.KeyEvent.VK_X) == false,
                "非快捷键键返回 false（不消费）");

        // ============ 2) Esc 清选中 ============
        Primer sel = mk("primer3_P1_F", 1000, 1020, '+');
        PrimerStore.selected = sel;
        check(PrimerEditTool.handleShortcutKey(java.awt.event.KeyEvent.VK_ESCAPE), "Esc 返回已处理");
        check(PrimerStore.selected == null, "Esc 清除选中");

        // ============ 3) flipSelected：方向/角色翻转 ============
        PrimerStore.selected = sel;
        check(sel.strand == '+' && "R1".equals(sel.role), "翻转前 F/R1");
        PrimerEditTool.flipSelected();
        check(sel.strand == '-' && "R2".equals(sel.role), "F→R 翻转后 strand=- role=R2");
        PrimerEditTool.flipSelected();
        check(sel.strand == '+' && "R1".equals(sel.role), "再翻回 F/R1");
        check(sel.start == 1000 && sel.end == 1020, "翻转不动位置");

        // ============ 4) cancelTempStates：清布防 + 清选中 ============
        setNewArm(true);
        PrimerStore.selected = sel;
        PrimerEditTool.cancelTempStates();
        check(!newArm(), "cancelTempStates 清布防态");
        check(!PrimerEditTool.newPrimerArmed(), "newPrimerArmed 同步false");
        check(PrimerStore.selected == null, "cancelTempStates 清选中");

        // ============ 5) zoomToSelected：无面板安全空转 ============
        PrimerStore.selected = sel;
        boolean zoomOk = true;
        try {
            PrimerEditTool.handleShortcutKey(java.awt.event.KeyEvent.VK_V);
        } catch (Throwable t) {
            zoomOk = false;
        }
        check(zoomOk, "zoomToSelected 无面板时不抛异常");
        check(PrimerStore.selected == sel, "V 定位不动选中");

        System.out.println("==== " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }
}

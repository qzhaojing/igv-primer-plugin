package org.broad.igv.primer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 引物操作历史（撤销/重做）。
 *
 * 设计：整库快照式——每次用户操作「开始前」把当前 PrimerStore 全量深拷贝一份压入撤销栈；
 * 撤销时把当前状态压入重做栈、再把栈顶快照还原回 PrimerStore。配对边按名字存储
 * （Primer.pairedWith 是 Set&lt;String&gt;），快照只需深拷贝引物对象即可完整还原连线关系。
 *
 * 容量上限 20 步（老板要求）。快照压栈即清空重做栈（线性历史，不做分支）。
 */
public class History {

    /** 撤销历史步数上限。 */
    public static final int CAP = 20;

    /** 一条快照：引物列表（独立深拷贝）+ 当时的选中引物名。 */
    private static class Snap {
        final List<Primer> primers;
        final String selName;
        Snap(List<Primer> p, String s) { primers = p; selName = s; }
    }

    private static final ArrayDeque<Snap> undoStack = new ArrayDeque<Snap>();
    private static final ArrayDeque<Snap> redoStack = new ArrayDeque<Snap>();

    /** 暂停记录（用于把多次内部变更合并为一步，如导入时的 removeGroup+addAll）。 */
    private static int suspendCount = 0;

    /** 把当前引物列表深拷贝为独立快照（每个元素都 .copy()，与后续 live 对象完全解耦）。 */
    private static List<Primer> deepCopy(List<Primer> src) {
        List<Primer> out = new ArrayList<Primer>(src.size());
        for (Primer p : src) out.add(p.copy());
        return out;
    }

    private static Snap snapNow() {
        List<Primer> p = PrimerStore.getPrimers();   // 新列表，但元素仍是 live 对象
        List<Primer> cp = deepCopy(p);                // 再逐元素深拷贝，彻底解耦
        String sel = (PrimerStore.selected != null) ? PrimerStore.selected.name : null;
        return new Snap(cp, sel);
    }

    /** 暂停记录（嵌套计数）。suspend 期间 recordUndo 为 no-op。 */
    public static synchronized void suspend() { suspendCount++; }

    /** 恢复记录。 */
    public static synchronized void resume() {
        suspendCount = Math.max(0, suspendCount - 1);
    }

    /**
     * 记录一次「操作前」快照（压入撤销栈，并清空重做栈）。
     * 须在真正改变 PrimerStore 状态之前调用。suspend 期间为 no-op。
     */
    public static synchronized void recordUndo() {
        if (suspendCount > 0) return;
        undoStack.addLast(snapNow());
        if (undoStack.size() > CAP) undoStack.removeFirst();
        redoStack.clear();
    }

    /** 丢弃最近一次快照（用于「点了但没改动」的拖拽，避免产生空撤销步）。 */
    public static synchronized void discardLast() {
        if (!undoStack.isEmpty()) undoStack.removeLast();
    }

    /** 撤销一步。 */
    public static synchronized void undo() {
        if (undoStack.isEmpty()) return;
        Snap cur = snapNow();
        Snap prev = undoStack.removeLast();
        PrimerStore.restoreSnapshot(prev.primers, prev.selName);
        redoStack.addLast(cur);
        if (redoStack.size() > CAP) redoStack.removeFirst();
        PrimerStore.refresh();
    }

    /** 重做一步。 */
    public static synchronized void redo() {
        if (redoStack.isEmpty()) return;
        Snap cur = snapNow();
        Snap nxt = redoStack.removeLast();
        PrimerStore.restoreSnapshot(nxt.primers, nxt.selName);
        undoStack.addLast(cur);
        if (undoStack.size() > CAP) undoStack.removeFirst();
        PrimerStore.refresh();
    }

    public static synchronized boolean canUndo() { return !undoStack.isEmpty(); }
    public static synchronized boolean canRedo() { return !redoStack.isEmpty(); }

    /** 清空历史（如导入/加载新文件后，旧历史对不上新状态）。 */
    public static synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}

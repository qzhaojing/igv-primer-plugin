package org.broad.igv.primer;

import javax.swing.*;
import java.awt.*;

/**
 * 透明全窗叠加层（glass pane）：拖拽引物时在 DataPanel 全高画红色虚线 ruler，
 * 对齐参考轨 / 其他 track。setEnabled(false) → 不拦截鼠标事件，穿透到下方 DataPanel。
 */
public class RulerGlass extends JComponent {
    public boolean active = false;
    public int[] xs;        // 线的 x（glass 坐标）
    public int yTop, yBot;  // 线的纵向范围（= DataPanel 在窗内的上下边界）
    public int[] guideBps;  // 对应基因组坐标（用于顶部标注）

    public RulerGlass() {
        setOpaque(false);
        setEnabled(false);  // 事件穿透，拖拽仍由下方 DataPanel 处理
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!active || xs == null) return;
        Graphics2D g2 = (Graphics2D) g;
        g2.setColor(new Color(255, 60, 60, 220));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 4f}, 0f));
        for (int x : xs) g2.drawLine(x, yTop, x, yBot);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.RED);
        g2.setFont(g2.getFont().deriveFont(10f));
        if (guideBps != null) {
            for (int i = 0; i < xs.length && i < guideBps.length; i++)
                g2.drawString(String.valueOf(guideBps[i] + 1), xs[i] + 2, yTop + 12);
        }
    }
}

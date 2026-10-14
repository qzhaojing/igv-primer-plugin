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

    // v0.1.68：N 键新建引物虚影（跟手半透明箭头）
    public boolean ghostActive = false;
    public int ghostX, ghostY, ghostW;   // glass 坐标（ghostY=鼠标所在行）
    public int ghostBp, ghostLen;        // 虚影中心 bp 与引物长度

    public RulerGlass() {
        setOpaque(false);
        setEnabled(false);  // 事件穿透，拖拽仍由下方 DataPanel 处理
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        // v0.1.68：新建虚影（淡绿圆角矩形 + 提示），先画避免被选区盖住
        if (ghostActive) {
            int h = 16;
            g2.setColor(new Color(60, 200, 90, 110));
            g2.fillRoundRect(ghostX, ghostY - h / 2, Math.max(4, ghostW), h, 5, 5);
            g2.setColor(new Color(20, 150, 60, 230));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(ghostX, ghostY - h / 2, Math.max(4, ghostW), h, 5, 5);
            g2.setFont(g2.getFont().deriveFont(10f));
            g2.drawString("新引物 " + ghostLen + "bp · 点击放置（Esc 取消）",
                    ghostX + 2, ghostY - h / 2 - 4);
        }
        if (!active || xs == null) return;
        int x1 = Math.min(xs[0], xs[xs.length - 1]);
        int x2 = Math.max(xs[0], xs[xs.length - 1]);
        // v0.1.65：起止之间画淡色半透明区域（"淡淡的选定区间"），上下沿 DataPanel 可见区
        g2.setColor(new Color(255, 205, 60, 38));
        g2.fillRect(x1, yTop, Math.max(1, x2 - x1), Math.max(1, yBot - yTop));
        // 两侧边线（实线，比区域深，圈出边界）
        g2.setColor(new Color(255, 150, 30, 220));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(x1, yTop, x1, yBot);
        g2.drawLine(x2, yTop, x2, yBot);
        // 顶部 bp 标注
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(200, 120, 10, 255));
        g2.setFont(g2.getFont().deriveFont(10f));
        if (guideBps != null) {
            for (int i = 0; i < xs.length && i < guideBps.length; i++)
                g2.drawString(String.valueOf(guideBps[i] + 1), xs[i] + 3, yTop + 12);
        }
    }
}

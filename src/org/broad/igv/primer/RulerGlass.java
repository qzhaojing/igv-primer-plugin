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

    // v0.1.82：PCR 设计实时预览（黄=产物区间，蓝=两段引物设计区）
    //   不再缓存像素坐标（旧法缩放/平移后高亮与 bp 脱节）——paintComponent 里每次经
    //   PrimerEditTool.pcrPreviewPaintCoords() 按当前视图实时换算，坐标随视图自动跟随。

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
        // v0.1.81：PCR 设计实时预览（即使无选区也绘制，故放在选区判断之前）
        // v0.1.82：坐标每次绘制时按当前视图实时换算 → 缩放/平移/滚动自动跟随
        int[] pv = PrimerEditTool.pcrPreviewPaintCoords();
        if (pv != null) {
            int yt = pv[6], yb = pv[7];
            // 产物区间：淡黄填充 + 棕边线
            g2.setColor(new Color(255, 205, 60, 38));
            g2.fillRect(pv[0], yt, Math.max(1, pv[1] - pv[0]), Math.max(1, yb - yt));
            g2.setColor(new Color(200, 120, 10, 255));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(pv[0], yt, pv[0], yb);
            g2.drawLine(pv[1], yt, pv[1], yb);
            // 两段设计区：蓝色填充 + 蓝边线（另一种颜色，区别于产物黄）
            g2.setColor(new Color(60, 130, 255, 70));
            g2.fillRect(pv[2], yt, Math.max(1, pv[3] - pv[2]), Math.max(1, yb - yt));
            g2.fillRect(pv[4], yt, Math.max(1, pv[5] - pv[4]), Math.max(1, yb - yt));
            g2.setColor(new Color(30, 90, 230, 230));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(pv[2], yt, pv[2], yb);
            g2.drawLine(pv[3], yt, pv[3], yb);
            g2.drawLine(pv[4], yt, pv[4], yb);
            g2.drawLine(pv[5], yt, pv[5], yb);
            // bp 标注（顶部；bp 本身不随视图变，坐标实时）
            int[] r = PrimerEditTool.pcrPreviewRegions();
            if (r != null) {
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(new Color(20, 120, 220, 255));
                g2.setFont(g2.getFont().deriveFont(10f));
                g2.drawString(String.valueOf(r[0] + 1), pv[0] + 3, yt + 12);
                g2.drawString(String.valueOf(r[1]), pv[1] + 3, yt + 12);
                g2.setColor(new Color(20, 70, 200, 255));
                g2.drawString(String.valueOf(r[2] + 1), pv[2] + 3, yt + 24);
                g2.drawString(String.valueOf(r[3]), pv[3] + 3, yt + 24);
                g2.drawString(String.valueOf(r[4] + 1), pv[4] + 3, yt + 24);
                g2.drawString(String.valueOf(r[5]), pv[5] + 3, yt + 24);
            }
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
        // v0.1.83：选区尺寸双箭头标注——箭头顶住选区两边，中间大字显示选区大小（自动 kb/bp）
        int dimY = yTop + 36;
        g2.setStroke(new BasicStroke(1.5f));
        g2.setColor(new Color(255, 90, 20, 255));
        int head = 6;
        g2.drawLine(x1, dimY, x2, dimY);                       // 横线
        g2.drawLine(x1, dimY, x1 + head, dimY - 3);            // 左箭头（指向左 = 顶住左缘）
        g2.drawLine(x1, dimY, x1 + head, dimY + 3);
        g2.drawLine(x2, dimY, x2 - head, dimY - 3);            // 右箭头（指向右 = 顶住右缘）
        g2.drawLine(x2, dimY, x2 - head, dimY + 3);
        int sizeBp = (guideBps != null && guideBps.length >= 2)
                ? Math.max(0, guideBps[1] - guideBps[0] + 1)   // 选区实际 bp 跨度
                : (x2 - x1);
        String sz = fmtSize(sizeBp);
        g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, 12f));
        java.awt.FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(sz), th = fm.getHeight();
        int tx = (x1 + x2) / 2;
        int bgX = tx - tw / 2 - 4, bgY = dimY - th / 2;
        g2.setColor(new Color(255, 255, 255, 215));            // 半透明白底，保证任何背景可读
        g2.fillRect(bgX, bgY, tw + 8, th);
        g2.setColor(new Color(220, 70, 10, 255));
        g2.drawString(sz, bgX + 4, bgY + fm.getAscent());
    }

    /** 选区大小格式化：≥1000bp 显示一位小数 kb（去 .0），否则显示 bp。例：1.1kb / 500bp / 10.5kb */
    private static String fmtSize(int bp) {
        if (bp >= 1000) {
            String s = String.format("%.1f", bp / 1000.0);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s + "kb";
        }
        return bp + "bp";
    }
}

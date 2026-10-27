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

    // v0.1.81：PCR 设计实时预览通道（黄=产物区间，蓝=两段引物设计区）
    public int[] pvProdXs;   // 2 个 x（产品区间左右边），glass 坐标
    public int[] pvBandXs;   // 4 个 x（左设计区[0,1]、右设计区[2,3]），glass 坐标
    public int pvYTop, pvYBot;
    public int[] pvProdBps;  // 2 个 bp（产品区间左右）
    public int[] pvBandBps;  // 4 个 bp（两段设计区）

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
        if (pvProdXs != null && pvProdXs.length >= 2) {
            int yt = pvYTop, yb = pvYBot;
            // 产物区间：淡黄填充 + 棕边线
            g2.setColor(new Color(255, 205, 60, 38));
            int pX1 = Math.min(pvProdXs[0], pvProdXs[1]), pX2 = Math.max(pvProdXs[0], pvProdXs[1]);
            g2.fillRect(pX1, yt, Math.max(1, pX2 - pX1), Math.max(1, yb - yt));
            g2.setColor(new Color(200, 120, 10, 255));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(pvProdXs[0], yt, pvProdXs[0], yb);
            g2.drawLine(pvProdXs[1], yt, pvProdXs[1], yb);
            // 两段设计区：蓝色填充 + 蓝边线（另一种颜色，区别于产物黄）
            if (pvBandXs != null && pvBandXs.length >= 4) {
                g2.setColor(new Color(60, 130, 255, 70));
                int aX1 = Math.min(pvBandXs[0], pvBandXs[1]), aX2 = Math.max(pvBandXs[0], pvBandXs[1]);
                int bX1 = Math.min(pvBandXs[2], pvBandXs[3]), bX2 = Math.max(pvBandXs[2], pvBandXs[3]);
                g2.fillRect(aX1, yt, Math.max(1, aX2 - aX1), Math.max(1, yb - yt));
                g2.fillRect(bX1, yt, Math.max(1, bX2 - bX1), Math.max(1, yb - yt));
                g2.setColor(new Color(30, 90, 230, 230));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(pvBandXs[0], yt, pvBandXs[0], yb);
                g2.drawLine(pvBandXs[1], yt, pvBandXs[1], yb);
                g2.drawLine(pvBandXs[2], yt, pvBandXs[2], yb);
                g2.drawLine(pvBandXs[3], yt, pvBandXs[3], yb);
            }
            // bp 标注（顶部）
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(20, 120, 220, 255));
            g2.setFont(g2.getFont().deriveFont(10f));
            if (pvProdBps != null && pvProdBps.length >= 2) {
                g2.drawString(String.valueOf(pvProdBps[0] + 1), pvProdXs[0] + 3, yt + 12);
                g2.drawString(String.valueOf(pvProdBps[1]), pvProdXs[1] + 3, yt + 12);
            }
            if (pvBandBps != null && pvBandBps.length >= 4) {
                g2.setColor(new Color(20, 70, 200, 255));
                g2.drawString(String.valueOf(pvBandBps[0] + 1), pvBandXs[0] + 3, yt + 24);
                g2.drawString(String.valueOf(pvBandBps[1]), pvBandXs[1] + 3, yt + 24);
                g2.drawString(String.valueOf(pvBandBps[2] + 1), pvBandXs[2] + 3, yt + 24);
                g2.drawString(String.valueOf(pvBandBps[3]), pvBandXs[3] + 3, yt + 24);
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
    }
}

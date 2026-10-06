package org.broad.igv.primer;

import org.broad.igv.ui.IGV;
import org.broad.igv.ui.panel.TrackPanel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.1.47：把引物轨写进 IGV session（.xml）并从 session 还原。
 *
 * 为什么不用 IGV 原生的 Track 序列化：SessionWriter 用 JAXB 按 Track 类族写出，引物轨会被当成
 * 普通 FeatureTrack，回来时没有数据源 → 打开即丢（用户长期现象）。这里改为在 session 根元素下
 * 追加一段自定义块：
 *
 *   <PrimerTracks version="1">
 *     <PrimerTrack group="primers"><![CDATA[<BED 正文，含 |p 配对边 / |r 布局行 / |c 颜色>]]></PrimerTrack>
 *   </PrimerTracks>
 *
 * 兼容性（用户要求）：IGV 的 IGVSessionReader.process() 是一串 else-if 逐元素名比对，
 * 未知元素名直接跳过 → 未修改的原版 IGV 打开这类 session 既不报错、也不显示引物轨，其余部分照常加载。
 *
 * 还原时机：SessionReader 不能被插件替换，因此这里由插件的守护定时器检测 session 路径变化，
 * 自行解析刚刚打开的那份 session 文件来还原（见 checkSessionSync）。
 */
public class PrimerSession {

    public static final String ROOT_TAG = "PrimerTracks";
    public static final String ITEM_TAG = "PrimerTrack";

    /** 被补丁版 SessionWriter 调用：把当前所有非空引物轨追加到 session 根元素下。 */
    public static void writeTo(Document doc, Element root) {
        try {
            if (doc == null || root == null) return;
            List<PrimerTrack> tracks = new ArrayList<PrimerTrack>(PrimerTrack.ALL);
            Element wrap = doc.createElement(ROOT_TAG);
            wrap.setAttribute("version", "1");
            int used = 0;
            for (PrimerTrack t : tracks) {
                String g = t.getGroup();
                List<Primer> list = PrimerStore.getPrimersByGroup(g);
                if (list.isEmpty()) continue;           // 空轨不写，避免回来多占一条空轨
                Element item = doc.createElement(ITEM_TAG);
                if (g != null) item.setAttribute("group", g);   // 无 group 属性 = 手动添加轨
                item.appendChild(doc.createCDATASection(ExportUtils.toBedText(list)));
                wrap.appendChild(item);
                used++;
            }
            if (used > 0) root.appendChild(wrap);
        } catch (Throwable ignore) {
            // 保存 session 绝不能因为引物写入失败而中断
        }
    }

    private static String lastSessionPath = null;

    /**
     * 守护定时器调用：当 IGV 当前 session 路径发生变化（=打开/切换到另一份 session）时，
     * 从该文件还原引物轨。session 中没有引物块则保持现状（不误删当前引物）。
     */
    public static synchronized void checkSessionSync(IGV igv) {
        try {
            if (igv == null) return;
            String path = null;
            if (igv.getSession() != null) path = igv.getSession().getPath();
            if (path == null || path.isEmpty()) { lastSessionPath = null; return; }
            if (path.equals(lastSessionPath)) return;   // 同一份 session 只还原一次
            lastSessionPath = path;
            restore(new File(path));
        } catch (Throwable ignore) {
        }
    }

    /** 解析 session 文件里的引物块（若存在），并把结果丢给 EDT 应用。返回还原的引物条数。 */
    public static int restore(File f) {
        try {
            if (f == null || !f.exists()) return 0;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(f);
            NodeList roots = doc.getElementsByTagName(ROOT_TAG);
            if (roots.getLength() == 0) return 0;
            // group -> BED 正文
            Map<String, String> textByGroup = new LinkedHashMap<String, String>();
            for (int i = 0; i < roots.getLength(); i++) {
                Element root = (Element) roots.item(i);
                NodeList items = root.getElementsByTagName(ITEM_TAG);
                for (int j = 0; j < items.getLength(); j++) {
                    Element it = (Element) items.item(j);
                    String g = it.getAttribute("group");
                    if (g == null || g.isEmpty()) g = null;
                    String txt = it.getTextContent();
                    if (txt == null || txt.trim().isEmpty()) continue;
                    String prev = textByGroup.get(g);
                    textByGroup.put(g, prev == null ? txt : prev + "\n" + txt);
                }
            }
            if (textByGroup.isEmpty()) return 0;
            final List<Object[]> entries = new ArrayList<Object[]>();
            int total = 0;
            for (Map.Entry<String, String> e : textByGroup.entrySet()) {
                List<Primer> out = new ArrayList<Primer>();
                int n = ExportUtils.parseBEDText(e.getValue(), e.getKey(), out);
                if (n > 0) { entries.add(new Object[]{e.getKey(), out}); total += n; }
            }
            if (entries.isEmpty()) return 0;
            SwingUtilities.invokeLater(new Runnable() {
                public void run() { apply(entries); }
            });
            return total;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** 应用还原：打开 session 语义等同替换 → 先清干净现有引物轨与数据，再按 group 逐条重建。 */
    private static void apply(List<Object[]> entries) {
        try {
            for (PrimerTrack t : new ArrayList<PrimerTrack>(PrimerTrack.ALL)) {
                try {
                    TrackPanel tp = TrackPanel.getParentPanel(t);
                    if (tp != null) tp.removeTracks(Collections.singletonList(t));
                } catch (Throwable ignore) { }
                PrimerPlugin.forgetTrack(t);   // 只注销，不触发兜底新建
            }
            PrimerStore.clear();
            for (Object[] en : entries) {
                String g = (String) en[0];
                @SuppressWarnings("unchecked")
                List<Primer> list = (List<Primer>) en[1];
                PrimerPlugin.ensureTrackForGroup(g);
                PrimerStore.addAll(list);
            }
            PrimerPlugin.ensureFallbackTrack();     // 保证「导入 BED」入口永在
            PrimerStore.refreshAll();
            try { IGV.getInstance().repaintDataPanels(); } catch (Throwable ignore) { }
        } catch (Throwable ignore) {
        }
    }
}

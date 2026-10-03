package org.broad.igv.primer;

import org.broad.igv.dev.api.NamedFeatureSearcher;
import org.broad.igv.feature.NamedFeature;
import org.broad.igv.ui.action.SearchCommand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * v0.1.42：把引物接入 IGV 顶栏搜索框。
 * 走官方扩展点 SearchCommand.registerNamedFeatureSearcher（IGV 2.3.80 源码验证：
 * parseToken 的 FEATURE 分支在 FeatureDB 精确未中后调用 comprehensiveFeatureSearch，
 * 后者遍历已注册 searcher 的 search(token, SEARCH_LIMIT)；结果 ≥2 个自动弹选择框）。
 *
 * 匹配语义（大小写不敏感）：
 *  - 引物名精确相等 → 唯一结果，直接跳转（如 fbiR-2）；
 *  - 否则引物名 前缀/包含 匹配（前缀优先排序）→ 多结果弹框选择；
 *    "包含"匹配同时覆盖「基因名」场景：输入 fbi 可列出 fbiF-1、fbiR-2…；
 *  - BED 分组名（=来源 BED 文件名）相等/包含 → 返回该组全部引物。
 */
public class PrimerSearcher implements NamedFeatureSearcher {

    private static boolean registered = false;

    /** 幂等注册（PrimerPlugin.init 调用）。SearchCommand 的 searcher 集合只在类初始化时清空，此后注册长期有效。 */
    public static synchronized void register() {
        if (registered) return;
        try {
            SearchCommand.registerNamedFeatureSearcher(new PrimerSearcher());
            registered = true;
        } catch (Throwable t) {
            System.err.println("[PrimerSearcher] register failed: " + t);
        }
    }

    public Collection<? extends NamedFeature> search(String s, int limit) {
        List<NamedFeature> out = new ArrayList<NamedFeature>();
        if (s == null) return out;
        final String q = s.trim().toLowerCase();
        if (q.isEmpty()) return out;
        int cap = Math.max(1, limit);

        List<Primer> all = PrimerStore.getPrimers();
        List<Primer> hits = new ArrayList<Primer>();
        for (Primer p : all) {
            String nm = p.name == null ? "" : p.name.toLowerCase();
            String grp = p.group == null ? "" : p.group.toLowerCase();
            if (nm.equals(q)) {                      // 精确命中：唯一返回，IGV 直接跳转不弹框
                out.add(new PrimerFeature(p));
                return out;
            }
            if (nm.startsWith(q) || nm.contains(q)
                    || grp.equals(q) || grp.contains(q)) {
                hits.add(p);
            }
        }
        // 排序：引物名前缀命中 > 包含命中 > 组名命中；同级按名称字典序
        java.util.Collections.sort(hits, new java.util.Comparator<Primer>() {
            public int compare(Primer a, Primer b) {
                int sa = rank(a), sb = rank(b);
                if (sa != sb) return sa < sb ? -1 : 1;
                return String.valueOf(a.name).compareTo(String.valueOf(b.name));
            }
            private int rank(Primer p) {
                String nm = p.name == null ? "" : p.name.toLowerCase();
                if (nm.startsWith(q)) return 0;
                if (nm.contains(q)) return 1;
                return 2;   // 仅组名命中
            }
        });
        for (Primer p : hits) {
            if (out.size() >= cap) break;
            out.add(new PrimerFeature(p));
        }
        return out;
    }
}

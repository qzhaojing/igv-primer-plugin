package org.broad.igv.primer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.1.56：Primer3 2.x 核心封装。
 *
 * primer3_core.exe 随插件 jar 分发（resources/org/broad/igv/primer/primer3_core.exe，Primer3 2.x，
 * 约 1MB 静态编译、无外部 DLL 依赖），首次运行释放到 user.home/.igv_primer3/ 并以 md5 校验，
 * 损坏/版本不符自动重写。通信走标准 Boulder IO（stdin 写 KEY=VALUE 行 + '=' 结束，stdout 读回
 * 引物对结果），纯 ASCII，无编码坑。单次调用约 17ms，可连续批量调用。
 *
 * 线程安全：ensureExe 加锁；design 本身无共享状态，可多线程（对话框在 EDT 同步调用即可）。
 */
public class Primer3Core {

    private static final String RES = "/org/broad/igv/primer/primer3_core.exe";
    private static final String MD5 = "55eb03d35a656a0e291bed5d21e27ea2";
    private static File cached;

    /** 释放（必要时）并返回 exe 文件。jar 内资源缺失或写出失败抛 IOException。 */
    public static synchronized File ensureExe() throws IOException {
        if (cached != null && cached.isFile()) return cached;
        InputStream in = Primer3Core.class.getResourceAsStream(RES);
        if (in == null) throw new IOException("jar 内未找到 primer3_core.exe 资源（" + RES + "）");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        byte[] data = bos.toByteArray();
        File dir = new File(System.getProperty("user.home"), ".igv_primer3");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录: " + dir);
        }
        File exe = new File(dir, "primer3_core.exe");
        if (!exe.isFile() || !MD5.equals(md5(data)) || !MD5.equals(md5Of(exe))) {
            OutputStream out = new FileOutputStream(exe);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            exe.setExecutable(true, true);
        }
        cached = exe;
        return exe;
    }

    /** exe 是否可用（资源在、能释放）。供入口处提前检查并给出友好提示。 */
    public static boolean available() {
        try {
            ensureExe();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设计引物（成对或单条，由 params 中的 PRIMER_PICK_LEFT/RIGHT_PRIMER 决定）。
     *
     * @param id        SEQUENCE_ID（会过滤非法字符）
     * @param template  模板序列（自动转大写）
     * @param params    附加参数（长度/Tm/GC/产物范围/OK_REGION 等）；PICK flags 缺省 1/1（成对）
     * @return 按 primer3 排序的结果列表；无解返回空列表（退出码仍为 0，须判 *_NUM_RETURNED=0）
     */
    public static List<Pair> design(String id, String template, Map<String, String> params) throws IOException {
        File exe = ensureExe();
        String safeId = (id == null ? "p3" : id.replaceAll("[^A-Za-z0-9_.-]", "_"));
        if (safeId.isEmpty()) safeId = "p3";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SEQUENCE_ID=").append(safeId).append('\n');
        sb.append("SEQUENCE_TEMPLATE=").append(template.toUpperCase()).append('\n');
        sb.append("PRIMER_TASK=generic\n");
        // v0.1.57：单引物模式由调用方通过 params 传 PICK flags；缺省成对
        sb.append("PRIMER_PICK_LEFT_PRIMER=").append(pick(params, "PRIMER_PICK_LEFT_PRIMER", 1)).append('\n');
        sb.append("PRIMER_PICK_RIGHT_PRIMER=").append(pick(params, "PRIMER_PICK_RIGHT_PRIMER", 1)).append('\n');
        sb.append("PRIMER_PICK_INTERNAL_OLIGO=0\n");
        sb.append("PRIMER_EXPLAIN_FLAG=1\n");
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if ("PRIMER_PICK_LEFT_PRIMER".equals(e.getKey())
                        || "PRIMER_PICK_RIGHT_PRIMER".equals(e.getKey())) continue;   // 已写，避免重复
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        sb.append("=\n");

        Process p = new ProcessBuilder(exe.getAbsolutePath()).start();
        try {
            OutputStream os = p.getOutputStream();
            os.write(sb.toString().getBytes("US-ASCII"));
            os.close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            int rc = p.waitFor();
            if (rc != 0) {
                throw new IOException("primer3_core 退出码 " + rc);
            }
            return parse(bos.toString("US-ASCII"));
        } catch (InterruptedException ie) {
            p.destroy();
            throw new IOException("primer3_core 被中断");
        } finally {
            p.getErrorStream().close();
        }
    }

    private static int pick(Map<String, String> params, String key, int def) {
        if (params == null) return def;
        String v = params.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** v0.1.60：最近一次 parse 的 primer3 EXPLAIN 诊断（无解原因：considered/ok 统计）；无则 null。 */
    public static String lastExplain = null;

    /**
     * 解析 Boulder IO 输出（KEY=VALUE 行）。
     * v0.1.57：兼容单引物模式——单左/单右时 PAIR_NUM_RETURNED=0 且对侧字段整体缺失，
     * 故按字段存在性解析：有 PRIMER_LEFT_i 才解左、有 PRIMER_RIGHT_i 才解右，两侧全无才跳过。
     */
    public static List<Pair> parse(String out) {
        Map<String, String> d = new HashMap<String, String>();
        for (String line : out.split("\r\n|\r|\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            d.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        // v0.1.60：提取 EXPLAIN 诊断（considered N, low tm N, ok n），无解时告知用户真实原因
        lastExplain = null;
        for (String k : new String[]{"PRIMER_LEFT_EXPLAIN", "PRIMER_RIGHT_EXPLAIN", "PRIMER_PAIR_EXPLAIN"}) {
            String v = d.get(k);
            if (v != null && !v.isEmpty()) {
                String tag = k.replace("PRIMER_", "").replace("_EXPLAIN", "");
                lastExplain = (lastExplain == null ? "" : lastExplain + "\n") + tag + ": " + v;
            }
        }
        // v0.1.61：primer3 输入非法（如 tag 值格式错误）时输出 PRIMER_ERROR 且不产出任何结果，
        // 退出码仍为 0。此前被静默吞掉 → 对话框只显示"未找到合适引物对"根本无法排查
        // （v0.1.56 的 SEQUENCE_PRIMER_PAIR_OK_REGION_LIST 单区格式 bug 正是这样藏了多个版本）。
        String perr = d.get("PRIMER_ERROR");
        if (perr != null && !perr.isEmpty()) {
            lastExplain = "primer3 报错: " + perr
                    + (lastExplain == null || lastExplain.isEmpty() ? "" : "\n" + lastExplain);
        }
        // 结果条数 = 成对/单左/单右各自 NUM_RETURNED 的最大值（单引物模式 PAIR_N=0）
        int n = 0;
        for (String k : new String[]{"PRIMER_PAIR_NUM_RETURNED", "PRIMER_LEFT_NUM_RETURNED", "PRIMER_RIGHT_NUM_RETURNED"}) {
            try {
                n = Math.max(n, Integer.parseInt(d.get(k)));
            } catch (Exception ignore) {
            }
        }
        List<Pair> res = new ArrayList<Pair>();
        for (int i = 0; i < n; i++) {
            try {
                String ls = d.get("PRIMER_LEFT_" + i);
                String rs = d.get("PRIMER_RIGHT_" + i);
                String lseq = d.get("PRIMER_LEFT_" + i + "_SEQUENCE");
                String rseq = d.get("PRIMER_RIGHT_" + i + "_SEQUENCE");
                boolean hasL = ls != null && lseq != null;
                boolean hasR = rs != null && rseq != null;
                if (!hasL && !hasR) continue;   // 该序号两侧都无结果
                Pair pr = new Pair();
                pr.idx = i;
                if (hasL) {
                    int[] l = parseStartLen(ls);
                    pr.leftStart = l[0];
                    pr.leftLen = l[1];
                    pr.leftSeq = lseq;
                    pr.leftTm = dbl(d.get("PRIMER_LEFT_" + i + "_TM"));
                    pr.leftGc = dbl(d.get("PRIMER_LEFT_" + i + "_GC_PERCENT"));
                    pr.hairpinL = dbl(d.get("PRIMER_LEFT_" + i + "_HAIRPIN_TH"));
                    pr.selfAnyL = dbl(d.get("PRIMER_LEFT_" + i + "_SELF_ANY_TH"));
                    pr.selfEndL = dbl(d.get("PRIMER_LEFT_" + i + "_SELF_END_TH"));
                    pr.leftEndStab = dbl(d.get("PRIMER_LEFT_" + i + "_END_STABILITY"));
                    pr.leftWarn = d.get("PRIMER_LEFT_" + i + "_WARNING");
                }
                if (hasR) {
                    int[] r = parseStartLen(rs);
                    pr.rightStart = r[0];
                    pr.rightLen = r[1];
                    pr.rightSeq = rseq;
                    pr.rightTm = dbl(d.get("PRIMER_RIGHT_" + i + "_TM"));
                    pr.rightGc = dbl(d.get("PRIMER_RIGHT_" + i + "_GC_PERCENT"));
                    pr.hairpinR = dbl(d.get("PRIMER_RIGHT_" + i + "_HAIRPIN_TH"));
                    pr.selfAnyR = dbl(d.get("PRIMER_RIGHT_" + i + "_SELF_ANY_TH"));
                    pr.selfEndR = dbl(d.get("PRIMER_RIGHT_" + i + "_SELF_END_TH"));
                    pr.rightEndStab = dbl(d.get("PRIMER_RIGHT_" + i + "_END_STABILITY"));
                    pr.rightWarn = d.get("PRIMER_RIGHT_" + i + "_WARNING");
                }
                if (hasL && hasR) {
                    pr.productSize = intOr(d.get("PRIMER_PAIR_" + i + "_PRODUCT_SIZE"), 0);
                    pr.complAny = dbl(d.get("PRIMER_PAIR_" + i + "_COMPL_ANY_TH"));
                    pr.complEnd = dbl(d.get("PRIMER_PAIR_" + i + "_COMPL_END_TH"));
                    pr.pairWarn = d.get("PRIMER_PAIR_" + i + "_WARNING");
                }
                res.add(pr);
            } catch (Exception skipOne) {
                // 单对坐标/序列字段损坏：跳过该对，不影响其他对
            }
        }
        return res;
    }

    private static int intOr(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (Exception e) {
            return def;
        }
    }

    private static int[] parseStartLen(String s) {
        int i = s.indexOf(',');
        return new int[]{Integer.parseInt(s.substring(0, i)), Integer.parseInt(s.substring(i + 1))};
    }

    private static double dbl(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5Of(File f) {
        try {
            InputStream in = new java.io.FileInputStream(f);
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                byte[] d = md.digest();
                StringBuilder sb = new StringBuilder(32);
                for (int i = 0; i < d.length; i++) {
                    int v = d[i] & 0xFF;
                    if (v < 16) sb.append('0');
                    sb.append(Integer.toHexString(v));
                }
                return sb.toString();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 一对设计结果（模板相对坐标 start,len 与基因组坐标的换算由调用方负责）。
     *  v0.1.57：单引物模式下只有一侧有值（leftSeq/rightSeq 其一为 null）。 */
    public static class Pair {
        public int idx;
        public String leftSeq, rightSeq;
        public int leftStart, leftLen, rightStart, rightLen;   // 模板 0-based 坐标（缺失侧为 -1/0）
        public double leftTm, rightTm, leftGc, rightGc;
        public int productSize;
        public double complAny, complEnd;                       // 配对二聚体（单引物模式无）
        public double hairpinL, hairpinR;
        public double selfAnyL, selfEndL, selfAnyR, selfEndR;
        public double leftEndStab, rightEndStab;                // 3' 端稳定性（ΔG，浮点）
        public String leftWarn, rightWarn, pairWarn;

        /** 是否单条结果（单引物模式：仅 F 或仅 R）。 */
        public boolean isSingle() {
            return (leftSeq == null) != (rightSeq == null);
        }

        /** 方向标签：F / R / 成对。 */
        public String dirLabel() {
            if (leftSeq == null) return "R";
            if (rightSeq == null) return "F";
            return "F+R";
        }

        /** 转成 Primer 列表（成对=2 条 F(+) / R(- 互链；单引物=1 条，strand 按存在侧）。
         *  tmplOffset=模板起点在基因组上的偏移。 */
        public List<Primer> toPrimers(String chr, String group, int tmplOffset) {
            return toPrimers(chr, group, tmplOffset, null);
        }

        /**
         * 同上；used 非 null 时按集合去重（冲突自动加 _2/_3 后缀并占用），
         * 防止同分组重复设计时引物名撞车导致 linkByName 配错线。
         */
        public List<Primer> toPrimers(String chr, String group, int tmplOffset, Set<String> used) {
            List<Primer> out = new ArrayList<Primer>();
            if (leftSeq != null) {
                String fn = unique(group + "_P" + (idx + 1) + "_F", used);
                Primer f = new Primer(fn, chr, tmplOffset + leftStart, tmplOffset + leftStart + leftLen,
                        '+', "R1", leftLen, null);
                f.group = group;
                f.seq = leftSeq;
                f.tm = leftTm;
                f.gc = leftGc;
                f.hairpinDG = hairpinL;
                f.selfDG = selfAnyL;
                f.heteroDG = Double.isNaN(complAny) ? 0 : complAny;
                f.max3pComp = 0;
                out.add(f);
                if (rightSeq != null) {
                    // 成对：补 R 并互链
                    String rn = unique(group + "_P" + (idx + 1) + "_R", used);
                    Primer r = new Primer(rn, chr, tmplOffset + rightStart, tmplOffset + rightStart + rightLen,
                            '-', "R2", rightLen, null);
                    r.group = group;
                    r.seq = rightSeq;
                    r.tm = rightTm;
                    r.gc = rightGc;
                    r.hairpinDG = hairpinR;
                    r.selfDG = selfAnyR;
                    r.heteroDG = f.heteroDG;
                    r.max3pComp = 0;
                    f.pairedWith.add(rn);
                    r.pairedWith.add(fn);
                    out.add(r);
                }
            } else if (rightSeq != null) {
                // 仅 R 向单引物
                String rn = unique(group + "_P" + (idx + 1) + "_R", used);
                Primer r = new Primer(rn, chr, tmplOffset + rightStart, tmplOffset + rightStart + rightLen,
                        '-', "R2", rightLen, null);
                r.group = group;
                r.seq = rightSeq;
                r.tm = rightTm;
                r.gc = rightGc;
                r.hairpinDG = hairpinR;
                r.selfDG = selfAnyR;
                r.heteroDG = Double.isNaN(complAny) ? 0 : complAny;
                r.max3pComp = 0;
                out.add(r);
            }
            return out;
        }

        private static String unique(String base, Set<String> used) {
            if (used == null) return base;
            if (!used.contains(base)) {
                used.add(base);
                return base;
            }
            for (int i = 2; i < 1000; i++) {
                String c = base + "_" + i;
                if (!used.contains(c)) {
                    used.add(c);
                    return c;
                }
            }
            String c = base + "_" + (System.currentTimeMillis() % 100000);
            used.add(c);
            return c;
        }
    }
}

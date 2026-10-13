package org.broad.igv.primer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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
     * 设计引物对。
     *
     * @param id        SEQUENCE_ID（会过滤非法字符）
     * @param template  模板序列（自动转大写）
     * @param params    附加参数（长度/Tm/GC/产物范围/OK_REGION 等），null=仅默认
     * @return 按 primer3 排序的引物对列表；无解返回空列表（退出码仍为 0，须判 PAIR_NUM_RETURNED）
     */
    public static List<Pair> design(String id, String template, Map<String, String> params) throws IOException {
        File exe = ensureExe();
        String safeId = (id == null ? "p3" : id.replaceAll("[^A-Za-z0-9_.-]", "_"));
        if (safeId.isEmpty()) safeId = "p3";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SEQUENCE_ID=").append(safeId).append('\n');
        sb.append("SEQUENCE_TEMPLATE=").append(template.toUpperCase()).append('\n');
        sb.append("PRIMER_TASK=generic\n");
        sb.append("PRIMER_PICK_LEFT_PRIMER=1\n");
        sb.append("PRIMER_PICK_RIGHT_PRIMER=1\n");
        sb.append("PRIMER_PICK_INTERNAL_OLIGO=0\n");
        sb.append("PRIMER_EXPLAIN_FLAG=1\n");
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
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

    /** 解析 Boulder IO 输出（KEY=VALUE 行，记录间以 '=' 分隔；单记录只取第一段）。 */
    public static List<Pair> parse(String out) {
        Map<String, String> d = new HashMap<String, String>();
        for (String line : out.split("\r\n|\r|\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            d.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        int nPairs = 0;
        try {
            nPairs = Integer.parseInt(d.get("PRIMER_PAIR_NUM_RETURNED"));
        } catch (Exception ignore) {
        }
        List<Pair> res = new ArrayList<Pair>();
        for (int i = 0; i < nPairs; i++) {
            try {
                // 必需字段缺失才跳过整对；其余字段宽松解析（版本差异/缺字段给 NaN，不吞对）
                String ls = d.get("PRIMER_LEFT_" + i);
                String rs = d.get("PRIMER_RIGHT_" + i);
                String lseq = d.get("PRIMER_LEFT_" + i + "_SEQUENCE");
                String rseq = d.get("PRIMER_RIGHT_" + i + "_SEQUENCE");
                if (ls == null || rs == null || lseq == null || rseq == null) continue;
                Pair pr = new Pair();
                pr.idx = i;
                int[] l = parseStartLen(ls);
                int[] r = parseStartLen(rs);
                pr.leftStart = l[0];
                pr.leftLen = l[1];
                pr.rightStart = r[0];
                pr.rightLen = r[1];
                pr.leftSeq = lseq;
                pr.rightSeq = rseq;
                pr.leftTm = dbl(d.get("PRIMER_LEFT_" + i + "_TM"));
                pr.rightTm = dbl(d.get("PRIMER_RIGHT_" + i + "_TM"));
                pr.leftGc = dbl(d.get("PRIMER_LEFT_" + i + "_GC_PERCENT"));
                pr.rightGc = dbl(d.get("PRIMER_RIGHT_" + i + "_GC_PERCENT"));
                pr.productSize = intOr(d.get("PRIMER_PAIR_" + i + "_PRODUCT_SIZE"), 0);
                pr.complAny = dbl(d.get("PRIMER_PAIR_" + i + "_COMPL_ANY_TH"));
                pr.complEnd = dbl(d.get("PRIMER_PAIR_" + i + "_COMPL_END_TH"));
                pr.hairpinL = dbl(d.get("PRIMER_LEFT_" + i + "_HAIRPIN_TH"));
                pr.hairpinR = dbl(d.get("PRIMER_RIGHT_" + i + "_HAIRPIN_TH"));
                pr.selfAnyL = dbl(d.get("PRIMER_LEFT_" + i + "_SELF_ANY_TH"));
                pr.selfEndL = dbl(d.get("PRIMER_LEFT_" + i + "_SELF_END_TH"));
                pr.selfAnyR = dbl(d.get("PRIMER_RIGHT_" + i + "_SELF_ANY_TH"));
                pr.selfEndR = dbl(d.get("PRIMER_RIGHT_" + i + "_SELF_END_TH"));
                pr.leftEndStab = dbl(d.get("PRIMER_LEFT_" + i + "_END_STABILITY"));
                pr.rightEndStab = dbl(d.get("PRIMER_RIGHT_" + i + "_END_STABILITY"));
                pr.leftWarn = d.get("PRIMER_LEFT_" + i + "_WARNING");
                pr.rightWarn = d.get("PRIMER_RIGHT_" + i + "_WARNING");
                pr.pairWarn = d.get("PRIMER_PAIR_" + i + "_WARNING");
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

    /** 一对设计结果（模板相对坐标 start,len 与基因组坐标的换算由调用方负责）。 */
    public static class Pair {
        public int idx;
        public String leftSeq, rightSeq;
        public int leftStart, leftLen, rightStart, rightLen;   // 模板 0-based 坐标
        public double leftTm, rightTm, leftGc, rightGc;
        public int productSize;
        public double complAny, complEnd;                       // 配对二聚体（双方共享）
        public double hairpinL, hairpinR;
        public double selfAnyL, selfEndL, selfAnyR, selfEndR;
        public double leftEndStab, rightEndStab;                // 3' 端稳定性（ΔG，浮点）
        public String leftWarn, rightWarn, pairWarn;

        /** 转成两个 Primer（F=+ / R=-，pairedWith 互链）。tmplOffset=模板起点在基因组上的偏移。 */
        public List<Primer> toPrimers(String chr, String group, int tmplOffset) {
            return toPrimers(chr, group, tmplOffset, null);
        }

        /**
         * 同上；used 非 null 时按集合去重（冲突自动加 _2/_3 后缀并占用），
         * 防止同分组重复设计时引物名撞车导致 linkByName 配错线。
         */
        public List<Primer> toPrimers(String chr, String group, int tmplOffset, Set<String> used) {
            String fn = unique(group + "_P" + (idx + 1) + "_F", used);
            String rn = unique(group + "_P" + (idx + 1) + "_R", used);
            Primer f = new Primer(fn, chr, tmplOffset + leftStart, tmplOffset + leftStart + leftLen,
                    '+', "R1", leftLen, null);
            Primer r = new Primer(rn, chr, tmplOffset + rightStart, tmplOffset + rightStart + rightLen,
                    '-', "R2", rightLen, null);
            f.group = group;
            r.group = group;
            f.seq = leftSeq;
            r.seq = rightSeq;
            f.tm = leftTm;
            r.tm = rightTm;
            f.gc = leftGc;
            r.gc = rightGc;
            f.hairpinDG = hairpinL;
            r.hairpinDG = hairpinR;
            f.selfDG = selfAnyL;
            r.selfDG = selfAnyR;
            f.heteroDG = Double.isNaN(complAny) ? 0 : complAny;
            r.heteroDG = f.heteroDG;
            f.max3pComp = 0;
            r.max3pComp = 0;
            f.pairedWith.add(rn);
            r.pairedWith.add(fn);
            return Arrays.asList(f, r);
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

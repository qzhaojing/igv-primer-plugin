package org.broad.igv.primer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * MFEprimer 命令行封装：把引物写成 FASTA、调用各子命令（dimer/spec/coverage/qc）、
 * 捕获 stdout 与输出文件内容返回给上层解析展示。
 *
 * 注意：不同 MFEprimer 大版本 CLI 标志有差异。这里用 4.x 文档默认参数，且一律
 * 返回原始输出文本，解析只做"尽力而为"——即便版本标志对不上，用户也能在报告里
 * 看到原始输出，我们再据此微调命令行。
 */
public class MfeCli {

    /** 把引物写成 MFEprimer 吃的 FASTA（id 用引物名净化后的安全串）。 */
    public static File writePrimerFasta(List<Primer> primers) throws Exception {
        File f = File.createTempFile("mfe_primers_", ".fa");
        f.deleteOnExit();
        BufferedWriter w = new BufferedWriter(new FileWriter(f));
        int n = 0;
        for (Primer p : primers) {
            if (p.seq == null || p.seq.length() < 4) continue;
            w.write(">" + safeId(p.name) + "\n" + p.seq + "\n");
            n++;
        }
        w.close();
        if (n == 0) return null;
        return f;
    }

    private static String safeId(String s) {
        if (s == null) return "primer";
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /**
     * 运行一个 MFEprimer 子命令。
     * @return String[2] = { stdout/err 文本, 输出文件文本（无则为 ""） }
     */
    public static String[] run(String sub, File inFasta, File genomeFasta, File outFile,
                              List<String> extra) throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(PrimerStore.mfeExePath.trim());
        cmd.add(sub);
        cmd.add("-i");
        cmd.add(inFasta.getAbsolutePath());
        if (genomeFasta != null) {
            cmd.add("-d");
            cmd.add(genomeFasta.getAbsolutePath());
        }
        if (outFile != null) {
            cmd.add("-o");
            cmd.add(outFile.getAbsolutePath());
        }
        if (extra != null) cmd.addAll(extra);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process pr = pb.start();
        String out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            out = sb.toString();
        }
        boolean ok = false;
        try {
            ok = pr.waitFor(600, java.util.concurrent.TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (Exception ignore) {}
        String fileOut = "";
        if (outFile != null && outFile.exists()) {
            fileOut = new String(Files.readAllBytes(outFile.toPath()), "UTF-8");
        }
        if (!ok) {
            out = out + "\n[进程退出码=" + pr.exitValue() + "]\n"
                    + "若提示命令/参数不被识别，请核对你的 MFEprimer 版本（不同大版本标志不同）。";
        }
        return new String[]{ out, fileOut };
    }

    /** 默认参数：特异性检查（pcr 口径，产物 50–1000bp）。 */
    public static List<String> defaultSpecArgs() {
        List<String> a = new ArrayList<String>();
        a.add("-s"); a.add("50");
        a.add("-S"); a.add("1000");
        a.add("-c"); a.add("8");
        return a;
    }

    // ---------------- v0.1.98：子命令能力探测（不同 MFEprimer 大版本子命令差异大） ----------------
    // 4.x 把 spec/coverage 合并进了 pcr；旧版/其它构建可能根本没有 coverage。
    // 启动时跑一次 `mfeprimer --help` 解析出支持的子命令集合，不支持的按钮置灰，并允许 spec/coverage 回退到 pcr。

    private static volatile boolean probed = false;
    private static volatile java.util.Set<String> probedSubs = new java.util.HashSet<String>();
    private static volatile String probeVer = "";

    /** 是否已探测过（路径设置后触发一次后台探测）。 */
    public static boolean isProbed() { return probed; }

    /** 强制（重新）探测当前 MFEprimer 支持的子命令，结果缓存。 */
    public static void forceProbe() {
        synchronized (MfeCli.class) {
            probeInternal();
            probed = true;
        }
    }

    private static void probeInternal() {
        probedSubs = new java.util.HashSet<String>();
        probeVer = "";
        if (PrimerStore.mfeExePath == null || PrimerStore.mfeExePath.trim().isEmpty()) return;
        String help = runProbe("--help");
        if (help == null) return;
        String lower = help.toLowerCase();
        // 候选子命令：在 help 文本中出现即视为支持（包含匹配，足够稳健）
        for (String c : new String[]{"dimer","hairpin","spec","coverage","qc","pcr"}) {
            if (lower.contains(c)) probedSubs.add(c);
        }
        // 版本号（形如 MFEprimer version 4.0.0 / v3.2.1）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(?:version|mfeprimer)[^\\n]*?([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)")
                .matcher(help);
        if (m.find()) probeVer = m.group(1);
    }

    private static String runProbe(String arg) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(PrimerStore.mfeExePath.trim());
            cmd.add(arg);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    /**
     * 解析实际应调用的子命令：
     *  - 自身支持 → 返回自身；
     *  - spec/coverage 不支持但 pcr 支持 → 回退到 pcr（4.x 用 pcr 同时做 spec+coverage）；
     *  - 都不支持 → 返回 null（调用方置灰/提示）。
     */
    public static String resolveSub(String sub) {
        java.util.Set<String> s = probedSubs;
        if (s.contains(sub)) return sub;
        if (("spec".equals(sub) || "coverage".equals(sub)) && s.contains("pcr")) return "pcr";
        return null;
    }

    public static String probeVersion() { return probeVer; }

    /** 人类可读的已支持子命令列表（供面板状态栏显示）。 */
    public static String supportedSummary() {
        StringBuilder sb = new StringBuilder();
        for (String c : new String[]{"dimer","hairpin","spec","coverage","qc","pcr"}) {
            if (probedSubs.contains(c)) { if (sb.length() > 0) sb.append(" "); sb.append(c); }
        }
        return sb.toString();
    }
}

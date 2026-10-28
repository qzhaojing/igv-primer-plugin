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
}

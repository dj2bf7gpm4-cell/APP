package dev.shellmcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 本机 shell 执行。root 时经 su 提权，并显式写死完整 PATH。 */
public final class Shell {

    // 有些设备 su 不设 PATH（甚至为空），导致 root 下命令大面积找不到。
    // 显式写死：系统各目录在前，Termux 目录兜底，两边命令都能用。
    private static final String ANDROID_PATH =
            "/system/bin:/system/xbin:/system_ext/bin:/product/bin:"
          + "/apex/com.android.runtime/bin:/vendor/bin:/vendor/xbin:/odm/bin:"
          + "/data/data/com.termux/files/usr/bin";

    private Shell() {}

    public static String run(String command, boolean root, int timeoutSec) {
        if (timeoutSec <= 0) timeoutSec = 30;
        String[] argv;
        if (root) {
            String wrapped = "export PATH=\"" + ANDROID_PATH + ":$PATH\"; " + command;
            argv = new String[]{suPath(), "-c", wrapped};
        } else {
            argv = new String[]{"/system/bin/sh", "-c", command};
        }

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);  // stderr 合并进 stdout，避免读取死锁
            p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 2_000_000) {  // 2MB 上限，防止被刷爆
                        sb.append("\n[输出过长已截断]");
                        break;
                    }
                }
            }

            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return "ERROR: 命令超时（>" + timeoutSec + "s）";
            }
            int code = p.exitValue();
            String out = sb.toString().trim();
            if (code != 0) {
                out = (out.isEmpty() ? "" : out + "\n") + "[exit code] " + code;
            }
            return out.isEmpty() ? "(无输出)" : out;

        } catch (Exception e) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignore) {}
            }
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (root && (msg.contains("su") || msg.contains("ENOENT") || msg.contains("error=2"))) {
                return "ERROR: 无法执行 su（设备未 root 或未授权）：" + msg;
            }
            return "ERROR: " + e.getClass().getSimpleName() + ": " + msg;
        }
    }

    public static String suPath() {
        String[] cands = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String c : cands) {
            if (c.indexOf('/') >= 0 && new java.io.File(c).exists()) return c;
        }
        return "su";
    }

    public static boolean hasSu() {
        String[] cands = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"};
        for (String c : cands) {
            if (new java.io.File(c).exists()) return true;
        }
        return false;
    }
}

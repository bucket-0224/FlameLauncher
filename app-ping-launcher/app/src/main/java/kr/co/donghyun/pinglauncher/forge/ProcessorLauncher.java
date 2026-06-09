package kr.co.donghyun.pinglauncher.forge;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Modern Forge / NeoForge processors 실행기.
 *
 * 동작:
 *  1) forge-install-data.properties 로딩
 *  2) processor 순차 실행. 각 단계마다 입력 파일 존재 / 출력 결과 / 예외를 모두 stdout 으로 dump.
 *  3) 마지막에 realMainClass.main(args) 호출.
 *
 * stdout 은 pingjvm.cpp 의 stdout_logger_thread 가 logcat "MinecraftJVM_IO" 태그로 중계함.
 */
public final class ProcessorLauncher {

    private static final String DELIM = "\u0001";

    public static void main(String[] args) throws Throwable {
        File userDir = new File(System.getProperty("user.dir", "."));
        File dataFile = new File(userDir, "forge-install-data.properties");
        if (!dataFile.exists()) {
            throw new IllegalStateException("forge-install-data.properties not found at " + dataFile);
        }

        Properties p = new Properties();
        try (InputStream in = new FileInputStream(dataFile)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        String realMainClass = require(p, "realMainClass");
        int count = Integer.parseInt(p.getProperty("processorCount", "0"));

        log("count=" + count + " realMain=" + realMainClass);
        log("workdir=" + userDir.getAbsolutePath());

        for (int i = 0; i < count; i++) {
            String prefix = "processor." + i + ".";
            String jar = require(p, prefix + "jar");
            String[] cp = split(p.getProperty(prefix + "classpath", ""));
            String[] pargs = split(p.getProperty(prefix + "args", ""));
            String[] outKeys = split(p.getProperty(prefix + "outputs.keys", ""));
            String[] outShas = split(p.getProperty(prefix + "outputs.values", ""));

            String jarName = new File(jar).getName();

            if (outKeys.length > 0 && outputsValid(outKeys, outShas)) {
                log("[" + i + "/" + count + "] SKIP (outputs already valid): " + jarName);
                continue;
            }

            log("[" + i + "/" + count + "] === run " + jarName + " ===");
            log("  args: " + Arrays.toString(pargs));

            // 1) 입력 파일 존재 여부 진단 — Processor 0 의 출력이 Processor 1 의 입력이 되는 식.
            //    여기서 missing/size=0 인 입력이 보이면 그게 진짜 원인.
            for (int a = 0; a < pargs.length - 1; a++) {
                String arg = pargs[a];
                if (arg.equals("--input") || arg.equals("--names")
                        || arg.equals("--mappings") || arg.equals("--patch")
                        || arg.equals("--data") || arg.equals("--lib")
                        || arg.equals("--mc") || arg.equals("--source")) {
                    String inPath = pargs[a + 1];
                    File inFile = new File(inPath);
                    log("  input " + arg + " = " + inPath
                            + " (exists=" + inFile.exists()
                            + " size=" + (inFile.exists() ? inFile.length() : -1) + ")");
                }
            }
            for (int k = 0; k < outKeys.length; k++) {
                File f = new File(outKeys[k]);
                log("  expect output[" + k + "]=" + outKeys[k]
                        + " (currently exists=" + f.exists()
                        + " size=" + (f.exists() ? f.length() : -1) + ")");
            }

            // 2) 실행
            try {
                runProcessor(jar, cp, pargs);
            } catch (Throwable t) {
                log("[" + i + "] processor THREW " + t.getClass().getName() + ": " + t.getMessage());
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("    caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                throw t;
            }
            log("[" + i + "/" + count + "] returned normally");

            // 3) 출력 검증 — outputs 선언이 있을 때만
            if (outKeys.length > 0 && !outputsValid(outKeys, outShas)) {
                StringBuilder msg = new StringBuilder("Processor ").append(i)
                        .append(" (").append(jarName).append(") did not produce expected outputs:");
                for (int k = 0; k < outKeys.length; k++) {
                    File f = new File(outKeys[k]);
                    msg.append("\n  - ").append(outKeys[k]).append(" → ");
                    if (!f.exists()) {
                        msg.append("MISSING");
                    } else if (f.length() == 0) {
                        msg.append("EMPTY");
                    } else {
                        try {
                            String actual = sha1(f);
                            if (actual.equalsIgnoreCase(outShas[k])) {
                                msg.append("OK");
                            } else {
                                msg.append("SHA mismatch (size=").append(f.length())
                                        .append(" expected=").append(outShas[k])
                                        .append(" actual=").append(actual).append(")");
                            }
                        } catch (Exception e) {
                            msg.append("CHECK_FAILED ").append(e);
                        }
                    }
                }
                throw new IllegalStateException(msg.toString());
            }
        }

        log("All processors complete. Launching " + realMainClass);

        Class<?> mainCls = Class.forName(realMainClass, true, ClassLoader.getSystemClassLoader());
        Method mainMethod = mainCls.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    private static void runProcessor(String jarPath, String[] classpath, String[] args) throws Throwable {
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < classpath.length; i++) {
            urls[i] = new File(classpath[i]).toURI().toURL();
        }
        URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());

        String mainClass;
        try (JarFile jf = new JarFile(jarPath)) {
            Manifest mf = jf.getManifest();
            if (mf == null) throw new IllegalStateException("No manifest in " + jarPath);
            mainClass = mf.getMainAttributes().getValue("Main-Class");
            if (mainClass == null) throw new IllegalStateException("No Main-Class in " + jarPath);
        }

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            Class<?> cls = Class.forName(mainClass, true, cl);
            Method m = cls.getMethod("main", String[].class);
            m.invoke(null, (Object) args);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    private static boolean outputsValid(String[] keys, String[] sha) throws Exception {
        if (keys.length != sha.length) return false;
        for (int i = 0; i < keys.length; i++) {
            File f = new File(keys[i]);
            if (!f.exists() || f.length() == 0) return false;
            if (!sha1(f).equalsIgnoreCase(sha[i])) return false;
        }
        return true;
    }

    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split(DELIM, -1);
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) throw new IllegalStateException("missing key: " + key);
        return v;
    }

    private static void log(String msg) {
        System.out.println("[ProcessorLauncher] " + msg);
    }
}
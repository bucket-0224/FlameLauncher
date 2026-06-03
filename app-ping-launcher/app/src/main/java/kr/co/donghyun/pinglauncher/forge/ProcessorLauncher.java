package kr.co.donghyun.pinglauncher.forge;


import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Modern Forge / NeoForge 부팅 전 processors 실행기.
 *
 * JVM 의 main entrypoint 로 동작 — InstanceMeta.mainClass 가 이 클래스를 가리키게 하면
 * pingjvm.cpp 가 JNI_CreateJavaVM 직후 여기로 들어온다.
 *
 * 동작:
 *  1. user.dir 의 forge-install-data.properties 로딩
 *  2. processors 를 순서대로 실행 (각 processor 의 jar 에서 Main-Class 추출 → URLClassLoader)
 *  3. 모든 outputs 가 valid 한 상태가 되면 realMainClass.main(args) 호출
 *
 * 의존성 없음 — pure JDK 만 사용. 자체 jar 안에 이 한 클래스만 있으면 됨.
 */
public final class ProcessorLauncher {

    /** properties 값 안에서 배열 구분자 (paths 에 등장할 일 없는 ASCII 0x01) */
    private static final String DELIM = "\u0001";

    public static void main(String[] args) throws Throwable {
        File userDir = new File(System.getProperty("user.dir", "."));
        File dataFile = new File(userDir, "forge-install-data.properties");
        if (!dataFile.exists()) {
            throw new IllegalStateException("forge-install-data.properties not found at " + dataFile);
        }

        Properties p = new Properties();
        try (InputStream in = new FileInputStream(dataFile)) {
            p.load(new InputStreamReader(in, "UTF-8"));
        }

        String realMainClass = require(p, "realMainClass");
        int count = Integer.parseInt(p.getProperty("processorCount", "0"));

        log("count=" + count + " realMain=" + realMainClass);

        for (int i = 0; i < count; i++) {
            String prefix = "processor." + i + ".";
            String jar = require(p, prefix + "jar");
            String[] cp = split(p.getProperty(prefix + "classpath", ""));
            String[] pargs = split(p.getProperty(prefix + "args", ""));
            String[] outKeys = split(p.getProperty(prefix + "outputs.keys", ""));
            String[] outShas = split(p.getProperty(prefix + "outputs.values", ""));

            if (outKeys.length > 0 && outputsValid(outKeys, outShas)) {
                log("[" + i + "/" + count + "] outputs already valid, skipping (" + jar + ")");
                continue;
            }
            log("[" + i + "/" + count + "] running " + jar);
            runProcessor(jar, cp, pargs);
            if (outKeys.length > 0 && !outputsValid(outKeys, outShas)) {
                throw new IllegalStateException("Processor " + i + " did not produce expected outputs: " + jar);
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
        // parent = systemClassLoader → MC 라이브러리들도 보임
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
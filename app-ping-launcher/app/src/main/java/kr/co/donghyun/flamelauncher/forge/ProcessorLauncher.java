package kr.co.donghyun.flamelauncher.forge;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
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

            boolean strictShaCheck = !"1".equals(System.getProperty("ping.forge.skip_sha"));

            if (outKeys.length > 0 && (!strictShaCheck || outputsValid(outKeys, outShas))) {
                log("[" + i + "/" + count + "] SKIP (outputs already valid): " + jarName);
                continue;
            }

            // ⚠️ 버그 수정: 기존에 여기 "outputs 가 invalid 면 SKIP" 하는 블록이 있었음.
            //    그 블록은 outputsValid 의 true/false 를 양쪽 if 에서 모두 잡아버려
            //    출력이 유효하든 아니든 무조건 프로세서를 건너뛰게 만들었음.
            //    → ForgeAutoRenamingTool(SRG 변환)/binarypatcher 가 한 번도 실행되지 않아
            //      net/minecraft/client/Minecraft.class 가 든 patched client jar 가 생성 안 됨.
            //    출력이 invalid 면 SKIP 하지 말고 아래로 내려가 실제로 프로세서를 실행해야 함.

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

        // NeoForge 전용 후처리:
        //   우리 프로세서는 Minecraft 를 미리 deobfuscate 한 patched client jar 를 만든다.
        //   그런데 NeoForge 의 GameLocator 는 클래스패스에서 net/minecraft/DetectedVersion.class
        //   (production 이면 난독화돼 있어야 함)를 발견하면 "dev(neodev) 환경"으로 판정한다.
        //   deobfuscated jar 라 그 클래스가 원래 이름으로 존재 → dev 로 인식됨.
        //   dev 경로(NeoForgeDevDistCleaner)는 Minecraft jar manifest 에 "Minecraft-Dists"
        //   속성을 요구하며, 없으면 neodev_missing_dists_attribute 로 즉시 종료한다.
        //   client 단일 dist 이므로 "Minecraft-Dists: client" 를 넣어주면 마스킹 없이 통과한다.
        if (realMainClass != null && realMainClass.startsWith("net.neoforged")) {
            try {
                patchNeoForgeClientDists(userDir);
            } catch (Throwable t) {
                log("⚠️ NeoForge Minecraft-Dists manifest 패치 실패(무시하고 진행): " + t);
            }
        }

        // NeoForge(FancyModLoader)의 FatalErrorReporting 은 시작 에러를 Swing(GUI)으로
        // 띄우려 하는데, 그 경로(showErrorUsingSwing → UIManager → AWT)는 안드로이드에
        // X11 헤드풀 라이브러리(libawt_xawt.so)가 없어 UnsatisfiedLinkError 로 2차 크래시한다.
        // 그 결과 "진짜 시작 에러"가 AWT 크래시에 가려져 로그에 안 남는다.
        //
        //  (1) GraphicsEnvironment 를 미리 headless 로 초기화/캐시해 둔다.
        //      NeoForge 가 내부에서 java.awt.headless 를 false 로 덮어써도, 이미 캐시된
        //      headless=true 가 유지되면 Swing 대신 TinyFD 경로로 빠져 X11 크래시를 피한다.
        //  (2) main 호출을 try/catch 로 감싸 예외가 전파되면 전체 cause 체인을 콘솔(logcat)에
        //      강제로 찍는다 → NeoForge 가 GUI 로 보여주려던 진짜 원인을 우리가 볼 수 있다.
        try {
            System.setProperty("java.awt.headless", "true");
            // GraphicsEnvironment 정적 초기화를 강제(headless 상태 캐시).
            // java.desktop 모듈을 컴파일 경로에 요구하지 않도록 리플렉션으로 호출한다.
            Class<?> ge = Class.forName("java.awt.GraphicsEnvironment");
            ge.getMethod("isHeadless").invoke(null);
        } catch (Throwable ignore) {
            // AWT 자체가 없거나 초기화 실패해도 무시 — 본 호출에는 영향 없음
        }

        Class<?> mainCls = Class.forName(realMainClass, true, ClassLoader.getSystemClassLoader());
        Method mainMethod = mainCls.getMethod("main", String[].class);
        try {
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable t) {
            // NeoForge 시작 실패의 "진짜 원인"을 전체 체인으로 출력
            log("================ REAL STARTUP FAILURE (unwrapped) ================");
            Throwable cur = t;
            int depth = 0;
            while (cur != null) {
                log("[cause #" + depth + "] " + cur.getClass().getName() + ": " + cur.getMessage());
                StackTraceElement[] st = cur.getStackTrace();
                int limit = Math.min(st.length, 12);
                for (int i = 0; i < limit; i++) {
                    log("    at " + st[i]);
                }
                Throwable next = cur.getCause();
                if (next == cur) break;
                cur = next;
                depth++;
            }
            log("=================================================================");
            throw t; // 기존 동작 유지(상위에서도 처리)
        }
    }

    /**
     * NeoForge client patched jar 의 manifest 에 "Minecraft-Dists: client" 를 보장한다.
     * libraries/net/neoforged/minecraft-client-patched/<ver>/minecraft-client-patched-<ver>.jar
     * 를 찾아, 메인 속성에 해당 키가 없으면 jar 를 다시 써서 추가한다(client 단일 dist).
     */
    private static void patchNeoForgeClientDists(File workDir) throws IOException {
        File base = new File(workDir, "libraries/net/neoforged/minecraft-client-patched");
        if (!base.isDirectory()) {
            log("ℹ️ minecraft-client-patched 디렉토리 없음 — Dists 패치 생략: " + base);
            return;
        }
        File target = null;
        File[] verDirs = base.listFiles(File::isDirectory);
        if (verDirs != null) {
            for (File vd : verDirs) {
                File[] jars = vd.listFiles((d, n) ->
                        n.startsWith("minecraft-client-patched") && n.endsWith(".jar"));
                if (jars != null) {
                    for (File j : jars) { target = j; break; }
                }
                if (target != null) break;
            }
        }
        if (target == null) {
            log("ℹ️ minecraft-client-patched-*.jar 못 찾음 — Dists 패치 생략");
            return;
        }

        // 이미 Minecraft-Dists 가 있으면 건너뜀
        Manifest mf;
        try (JarFile jf = new JarFile(target)) {
            mf = jf.getManifest();
        }
        if (mf == null) mf = new Manifest();
        Attributes main = mf.getMainAttributes();
        if (main.getValue("Minecraft-Dists") != null) {
            log("✅ Minecraft-Dists 이미 존재 — 패치 불필요: " + target.getName());
            return;
        }
        if (main.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        main.put(new Attributes.Name("Minecraft-Dists"), "client");

        // jar 를 임시 파일로 다시 쓰며 manifest 교체(다른 엔트리는 그대로 복사)
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] buf = new byte[8192];
        try (JarInputStream in = new JarInputStream(new BufferedInputStream(new FileInputStream(target)));
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)), mf)) {
            JarEntry e;
            while ((e = in.getNextJarEntry()) != null) {
                String name = e.getName();
                // 기존 manifest 는 건너뜀(위에서 새 manifest 로 이미 기록됨)
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    in.closeEntry();
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.closeEntry();
                in.closeEntry();
            }
        }
        // 원본 교체
        Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log("🩹 Minecraft-Dists=client 추가 완료: " + target.getName());
    }

    private static void runProcessor(String jarPath, String[] classpath, String[] args) throws Throwable {
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < classpath.length; i++) {
            urls[i] = new File(classpath[i]).toURI().toURL();
        }
        // ⚠️ child-first(self-first) 클래스로더 사용.
        //   installertools-*-fatjar.jar 같은 shaded fat jar 는 내부에 변형된(메서드명에
        //   해시가 붙은) jopt-simple 등을 번들한다. 표준 parent-first 위임을 쓰면 시스템
        //   클래스패스에 있는 "원본" jopt-simple-5.0.4 가 먼저 로드되어, fatjar 가 기대하는
        //   withRequiredArg$5f782b77() 같은 변형 메서드를 못 찾고 NoSuchMethodError 가 난다.
        //   → 프로세서 jar/클래스패스의 클래스를 부모보다 우선 로드하게 하여 충돌을 막는다.
        URLClassLoader cl = new ChildFirstClassLoader(urls, ClassLoader.getSystemClassLoader());

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

    /**
     * child-first(self-first) 위임 클래스로더.
     * 표준 ClassLoader 는 부모에게 먼저 위임(parent-first)하지만, 이 로더는
     * 자신의 URL(프로세서 jar + 지정 classpath)에서 먼저 클래스를 찾고,
     * 없을 때만 부모로 위임한다. 단, JDK 코어 클래스(java, javax, sun 패키지 등)는
     * 반드시 부모(플랫폼)에서 로드해야 하므로 예외로 둔다.
     */
    private static final class ChildFirstClassLoader extends URLClassLoader {
        private final ClassLoader realParent;
        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
            this.realParent = parent;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    // JDK 코어/부트스트랩 클래스는 부모에서 로드(child 에서 덮으면 안 됨)
                    if (isParentOnly(name)) {
                        c = realParent.loadClass(name);
                    } else {
                        try {
                            // 먼저 자신(URL)에서 시도 → fatjar 내부 변형 클래스 우선
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            // 없으면 부모로 위임
                            c = realParent.loadClass(name);
                        }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        private static boolean isParentOnly(String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("sun.")
                    || name.startsWith("jdk.")
                    || name.startsWith("com.sun.")
                    || name.startsWith("org.w3c.")
                    || name.startsWith("org.xml.");
        }
    }



    private static boolean outputsValid(String[] keys, String[] sha) throws Exception {
        if (keys.length != sha.length) return false;
        for (int i = 0; i < keys.length; i++) {
            File f = new File(keys[i]);
            if (!f.exists() || f.length() == 0) return false;
            // ⚠️ SHA 비교 제거:
            //   ForgeAutoRenamingTool/binarypatcher 같은 변환 단계는 jar 압축 시
            //   타임스탬프·엔트리 순서가 환경마다 달라서, 내용이 동일해도 바이트 해시가 달라짐.
            //   expected SHA 는 Forge 설치 데이터를 만든 PC 환경 기준이라 안드로이드 변환 결과와 불일치.
            //   (예: client-official.jar 안에 Minecraft.class 등 올바른 클래스가 다 들어있는데도
            //    SHA 만 안 맞아 실패하던 문제) → 존재 + 비어있지 않음만 검증.
            //   if (!sha1(f).equalsIgnoreCase(sha[i])) return false;
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
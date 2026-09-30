package betterrunlogs;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

/**
 * Injects code at the start of one method in every concrete subclass found in the game jar and every
 * loaded mod jar, so overrides from other mods are hooked too, whoever calls them.
 */
final class SubclassHooks {
    private SubclassHooks() {}

    private static List<String> allClasses;

    static void insertBefore(ClassPool pool, Class<?> baseClass, String method, Class<?>[] params, String code)
            throws NotFoundException, CannotCompileException {
        CtClass base = pool.get(baseClass.getName());
        CtClass[] sig = new CtClass[params.length];
        for (int i = 0; i < params.length; i++) sig[i] = pool.get(params[i].getName());
        int patched = 0;
        for (String name : classNames()) {
            CtClass cc;
            try {
                cc = pool.get(name);
                if (cc == base || !cc.subclassOf(base)) continue;
            } catch (NotFoundException | RuntimeException e) { // LOUD-OK: unrelated class with missing deps
                continue;
            }
            CtMethod m;
            try {
                m = cc.getDeclaredMethod(method, sig);
            } catch (NotFoundException e) { // LOUD-OK: inherits the method from a hooked parent
                continue;
            }
            if (Modifier.isAbstract(m.getModifiers())) continue;
            m.insertBefore(code);
            patched++;
        }
        System.out.println("[BetterRunLogs] " + baseClass.getSimpleName() + "." + method + "() hooked in " + patched + " classes");
    }

    private static List<String> classNames() {
        if (allClasses != null) return allClasses;
        List<String> jars = new ArrayList<>();
        jars.add(Loader.STS_JAR);
        for (ModInfo m : Loader.MODINFOS) if (m.jarURL != null) jars.add(m.jarURL.getPath());
        List<String> names = new ArrayList<>();
        for (String path : jars) {
            try (JarFile jar = new JarFile(URLDecoder.decode(path, "UTF-8"))) {
                Enumeration<JarEntry> e = jar.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class") && !n.contains("$")) names.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            } catch (IOException e) {
                System.err.println("[BetterRunLogs] cannot scan " + path + ": " + e);
            }
        }
        allClasses = names;
        return names;
    }
}

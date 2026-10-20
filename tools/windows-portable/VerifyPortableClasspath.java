import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Class-loading checks only: does not initialize the app, GUI, or native DLL. */
public final class VerifyPortableClasspath {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: VerifyPortableClasspath LIB_DIRECTORY VERSION");
        }
        List<Path> jars;
        try (var files = Files.list(Path.of(args[0]))) {
            jars = files.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
        }
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        for (String first : List.of("pdsync.AppendOnlyWebDav", "pdsync.DesktopDiagnosticExport")) {
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                Class.forName(first, false, loader);
                for (String name : List.of("pdsync.MainKt", "pdsync.DesktopDiagnosticExport", "pdsync.AppendOnlyWebDav")) {
                    Class<?> type = Class.forName(name, false, loader);
                    if (!args[1].equals(type.getPackage().getImplementationVersion())) {
                        throw new AssertionError("Stale diagnostic package version: " + name);
                    }
                }
                Class.forName("pdsync.MainKt", false, loader).getMethod("main", String[].class);
                Class.forName("org.jetbrains.skiko.SkiaLayer", false, loader);
                if (loader.getResource("skiko-windows-x64.dll") == null ||
                    loader.getResource("icudtl.dat") == null ||
                    loader.getResource("libskiko-linux-x64.so") != null) {
                    throw new AssertionError("Missing Windows runtime resources or unexpected Linux runtime");
                }
            }
        }
        System.out.println("PORTABLE_CLASSPATH_OK version=" + args[1] +
            " jars=" + jars.size() + " verificationJava=" + System.getProperty("java.version"));
        System.out.println("Class-loading only; no application, native DLL, or Windows GUI was executed.");
    }
}

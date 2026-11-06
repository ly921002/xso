import com.sun.jna.Library;
import com.sun.jna.Native;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal JNA sample: load the c-shared xray, start it with a JSON config,
 * query its version, then stop it.
 *
 * <pre>
 * javac -cp jna.jar JnaXray.java
 * java  -cp jna.jar:. JnaXray /opt/esx/x.so ./config.json
 * </pre>
 */
public final class JnaXray {

    /** Must match the exported symbols in cshared/main.go exactly. */
    public interface XrayLibrary extends Library {
        int StartXray(String configJson);

        int StopXray();

        int XrayRunning();

        String XrayLastError();

        String XrayVersion();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: JnaXray <path-to-x.so> <config.json>");
            System.exit(2);
        }

        Path libraryPath = Path.of(args[0]).toAbsolutePath();
        Path configPath = Path.of(args[1]).toAbsolutePath();

        if (!Files.isRegularFile(libraryPath)) {
            throw new IllegalArgumentException("library not found: " + libraryPath);
        }

        // setAllowUnresolved(false) is the default; we fail fast on missing symbols.
        XrayLibrary xray = Native.load(libraryPath.toString(), XrayLibrary.class);

        System.out.println("loaded   : " + libraryPath);
        System.out.println("version  : " + xray.XrayVersion());
        System.out.println("running  : " + xray.XrayRunning());

        String config = Files.readString(configPath, StandardCharsets.UTF_8);
        int rc = xray.StartXray(config);
        if (rc != 0) {
            throw new IllegalStateException("StartXray failed with " + rc
                    + ": " + xray.XrayLastError());
        }

        System.out.println("started  : running=" + xray.XrayRunning());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("stopping ...");
            int stopRc = xray.StopXray();
            System.out.println("stopped  : rc=" + stopRc);
        }, "xray-shutdown"));

        // Keep the process alive so the core keeps serving.
        Thread.currentThread().join();
    }
}

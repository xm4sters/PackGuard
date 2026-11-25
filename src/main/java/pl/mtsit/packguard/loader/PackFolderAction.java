package pl.mtsit.packguard.loader;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.mtsit.packguard.font.FontWarning;

public final class PackFolderAction {
    private static final AtomicBoolean OPENING = new AtomicBoolean();

    private PackFolderAction() {}

    public static void open() {
        if (!OPENING.compareAndSet(false, true)) return;
        Thread request = new Thread(() -> {
            try {
                Path directory = PackGuardPlatform.gameDirectory().resolve("resourcepacks").toAbsolutePath().normalize();
                if (!openInExplorer(directory)) copyInGame(directory);
            } finally {
                OPENING.set(false);
            }
        }, "PackGuard pack folder");
        request.setDaemon(true);
        request.start();
    }

    private static boolean openInExplorer(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            Shell32 shell = Native.load("shell32", Shell32.class);
            Pointer result = shell.ShellExecuteW(null, new WString("explore"), new WString(directory.toString()), null, null, 1);
            return Pointer.nativeValue(result) > 32;
        } catch (RuntimeException | LinkageError failure) {
            System.getLogger("PackGuard").log(System.Logger.Level.DEBUG, "Could not open the resource pack folder", failure);
            return false;
        }
    }

    private static void copyInGame(Path directory) {
        try {
            Class<?> minecraft = MinecraftMappings.CLIENT.load(false);
            Object client = minecraft.getMethod("getInstance").invoke(null);
            if (client instanceof Executor executor) {
                executor.execute(() -> {
                    try {
                        Object keyboard = minecraft.getField("keyboardHandler").get(client);
                        keyboard.getClass().getMethod("setClipboard", String.class).invoke(keyboard, directory.toString());
                        FontWarning.folderCopied();
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        FontWarning.folderUnavailable(directory);
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FontWarning.folderUnavailable(directory);
        }
    }

    public interface Shell32 extends StdCallLibrary {
        Pointer ShellExecuteW(Pointer window, WString verb, WString file, WString parameters, WString workingDirectory, int show);
    }
}

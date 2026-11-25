package pl.mtsit.packguard.font;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import pl.mtsit.packguard.history.BlockHistory;
import pl.mtsit.packguard.loader.MinecraftMappings;
import pl.mtsit.packguard.loader.PackGuardPlatform;

public final class FontWarning {
    private static final System.Logger LOGGER = System.getLogger("PackGuard");


    private FontWarning() {}

    public static void blocked() {
        if (!BlockHistory.record("font", "unknown", "native-truetype-blocked")) return;
        warn("PackGuard: font blocked", "A resource pack uses TrueType. Minecraft skipped the font for safety.");
    }

    public static void archiveBlocked(String archive, String reason) {
        if (!BlockHistory.record("server-zip", archive, reason)) return;
        warn("PackGuard: resource pack blocked", "The server ZIP failed safety checks.");
    }

    public static void folderCopied() {
        warn("PackGuard: pack folder", "Path copied. Paste it into File Explorer.");
    }

    public static void folderUnavailable(Path directory) {
        warn("PackGuard: pack folder", directory.toString());
    }

    private static void warn(String title, String description) {
        LOGGER.log(System.Logger.Level.WARNING, title);
        try {
            boolean intermediary = PackGuardPlatform.intermediary();
            Class<?> minecraft = MinecraftMappings.CLIENT.load(intermediary);
            Object client = mappedMethod(minecraft, intermediary ? "method_1551" : "getInstance", "m_91087_").invoke(null);
            if (client instanceof Executor executor) {
                executor.execute(() -> show(client, intermediary, title, description));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logUnavailable(failure);
        }
    }

    private static void show(Object client, boolean intermediary, String title, String description) {
        try {
            Class<?> component = MinecraftMappings.COMPONENT.load(intermediary);
            Class<?> toast = MinecraftMappings.SYSTEM_TOAST.load(intermediary);

            Object toasts = toastManager(client, intermediary);
            Method literal = mappedMethod(component, intermediary ? "method_43470" : "literal", "m_237113_", String.class);
            Object toastTitle = literal.invoke(null, title);
            Object toastDescription = literal.invoke(null, description);
            Class<?> id = toastId(intermediary);
            Object token = id.isEnum() ? id.getField(intermediary ? "field_2218" : "PACK_LOAD_FAILURE").get(null) : id.getConstructor().newInstance();
            mappedMethod(toast, intermediary ? "method_27024" : "addOrUpdate", "m_94869_", toasts.getClass(), id, component, component)
                    .invoke(null, toasts, token, toastTitle, toastDescription);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logUnavailable(failure);
        }
    }

    private static Method mappedMethod(Class<?> type, String name, String srg, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            if (!PackGuardPlatform.forge()) throw missing;
            return type.getMethod(srg, parameters);
        }
    }

    private static Class<?> toastId(boolean intermediary) throws ClassNotFoundException {
        try {
            return MinecraftMappings.TOAST_ID.load(intermediary);
        } catch (ClassNotFoundException missing) {
            if (!intermediary && !PackGuardPlatform.forge()) throw missing;
            return MinecraftMappings.OLD_TOAST_ID.load(intermediary);
        }
    }
    private static Object toastManager(Object client, boolean intermediary) throws ReflectiveOperationException {
        if (intermediary) return client.getClass().getMethod("method_1566").invoke(client);

        try {
            return mappedMethod(client.getClass(), "getToastManager", "m_91300_").invoke(client);
        } catch (NoSuchMethodException movedToGui) {
            Object gui = client.getClass().getField("gui").get(client);
            return gui.getClass().getMethod("toastManager").invoke(gui);
        }
    }

    private static void logUnavailable(Throwable failure) {
        LOGGER.log(System.Logger.Level.WARNING, "Could not show the font warning in game: " + failure);
    }
}

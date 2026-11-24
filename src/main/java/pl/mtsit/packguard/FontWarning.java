package pl.mtsit.packguard;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import net.fabricmc.loader.api.FabricLoader;

public final class FontWarning {
    private static final System.Logger LOGGER = System.getLogger("PackGuard");


    private FontWarning() {}

    public static void blocked() {
        if (!BlockHistory.record("font", "unknown", "native-truetype-blocked")) return;
        warn("PackGuard: font blocked", "A resource pack uses TrueType. Minecraft skipped the font for safety.");
    }

    static void archiveBlocked(String archive, String reason) {
        if (!BlockHistory.record("server-zip", archive, reason)) return;
        warn("PackGuard: resource pack blocked", "The server ZIP failed safety checks.");
    }

    private static void warn(String title, String description) {
        LOGGER.log(System.Logger.Level.WARNING, title);
        try {
            boolean intermediary = FabricLoader.getInstance().getMappingResolver()
                    .getCurrentRuntimeNamespace().equals("intermediary");
            Class<?> minecraft = gameClass(intermediary, "class_310", "client.Minecraft");
            Object client = minecraft.getMethod(intermediary ? "method_1551" : "getInstance").invoke(null);
            if (client instanceof Executor executor) {
                executor.execute(() -> show(client, intermediary, title, description));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logUnavailable(failure);
        }
    }

    private static void show(Object client, boolean intermediary, String title, String description) {
        try {
            Class<?> component = gameClass(intermediary, "class_2561", "network.chat.Component");
            Class<?> toast = gameClass(intermediary, "class_370", "client.gui.components.toasts.SystemToast");
            Class<?> manager = gameClass(intermediary, "class_374", "client.gui.components.toasts.ToastManager");
            Object toasts = toastManager(client, intermediary);
            Method literal = component.getMethod(intermediary ? "method_43470" : "literal", String.class);
            Object toastTitle = literal.invoke(null, title);
            Object toastDescription = literal.invoke(null, description);
            Class<?> id = intermediary ? legacyToastId() : gameClass(false, "", "client.gui.components.toasts.SystemToast$SystemToastId");
            Object token = intermediary && id.isEnum() ? id.getField("field_2218").get(null) : id.getConstructor().newInstance();
            toast.getMethod(intermediary ? "method_27024" : "addOrUpdate", manager, id, component, component)
                    .invoke(null, toasts, token, toastTitle, toastDescription);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            logUnavailable(failure);
        }
    }

    private static Class<?> legacyToastId() throws ClassNotFoundException {
        try {
            return gameClass(true, "class_370$class_9037", "");
        } catch (ClassNotFoundException unavailable) {
            return gameClass(true, "class_370$class_371", "");
        }
    }

    private static Object toastManager(Object client, boolean intermediary) throws ReflectiveOperationException {
        if (intermediary) return client.getClass().getMethod("method_1566").invoke(client);

        try {
            return client.getClass().getMethod("getToastManager").invoke(client);
        } catch (NoSuchMethodException movedToGui) {
            Object gui = client.getClass().getField("gui").get(client);
            return gui.getClass().getMethod("toastManager").invoke(gui);
        }
    }

    private static Class<?> gameClass(boolean intermediary, String legacy, String modern) throws ClassNotFoundException {
        return Class.forName("net.minecraft." + (intermediary ? legacy : modern), false, FontWarning.class.getClassLoader());
    }

    private static void logUnavailable(Throwable failure) {
        LOGGER.log(System.Logger.Level.WARNING, "Could not show the font warning in game: " + failure);
    }
}

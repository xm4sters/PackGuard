package pl.mtsit.packguard.loader;

public enum MinecraftMappings {
    TRUE_TYPE("net.minecraft.class_8557", "net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition"),
    HTTP("net.minecraft.class_3521", "net.minecraft.util.HttpUtil"),
    DOWNLOADED_PACK("net.minecraft.class_1066", "net.minecraft.client.resources.DownloadedPackSource"),
    CLIENT("net.minecraft.class_310", "net.minecraft.client.Minecraft"),
    COMPONENT("net.minecraft.class_2561", "net.minecraft.network.chat.Component"),
    SYSTEM_TOAST("net.minecraft.class_370", "net.minecraft.client.gui.components.toasts.SystemToast"),
    TOAST_ID("net.minecraft.class_370$class_9037", "net.minecraft.client.gui.components.toasts.SystemToast$SystemToastId"),
    OLD_TOAST_ID("net.minecraft.class_370$class_371", "net.minecraft.client.gui.components.toasts.SystemToast$SystemToastIds");

    private final String intermediaryName;
    private final String mojangName;

    MinecraftMappings(String intermediaryName, String mojangName) {
        this.intermediaryName = intermediaryName;
        this.mojangName = mojangName;
    }

    public String className(boolean intermediary) {
        return intermediary ? intermediaryName : mojangName;
    }

    public Class<?> load(boolean intermediary) throws ClassNotFoundException {
        return Class.forName(className(intermediary), false, MinecraftMappings.class.getClassLoader());
    }
}

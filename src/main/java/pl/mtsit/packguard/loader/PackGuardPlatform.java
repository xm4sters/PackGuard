package pl.mtsit.packguard.loader;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class PackGuardPlatform {
    private static final boolean FORGE = forgeArtifact();

    private PackGuardPlatform() {}

    public static boolean forge() {
        return FORGE;
    }

    public static boolean intermediary() {
        return !FORGE && FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace().equals("intermediary");
    }

    static boolean client() {
        return !FORGE || forgeField("FMLEnvironment", "dist").toString().equals("CLIENT");
    }

    public static Path gameDirectory() {
        if (!FORGE) return FabricLoader.getInstance().getGameDir();
        Object directory = forgeField("FMLPaths", "GAMEDIR");
        try {
            return (Path) directory.getClass().getMethod("get").invoke(directory);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PackGuard cannot resolve the Forge game directory", failure);
        }
    }

    private static Object forgeField(String className, String field) {
        try {
            Class<?> type = Class.forName("net.minecraftforge.fml.loading." + className);
            return type.getField(field).get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PackGuard cannot access Forge " + className, failure);
        }
    }

    private static boolean forgeArtifact() {
        try {
            Class.forName("pl.mtsit.packguard.loader.ForgePackGuard", false, PackGuardPlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}

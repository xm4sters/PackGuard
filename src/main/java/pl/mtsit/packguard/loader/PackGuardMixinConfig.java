package pl.mtsit.packguard.loader;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

public final class PackGuardMixinConfig implements IMixinConfigPlugin {
    private static final String LEGACY = "net.minecraft.class_8557";
    private static final String MODERN = "net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition";
    private static final String MARKER = "packguard$verifiedTrueTypeBlock";

    private static final String ARCHIVE_MARKER = "packguard$verifiedArchiveBlock";
    private static volatile String archiveTarget;

    public void onLoad(String name) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return true; }
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {}
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public List<String> getMixins() {
        if (!PackGuardPlatform.client()) return List.of();
        boolean legacy = PackGuardPlatform.intermediary();
        String target = providerClass();
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(target);
        } catch (Exception failure) {
            throw new IllegalStateException("PackGuard cannot verify the TrueType provider for this Minecraft version", failure);
        }
        String fontMixin = legacy ? "IntermediaryFontProvider" : "MojangFontProvider";
        archiveTarget = legacy ? "net.minecraft.class_3521" : "net.minecraft.util.HttpUtil";
        if (legacy || PackGuardPlatform.forge()) {
            try {
                ClassNode http = MixinService.getService().getBytecodeProvider().getClassNode(archiveTarget);
                if (http.methods.stream().noneMatch(PackGuardMixinConfig::isPackDownload)) {
                    String source = legacy ? "net.minecraft.class_1066" : "net.minecraft.client.resources.DownloadedPackSource";
                    ClassNode packs = MixinService.getService().getBytecodeProvider().getClassNode(source);
                    legacyContinuationCall(packs, legacyPackApplication(packs));
                    archiveTarget = source;
                    return List.of(fontMixin, legacy ? "LegacyServerPack" : "ForgeLegacyServerPack");
                }
            } catch (Exception failure) {
                throw new IllegalStateException("PackGuard cannot inspect the resource pack downloader", failure);
            }
        }
        return List.of(fontMixin, legacy ? "IntermediaryPackDownload" : "MojangPackDownload");
    }

    static void verifyInstalled() {
        try {
            Class<?> provider = Class.forName(providerClass(), false, PackGuardMixinConfig.class.getClassLoader());
            if (provider.getDeclaredField(MARKER).getType() != boolean.class)
                throw new IllegalStateException("PackGuard font marker has an unexpected type");
            String archive = archiveTarget;
            if (archive == null || Class.forName(archive, false, PackGuardMixinConfig.class.getClassLoader())
                    .getDeclaredField(ARCHIVE_MARKER).getType() != boolean.class)
                throw new IllegalStateException("PackGuard cannot verify server archive protection");
        } catch (ClassNotFoundException | NoSuchFieldException failure) {
            throw new IllegalStateException("PackGuard could not confirm resource pack protection. Minecraft startup stopped.", failure);
        }
    }


    private static String providerClass() {
        return PackGuardPlatform.intermediary() ? LEGACY : MODERN;
    }

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.endsWith("LegacyServerPack")) {
            applyLegacyArchivePolicy(node);
            markArchivePolicy(node);
            return;
        }
        if (mixin.endsWith("PackDownload")) {
            applyArchivePolicy(node);
            markArchivePolicy(node);
            return;
        }
        applyFontPolicy(node);
        System.out.println("[PackGuard] Minecraft TrueType provider disabled");
    }

    private static void markArchivePolicy(ClassNode node) {
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                ARCHIVE_MARKER, "Z", null, 1));
        System.out.println("[PackGuard] Server archive validation active");
    }

    static void applyLegacyArchivePolicy(ClassNode node) {
        MethodNode method = legacyPackApplication(node);
        legacyContinuationCall(node, method).name = "thenComposeAsync";
        method.maxStack++;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!isLegacyApplication(node, instruction)) continue;
            InsnList validation = new InsnList();
            validation.add(new VarInsnNode(Opcodes.ALOAD, 2));
            validation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pl/mtsit/packguard/archive/PackArchivePolicy",
                    "validateLegacy", "(Ljava/io/File;)V", false));
            method.instructions.insertBefore(instruction, validation);
        }
    }

    private static MethodNode legacyPackApplication(ClassNode node) {
        return node.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) == 0
                        && method.desc.startsWith("(Ljava/lang/String;Ljava/io/File;L")
                        && method.desc.endsWith(";ZLjava/lang/Object;)Ljava/util/concurrent/CompletionStage;"))
                .filter(method -> {
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (isLegacyApplication(node, instruction)) return true;
                    }
                    return false;
                })
                .findFirst().orElseThrow(() -> new IllegalStateException("PackGuard could not locate legacy server pack application"));
    }

    private static MethodInsnNode legacyContinuationCall(ClassNode node, MethodNode continuation) {
        for (MethodNode method : node.methods) {
            if (!method.desc.equals("(Ljava/net/URL;Ljava/lang/String;Z)Ljava/util/concurrent/CompletableFuture;")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode factory)) continue;
                boolean matches = false;
                for (Object argument : factory.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getOwner().equals(node.name)
                            && handle.getName().equals(continuation.name) && handle.getDesc().equals(continuation.desc)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
                AbstractInsnNode next = instruction.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof MethodInsnNode call && call.owner.equals("java/util/concurrent/CompletableFuture")
                        && call.name.equals("thenCompose")
                        && call.desc.equals("(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;")) return call;
            }
        }
        throw new IllegalStateException("PackGuard could not locate the legacy download continuation");
    }

    private static boolean isLegacyApplication(ClassNode node, AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                && call.desc.startsWith("(Ljava/io/File;L")
                && call.desc.endsWith(";)Ljava/util/concurrent/CompletableFuture;");
    }

    static void applyArchivePolicy(ClassNode node) {
        MethodNode download = node.methods.stream().filter(PackGuardMixinConfig::isPackDownload)
                .findFirst().orElseThrow(() -> new IllegalStateException("PackGuard could not locate the resource pack downloader"));
        for (AbstractInsnNode instruction : download.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList validation = new InsnList();
            validation.add(new VarInsnNode(Opcodes.ILOAD, 5));
            validation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pl/mtsit/packguard/archive/PackArchivePolicy",
                    "validate", "(Ljava/nio/file/Path;I)Ljava/nio/file/Path;", false));
            download.instructions.insertBefore(instruction, validation);
        }
        download.maxStack = Math.max(2, download.maxStack);
    }

    private static boolean isPackDownload(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0
                && method.desc.startsWith("(Ljava/nio/file/Path;Ljava/net/URL;Ljava/util/Map;Lcom/google/common/hash/HashFunction;Lcom/google/common/hash/HashCode;ILjava/net/Proxy;")
                && method.desc.endsWith(")Ljava/nio/file/Path;");
    }

    static void applyFontPolicy(ClassNode node) {
        boolean guarded = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call) || !isNativeFontInput(call)) continue;
                block(method);
                guarded = true;
                break;
            }
        }
        if (!guarded) throw new IllegalStateException("PackGuard could not locate native font loading");
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                MARKER, "Z", null, 1));
    }

    private static void block(MethodNode method) {
        if (method.name.startsWith("<")) throw new IllegalStateException("Unsupported PackGuard font entry point");
        InsnList body = method.instructions;
        body.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        Type provider = Type.getReturnType(method.desc);
        if (provider.getSort() != Type.OBJECT) throw new IllegalStateException("Unsupported PackGuard font result");
        body.add(new LdcInsnNode(provider));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "pl/mtsit/packguard/font/BlockedFontProvider",
                "empty", "(Ljava/lang/Class;)Ljava/lang/Object;", false));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, provider.getInternalName()));
        body.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
    }

    private static boolean isNativeFontInput(MethodInsnNode call) {
        return switch (call.owner) {
            case "org/lwjgl/util/freetype/FreeType" -> switch (call.name) {
                case "FT_New_Memory_Face", "nFT_New_Memory_Face", "FT_New_Face", "nFT_New_Face",
                        "FT_Open_Face", "nFT_Open_Face" -> true;
                default -> false;
            };
            case "org/lwjgl/stb/STBTruetype" -> switch (call.name) {
                case "stbtt_InitFont", "nstbtt_InitFont", "stbtt_GetFontOffsetForIndex", "nstbtt_GetFontOffsetForIndex" -> true;
                default -> false;
            };
            default -> false;
        };
    }
}


package pl.mtsit.packguard.loader;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import pl.mtsit.packguard.archive.PackArchivePolicy;
import pl.mtsit.packguard.font.BlockedFontProvider;
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
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;

public final class PackGuardMixinConfig implements IMixinConfigPlugin {
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
        boolean intermediary = PackGuardPlatform.intermediary();
        String target = providerClass();
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(target);
        } catch (Exception failure) {
            throw new IllegalStateException("PackGuard cannot verify the TrueType provider for this Minecraft version", failure);
        }
        String fontMixin = intermediary ? "IntermediaryFontProvider" : "MojangFontProvider";
        archiveTarget = MinecraftMappings.HTTP.className(intermediary);
        if (intermediary || PackGuardPlatform.forge()) {
            try {
                IClassBytecodeProvider bytecode = MixinService.getService().getBytecodeProvider();
                ClassNode downloader = bytecode.getClassNode(archiveTarget);
                if (downloader.methods.stream().noneMatch(method -> isPackDownload(method))) {
                    String source = MinecraftMappings.DOWNLOADED_PACK.className(intermediary);
                    ClassNode packs = bytecode.getClassNode(source);
                    legacyContinuationCall(packs, legacyPackApplication(packs));
                    archiveTarget = source;
                    return List.of(fontMixin, intermediary ? "LegacyServerPack" : "ForgeLegacyServerPack");
                }
            } catch (Exception failure) {
                throw new IllegalStateException("PackGuard cannot inspect the resource pack downloader", failure);
            }
        }
        String archiveMixin = intermediary ? "IntermediaryPackDownload" : "MojangPackDownload";
        if (!intermediary && System.getProperty("os.name", "").startsWith("Windows") && usesProcessFolderAction()) {
            return List.of(fontMixin, archiveMixin, "PackFolder");
        }
        return List.of(fontMixin, archiveMixin);
    }

    private static boolean usesProcessFolderAction() {
        try {
            ClassNode screen = MixinService.getService().getBytecodeProvider()
                    .getClassNode(MinecraftMappings.PACK_SCREEN.className(false));
            for (MethodNode method : screen.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (isPackFolderOpen(instruction)) return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | IOException unavailable) {
            return false;
        }
    }

    private static boolean isPackFolderOpen(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals("net/minecraft/util/Util$OS")
                && call.name.equals("openPath")
                && call.desc.equals(Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Path.class)));
    }

    private static void replacePackFolderAction(ClassNode screen) {
        for (MethodNode method : screen.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!isPackFolderOpen(instruction)) continue;
                method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP2));
                method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(PackFolderAction.class), "open", Type.getMethodDescriptor(Type.VOID_TYPE), false));
            }
        }
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
        return MinecraftMappings.TRUE_TYPE.className(PackGuardPlatform.intermediary());
    }

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (mixin.endsWith(".PackFolder")) {
            replacePackFolderAction(node);
            return;
        }
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
            validation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(PackArchivePolicy.class),
                    "validateLegacy", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(File.class)), false));
            method.instructions.insertBefore(instruction, validation);
        }
    }

    private static MethodNode legacyPackApplication(ClassNode node) {
        return node.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) == 0
                        && isLegacyContinuation(method.desc))
                .filter(method -> {
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (isLegacyApplication(node, instruction)) return true;
                    }
                    return false;
                })
                .findFirst().orElseThrow(() -> new IllegalStateException("PackGuard could not locate legacy server pack application"));
    }

    private static boolean isLegacyContinuation(String descriptor) {
        if (!Type.getType(CompletionStage.class).equals(Type.getReturnType(descriptor))) return false;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        return arguments.length == 5 && Type.getType(String.class).equals(arguments[0]) && Type.getType(File.class).equals(arguments[1])
                && arguments[2].getSort() == Type.OBJECT && Type.BOOLEAN_TYPE.equals(arguments[3])
                && Type.getType(Object.class).equals(arguments[4]);
    }

    private static MethodInsnNode legacyContinuationCall(ClassNode node, MethodNode continuation) {
        Type future = Type.getType(CompletableFuture.class);
        String download = Type.getMethodDescriptor(future, Type.getType(URL.class),
                Type.getType(String.class), Type.BOOLEAN_TYPE);
        String compose = Type.getMethodDescriptor(future, Type.getType(Function.class));
        for (MethodNode method : node.methods) {
            if (!method.desc.equals(download)) continue;
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
                if (next instanceof MethodInsnNode call && call.owner.equals(future.getInternalName())
                        && call.name.equals("thenCompose")
                        && call.desc.equals(compose)) return call;
            }
        }
        throw new IllegalStateException("PackGuard could not locate the legacy download continuation");
    }

    private static boolean isLegacyApplication(ClassNode node, AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(node.name)
                || !Type.getType(CompletableFuture.class).equals(Type.getReturnType(call.desc))) return false;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        return arguments.length == 2 && Type.getType(File.class).equals(arguments[0]) && arguments[1].getSort() == Type.OBJECT;
    }

    static void applyArchivePolicy(ClassNode node) {
        MethodNode download = node.methods.stream().filter(method -> isPackDownload(method))
                .findFirst().orElseThrow(() -> new IllegalStateException("PackGuard could not locate the resource pack downloader"));
        boolean validated = false;
        for (AbstractInsnNode instruction : download.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) continue;
            InsnList validation = new InsnList();
            validation.add(new VarInsnNode(Opcodes.ILOAD, 5));
            validation.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(PackArchivePolicy.class),
                    "validate", Type.getMethodDescriptor(Type.getType(Path.class), Type.getType(Path.class), Type.INT_TYPE), false));
            download.instructions.insertBefore(instruction, validation);
            validated = true;
        }
        if (!validated) throw new IllegalStateException("PackGuard found no resource pack download return");
        download.maxStack = Math.max(2, download.maxStack);
    }

    private static boolean isPackDownload(MethodNode method) {
        Type path = Type.getType(Path.class);
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !path.equals(Type.getReturnType(method.desc))) return false;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length >= 7 && path.equals(arguments[0])
                && Type.getType(URL.class).equals(arguments[1])
                && Type.getType(Map.class).equals(arguments[2])
                && Type.getObjectType("com/google/common/hash/HashFunction").equals(arguments[3])
                && Type.getObjectType("com/google/common/hash/HashCode").equals(arguments[4])
                && Type.INT_TYPE.equals(arguments[5])
                && Type.getType(Proxy.class).equals(arguments[6]);
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
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(BlockedFontProvider.class),
                "empty", Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Class.class)), false));
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


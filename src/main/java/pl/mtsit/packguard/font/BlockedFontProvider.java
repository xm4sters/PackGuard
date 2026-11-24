package pl.mtsit.packguard.font;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class BlockedFontProvider {
    private static final ClassValue<Object> PROVIDERS = new ClassValue<>() {
        protected Object computeValue(Class<?> type) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "PackGuard empty glyph provider";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                if (method.getReturnType().getName().equals("it.unimi.dsi.fastutil.ints.IntSet")) {
                    Class<?> sets = Class.forName("it.unimi.dsi.fastutil.ints.IntSets", true, type.getClassLoader());
                    return sets.getField("EMPTY_SET").get(null);
                }
                return null;
            });
        }
    };

    private BlockedFontProvider() {}

    public static Object empty(Class<?> provider) {
        FontWarning.blocked();
        return PROVIDERS.get(provider);
    }
}

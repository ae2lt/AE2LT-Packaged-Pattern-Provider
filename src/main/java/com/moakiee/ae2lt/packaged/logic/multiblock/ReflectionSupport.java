package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ReflectionSupport {
    private static final ConcurrentMap<String, Optional<Class<?>>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<MethodLookupKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<MethodLookupKey, Optional<Method>> DECLARED_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldLookupKey, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldLookupKey, Optional<Field>> DECLARED_FIELD_CACHE = new ConcurrentHashMap<>();

    private ReflectionSupport() {
    }

    public static Optional<Class<?>> findClass(String className) {
        try {
            return Optional.of(Class.forName(className));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    public static Optional<Class<?>> findClassCached(String className) {
        return CLASS_CACHE.computeIfAbsent(className, ReflectionSupport::findClass);
    }

    public static Optional<Method> findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return Optional.of(type.getMethod(name, parameterTypes));
        } catch (NoSuchMethodException | SecurityException e) {
            return Optional.empty();
        }
    }

    public static Optional<Method> findMethodCached(Class<?> type, String name, Class<?>... parameterTypes) {
        return METHOD_CACHE.computeIfAbsent(MethodLookupKey.of(type, name, parameterTypes),
                key -> accessible(findMethod(key.type(), key.name(), key.parameterTypes().toArray(Class<?>[]::new)).orElse(null)));
    }

    public static Optional<Method> findDeclaredMethodCached(Class<?> type, String name, Class<?>... parameterTypes) {
        return DECLARED_METHOD_CACHE.computeIfAbsent(MethodLookupKey.of(type, name, parameterTypes),
                key -> accessible(findDeclaredMethod(key.type(), key.name(), key.parameterTypes().toArray(Class<?>[]::new)).orElse(null)));
    }

    public static Optional<Field> findFieldCached(Class<?> type, String name) {
        return FIELD_CACHE.computeIfAbsent(new FieldLookupKey(type, name),
                key -> accessible(findField(key.type(), key.name()).orElse(null)));
    }

    public static Optional<Field> findDeclaredFieldCached(Class<?> type, String name) {
        return DECLARED_FIELD_CACHE.computeIfAbsent(new FieldLookupKey(type, name),
                key -> accessible(findDeclaredField(key.type(), key.name()).orElse(null)));
    }

    public static Optional<Object> invoke(Method method, Object target, Object... args) {
        try {
            return Optional.ofNullable(method.invoke(target, args));
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Invokes a mutation without turning an uncertain side effect into a rejection. */
    public static Optional<Object> invokeMutation(Method method, Object target, Object... args) {
        if (method == null) {
            throw new IllegalStateException("Required mutation method is unavailable");
        }
        try {
            return Optional.ofNullable(method.invoke(target, args));
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof LinkageError linkage) {
                throw linkage;
            }
            throw new IllegalStateException("Mutation failed: " + method, cause);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot invoke mutation: " + method, e);
        }
    }

    private static Optional<Method> findDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return Optional.of(type.getDeclaredMethod(name, parameterTypes));
        } catch (NoSuchMethodException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        try {
            return Optional.of(type.getField(name));
        } catch (NoSuchFieldException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<Field> findDeclaredField(Class<?> type, String name) {
        try {
            return Optional.of(type.getDeclaredField(name));
        } catch (NoSuchFieldException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<Method> accessible(Method method) {
        if (method == null) {
            return Optional.empty();
        }
        try {
            method.setAccessible(true);
            return Optional.of(method);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Field> accessible(Field field) {
        if (field == null) {
            return Optional.empty();
        }
        try {
            field.setAccessible(true);
            return Optional.of(field);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record MethodLookupKey(Class<?> type, String name, List<Class<?>> parameterTypes) {
        private MethodLookupKey {
            parameterTypes = List.copyOf(parameterTypes);
        }

        private static MethodLookupKey of(Class<?> type, String name, Class<?>... parameterTypes) {
            return new MethodLookupKey(type, name, Arrays.asList((Class<?>[]) parameterTypes.clone()));
        }
    }

    private record FieldLookupKey(Class<?> type, String name) {
    }
}

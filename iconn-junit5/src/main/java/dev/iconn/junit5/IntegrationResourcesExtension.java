package dev.iconn.junit5;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceException;
import dev.iconn.api.ResourceManager;
import dev.iconn.core.Iconn;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * JUnit 5 extension managing the lifecycle of an iconn environment and dependency injection.
 */
public class IntegrationResourcesExtension implements
    BeforeAllCallback,
    BeforeEachCallback,
    AfterAllCallback,
    ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(IntegrationResourcesExtension.class);
    private static final String RESOURCE_MANAGER_KEY = "iconn.resourceManager";
    private static final String CONFIG_KEY = "iconn.config";

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        IntegrationResources config = AnnotationUtils.findAnnotation(testClass, IntegrationResources.class)
            .orElseThrow(() -> new IllegalStateException("Missing @IntegrationResources on " + testClass));

        ResourceManager manager = Iconn.load(config.value());
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put(RESOURCE_MANAGER_KEY, manager);
        store.put(CONFIG_KEY, config);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        ResourceManager manager = getResourceManager(context);
        IntegrationResources config = context.getStore(NAMESPACE).get(CONFIG_KEY, IntegrationResources.class);

        if (config != null && config.resetBetweenTests()) {
            manager.reset();
        }

        // Inject fields on test instance
        Object testInstance = context.getRequiredTestInstance();
        Class<?> clazz = testInstance.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectResource.class)) {
                    injectField(field, testInstance, manager);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ResourceManager manager = context.getStore(NAMESPACE).get(RESOURCE_MANAGER_KEY, ResourceManager.class);
        if (manager != null) {
            manager.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
        return parameterContext.isAnnotated(InjectResource.class)
            || ResourceManager.class.isAssignableFrom(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
        throws ParameterResolutionException {
        ResourceManager manager = getResourceManager(extensionContext);
        Parameter parameter = parameterContext.getParameter();
        Class<?> type = parameter.getType();

        if (ResourceManager.class.isAssignableFrom(type)) {
            return manager;
        }

        InjectResource annotation = parameter.getAnnotation(InjectResource.class);
        String name = annotation != null ? annotation.value() : "";
        if (name.isBlank()) {
            name = parameter.getName();
        }

        return resolveValue(name, type, manager);
    }

    private ResourceManager getResourceManager(ExtensionContext context) {
        ExtensionContext current = context;
        while (current != null) {
            ResourceManager manager = current.getStore(NAMESPACE).get(RESOURCE_MANAGER_KEY, ResourceManager.class);
            if (manager != null) {
                return manager;
            }
            current = current.getParent().orElse(null);
        }
        throw new IllegalStateException("No iconn ResourceManager found in test extension context");
    }

    private void injectField(Field field, Object target, ResourceManager manager) throws IllegalAccessException {
        InjectResource annotation = field.getAnnotation(InjectResource.class);
        String name = annotation.value();
        if (name.isBlank()) {
            name = field.getName();
        }

        Object value = resolveValue(name, field.getType(), manager);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(String name, Class<?> targetType, ResourceManager manager) {
        if (ResourceManager.class.isAssignableFrom(targetType)) {
            return manager;
        }

        if (Resource.class.isAssignableFrom(targetType)) {
            return manager.get(name, (Class<? extends Resource>) targetType);
        }

        // Check if the target is a capability on the named resource
        Resource res = manager.get(name);
        Optional<?> cap = res.capability(targetType);
        if (cap.isPresent()) {
            return cap.get();
        }

        throw new ResourceException(
            "Cannot inject resource '" + name + "' as " + targetType.getName() +
            ": resource does not implement target type or provide matching capability."
        );
    }
}

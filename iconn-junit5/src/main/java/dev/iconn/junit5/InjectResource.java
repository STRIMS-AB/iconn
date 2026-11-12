package dev.iconn.junit5;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a managed {@link dev.iconn.api.Resource}, {@link dev.iconn.api.ResourceManager}, or capability
 * into a test field or test method parameter.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InjectResource {

    /**
     * Name of the resource declared in the topology YAML.
     * If left blank on a field, the field's name will be used as the resource name.
     */
    String value() default "";
}

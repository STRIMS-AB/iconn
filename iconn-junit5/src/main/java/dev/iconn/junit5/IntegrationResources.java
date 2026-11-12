package dev.iconn.junit5;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Activates an iconn integration environment for a JUnit 5 test class.
 *
 * <pre>{@code
 * @IntegrationResources("integration-resources.yaml")
 * class OrderServiceIntegrationTest {
 *
 *     @InjectResource("customerApi")
 *     HttpResource customerApi;
 *
 *     @InjectResource("email")
 *     EmailResource email;
 *
 *     @Test
 *     void testOrderWorkflow() {
 *         // test logic
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(IntegrationResourcesExtension.class)
public @interface IntegrationResources {

    /**
     * Path or classpath location of the iconn topology YAML file.
     * Defaults to {@code "iconn.yaml"}.
     */
    String value() default "iconn.yaml";

    /**
     * If true, resets all resources (stubs, mailboxes, state) between individual tests.
     */
    boolean resetBetweenTests() default true;
}

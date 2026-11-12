package dev.iconn.junit5;

import dev.iconn.core.Iconn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class IconnCliLauncherTest {

    @Test
    @DisplayName("Should successfully launch topology via Iconn.main entrypoint and stop gracefully on interrupt")
    void shouldLaunchTopologyViaMainEntrypoint() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean completedNormally = new AtomicBoolean(false);

        Thread cliThread = new Thread(() -> {
            try {
                started.countDown();
                Iconn.main(new String[]{"path-patterns.yaml"});
                completedNormally.set(true);
            } catch (Exception ignored) {
            }
        }, "iconn-cli-test-thread");

        cliThread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // Let the daemon run briefly
        Thread.sleep(300);

        // Verify property was exported
        String port = System.getProperty("dealers.api.port");
        assertThat(port).isNotNull();

        // Interrupt thread to simulate termination
        cliThread.interrupt();
        cliThread.join(2000);

        assertThat(cliThread.isAlive()).isFalse();
    }
}

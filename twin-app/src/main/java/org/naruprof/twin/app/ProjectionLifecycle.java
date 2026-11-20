package org.naruprof.twin.app;

import io.grpc.ManagedChannel;
import org.naruprof.twin.service.impl.ProfileProjector;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * Starts the projector after the context is ready and drains it on shutdown, before the gRPC
 * channel and the datasource go away (graceful drain and cancellation).
 */
public class ProjectionLifecycle implements SmartLifecycle {

    private final ProfileProjector projector;
    private final ManagedChannel channel;
    private volatile boolean running;

    public ProjectionLifecycle(ProfileProjector projector, ManagedChannel channel) {
        this.projector = projector;
        this.channel = channel;
    }

    @Override
    public void start() {
        projector.start();
        running = true;
    }

    @Override
    public void stop() {
        projector.close();
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000; // stop early, after the web server stops taking requests
    }
}

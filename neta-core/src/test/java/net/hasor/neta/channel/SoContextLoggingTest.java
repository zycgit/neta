/*
 * Copyright 2008-2009 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.neta.channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoContextLoggingTest {
    @Test
    public void disabledDebugDoesNotRenderUnconsumedPayloads() throws Exception {
        try (Fixture fixture = new Fixture(Level.INFO)) {
            for (boolean[] direction : new boolean[][] { { true, false }, { false, true }, { false, false } }) {
                fixture.context.trigger(PlayLoadObject.of(fixture.channel, fixture.payload, direction[0], direction[1]));
            }
            assertEquals(0, fixture.rendered.get());
            assertTrue(fixture.records.isEmpty());
        }
    }

    @Test
    public void unconsumedVirtualWriteDoesNotRenderPayloadWhenDebugDisabled() throws Exception {
        try (Fixture fixture = new Fixture(Level.INFO)) {
            fixture.channel.sendData(fixture.payload).get();
            assertEquals(0, fixture.rendered.get());
            assertTrue(fixture.records.isEmpty());
        }
    }

    @Test
    public void enabledDebugPreservesPayloadAndDirection() throws Exception {
        try (Fixture fixture = new Fixture(Level.FINE)) {
            boolean[][] directions = { { true, false }, { false, true }, { false, false } };
            String[] prefixes = { "rcv", "snd", "event" };
            for (int i = 0; i < directions.length; i++) {
                fixture.context.trigger(PlayLoadObject.of(fixture.channel, fixture.payload, directions[i][0], directions[i][1]));
                assertEquals(prefixes[i] + "(" + fixture.channel.getChannelId()
                        + ") There are no program at the tail of the ProtoStackChain, Skipping event: payload", fixture.records.get(i).getMessage());
                assertEquals(Level.FINE, fixture.records.get(i).getLevel());
            }
            assertEquals(3, fixture.rendered.get());
            assertEquals(3, fixture.records.size());
        }
    }

    @Test
    public void allMatchingSubscribersStillReceiveTheOriginalPayload() throws Exception {
        for (Level level : new Level[] { Level.INFO, Level.FINE }) {
            try (Fixture fixture = new Fixture(level)) {
                List<PlayLoad> received = new ArrayList<>();
                fixture.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, received::add);
                fixture.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, received::add);
                PlayLoad data = PlayLoadObject.of(fixture.channel, fixture.payload, true, false);
                fixture.context.trigger(data);
                assertEquals(2, received.size());
                assertSame(data, received.get(0));
                assertSame(data, received.get(1));
                assertSame(fixture.payload, received.get(0).getData());
                assertEquals(0, fixture.rendered.get());
                assertTrue(fixture.records.isEmpty());
            }
        }
    }

    @Test
    public void failedSubscriberStillLogsAndDoesNotPreventLaterDelivery() throws Exception {
        try (Fixture fixture = new Fixture(Level.INFO)) {
            IllegalStateException failure = new IllegalStateException("listener failure");
            List<PlayLoad> received = new ArrayList<>();
            fixture.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> { throw failure; });
            fixture.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, received::add);
            PlayLoad data = PlayLoadObject.of(fixture.channel, fixture.payload, true, false);
            fixture.context.trigger(data);
            assertEquals(1, received.size());
            assertSame(data, received.get(0));
            assertEquals(1, fixture.records.size());
            assertSame(failure, fixture.records.get(0).getThrown());
            assertEquals(Level.SEVERE, fixture.records.get(0).getLevel());
            assertEquals(0, fixture.rendered.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Logger logger = Logger.getLogger(SoContextService.class.getName());
        private final Level previousLevel = this.logger.getLevel();
        private final boolean previousParents = this.logger.getUseParentHandlers();
        private final List<LogRecord> records = new ArrayList<>();
        private final Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) { records.add(record); }
            @Override
            public void flush() { }
            @Override
            public void close() { }
        };
        private final NetManager manager;
        private final NetChannel channel;
        private final SoContextService context;
        private final AtomicInteger rendered = new AtomicInteger();
        private final Object payload = new Object() {
            @Override
            public String toString() {
                rendered.incrementAndGet();
                return "payload";
            }
        };

        private Fixture(Level level) throws Exception {
            NetConfig config = new NetConfig();
            config.setPrintLog(false);
            config.setIoThreads(1);
            config.setTaskThreads(1);
            this.manager = new NetManager(config);
            try {
                this.channel = this.manager.connectSync(new VrtSocketAddress(1), context -> { }, new VrtSoConfig());
                this.context = (SoContextService) this.channel.getContext();
            } catch (Exception | Error e) {
                this.manager.shutdown();
                throw e;
            }
            this.handler.setLevel(Level.ALL);
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(level);
            this.logger.addHandler(this.handler);
        }

        @Override
        public void close() throws IOException {
            this.logger.removeHandler(this.handler);
            this.logger.setLevel(this.previousLevel);
            this.logger.setUseParentHandlers(this.previousParents);
            this.manager.shutdown();
        }
    }
}

/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.util.concurrent.*;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtoContextAttachmentTest {
    @Test
    public void equalInheritedValueStillCreatesALocalBinding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProtoContextService root = fixture.root;
            ProtoContextService child = new ProtoContextService(root, 8, 8, null, null);
            ProtoContextService sibling = new ProtoContextService(root, 8, 8, null, null);
            String first = new String("first");
            root.context(String.class, first);
            assertSame(first, child.context(String.class));
            assertSame(first, child.context(String.class, first));
            root.context(String.class, "second");
            assertSame(first, child.context(String.class));
            assertEquals("second", sibling.context(String.class));
            assertNull(child.context(String.class, null));
            assertEquals("second", child.context(String.class));
            child.context(String.class, root.context(String.class));
            root.context(String.class, "third");
            assertEquals("second", child.context(String.class));
            child.rootContext(String.class, "fourth");
            assertEquals("fourth", child.rootContext(String.class));
            assertEquals("second", child.context(String.class));
            child.context(String.class, null);
            root.context(String.class, null);
            assertNull(child.context(String.class));
        }
    }

    @Test
    public void bindingsRemainTypedAndMutableValuesKeepIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProtoContextService context = fixture.root;
            StringBuilder value = new StringBuilder("first");
            assertSame(value, context.context(StringBuilder.class, value));
            context.context(String.class, "text");
            context.context(Integer.class, 42);
            value.append("-changed");
            assertSame(value, context.context(StringBuilder.class, value));
            assertEquals("first-changed", context.context(StringBuilder.class).toString());
            assertEquals("text", context.context(String.class));
            assertEquals(Integer.valueOf(42), context.context(Integer.class));
            for (int i = 0; i < 1000; i++) {
                context.context(StringBuilder.class, null);
                assertNull(context.context(StringBuilder.class));
                assertSame(value, context.context(StringBuilder.class, value));
            }
            assertThrows(NullPointerException.class, () -> context.context(null));
            assertThrows(NullPointerException.class, () -> context.context(null, null));
        }
    }

    @Test(timeout = 15000)
    public void concurrentRemovalAndUpdateDoNotResurrectOldBindings() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(3);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Future<?>[] tasks = new Future<?>[3];
                for (int worker = 0; worker < tasks.length; worker++) {
                    final int id = worker;
                    tasks[worker] = workers.submit(() -> {
                        start.await();
                        for (int i = 0; i < 10000; i++) {
                            if (id == 0) {
                                fixture.root.context(Payload.class, null);
                            } else {
                                Payload payload = new Payload(i + id);
                                assertSame(payload, fixture.root.context(Payload.class, payload));
                            }
                            Payload observed = fixture.root.context(Payload.class);
                            if (observed != null) {
                                assertEquals(observed.value ^ 0x5a5a5a5a, observed.check);
                            }
                        }
                        return null;
                    });
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(10, TimeUnit.SECONDS);
                }
                fixture.root.context(Payload.class, null);
                assertNull(fixture.root.context(Payload.class));
                Payload last = new Payload(123);
                fixture.root.context(Payload.class, last);
                assertSame(last, fixture.root.context(Payload.class));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Payload {
        private int value;
        private int check;

        private Payload(int value) {
            this.value = value;
            this.check = value ^ 0x5a5a5a5a;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final NetManager          manager = new NetManager();
        private final ProtoContextService root;

        private Fixture() throws Exception {
            NetChannel channel = (NetChannel) this.manager.connectSync(new VrtSocketAddress(186), ctx -> {
            }, VrtSoConfig.asServer());
            this.root = channel.protoCtx;
        }

        @Override
        public void close() throws Exception {
            this.manager.shutdown();
        }
    }
}

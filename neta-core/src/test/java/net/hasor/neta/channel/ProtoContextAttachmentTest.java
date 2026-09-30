/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    public void removedAttachmentsDoNotRetainTheirTypeKeys() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Field storageField = ProtoContextService.class.getDeclaredField("contextData");
            storageField.setAccessible(true);
            Map<?, ?> storage = (Map<?, ?>) storageField.get(fixture.root);
            for (int i = 0; i < 1000; i++) {
                Payload payload = new Payload(i);
                fixture.root.context(Payload.class, payload);
                assertTrue(storage.containsKey(Payload.class));
                fixture.root.context(Payload.class, null);
                assertFalse(storage.containsKey(Payload.class));
            }
        }
    }

    @Test(timeout = 15000)
    public void concurrentRemoveUpdateAndReadHistoriesAreLinearizable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 500; round++) {
                    Payload initial = new Payload(round);
                    fixture.root.context(Payload.class, initial);
                    AtomicInteger clock = new AtomicInteger();
                    CountDownLatch start = new CountDownLatch(1);
                    Operation[] history = { new Operation(Operation.WRITE, new Payload(round + 1)), new Operation(Operation.READ, null), new Operation(Operation.WRITE, null), new Operation(Operation.WRITE, new Payload(round + 2)), new Operation(Operation.READ, null) };
                    Future<?> first = workers.submit(() -> {
                        start.await();
                        history[0].run(fixture.root, clock);
                        history[1].run(fixture.root, clock);
                        return null;
                    });
                    Future<?> second = workers.submit(() -> {
                        start.await();
                        history[2].run(fixture.root, clock);
                        history[3].run(fixture.root, clock);
                        return null;
                    });
                    start.countDown();
                    first.get(5, TimeUnit.SECONDS);
                    second.get(5, TimeUnit.SECONDS);
                    history[4].run(fixture.root, clock);
                    assertTrue("No legal sequential history in round " + round, linearizable(history, 0, initial));
                }
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void twoRemoversWriterAndReaderHaveLinearizableHistories() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < 250; round++) {
                    Payload initial = new Payload(round);
                    fixture.root.context(Payload.class, initial);
                    AtomicInteger clock = new AtomicInteger();
                    CountDownLatch ready = new CountDownLatch(4);
                    CountDownLatch start = new CountDownLatch(1);
                    Operation[] history = { new Operation(Operation.WRITE, null), new Operation(Operation.WRITE, null), new Operation(Operation.WRITE, new Payload(round + 1)), new Operation(Operation.READ, null), new Operation(Operation.READ, null) };
                    Future<?>[] tasks = new Future<?>[4];
                    for (int worker = 0; worker < tasks.length; worker++) {
                        Operation operation = history[worker];
                        tasks[worker] = workers.submit(() -> {
                            ready.countDown();
                            start.await();
                            operation.run(fixture.root, clock);
                            return null;
                        });
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    start.countDown();
                    for (Future<?> task : tasks) {
                        task.get(5, TimeUnit.SECONDS);
                    }
                    history[4].run(fixture.root, clock);
                    assertTrue("No legal two-remover history in round " + round, linearizable(history, 0, initial));
                }
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void mutableAttachmentIdentitySurvivesExternallySynchronizedUpdates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Payload value = new Payload(0);
            fixture.root.context(Payload.class, value);
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                for (int i = 1; i <= 500; i++) {
                    final int nextValue = i;
                    // Future.get orders these threads; this checks identity and API behavior, not publication strength.
                    workers.submit(() -> {
                        value.value = nextValue;
                        value.check = nextValue ^ 0x5a5a5a5a;
                        assertSame(value, fixture.root.context(Payload.class, value));
                    }).get(5, TimeUnit.SECONDS);
                    Payload observed = fixture.root.context(Payload.class);
                    assertSame(value, observed);
                    assertEquals(i, observed.value);
                    assertEquals(i ^ 0x5a5a5a5a, observed.check);
                }
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static boolean linearizable(Operation[] history, int selected, Payload value) {
        if (selected == (1 << history.length) - 1) {
            return true;
        }
        for (int i = 0; i < history.length; i++) {
            if ((selected & (1 << i)) != 0) {
                continue;
            }
            Operation operation = history[i];
            boolean predecessorMissing = false;
            for (int j = 0; j < history.length; j++) {
                if ((selected & (1 << j)) == 0 && history[j].completed < operation.started) {
                    predecessorMissing = true;
                    break;
                }
            }
            if (predecessorMissing || (operation.kind == Operation.READ && operation.observed != value)) {
                continue;
            }
            Payload nextValue = operation.kind == Operation.WRITE ? operation.value : value;
            if (linearizable(history, selected | (1 << i), nextValue)) {
                return true;
            }
        }
        return false;
    }

    private static final class Operation {
        private static final int     READ  = 0;
        private static final int     WRITE = 1;
        private final        int     kind;
        private final        Payload value;
        private              Payload observed;
        private              int     started;
        private              int     completed;

        private Operation(int kind, Payload value) {
            this.kind = kind;
            this.value = value;
        }

        private void run(ProtoContextService context, AtomicInteger clock) {
            this.started = clock.incrementAndGet();
            this.observed = this.kind == READ ? context.context(Payload.class) : context.context(Payload.class, this.value);
            this.completed = clock.incrementAndGet();
            if (this.kind == WRITE) {
                assertSame(this.value, this.observed);
            } else if (this.observed != null) {
                assertEquals(this.observed.value ^ 0x5a5a5a5a, this.observed.check);
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

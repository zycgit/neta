/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.Function;
/**
 * Ring Buffer linked list.
 * Uses {@link StampedLock} for efficient read-write concurrency control.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferRing<T> {
    private final AtomicInteger size;
    private final StampedLock   lock;
    private volatile Node<T>    curNode;

    public BufferRing() {
        this.size = new AtomicInteger();
        this.lock = new StampedLock();
    }

    protected <R> R readLock(Function<BufferRing<T>, R> self) {
        long stamp = this.lock.readLock();
        try {
            return self.apply(this);
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    protected void writeLock(Consumer<BufferRing<T>> self) {
        long stamp = this.lock.writeLock();
        try {
            self.accept(this);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public int size() {
        return this.size.get();
    }

    public T find(int skip) {
        long stamp = this.lock.readLock();
        try {
            final Node<T> curNode = this.curNode;
            if (curNode == null) {
                return null;
            } else if (skip <= 0) {
                return curNode.data;
            } else {
                int i = skip <= this.size.get() ? skip : (skip % this.size.get());
                Node<T> visitorNode = curNode;
                T visitorData;

                do {
                    visitorNode = visitorNode.next;
                    visitorData = visitorNode.data;
                    i--;
                } while (i > 0);

                return visitorData;
            }
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public T next() {
        long stamp = this.lock.readLock();
        try {
            final Node<T> curNode = this.curNode;
            if (curNode == null) {
                return null;
            } else {
                Node<T> nextNode = curNode.next;
                T nextData = nextNode.data;
                this.curNode = nextNode;
                return nextData;
            }
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public void add(T data) {
        this.writeLock(self -> {
            Node<T> node = new Node<>();
            node.data = data;

            if (this.curNode == null) {
                node.next = node;
                this.curNode = node;
            } else {
                node.next = this.curNode.next;
                this.curNode.next = node;
                this.curNode = node;
            }

            this.size.incrementAndGet();
        });
    }

    public void remove(T data) {
        if (data == null) {
            return;
        }
        this.writeLock(self -> {
            if (this.curNode == null) {
                return;
            }

            final Node<T> startNode = this.curNode;
            Node<T> visitorNode = startNode;

            do {
                final Node<T> nextNode = visitorNode.next;
                if (nextNode == null) {
                    this.curNode = null;
                    this.size.set(0);
                    return;
                }

                final T nextData = nextNode.data;
                if (Objects.equals(nextData, data)) {
                    // Check if it's the last element
                    if (this.size.decrementAndGet() == 0) {
                        this.curNode = null;
                    } else {
                        visitorNode.next = nextNode.next;
                        if (nextNode == this.curNode) {
                            this.curNode = visitorNode;
                        }
                    }

                    nextNode.next = null;
                    nextNode.data = null;
                    break;
                } else {
                    visitorNode = nextNode;
                }
            } while (visitorNode != startNode);
        });
    }

    private static class Node<T> {
        volatile Node<T> next;
        volatile T       data;

        @Override
        public String toString() {
            return String.valueOf(this.data);
        }
    }
}
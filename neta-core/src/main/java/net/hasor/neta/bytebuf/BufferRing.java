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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Ring Buffer linked list.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferRing<T> {
    private volatile Node<T>       curNode;
    private final    AtomicInteger size;
    private final    ReadWriteLock lock;

    public BufferRing() {
        this.size = new AtomicInteger();
        this.lock = new ReentrantReadWriteLock(false);
    }

    public int size() {
        try {
            this.lock.readLock().lock();
            return this.size.get();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private static class Node<T> {
        volatile Node<T> next;
        volatile T       data;
        volatile boolean hole;

        @Override
        public String toString() {
            return this.data.toString();
        }
    }

    public T find(int skip) {
        try {
            this.lock.readLock().lock();

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
                    if (!visitorNode.hole) {
                        i--;
                    }
                } while (i > 0);

                return visitorData;
            }

        } finally {
            this.lock.readLock().unlock();
        }
    }

    public T next() {
        try {
            this.lock.readLock().lock();

            final Node<T> curNode = this.curNode;
            if (curNode == null) {
                return null;
            } else {
                Node<T> visitorNode = curNode;
                T visitorData;

                do {
                    visitorNode = visitorNode.next;
                    visitorData = visitorNode.data;
                    if (!visitorNode.hole) {
                        break;
                    }
                } while (visitorNode != curNode);

                this.curNode = visitorNode;
                return visitorData;
            }

        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void add(T data) {
        try {
            this.lock.writeLock().lock();

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
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void remove(T data) {
        if (data == null) {
            return;
        }
        try {
            this.lock.writeLock().lock();
            this.size.decrementAndGet();
            if (this.size.get() == 0) {
                final Node<T> curNode = this.curNode;
                this.curNode = null;
                curNode.next = null;
                return;
            }

            final Node<T> curNode = this.curNode;
            Node<T> visitorNode = curNode;

            do {
                final Node<T> nextNode = visitorNode.next;
                final T nextData = nextNode.data;
                if (!nextNode.hole && Objects.equals(nextData, data)) {
                    visitorNode.next = nextNode.next;

                    if (nextNode == curNode) {
                        this.curNode = visitorNode;
                    }

                    nextNode.next = null;
                    nextNode.data = null;
                    break;
                } else {
                    visitorNode = visitorNode.next;
                }
            } while (visitorNode != curNode);

        } finally {
            this.lock.writeLock().unlock();
        }
    }
}

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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Ring Buffer linked list.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferRing<T> {
    private       Node<T>       lastNode;
    private       Node<T>       curNode;
    private       int           size;
    private final ReadWriteLock lock;

    public BufferRing() {
        this.lock = new ReentrantReadWriteLock(false);
    }

    public int size() {
        return this.size;
    }

    private static class Node<T> {
        Node<T> next;
        T       data;

        @Override
        public String toString() {
            return this.data.toString();
        }
    }

    public T find(int skip) {
        try {
            this.lock.readLock().lock();

            Node<T> curNode = this.curNode;
            if (curNode == null) {
                return null;
            } else if (curNode == this.lastNode) {
                return curNode.data;
            } else {
                Node<T> tmpCurNode = curNode;
                for (int i = 0; i < skip; i++) {
                    tmpCurNode = tmpCurNode.next;
                }
                return tmpCurNode == null ? null : tmpCurNode.data;
            }

        } finally {
            this.lock.readLock().unlock();
        }
    }

    public T next() {
        try {
            this.lock.readLock().lock();

            Node<T> curNode = this.curNode;
            if (curNode == null) {
                return null;
            } else {
                this.lastNode = curNode;
                this.curNode = curNode.next;
                return curNode.data;
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
                this.lastNode = node;
                this.curNode = node;
            } else {
                node.next = this.curNode.next;
                this.curNode.next = node;
                this.lastNode = this.curNode;
                this.curNode = node;
            }

            this.size = this.size + 1;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void remove(T data) {
        if (data == null || this.size == 0) {
            return;
        }
        try {
            this.lock.writeLock().lock();
            this.size = this.size - 1;

            Node<T> curNode = this.curNode;
            Node<T> tmpLastNode = this.lastNode;
            Node<T> tmpCurNode = this.curNode;
            do {
                if (Objects.equals(tmpCurNode.data, data)) {
                    if (tmpCurNode.next == tmpCurNode) {
                        // A -> A -> A
                        //      ^
                        tmpCurNode.next = null;

                        this.lastNode = null;
                        this.curNode = null;
                    } else if (tmpCurNode.next == tmpLastNode) {
                        // A -> B -> A      A -> B -> A
                        // ^            or       ^
                        tmpLastNode.next = tmpLastNode;
                        tmpCurNode.next = null;

                        this.lastNode = tmpLastNode;
                        this.curNode = tmpLastNode;
                    } else {
                        if (tmpCurNode == this.lastNode) {
                            // A -> B -> C
                            // ^
                            this.lastNode = tmpCurNode.next;
                            this.curNode = tmpCurNode.next.next;
                        } else if (tmpCurNode == this.curNode) {
                            // A -> B -> C
                            //      ^
                            this.curNode = tmpCurNode.next;
                        }

                        tmpLastNode.next = tmpCurNode.next;
                        tmpCurNode.next = null;
                    }

                    break;
                } else {
                    tmpLastNode = tmpCurNode;
                    tmpCurNode = tmpCurNode.next;
                }
            } while (curNode != tmpCurNode);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    //    private void remove(Node<T> foundPrev, Node<T> found) {
    //            Node<T> curNode = this.curNode;
    //            if (this.lastNode == found) {
    //                this.lastNode = curNode;
    //                this.curNode = curNode.next;
    //            } else if (curNode == found) {
    //                this.curNode = curNode.next;
    //            }
    //
    //            foundPrev.next = found.next;
    //            found.next = null;
    //            this.size = this.size - 1;
    //
    //            if (this.size == 0) {
    //                this.lastNode = null;
    //                this.curNode = null;
    //            }
    //    }
}

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

/**
 * Ring Buffer linked list
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferRing<T> {
    private Node<T> lastNode;
    private Node<T> curNode;
    private int     size;

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

    public T next(int skip) {
        if (this.curNode == null) {
            return null;
        } else if (this.curNode == this.lastNode) {
            return this.curNode.data;
        } else {
            Node<T> tmpCurNode = this.curNode;
            for (int i = 0; i < skip; i++) {
                tmpCurNode = tmpCurNode.next;
            }
            return tmpCurNode == null ? null : tmpCurNode.data;
        }
    }

    public T next() {
        if (this.curNode == null) {
            return null;
        } else {
            this.lastNode = this.curNode;
            this.curNode = this.curNode.next;
            return this.curNode.data;
        }
    }

    public void add(T data) {
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
    }

    public void remove(T data) {
        if (this.curNode == null) {
            return;
        }

        final Node<T> startNode = this.curNode;
        Node<T> tmpLastNode = this.lastNode;
        Node<T> tmpCurNode = this.curNode;
        do {
            if (Objects.equals(tmpCurNode.data, data)) {
                remove(tmpLastNode, tmpCurNode);
                break;
            }

            tmpLastNode = tmpCurNode;
            tmpCurNode = tmpCurNode.next;
        } while (startNode != tmpCurNode);
    }

    private void remove(Node<T> foundPrev, Node<T> found) {
        if (this.lastNode == found) {
            this.lastNode = this.curNode;
            this.curNode = this.curNode.next;
        } else if (this.curNode == found) {
            this.curNode = this.curNode.next;
        }

        foundPrev.next = found.next;
        found.next = null;
        this.size = this.size - 1;

        if (this.size == 0) {
            this.lastNode = null;
            this.curNode = null;
        }
    }
}

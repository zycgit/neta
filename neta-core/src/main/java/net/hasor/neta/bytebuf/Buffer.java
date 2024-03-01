/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;

/**
 * Memory block basic read and write interface
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
interface Buffer {
    /** Queries if this buffer is Available(read or write) or not. */
    boolean isAvailable();

    /** The capacity of this buffer, that is, the maximum number of bytes it can contain. */
    int capacity();

    byte get(int index);

    void put(int index, byte b);

    void get(int index, byte[] dst, int dstOffset, int length);

    void put(int index, byte[] src, int srcOffset, int length);

    void get(int index, ByteBuffer dst, int dstOffset, int length);

    void put(int index, ByteBuffer src, int srcOffset, int length);

    void free();
}
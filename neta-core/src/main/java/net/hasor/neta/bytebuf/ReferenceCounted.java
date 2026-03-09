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

/**
 * Contract for objects whose lifetime is managed through explicit reference
 * counting.
 * <p>A newly created instance starts with {@code refCnt() == 1}. Additional
 * shared ownership is recorded with {@link #retain()}, and ownership is
 * relinquished with {@link #release()}. Once the count reaches zero, the
 * implementation deallocates the underlying resource immediately.
 * <p>After the final release, further access is invalid and typically results in
 * implementation-specific failures such as {@link IllegalStateException}.
 */
public interface ReferenceCounted {
    /**
     * Returns the reference count of this object.  If {@code 0}, it means this object has been deallocated.
     */
    int refCnt();

    /**
     * Increases the reference count by {@code 1}.
     */
    ReferenceCounted retain();

    /**
     * Increases the reference count by the specified {@code increment}.
     */
    ReferenceCounted retain(int increment);

    /**
     * Decreases the reference count by {@code 1} and deallocates this object if the reference count reaches at {@code 0}.
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release();

    /**
     * Decreases the reference count by the specified {@code decrement} and deallocates this object if the reference count reaches at {@code 0}.
     * @return {@code true} if and only if the reference count became {@code 0} and this object has been deallocated
     */
    boolean release(int decrement);
}

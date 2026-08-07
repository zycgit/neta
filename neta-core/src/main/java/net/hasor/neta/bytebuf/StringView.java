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
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.ref.RecycleObjectPool;
/**
 * A lightweight {@link CharSequence} view over a visible range of a {@link ByteBuf}.
 * <p>
 * The visible range is defined by the constructor arguments {@code source}, {@code offset},
 * and {@code length}. This type does not copy bytes eagerly. Instead, it retains the source
 * buffer and reads characters from the specified range on demand, caching the decoded string
 * after the first full materialization.
 * <p>
 * This is intentionally only a simple view utility. It does not provide immutability guarantees.
 * If the underlying {@link ByteBuf} content changes before the view is materialized, the observed
 * characters may change as well. In other words, the stability of this view depends on the
 * stability of the underlying buffer content.
 */
public class StringView implements CharSequence, Release {
    private static final RecycleObjectPool.Recycler<StringView> RECYCLER = RecycleObjectPool.recycler(//
            StringView::new, StringView::resetState, StringView::onRecycle);

    private int     offset;
    private int     length;
    private ByteBuf source;
    private String  cachedValue;

    protected StringView() {
    }

    private void resetState() {
        this.source = null;
        this.offset = 0;
        this.length = 0;
        this.cachedValue = null;
    }

    private void onRecycle() {
        this.releaseSource();
        this.source = null;
        this.offset = 0;
        this.length = 0;
        this.cachedValue = null;
    }

    public static StringView request(ByteBuf source, int offset, int length) {
        StringView view = RECYCLER.get();
        view.init(source, offset, length);
        return view;
    }

    public StringView duplicate() {
        ByteBuf current = this.source;
        if (current != null) {
            ByteBuf duplicateBuf = ByteBufAllocator.DEFAULT.buffer(this.length, Integer.MAX_VALUE);
            current.getBuffer(this.offset, duplicateBuf, this.length);
            duplicateBuf.markWriter();

            StringView duplicate = StringView.request(duplicateBuf, 0, this.length);
            duplicateBuf.release();
            return duplicate;
        }

        StringView duplicate = RECYCLER.get();
        duplicate.source = null;
        duplicate.offset = 0;
        duplicate.length = this.length;
        duplicate.cachedValue = this.resolve();
        return duplicate;
    }

    protected void init(ByteBuf source, int offset, int length) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset < 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");
        }
        this.source = source.retain();
        this.offset = offset;
        this.length = length;
        this.cachedValue = null;
    }

    @Override
    public void release() {
        RECYCLER.recycle(this);
    }

    //

    @Override
    public int length() {
        return this.length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= this.length) {
            throw new IndexOutOfBoundsException("index: " + index + ", length: " + this.length);
        }

        ByteBuf current = this.source;
        if (current != null) {
            return (char) (current.getByte(this.offset + index) & 0xFF);
        }
        return this.cachedValue.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end < start || end > this.length) {
            throw new IndexOutOfBoundsException("start: " + start + ", end: " + end + ", length: " + this.length);
        }
        if (start == 0 && end == this.length) {
            return this;
        }
        if (start == end) {
            return "";
        }

        ByteBuf current = this.source;
        if (current != null) {
            return StringView.request(current, this.offset + start, end - start);
        }
        return this.cachedValue.subSequence(start, end);
    }

    public String stringValue() {
        return this.resolve();
    }

    public boolean isResolved() {
        return this.source == null;
    }

    public String resolve() {
        if (this.cachedValue != null) {
            return this.cachedValue;
        }

        ByteBuf current = this.source;
        if (current == null) {
            this.cachedValue = "";
            return this.cachedValue;
        }

        try {
            this.cachedValue = this.readSourceValue(current);
        } finally {
            this.releaseSource();
            this.source = null;
        }
        return this.cachedValue;
    }

    private String readSourceValue(ByteBuf current) {
        if (current.isFree()) {
            return "";
        }
        try {
            return current.getString(this.offset, this.length, StandardCharsets.US_ASCII);
        } catch (IllegalStateException e) {
            return "";
        }
    }

    private void releaseSource() {
        ByteBuf current = this.source;
        if (current != null && !current.isFree()) {
            current.release();
        }
    }

    //

    @Override
    protected void finalize() throws Throwable {
        try {
            this.release();
        } finally {
            super.finalize();
        }
    }

    @Override
    public String toString() {
        return this.resolve();
    }
}

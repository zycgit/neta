/*
 * Copyright 2015-2022 the original author or authors.
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
package net.hasor.neta.channel;
import java.util.*;

/**
 * Layered {@link Map} implementation with local-write, parent-read semantics.
 * <p>Reads such as {@link #get(Object)} and {@link #containsKey(Object)} first check the local map
 * and then fall through to the optional parent chain. Writes such as {@link #put(Object, Object)}
 * and {@link #remove(Object)} affect only the local layer; parent layers are never mutated.
 * <p>This class is a general utility that remains in the channel package, but the current
 * {@link ProtoContextService} implementation no longer uses it directly for flash storage.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2016-07-17
 */
public class MergedMap<K, T> extends AbstractMap<K, T> {
    private final HashMap<K, T>   local;
    private final MergedMap<K, T> parent;

    /** Creates a root map with no parent. */
    public MergedMap() {
        this(null);
    }

    /** Creates a child map whose reads fall through to {@code parent} when not found locally. */
    public MergedMap(MergedMap<K, T> parent) {
        this.local = new HashMap<>();
        this.parent = parent;
    }

    // ---- reads — check local first, then parent chain ----

    @Override
    public T get(Object key) {
        if (this.local.containsKey(key)) {
            return this.local.get(key);
        }
        return this.parent != null ? this.parent.get(key) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return this.local.containsKey(key) || (this.parent != null && this.parent.containsKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return this.local.containsValue(value) || (this.parent != null && this.parent.containsValue(value));
    }

    // ---- writes — local only ----

    @Override
    public T put(K key, T value) {
        return this.local.put(key, value);
    }

    @Override
    public T remove(Object key) {
        return this.local.remove(key);
    }

    @Override
    public void clear() {
        this.local.clear();
        // parent layers are intentionally NOT cleared
    }

    // ---- derived views ----

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public boolean isEmpty() {
        return this.local.isEmpty() && (this.parent == null || this.parent.isEmpty());
    }

    @Override
    public Set<K> keySet() {
        if (this.parent == null) {
            return new HashSet<>(this.local.keySet());
        }
        Set<K> keys = new HashSet<>(this.parent.keySet());
        keys.addAll(this.local.keySet());
        return keys;
    }

    @Override
    public Collection<T> values() {
        return buildMerged().values();
    }

    @Override
    public Set<Entry<K, T>> entrySet() {
        return buildMerged().entrySet();
    }

    /** Build a flat snapshot where local entries shadow parent entries. */
    private Map<K, T> buildMerged() {
        LinkedHashMap<K, T> combined = new LinkedHashMap<>();
        if (this.parent != null) {
            combined.putAll(this.parent.buildMerged());
        }
        combined.putAll(this.local);
        return combined;
    }
}
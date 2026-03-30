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
package net.hasor.neta.channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PartitionKey {
    private static final int                       CACHE_LIMIT = 8192;
    private static final Map<String, PartitionKey> KEY_CACHE   = new ConcurrentHashMap<>();

    private final String partitionKey;
    private final int    hashCode;

    PartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
        this.hashCode = partitionKey.hashCode();
    }

    public String getKey() {
        return this.partitionKey;
    }

    @Override
    public String toString() {
        return this.partitionKey;
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PartitionKey)) {
            return false;
        }

        PartitionKey that = (PartitionKey) obj;
        return this.partitionKey.equals(that.partitionKey);
    }

    public static PartitionKey findKey(ProtoContext context) {
        return context.context(PartitionKey.class);
    }

    public static PartitionKey newKey(String key) {
        return cacheKey(String.valueOf(key));
    }

    public static PartitionKey newKey(byte key) {
        return cacheKey(String.valueOf(key));
    }

    public static PartitionKey newKey(short key) {
        return cacheKey(String.valueOf(key));
    }

    public static PartitionKey newKey(int key) {
        return cacheKey(String.valueOf(key));
    }

    public static PartitionKey newKey(long key) {
        return cacheKey(String.valueOf(key));
    }

    public static PartitionKey newKey(float key) {
        return cacheKey(String.valueOf(Float.floatToIntBits(key)));
    }

    public static PartitionKey newKey(double key) {
        return cacheKey(String.valueOf(Double.doubleToLongBits(key)));
    }

    private static PartitionKey cacheKey(String key) {
        PartitionKey cached = KEY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        PartitionKey created = new PartitionKey(key);
        if (KEY_CACHE.size() >= CACHE_LIMIT) {
            return created;
        }

        PartitionKey existing = KEY_CACHE.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }
}
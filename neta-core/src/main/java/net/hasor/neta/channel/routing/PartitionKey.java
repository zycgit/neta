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
package net.hasor.neta.channel.routing;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.channel.ProtoContext;
/**
 * Key object used to identify a logical partition.
 * <p>Partitioned pipelines use this key to route messages or events into the corresponding
 * partition sub-pipeline. Identical key values try to reuse the same {@code PartitionKey}
 * instance to reduce object allocation overhead in high-frequency partitioning scenarios.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-31
 * @see ProtoPartitionSelector
 * @see ProtoPartitionControl
 */
public class PartitionKey {
    private static final int                       CACHE_LIMIT = 8192;
    private static final Map<String, PartitionKey> KEY_CACHE   = new ConcurrentHashMap<>();
    private static final PartitionKey              defaultPartitionKey;
    private final String                           partitionKey;
    private final int                              hashCode;

    static {
        class DefaultPartitionKey {
        }
        defaultPartitionKey = newKey(DefaultPartitionKey.class.getName());
    }

    PartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
        this.hashCode = partitionKey.hashCode();
    }

    /**
     * Return the string form of the partition key.
     * @return partition key text
     */
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

    /**
     * Return the singleton key representing the explicit default partition.
     * <p>This key is only used when a {@link ProtoPartitionSelector} intentionally routes data into
     * the default partition. It is not equivalent to {@code null}. A selector returning
     * {@code null} means "unmatched", while returning this key means "enter the default
     * partition".</p>
     */
    public static PartitionKey defaultKey() {
        return defaultPartitionKey;
    }

    public static PartitionKey newOrDefault(String key) {
        return key != null ? newKey(key) : defaultPartitionKey;
    }

    /**
     * Find the partition key currently bound in the protocol context.
     * <p>Once data has entered a partition path, this method can be used to retrieve the current
     * partition identity.</p>
     * @param context protocol context
     * @return partition key associated with the current context, or null if the flow has not entered a partition yet
     */
    public static PartitionKey findKey(ProtoContext context) {
        return context.context(PartitionKey.class);
    }

    /**
     * Create a partition key from a string value.
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(String key) {
        return cacheKey(String.valueOf(key));
    }

    /**
     * Create a partition key from a byte value.
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(byte key) {
        return cacheKey(String.valueOf(key));
    }

    /**
     * Create a partition key from a short value.
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(short key) {
        return cacheKey(String.valueOf(key));
    }

    /**
     * Create a partition key from an int value.
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(int key) {
        return cacheKey(String.valueOf(key));
    }

    /**
     * Create a partition key from a long value.
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(long key) {
        return cacheKey(String.valueOf(key));
    }

    /**
     * Create a partition key from a float value.
     * <p>The bit pattern is used instead of decimal text so NaN values and sign bits map stably.</p>
     * @param key key content
     * @return corresponding partition key instance
     */
    public static PartitionKey newKey(float key) {
        return cacheKey(String.valueOf(Float.floatToIntBits(key)));
    }

    /**
     * Create a partition key from a double value.
     * <p>The bit pattern is used instead of decimal text so the key mapping stays stable and reproducible.</p>
     * @param key key content
     * @return corresponding partition key instance
     */
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
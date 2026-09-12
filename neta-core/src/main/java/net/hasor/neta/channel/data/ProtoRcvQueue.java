/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.data;
import java.util.List;
import java.util.function.Predicate;
/**
 * Main receive-side queue interface.
 * <p>On top of the read semantics defined by {@link ProtoRcvData}, it adds capacity management, named views, and the ability to transfer data from the main queue into a view.</p>
 * <p>A main receive queue can maintain multiple named receive views so that data currently in the main queue can be transferred by key into a local view for further processing.</p>
 * <p>The main queue and these named receive views share the same capacity limit. In other words, even after data has been moved from the main queue into a view,
 * it still consumes the same shared capacity and does not release capacity simply because it entered a view.</p>
 * <p>This shared-capacity model is the foundation of receive-side backpressure: backpressure decisions are based on the total amount of data currently held by the entire receive-container system,
 * not just by the number of elements currently present in the main queue itself.</p>
 * @param <T> receive message type
 * @author Yongchun Zhao (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvData
 * @see ProtoRcvQueueView
 * @see ProtoQueue
 */
public interface ProtoRcvQueue<T> extends ProtoRcvData<T> {
    /**
     * Returns the capacity limit of the current main queue.
     * <p>If the implementation allows negative values to represent "unlimited capacity", it should usually normalize that internally to an equivalent unbounded limit.</p>
     * <p>The returned value is the total capacity shared by the main queue and all of its named receive views, rather than capacity reserved exclusively for the main queue itself.</p>
     */
    int getCapacity();

    /**
     * Transfers up to {@code cnt} data items from the current main queue into the receive view identified by the specified key.
     * <p>After the transfer, the data is no longer kept in the main queue, but it still remains within the ownership scope of the same receive-container system.</p>
     * @param key target receive view name
     * @param cnt maximum number of items to transfer; whether values less than 0 are supported is implementation-specific
     */
    void drainToQueue(String key, int cnt);

    /**
     * Transfers all data items matching the predicate from the current main queue into the named receive view.
     * <p>Items that do not match remain in the main queue and keep their relative order.</p>
     * <p>A {@code null} predicate means all items are considered matched.</p>
     * @param key target receive view name
     * @param predicate selector used to decide which items move into the view
     */
    default void drainToQueue(String key, Predicate<T> predicate) {
        this.drainToQueue(key, -1, predicate);
    }

    /**
     * Transfers up to {@code cnt} data items matching the predicate from the current main queue into the named receive view.
     * <p>Only items accepted by the predicate are moved. Items not accepted remain in the main queue and keep their relative order.</p>
     * <p>A {@code null} predicate means all items are considered matched.</p>
     * @param key target receive view name
     * @param cnt maximum number of matched items to transfer; values less than 0 mean transferring all matched items
     * @param predicate selector used to decide which items move into the view
     */
    void drainToQueue(String key, int cnt, Predicate<T> predicate);

    /**
     * Returns the names of all receive views that currently exist under this main queue.
     * @return list of all receive view names
     */
    List<String> queueNames();

    /**
     * Determines whether a receive view with the specified name already exists.
     * @param key receive view name
     * @return {@code true} if the view exists
     */
    boolean hasQueue(String key);

    /**
     * Discards all data in the current view and removes the view from its owning main queue.
     * <p>Once the data in the view is discarded, the corresponding shared capacity is released as well.</p>
     */
    void discard(String key);

    /**
     * Returns the receive view with the specified name.
     * <p>If the view does not yet exist, whether it is created automatically is implementation-specific.</p>
     * <p>Whether newly created or not, the view shares the same capacity constraint as the current main queue.</p>
     * @param key receive view name
     * @return the corresponding receive view
     */
    ProtoRcvQueueView<T> queueView(String key);
}

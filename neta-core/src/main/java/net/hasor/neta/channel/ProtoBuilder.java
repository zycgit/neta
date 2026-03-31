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
import java.util.Objects;
import java.util.function.Consumer;
import net.hasor.cobble.StringUtils;

/**
 * API for assembling a protocol pipeline step by step.
 * <p>This interface describes pipeline state from the perspective of current inbound input type
 * {@code RCV_UP} and current outbound output type {@code SND_DOWN}. Each call to a {@code next*}
 * method appends a new handler to the tail of the current build chain and returns a new
 * {@link ProtoBuilder} view that describes the type changes after that node is added.</p>
 * <p>Common build patterns include:</p>
 * <ul>
 *   <li>Appending a complete duplexer through {@link #nextDuplex(ProtoDuplexer)}.</li>
 *   <li>Adding a one-way handler, decoder or encoder, through {@link #nextDecoder(ProtoHandler)} or {@link #nextEncoder(ProtoHandler)}.</li>
 *   <li>Inserting a routing duplexer through {@code nextRouteAsStatic}/{@code nextRouteAsRealtime} and splitting later processing into multiple branch pipelines.</li>
 *   <li>Inserting a partition duplexer through {@link #nextPartition(String, ProtoPartitionSelector, Consumer)} to dispatch inbound data into different partition pipelines.</li>
 * </ul>
 * <p>After all steps are defined, call {@link #build()} to create the final {@link ProtoInitializer}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see ProtoHelper
 */
public interface ProtoBuilder<RCV_UP, SND_DOWN> extends ProtoBuild {
    /**
     * Append a duplexer at the current build position, using the handler class name as the node
     * name.
     * <p>The duplexer participates in both the receive chain and the send chain. After it is
     * appended, the new receive output type becomes {@code RCV_DOWN}, and the new send input type
     * becomes {@code SND_UP}.</p>
     * @param duplexer duplexer to append
     * @param <RCV_DOWN> receive output type after this duplexer processes inbound data
     * @param <SND_UP> send input type before this duplexer processes outbound data
     * @return the next builder view after appending this duplexer
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
        return this.nextDuplex(duplexer.getClass().getSimpleName(), ProtoConfig.DEFAULT, duplexer);
    }

    /**
     * Append a duplexer at the current build position with an explicit node name.
     * @param name node name
     * @param duplexer duplexer to append
     * @param <RCV_DOWN> receive output type after this duplexer processes inbound data
     * @param <SND_UP> send input type before this duplexer processes outbound data
     * @return the next builder view after appending this duplexer
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer) {
        return this.nextDuplex(name, ProtoConfig.DEFAULT, duplexer);
    }

    /**
     * Append a duplexer at the current build position with an explicit node name and config.
     * @param name node name
     * @param protoConf node configuration such as queue capacities
     * @param duplexer duplexer to append
     * @param <RCV_DOWN> receive output type after this duplexer processes inbound data
     * @param <SND_UP> send input type before this duplexer processes outbound data
     * @return the next builder view after appending this duplexer
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> duplexer);

    /**
     * Append a duplex step composed from a decoder and an encoder.
     * <p>The receive direction is handled by {@code decoder}, and the send direction is handled by
     * {@code encoder}. This overload automatically derives a default node name in the form
     * {@code decoderName/encoderName}.</p>
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this step
     * @param <SND_UP> new send input type after appending this step
     * @return the next builder view after appending this step
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        String decName = decoder.getClass().getSimpleName();
        String encName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? Integer.toHexString(System.identityHashCode(decoder)) : decName;
        encName = StringUtils.isBlank(encName) ? Integer.toHexString(System.identityHashCode(encoder)) : encName;

        String name = String.format("%s/%s", decName, encName);
        return this.nextDuplex(name, ProtoConfig.DEFAULT, decoder, encoder);
    }

    /**
     * Append a duplex step composed from a decoder and an encoder with an explicit name.
     * @param name node name
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this step
     * @param <SND_UP> new send input type after appending this step
     * @return the next builder view after appending this step
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        return this.nextDuplex(name, ProtoConfig.DEFAULT, decoder, encoder);
    }

    /**
     * Append a duplex step composed from a decoder and an encoder with an explicit name and config.
     * @param name node name
     * @param protoConf node configuration such as queue capacities
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this step
     * @param <SND_UP> new send input type after appending this step
     * @return the next builder view after appending this step
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder);

    /**
     * Append a decoder only on the receive direction while keeping the send-direction type
     * unchanged.
     * <p>This is suitable for inbound-only transformations, for example decoding a byte stream into
     * message objects.</p>
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    default <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        Objects.requireNonNull(decoder, "decoder is null.");
        String decName = decoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

        String name = String.format("%s/--", decName);
        return this.nextDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /**
     * Append a decoder only on the receive direction with an explicit name, while keeping the
     * send-direction type unchanged.
     * @param name node name
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    default <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        return this.nextDecoder(name, ProtoConfig.DEFAULT, decoder);
    }

    /**
     * Append a decoder only on the receive direction with an explicit name and config, while
     * keeping the send-direction type unchanged.
     * @param name node name
     * @param protoConf node configuration such as queue capacities
     * @param decoder decoder that converts {@code RCV_UP} to {@code RCV_DOWN}
     * @param <RCV_DOWN> new receive output type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    <RCV_DOWN> ProtoBuilder<RCV_DOWN, SND_DOWN> nextDecoder(String name, ProtoConfig protoConf, ProtoHandler<RCV_UP, RCV_DOWN> decoder);

    /**
     * Append an encoder only on the send direction while keeping the receive-direction type
     * unchanged.
     * <p>This is suitable for outbound-only transformations, for example encoding a message object
     * into a byte stream.</p>
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <SND_UP> new send input type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    default <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(ProtoHandler<SND_UP, SND_DOWN> encoder) {
        Objects.requireNonNull(encoder, "encoder is null.");
        String decName = encoder.getClass().getSimpleName();
        decName = StringUtils.isBlank(decName) ? "Unknown" : decName;

        String name = String.format("--/%s", decName);
        return this.nextEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /**
     * Append an encoder only on the send direction with an explicit name, while keeping the
     * receive-direction type unchanged.
     * @param name node name
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <SND_UP> new send input type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    default <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(String name, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        return this.nextEncoder(name, ProtoConfig.DEFAULT, encoder);
    }

    /**
     * Append an encoder only on the send direction with an explicit name and config, while keeping
     * the receive-direction type unchanged.
     * @param name node name
     * @param protoConf node configuration such as queue capacities
     * @param encoder encoder that converts {@code SND_UP} to {@code SND_DOWN}
     * @param <SND_UP> new send input type after appending this handler
     * @return the next builder view after appending this one-way handler
     */
    <SND_UP> ProtoBuilder<RCV_UP, SND_UP> nextEncoder(String name, ProtoConfig protoConf, ProtoHandler<SND_UP, SND_DOWN> encoder);

    /**
     * Append a routing duplexer using static data routing with an explicit name.
     * <p>Whenever inbound data reaches this routing duplexer, {@code routing} is invoked to decide
     * which branch to enter based on the current context and current data. The selected branch must
     * come from the branch pipelines declared in {@code branches}.</p>
     * <p>Static routing caches the selected branch after the first successful route, and later
     * inbound data reuses that result. To switch explicitly, a branch handler can call
     * {@link ProtoRoutingControl#switchRoute(String)} to move to another branch.</p>
     * @param name routing node name
     * @param routing router that chooses a branch based on context or inbound data
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsStatic(String name, ProtoRoutingDataSelector<RCV_DOWN, SND_UP> routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches) {
        return this.nextRouteAsStatic(name, ProtoConfig.DEFAULT, routing, branches);
    }

    /**
     * Append a routing duplexer using static data routing with an explicit name and config.
     * <p>Whenever inbound data reaches this routing duplexer, {@code routing} is invoked to decide
     * which branch to enter based on the current context and current data. The selected branch must
     * come from the branch pipelines declared in {@code branches}.</p>
     * <p>Static routing caches the selected branch after the first successful route, and later
     * inbound data reuses that result. To switch explicitly, a branch handler can call
     * {@link ProtoRoutingControl#switchRoute(String)} to move to another branch.</p>
     * @param name routing node name
     * @param protoConf node configuration such as queue capacities
     * @param routing router that chooses a branch based on context or inbound data
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsStatic(String name, ProtoConfig protoConf, ProtoRoutingDataSelector<RCV_DOWN, SND_UP> routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches);

    /**
     * Append a routing duplexer using static event routing with an explicit name.
     * <p>When a network event reaches this routing duplexer, {@code routing} is invoked to decide
     * which branch to enter based on the current event. The selected branch must come from the
     * branch pipelines declared in {@code branches}.</p>
     * <p>This route decision is based on network events and is cached after the first hit. To
     * switch explicitly, a branch handler can call
     * {@link ProtoRoutingControl#switchRoute(String)} to move to another branch.</p>
     * @param name routing node name
     * @param routing router that chooses a branch based on network events
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsStatic(String name, ProtoRoutingEventSelector routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches) {
        return this.nextRouteAsStatic(name, ProtoConfig.DEFAULT, routing, branches);
    }

    /**
     * Append a routing duplexer using static event routing with an explicit name and config.
     * <p>When a network event reaches this routing duplexer, {@code routing} is invoked to decide
     * which branch to enter based on the current event. The selected branch must come from the
     * branch pipelines declared in {@code branches}.</p>
     * <p>Static event routing caches the first matching branch selection and reuses it on later
     * hits. To switch explicitly, a branch handler can call
     * {@link ProtoRoutingControl#switchRoute(String)} to move to another branch.</p>
     * @param name routing node name
     * @param protoConf node configuration such as queue capacities
     * @param routing router that chooses a branch based on network events
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsStatic(String name, ProtoConfig protoConf, ProtoRoutingEventSelector routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches);

    /**
     * Append a routing duplexer using realtime data routing with an explicit name.
     * <p>Each time inbound data reaches this routing duplexer, {@code routing} is invoked to decide
     * again which branch to enter based on the current context and current data. The selected
     * branch must come from the branch pipelines declared in {@code branches}.</p>
     * <p>Realtime routing does not cache branch results, so the active branch may be recomputed on
     * every visit to the routing node.</p>
     * @param name routing node name
     * @param routing router that chooses a branch based on context or inbound data
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    default <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsRealtime(String name, ProtoRoutingDataSelector<RCV_DOWN, SND_UP> routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches) {
        return this.nextRouteAsRealtime(name, ProtoConfig.DEFAULT, routing, branches);
    }

    /**
     * Append a routing duplexer using realtime data routing with an explicit name and config.
     * <p>Each time inbound data reaches this routing duplexer, {@code routing} is invoked to decide
     * again which branch to enter based on the current context and current data. The selected
     * branch must come from the branch pipelines declared in {@code branches}.</p>
     * <p>Realtime routing does not cache branch results, so each hit triggers a fresh branch
     * selection.</p>
     * @param name routing node name
     * @param protoConf node configuration such as queue capacities
     * @param routing router that chooses a branch based on context or inbound data
     * @param branches callback used to declare branch pipelines
     * @param <RCV_DOWN> receive output type after the routing node
     * @param <SND_UP> send input type before the routing node
     * @return the next builder view after appending this routing node
     */
    <RCV_DOWN, SND_UP> ProtoBuilder<RCV_DOWN, SND_UP> nextRouteAsRealtime(String name, ProtoConfig protoConf, ProtoRoutingDataSelector<RCV_DOWN, SND_UP> routing, Consumer<ProtoRoutingBuilder<RCV_DOWN, SND_UP>> branches);

    /**
     * Append a partition duplexer using partition routing with an explicit name.
     * <p>When inbound data reaches this partition duplexer, {@code routing} is invoked to compute
     * the {@link PartitionKey} for the current data, and the data is then delivered into the
     * partition sub-pipeline associated with that key.</p>
     * <p>The partition sub-pipelines keep independent context and handler state for different
     * partition keys, while the same partition key continuously reuses the same partition instance.</p>
     * <p>If you need to read the current partition ID inside a partition sub-pipeline, use
     * {@link PartitionKey#findKey(ProtoContext)} or read {@link PartitionKey} directly through
     * {@link ProtoContext#context(Class)}.</p>
     * @param name partition node name
     * @param routing partition selector
     * @param initializer callback used to declare the internal partition pipeline
     * @return the builder view after appending this partition node
     */
    default ProtoBuilder<RCV_UP, SND_DOWN> nextPartition(String name, ProtoPartitionSelector routing, Consumer<ProtoPartitionBuilder<RCV_UP, SND_DOWN>> initializer) {
        return this.nextPartition(name, ProtoConfig.DEFAULT, routing, initializer);
    }

    /**
     * Append a partition duplexer using partition routing with an explicit name and config.
     * <p>When inbound data reaches this partition duplexer, {@code routing} is invoked to compute
     * the {@link PartitionKey} for the current data, and the data is then delivered into the
     * partition sub-pipeline associated with that key.</p>
     * <p>The partition sub-pipelines keep independent context and handler state for different
     * partition keys, while the same partition key continuously reuses the same partition instance.</p>
     * <p>If you need to read the current partition ID inside a partition sub-pipeline, use
     * {@link PartitionKey#findKey(ProtoContext)} or read {@link PartitionKey} directly through
     * {@link ProtoContext#context(Class)}.</p>
     * @param name partition node name
     * @param protoConf node configuration such as queue capacities
     * @param routing partition selector
     * @param initializer callback used to declare the internal partition pipeline
     * @return the builder view after appending this partition node
     */
    ProtoBuilder<RCV_UP, SND_DOWN> nextPartition(String name, ProtoConfig protoConf, ProtoPartitionSelector routing, Consumer<ProtoPartitionBuilder<RCV_UP, SND_DOWN>> initializer);
}
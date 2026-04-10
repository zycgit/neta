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
package net.hasor.neta.codec.http.cors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/**
 * Pipeline handler that provides CORS (Cross-Origin Resource Sharing) support.
 * <h3>How It Works</h3>
 * This handler accepts a staged {@link HttpObject} request stream rather than requiring the upstream
 * side to aggregate the whole request into a single object first.
 * It buffers the request line and header-stage objects for each stream and waits until
 * {@link LastHttpHeaders} arrives before deciding whether to:
 * <ul>
 *   <li><strong>Preflight Request</strong> ({@code OPTIONS} + {@code Access-Control-Request-Method}):
 *       emit a streaming {@code 204 No Content} response, namely
 *       {@link DefaultHttpResponse} + {@link DefaultLastHttpHeaders} + {@link DefaultLastHttpContent}。
 *       When the current origin satisfies the configuration, CORS headers are added to the response headers.
 *       The original request will not continue downstream.</li>
 *   <li><strong>Regular Request</strong>:
 *       once the header stage has been fully examined, the request objects continue downstream unchanged.
 *       This handler does not reject origins and does not add simple-request CORS headers to the eventual
 *       business response.</li>
 *   <li><strong>CORS Disabled Configuration</strong>:
 *       the request is passed through unchanged.</li>
 * </ul>
 * <h3>Usage Example</h3>
 * <pre>
 *   CorsConfig config = CorsConfig.builder()
 *       .allowOrigins("https://example.com")
 *       .allowMethods("GET", "POST")
 *       .allowHeaders("Content-Type", "Authorization")
 *       .allowCredentials(true)
 *       .maxAge(3600)
 *       .build();
 *   ProtoInitializer init = ctx -> {
 *       ctx.addLastDecoder("frame",   new HttpRequestDecoder());
 *       ctx.addLastDecoder("cors",    new CorsHandler(config));
 *       ctx.addLastEncoder("enc",     new HttpResponseEncoder());
 *   };
 *   netManager.subscribe(payload -> {
 *       Object msg = payload.getData();
 *       // Preflight responses are emitted directly as HttpResponse / LastHttpHeaders / LastHttpContent
 *       // Regular requests continue downstream as the original HttpObject stream
 *   });
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 * @see CorsConfig
 * @see CorsUtil
 */
public class CorsHandler implements ProtoHandler<HttpObject, Object> {
    private final CorsConfig             config;
    private final Map<Long, StreamState> streamState = new HashMap<Long, StreamState>();

    /**
     * Creates a new {@code CorsHandler} with the given configuration.
     * @param config the CORS configuration, which must not be {@code null}
     */
    public CorsHandler(CorsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    /** Returns the CORS configuration used by the current handler. */
    public CorsConfig config() {
        return config;
    }

    /**
     * Processes staged HTTP requests and generates a preflight response or passes the request through as needed.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
        if (!src.hasMore()) {
            return ProtoStatus.Stop;
        }

        while (src.hasMore()) {
            HttpObject object = src.takeMessage();
            if (object == null) {
                continue;
            }
            processObject(object, dst);
        }
        return ProtoStatus.Next;
    }

    private void processObject(HttpObject object, ProtoSndQueue<Object> dst) {
        long streamId = object.streamId();
        StreamState state = this.streamState.get(streamId);

        if (state != null && state.discarding) {
            discardObject(state, object);
            return;
        }

        if (object instanceof HttpRequest) {
            if (state != null) {
                flushBufferedRequest(state, dst);
            }
            state = new StreamState((HttpRequest) object);
            this.streamState.put(streamId, state);
            state.buffered.add(object);
        } else if (state != null && !state.headerPhaseFinished) {
            state.buffered.add(object);
        } else {
            dst.offerMessage(object);
            return;
        }

        if (object instanceof HttpHeaders) {
            state.requestHeaders.appendHeaders((HttpHeaders) object);
        }

        if (object instanceof LastHttpHeaders) {
            state.headerPhaseFinished = true;
            if (shouldHandlePreflight(state)) {
                emitPreflightResponse(state, dst);
                releaseBufferedRequest(state);
                if (isRequestComplete(object)) {
                    removeState(state.streamId);
                } else {
                    state.discarding = true;
                }
                return;
            }

            flushBufferedRequest(state, dst);
        }
    }

    private boolean shouldHandlePreflight(StreamState state) {
        if (!this.config.isEnabled()) {
            return false;
        }

        String origin = CorsUtil.getOrigin(state.requestHeaders);
        if (StringUtils.isBlank(origin)) {
            return false;
        }

        return CorsUtil.isPreflightRequest(state.requestLine, state.requestHeaders);
    }

    private void emitPreflightResponse(StreamState state, ProtoSndQueue<Object> dst) {
        DefaultHttpResponse response = new DefaultHttpResponse(state.requestLine.protocolVersion(), HttpStatus.NO_CONTENT);
        DefaultLastHttpHeaders responseHeaders = new DefaultLastHttpHeaders();
        DefaultLastHttpContent responseContent = new DefaultLastHttpContent(ByteBuf.EMPTY);

        response.streamId(state.streamId);
        responseHeaders.streamId(state.streamId);
        responseContent.streamId(state.streamId);
        responseHeaders.setHeader(HttpHeaderNames.CONTENT_LENGTH, "0");

        CorsUtil.applyPreflightCorsHeaders(state.requestLine, state.requestHeaders, responseHeaders, this.config);

        dst.offerMessage(response);
        dst.offerMessage(responseHeaders);
        dst.offerMessage(responseContent);
    }

    private void flushBufferedRequest(StreamState state, ProtoSndQueue<Object> dst) {
        if (!state.buffered.isEmpty()) {
            for (HttpObject object : state.buffered) {
                dst.offerMessage(object);
            }
            state.buffered.clear();
        }
        removeState(state.streamId);
    }

    private void discardObject(StreamState state, HttpObject object) {
        object.release();
        if (isRequestComplete(object)) {
            removeState(state.streamId);
        }
    }

    private void releaseBufferedRequest(StreamState state) {
        for (HttpObject object : state.buffered) {
            object.release();
        }
        state.buffered.clear();
    }

    private boolean isRequestComplete(HttpObject object) {
        return object instanceof LastHttpContent;
    }

    private void removeState(long streamId) {
        StreamState removed = this.streamState.remove(streamId);
        if (removed != null) {
            removed.requestHeaders.release();
        }
    }

    private static class StreamState {
        private final long               streamId;
        private final HttpRequest        requestLine;
        private final DefaultHttpHeaders requestHeaders = new DefaultHttpHeaders();
        private final List<HttpObject>   buffered       = new ArrayList<HttpObject>();
        private       boolean            headerPhaseFinished;
        private       boolean            discarding;

        private StreamState(HttpRequest requestLine) {
            this.streamId = requestLine.streamId();
            this.requestLine = requestLine;
            this.requestHeaders.streamId(this.streamId);
        }
    }
}

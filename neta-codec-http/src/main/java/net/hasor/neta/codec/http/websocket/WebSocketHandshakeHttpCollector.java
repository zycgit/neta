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
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.codec.http.*;

final class WebSocketHandshakeHttpCollector {
    private WebSocketHandshakeHttpCollector() {
    }

    static final class RequestCollector {
        private DefaultHttpRequest   requestLine;
        private DefaultHttpHeaders   headers;
        private CompositeByteBuf     content;
        private boolean              headersClosed;
        private boolean              complete;

        boolean isActive() {
            return this.requestLine != null;
        }

        boolean isHeadersClosed() {
            return this.headersClosed;
        }

        boolean isComplete() {
            return this.complete;
        }

        void append(HttpObject msg) {
            if (msg instanceof FullHttpRequest) {
                this.captureFullRequest((FullHttpRequest) msg);
                return;
            }
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;
                this.requestLine = new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
                this.requestLine.streamId(request.streamId());
            }
            if (msg instanceof HttpHeaders) {
                this.headers().appendHeaders((HttpHeaders) msg);
                if (msg instanceof LastHttpHeaders) {
                    this.headersClosed = true;
                }
            }
            if (msg instanceof HttpContent) {
                this.appendContent(((HttpContent) msg).content());
                if (msg instanceof LastHttpContent) {
                    this.complete = true;
                }
            }
        }

        FullHttpRequest snapshot() {
            if (this.requestLine == null) {
                return null;
            }
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(this.requestLine.protocolVersion(), this.requestLine.method(), this.requestLine.uri(), cloneContent(this.content), copyHeaders(this.headers));
            request.streamId(this.requestLine.streamId());
            return request;
        }

        void reset() {
            if (this.content != null) {
                this.content.free();
            }
            this.requestLine = null;
            this.headers = null;
            this.content = null;
            this.headersClosed = false;
            this.complete = false;
        }

        private void captureFullRequest(FullHttpRequest request) {
            this.reset();
            this.requestLine = new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
            this.requestLine.streamId(request.streamId());
            this.headers = copyHeaders(request);
            this.headersClosed = true;
            this.complete = true;
            this.appendContent(request.content());
        }

        private DefaultHttpHeaders headers() {
            if (this.headers == null) {
                this.headers = new DefaultHttpHeaders();
            }
            return this.headers;
        }

        private void appendContent(ByteBuf part) {
            if (part == null || part.readableBytes() == 0) {
                return;
            }
            if (this.content == null) {
                this.content = new CompositeByteBuf(ByteBufAllocator.DEFAULT);
            }
            this.content.addComponent(cloneContent(part));
        }
    }

    static final class ResponseCollector {
        private DefaultHttpResponse  responseLine;
        private DefaultHttpHeaders   headers;
        private CompositeByteBuf     content;
        private boolean              complete;

        boolean isActive() {
            return this.responseLine != null;
        }

        boolean isComplete() {
            return this.complete;
        }

        void append(HttpObject msg) {
            if (msg instanceof FullHttpResponse) {
                this.captureFullResponse((FullHttpResponse) msg);
                return;
            }
            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                this.responseLine = new DefaultHttpResponse(response.protocolVersion(), response.status());
                this.responseLine.streamId(response.streamId());
            }
            if (msg instanceof HttpHeaders) {
                this.headers().appendHeaders((HttpHeaders) msg);
            }
            if (msg instanceof HttpContent) {
                this.appendContent(((HttpContent) msg).content());
                if (msg instanceof LastHttpContent) {
                    this.complete = true;
                }
            }
        }

        FullHttpResponse snapshot() {
            if (this.responseLine == null) {
                return null;
            }
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(this.responseLine.protocolVersion(), this.responseLine.status(), cloneContent(this.content), copyHeaders(this.headers));
            response.streamId(this.responseLine.streamId());
            return response;
        }

        void reset() {
            if (this.content != null) {
                this.content.free();
            }
            this.responseLine = null;
            this.headers = null;
            this.content = null;
            this.complete = false;
        }

        private void captureFullResponse(FullHttpResponse response) {
            this.reset();
            this.responseLine = new DefaultHttpResponse(response.protocolVersion(), response.status());
            this.responseLine.streamId(response.streamId());
            this.headers = copyHeaders(response);
            this.complete = true;
            this.appendContent(response.content());
        }

        private DefaultHttpHeaders headers() {
            if (this.headers == null) {
                this.headers = new DefaultHttpHeaders();
            }
            return this.headers;
        }

        private void appendContent(ByteBuf part) {
            if (part == null || part.readableBytes() == 0) {
                return;
            }
            if (this.content == null) {
                this.content = new CompositeByteBuf(ByteBufAllocator.DEFAULT);
            }
            this.content.addComponent(cloneContent(part));
        }
    }

    private static DefaultHttpHeaders copyHeaders(HttpHeaders source) {
        DefaultHttpHeaders copied = new DefaultHttpHeaders();
        if (source != null) {
            copied.appendHeaders(source);
        }
        return copied;
    }

    private static ByteBuf cloneContent(ByteBuf source) {
        if (source == null || source.readableBytes() == 0) {
            return ByteBuf.EMPTY;
        }
        int length = source.readableBytes();
        byte[] copied = new byte[length];
        source.getBytes(0, copied, 0, length);
        ByteBuf target = ByteBufAllocator.DEFAULT.buffer(length, Integer.MAX_VALUE);
        target.writeBytes(copied, 0, copied.length);
        target.markWriter();
        return target;
    }
}
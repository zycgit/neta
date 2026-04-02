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
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.codec.http.AbstractHttpEvent;

/**
 * Network event that requests an active reset of a specific HTTP/3 request stream.
 */
public class Http3ResetEvent extends AbstractHttpEvent {
    public static final long CANCEL         = -1L;
    public static final long INTERNAL_ERROR = -2L;
    public static final long REFUSED        = -3L;

    private final long errorCode;

    public Http3ResetEvent(long streamId, long errorCode) {
        this.streamId(streamId);
        this.errorCode = errorCode;
    }

    public long errorCode() {
        return this.errorCode;
    }
}
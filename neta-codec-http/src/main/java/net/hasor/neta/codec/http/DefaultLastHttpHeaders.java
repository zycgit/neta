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
package net.hasor.neta.codec.http;

/**
 * Default implementation of {@link LastHttpHeaders}.
 * <p>
 * This is the final header block of a message. Any following object belongs to the body section.
 */
public class DefaultLastHttpHeaders extends DefaultHttpHeaders implements LastHttpHeaders {
    public static final LastHttpHeaders EMPTY = new DefaultLastHttpHeaders();

    /** Creates an empty terminal header block. */
    public DefaultLastHttpHeaders() {
        super();
    }
}
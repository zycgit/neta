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
/**
 * Base exception for channel states related to closure.
 * <p>This exception family covers both fully closed channels and transport-specific partial-close
 * signals. {@link SoCloseException} itself represents a terminal close state, where the channel
 * should no longer be considered usable. {@link SoInputCloseException} is more specific: it means
 * the inbound side has been closed while outbound writes may still be possible.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoInputCloseException
 * @see SoChannel#isClose()
 */
public class SoCloseException extends SoException {
    public SoCloseException(String s) {
        super(s);
    }

    public SoCloseException(String s, Throwable e) {
        super(s, e);
    }
}
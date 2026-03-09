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
 * Thrown when a server channel fails to bind to the requested local address or port.
 * <p>Common causes:
 * <ul>
 *   <li><b>Address already in use</b> – another process or Neta server channel is already
 *       listening on the same address/port combination.</li>
 *   <li><b>Permission denied</b> – binding to a privileged port (&lt;1024) without sufficient
 *       OS-level permissions.</li>
 *   <li><b>Invalid local address</b> – the address does not correspond to any active
 *       network interface on the local machine.</li>
 * </ul>
 * <p>When this exception is thrown the server channel was never successfully opened —
 * no {@link SoCloseException} will follow.  The failed channel object should be
 * discarded and the configuration corrected before trying again.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoException
 */
public class SoBindException extends SoException {
    public SoBindException(String s) {
        super(s);
    }

    public SoBindException(String s, Throwable e) {
        super(s, e);
    }
}
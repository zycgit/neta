/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Thrown when a server channel cannot bind to the requested local address or port.
 * <p>Common causes include:</p>
 * <ul>
 *   <li><b>Address already in use</b>: another process or Neta server channel is already listening
 *       on the same address and port combination.</li>
 *   <li><b>Insufficient privileges</b>: binding a privileged port (&lt;1024) without adequate system permission.</li>
 *   <li><b>Invalid local address</b>: the address does not belong to any active network interface on the host.</li>
 * </ul>
 * <p>When this exception is thrown, the server channel never actually opened successfully, so it is
 * not followed by {@link SoCloseException}. The failed channel object should be discarded, the
 * configuration corrected, and the bind retried.</p>
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

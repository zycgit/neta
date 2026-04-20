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
package net.hasor.neta.channel.transport.sctp;
import java.net.SocketAddress;
import java.util.Set;
import com.sun.nio.sctp.Association;
import net.hasor.cobble.StringUtils;
/**
 * Wrapper object for SCTP address information.
 * <p>This type holds both the {@link Association} and a set of bound addresses so that
 * multi-homed address information can be represented uniformly at the framework level.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SctpSocketAddress extends SocketAddress {
    private final Association        association;
    private final Set<SocketAddress> addresses;

    SctpSocketAddress(Association association, Set<SocketAddress> addresses) {
        this.association = association;
        this.addresses = addresses;
    }

    /**
     * Return the SCTP association for the current address.
     * @return the SCTP association
     */
    public Association getAssociation() {
        return this.association;
    }

    /**
     * Return the set of addresses currently bound to this association.
     * @return the address set
     */
    public Set<SocketAddress> getAddresses() {
        return this.addresses;
    }

    /**
     * Return an address description suitable for logging.
     * @return the textual address description
     */
    @Override
    public String toString() {
        return "association=" + this.association + ", addresses[" + StringUtils.join(this.addresses.toArray(), ", ") + "]";
    }
}
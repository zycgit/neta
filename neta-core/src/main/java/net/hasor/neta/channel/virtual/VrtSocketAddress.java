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
package net.hasor.neta.channel.virtual;
import java.net.SocketAddress;
import net.hasor.cobble.ObjectUtils;

/**
 * Address object used by the virtual transport.
 * <p>This address is not an IP/port tuple. It is a simple integer endpoint identifier.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtSocketAddress extends SocketAddress {
    private final int     address;
    private final boolean connectMode;

    /**
     * Create a virtual address in bind mode.
     * @param address the numeric address
     */
    public VrtSocketAddress(int address) {
        this(address, false);
    }

    /**
     * Create a virtual address.
     * @param address the numeric address
     * @param connectMode whether it is used in connect mode
     */
    public VrtSocketAddress(int address, boolean connectMode) {
        this.address = ObjectUtils.checkPositiveOrZero(address, "address");
        this.connectMode = connectMode;
    }

    /**
     * Return the numeric endpoint carried by this virtual address.
     * @return the numeric address
     */
    public int getAddress() {
        return this.address;
    }

    /**
     * Determine whether the current address is used in connect mode.
     * @return true if this is a connect-mode address
     */
    public boolean isConnectMode() {
        return this.connectMode;
    }

    /**
     * Compute the hash code of the current address.
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Long.hashCode(this.address);
    }

    /**
     * Determine whether another object is equal to this virtual address.
     * @param obj the object to compare
     * @return true if the two objects are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof VrtSocketAddress) {
            VrtSocketAddress that = (VrtSocketAddress) obj;
            return this.address == that.address;
        }
        return false;
    }

    /**
     * Return the string form of the current virtual address.
     * @return the address string
     */
    @Override
    public String toString() {
        return "vrt:" + (this.connectMode ? "connect" : "bind") + ":" + this.address;
    }
}
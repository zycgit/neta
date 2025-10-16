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
import net.hasor.neta.channel.SoChannel;

/**
 * Base class for {@link SoChannel} implementations that are used in an embedded fashion.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtSocketAddress extends SocketAddress {
    private final int     address;
    private final boolean connectMode;

    public VrtSocketAddress(int address) {
        this(address, false);
    }

    public VrtSocketAddress(int address, boolean connectMode) {
        this.address = ObjectUtils.checkPositiveOrZero(address, "address");
        this.connectMode = connectMode;
    }

    public int getAddress() {
        return this.address;
    }

    public boolean isConnectMode() {
        return this.connectMode;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(this.address);
    }

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

    @Override
    public String toString() {
        return "vrt:" + (this.connectMode ? "connect" : "bind") + ":" + this.address;
    }
}
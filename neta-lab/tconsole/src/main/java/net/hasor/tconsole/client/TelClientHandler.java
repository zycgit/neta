/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package net.hasor.tconsole.client;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.PlayLoadListener;
import net.hasor.tconsole.TelAttribute;
import static net.hasor.tconsole.TelOptions.ENDCODE_OF_SILENT;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2019年10月30日
 */
class TelClientHandler implements PlayLoadListener {
    private static final Logger                 logger       = LoggerFactory.getLogger(TelClientHandler.class);
    private final        TelAttribute           telAttribute;
    private final        TelClientEventListener closeFuture;
    private final        BasicFuture<Object>    activeFuture;
    private final        ByteBuf                receiveDataBuffer;
    private              boolean                receiveReady = false;

    TelClientHandler(TelAttribute telAttribute, TelClientEventListener closeFuture, ByteBuf receiveDataBuffer) {
        this.activeFuture = new BasicFuture<>();
        this.closeFuture = closeFuture;
        this.receiveDataBuffer = receiveDataBuffer;
        this.telAttribute = telAttribute;
    }

    public void waitActive() {
        try {
            this.activeFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEvent(PlayLoad data) {
        if (!data.isSuccess()) {
            Throwable e = data.getError();
            logger.error(e.getMessage(), e);
            data.getSource().close();
            this.closeFuture.onEventClient();
            if (!this.activeFuture.isDone()) {
                this.activeFuture.failed(e);
            }
            return;
        }

        if (!this.receiveReady) {
            if (data.getData().equals(this.telAttribute.getAttribute(ENDCODE_OF_SILENT))) {
                this.receiveReady = true;//上面设置 ENDCODE_OF_SILENT 之后就会返回。
                this.activeFuture.completed(new Object());
            }
            return;
        }

        this.receiveDataBuffer.writeString(data.getData() + "\n", StandardCharsets.UTF_8);
        this.receiveDataBuffer.markWriter();
    }
}
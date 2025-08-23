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
package net.hasor.neta.handler;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.SoChannel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application stack builder
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public class ProtoEventBus implements EventBus {
    private static final Logger              logger   = Logger.getLogger(ProtoEventBus.class);
    private final        List<EventListener> rootList = new CopyOnWriteArrayList<>();

    @Override
    public void triggerReceive(SoChannel<?> channel, Object obj) {
        if (this.rootList.isEmpty()) {
            String msg = "rcv(" + channel.getChannelId() + ") There are no program at the tail of the ProtoStack, Skipping event: ";
            logger.warn(msg + obj);
        } else {
            BusPlayLod playLod = new BusPlayLod(obj, null, channel);
            this.trigger(EventBus.TOPIC_CHANNEL + channel.getChannelId(), playLod);
        }
    }

    @Override
    public void triggerError(SoChannel<?> channel, Throwable error, boolean isRcv) {
        String msg;
        if (isRcv) {
            msg = "rcv(" + channel.getChannelId() + ") rcv Exception was fired, and it reached at the tail of the ProtoStack." //
                    + " It usually means the last handler in the ProtoStack did not handle the rcv exception.";
        } else {
            msg = "snd(" + channel.getChannelId() + ") snd Exception was fired, and it reached at the head of the ProtoStack." //
                    + " It usually means the first handler in the ProtoStack did not handle the snd exception.";
        }
        logger.warn(msg, error);
        BusPlayLod playLod = new BusPlayLod(null, error, channel);
        this.trigger(EventBus.TOPIC_CHANNEL + channel.getChannelId(), playLod);
    }

    //

    @Override
    public void trigger(String channel, Object data) {
        BusPlayLod playLod = data instanceof BusPlayLod ? (BusPlayLod) data : new BusPlayLod(data, null, null);
        for (EventListener listener : this.rootList) {
            try {
                listener.onEvent(playLod);
            } catch (Exception e) {
                logger.error("event(" + channel + ") eventListener " + listener.getClass().getName() + " has error " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void subscribe(String topic, EventListener listener) {
        this.rootList.add(listener);
    }

    private static final class BusPlayLod implements PlayLod {
        private final boolean      success;
        private final Object       data;
        private final Throwable    error;
        private final SoChannel<?> source;

        public BusPlayLod(Object data, Throwable error, SoChannel<?> source) {
            this.success = error == null;
            this.data = data;
            this.error = error;
            this.source = source;
        }

        @Override
        public SoChannel<?> getSource() {
            return this.source;
        }

        @Override
        public Object getData() {
            return this.data;
        }

        @Override
        public Throwable getError() {
            return this.error;
        }

        @Override
        public boolean isSuccess() {
            return this.success;
        }
    }
}

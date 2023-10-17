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
package net.hasor.cobble.net;
import net.hasor.cobble.concurrent.future.Future;

/**
 * manage all network NetChannel and NetListen
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SoContext {
    /** return global config. */
    SoConfig getConfig();

    /** default {@link SoResManager}  */
    SoResManager getResourceManager();

    /** submit async tasks */
    <T> Future<T> submitSoTask(AbstractSoTask mainTask, T result);

    /** submit async tasks, use special {@link SoResManager} run it. */
    <T> Future<T> submitSoTask(SoResManager rm, AbstractSoTask mainTask, T result);

    /** test channel is not exist or closed */
    boolean isClose(long channelID);

    /** force close network channel, like {@link SoChannel#closeNow()} */
    void closeChannel(long channelID, String message);
}
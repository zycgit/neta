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
 * Handler status
 * @version : 2024-01-07
 * @author 赵永春 (zyc@hasor.net)
 */
public enum SoHandlerStatus {
    /** Waiting to be processed or in progress */
    PENDING,
    /** Waiting for IO. */
    WAITING,
    /** Idle, which usually means that the handler has exited the event loop. */
    IDLE
}
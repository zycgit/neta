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
/**
 * Role marker used by virtual channels.
 * <p>{@link #Client} and {@link #Server} are applied to the concrete
 * {@link VrtChannel} views exposed after a virtual link is established. The
 * {@link #Default} value is the neutral preset used by configuration and by
 * connect-mode client creation before either side-specific channel is materialized.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public enum VrtMode {
    Default,
    Server,
    Client
}
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
package net.hasor.cobble.net.channel;

import java.io.IOException;

/**
 * The network protocol layer is used to represent unidirectional data processors,
 * and two {@link PipeHandler} in opposite directions form a duplex protocol layer {@link PipeLayer}
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.cobble.net.channel.PipeLayer
 */
public interface PipeHandler<IN, OUT> {
    void doHandler(PipeContext context, IN src, OUT dst) throws IOException;
}
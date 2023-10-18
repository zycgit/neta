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
package net.hasor.cobble.net.handler;

import net.hasor.cobble.net.channel.PipeContext;
import net.hasor.cobble.net.channel.PipeLayer;

import java.io.IOException;

/**
 * 网络协议层用于表示单向的数据处理器，两个方向相反的数据处理器会组成一个全双工协议层 {@link PipeLayer}
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see net.hasor.cobble.net.channel.PipeLayer
 */
public interface PipeHandler<IN, OUT> {
    void doHandler(PipeContext context, IN src, OUT dst) throws IOException;
}
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
package net.hasor.neta.handler.ssl;
/**
 * SSL mode of working
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-12-19
 */
public enum SslMode {
    /**
     * always work in ssl mode, If ssl is closed, the handshake is restarted
     */
    Always,
    /**
     * The use of ssl is done manually. see {@link SslContext#closeSSL()}、{@link SslContext#openSSL()}
     */
    Manual,
}
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
/**
 * Gets called if a Throwable was thrown.
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeLayer
 * @see PipeHandler
 * */
public interface PipeExceptionHandler {
    /**
     * clear the exception state and continue piple execution
     *
     * <p>You can clear the exception flag with the {@link PipeExceptionHandler#clear()} method, and piple execution will continue normally</p>
     *
     * <pre>
     *  ... -> doLayer -> doError -> doError(invoker clear) -> doLayer -> ...
     * </pre>
     */
    void clear();

    /**
     * get exceptions
     */
    Throwable getException();
}

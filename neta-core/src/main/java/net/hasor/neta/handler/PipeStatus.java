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
 * A status for {@link PipeLayer}
 * @version : 2023-10-18
 * @author 赵永春 (zyc@hasor.net)
 */
public enum PipeStatus {
    /**
     * Continuing the execution pipeline
     * <pre>
     *  /---------------\     /---------------\     /---------------\
     *  | PipeLayer (0) |  >  | PipeLayer (1) |  >  | PipeLayer (2) | > ...
     *  \---------------/     \---------------/     \---------------/
     * </pre>
     */
    Next,

    /**
     * Retry this method call, using again to avoid recursion
     * <pre>
     *                     ┏━━━━━━┓
     *  /---------------\  ┃  /---┸-----------\     /---------------\
     *  | PipeLayer (0) |  ┸> | PipeLayer (1) |  >  | PipeLayer (2) | > ...
     *  \---------------/     \---------------/     \---------------/
     *                             Current
     * </pre>
     */
    Again,

    /**
     * Interrupt pipeline event propagation, until next time.
     * <pre>
     *     /---------------\     /---------------\     /---------------\
     * ... | PipeLayer (0) |  ×  | PipeLayer (1) |  ×  | PipeLayer (2) |
     *     \---------------/     \---------------/     \---------------/
     *          Current                Skip                  Skip
     * </pre>
     */
    Exit,

    /**
     * Interrupt the pipeline event propagation and go back to the head of the pipeline.
     * <pre>
     * ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
     * ┃  /---------------\     /---------------\     /-┸-------------\
     * ┗> | PipeLayer (0) |  >  | PipeLayer (1) |  >  | PipeLayer (2) | ...
     *    \---------------/     \---------------/     \---------------/
     *        go head                                      Current
     * </pre>
     */
    StartOver,
}
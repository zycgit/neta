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
 * A status for {@link ProtoDuplexer}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 */
public enum ProtoStatus {
    /**
     * Continuing the execution protocol stack
     * <pre>
     *  ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
     *  ┃ Handler (0) ┃ > ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
     *       Next              Next              Next
     * </pre>
     */
    Next,

    /**
     * Retry this method call, using again to avoid recursion
     * <pre>
     *                  ╭──────╮
     *  ┏━━━━━━━━━━━━━┓ │  ┏━━━┷━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
     *  ┃ Handler (0) ┃ ┷> ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
     *       Next               Retry             Next
     * </pre>
     */
    Retry,

    /**
     * Interrupt protocol stack event propagation, and Skip all the following {@link ProtoDuplexer}
     * <pre>
     *     ┏━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄╮
     * ... ┃ Handler (0) ┃ > ┆ Handler (1) ┆ > ┆ Handler (2) ┆ > end
     *     ┗━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
     *          Stop              Skip              Skip
     * </pre>
     */
    Stop,
}
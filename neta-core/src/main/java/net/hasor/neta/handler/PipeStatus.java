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
 * A status for {@link PipeDuplex}
 * @version : 2023-10-18
 * @author 赵永春 (zyc@hasor.net)
 */
public enum PipeStatus {
    /**
     * Continuing the execution pipeline
     * <pre>
     *  ┏━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━┓
     *  ┃ PipeNode (0) ┃ > ┃ PipeNode (1) ┃ > ┃ PipeNode (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━┛
     *        Next               Next               Next
     * </pre>
     */
    Next,

    /**
     * Retry this method call, using again to avoid recursion
     * <pre>
     *                   ╭──────╮
     *  ┏━━━━━━━━━━━━━━┓ │  ┏━━━┷━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━┓
     *  ┃ PipeNode (0) ┃ ┷> ┃ PipeNode (1) ┃ > ┃ PipeNode (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━┛
     *        Next                Retry              Next
     * </pre>
     */
    Retry,

    /**
     * restart when pipeline finished.
     *
     * <p>If the pipeline is Interrupt, it will not be restarted</p>
     *
     * <pre>
     * ╭────────────────────────────────────────────────╮
     * │  ┏━━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━━┓         ┏━┷━━━━━━━━━━━━┓
     * ┕> ┃ PipeNode (0) ┃ > ┃ PipeNode (1) ┃ > ... > ┃ PipeNode (2) ┃ > ...
     *    ┗━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━┛         ┗━━━━━━━━━━━━━━┛
     *          Next               Again                    Next
     * </pre>
     */
    Again,

    /**
     * restarted from the current node after pipeline finished.
     *
     * <p>If the pipeline is Interrupt, it will not be restarted</p>
     *
     * <pre>
     *                   ╭─────────────────────────────╮
     *  ┏━━━━━━━━━━━━━━┓ │  ┏━━━━━━━━━━━━━━┓         ┏━┷━━━━━━━━━━━━┓
     *  ┃ PipeNode (0) ┃ ┷> ┃ PipeNode (1) ┃ > ... > ┃ PipeNode (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━━┛         ┗━━━━━━━━━━━━━━┛
     *        Next                Back                     Next
     * </pre>
     */
    Back,

    /**
     * Interrupt the pipeline event propagation and restarted of the pipeline.
     * <pre>
     * ╭───────────────────────╮
     * │  ┏━━━━━━━━━━━━━━┓   ┏━┷━━━━━━━━━━━━┓   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮
     * ┕> ┃ PipeNode (0) ┃ > ┃ PipeNode (1) ┃ > ┆ PipeNode (2) ┆ > ...
     *    ┗━━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯
     *          Next             Restart              Skip
     * </pre>
     */
    Restart,

    /**
     * Execution continues by skipping the next node of the pipeline.
     * <pre>
     *     ┏━━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┏━━━━━━━━━━━━━━┓
     * ... ┃ PipeNode (0) ┃ > ┆ PipeNode (1) ┆ > ┃ PipeNode (2) ┃ > ...
     *     ┗━━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ┗━━━━━━━━━━━━━━┛
     *           Skip            (skipped)             Next
     * </pre>
     */
    Skip,

    /**
     * Interrupt pipeline event propagation, and Skip all the following {@link PipeDuplex}
     * <pre>
     *     ┏━━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄╮
     * ... ┃ PipeNode (0) ┃ > ┆ PipeNode (1) ┆ > ┆ PipeNode (2) ┆ > ...
     *     ┗━━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄┄╯
     *           Exit               Skip               Skip
     * </pre>
     */
    Exit,

    /**
     * Interrupt pipeline event propagation, and throw Error
     * <pre>
     *     ┏━━━━━━━━━━━━━━┓
     * ... ┃ PipeNode (0) ┃ > Throw Error
     *     ┗━━━━━━━━━━━━━━┛
     *         Interrupt
     * </pre>
     */
    Interrupt,
}
/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Progress status returned by {@link ProtoDuplex} and {@link ProtoHandler} after one processing round.
 * <p>{@link ProtoStackChain} interprets these states to decide whether the current node should keep
 * running, retry itself, stop propagation in the current direction, or terminate the current round
 * immediately.</p>
 * <p>The state affects only the current RCV or SND execution round and does not directly modify
 * queue contents. Whether queued messages were consumed or forwarded depends on the actual reads and
 * writes performed by the handler before it returned the status.</p>
 * <h3>Execution position</h3>
 * <ul>
 *   <li>RCV runs from head to tail.</li>
 *   <li>SND runs from tail to head.</li>
 *   <li>{@link ProtoStatus#Retry} re-executes {@code doLayer(...)} only inside the current node and never jumps back to the previous node.</li>
 *   <li>{@link ProtoStatus#Stop} and {@link ProtoStatus#Abort} both end execution of subsequent nodes in the current direction.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 */
public enum ProtoStatus {
    /**
     * The current node finished processing and execution should continue to the next node in the current direction.
     * <p>On the RCV side, if the current node is already the last node, the protocol stack enters one
     * SND lifecycle pass to handle outbound data produced during this receive round.</p>
     * <pre>
     *  ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
     *  ┃ Handler (0) ┃ > ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
     *       Next              Next              Next
     * </pre>
     */
    Next,

    /**
     * Immediately rerun the current node for this round.
     * <p>{@link ProtoStackChain} invokes {@code doLayer(...)} again inside the current node until the
     * return value becomes {@link #Next}, {@link #Stop}, or {@link #Abort}. The flow does not move
     * to any other node and does not recurse back into the current node.</p>
     * <pre>
     *                  ╭──────╮
     *  ┏━━━━━━━━━━━━━┓ │  ┏━━━┷━━━━━━━━━┓   ┏━━━━━━━━━━━━━┓
     *  ┃ Handler (0) ┃ ┷> ┃ Handler (1) ┃ > ┃ Handler (2) ┃ > ...
     *  ┗━━━━━━━━━━━━━┛    ┗━━━━━━━━━━━━━┛   ┗━━━━━━━━━━━━━┛
     *       Next             Retry/Retry           Next
     * </pre>
     */
    Retry,

    /**
     * Stop executing subsequent nodes in the current direction and return the results produced so far in this round.
     * <p>On the RCV side, the framework runs one additional SND lifecycle starting from the current
     * node so outbound data generated in the current round can continue along the send path.</p>
     * <p>On the SND side, the framework terminates the current send-chain execution immediately.</p>
     * <pre>
     *     ┏━━━━━━━━━━━━━┓   ╭┄┄┄┄┄┄┄┄┄┄┄┄┄╮   ┌┄┄┄┄┄┄┄┄┄┄┄┄┄╮
     * ... ┃ Handler (0) ┃ > ┆ Handler (1) ┆ > ┆ Handler (2) ┆ > end
     *     ┗━━━━━━━━━━━━━┛   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯   ╰┄┄┄┄┄┄┄┄┄┄┄┄┄╯
     *          Stop              Skip              Skip
     * </pre>
     */
    Stop,

    /**
     * Terminate the current round immediately without executing later nodes and without running any additional send-chain pass for this round.
     * <p>On the RCV side, this does not run the extra SND lifecycle that {@link #Stop} would run.</p>
     * <p>On the SND side, it immediately terminates the current send-chain execution.</p>
     * <p>The current implementation treats this as a hard termination state. It is not automatically
     * converted into a connection close. Whether the connection closes depends on how the outer
     * caller handles the result and error of this round.</p>
     */
    Abort,
}

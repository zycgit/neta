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
package net.hasor.neta.channel.routing;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for data, error, and user-event propagation through the routing (branched) pipeline tree.
 * Covers the three gaps documented in branch-error-propagation.md:
 * - Gap #3: branch onMessage exception propagates back to the main pipeline's error chain via cr.error.
 * - Gap #1: Router.onError only calls eh.clear() when the branch actually handled the error.
 * - Gap #2: selectedRoute == null causes the error to pass through naturally without being discarded.
 * Also covers: basic data flow (pre/branch/post), SND error propagation, user-event crossing
 * from branch to parent pipeline, and nested routing error propagation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-07
 */
public class ProtoRoutingPropagationTest extends AbstractStackTest {

    // -----------------------------------------------------------------
    // Local helper handlers
    // -----------------------------------------------------------------

    /** Handler whose onError calls eh.clear(), absorbing the error and preventing propagation. */
    private static ProtoHandler<Integer, Integer> errClearHandler(String tag, List<String> log) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                log.add(tag + "Msg");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                log.add(tag + "ErrClear");
                eh.clear();
                return ProtoStatus.Next;
            }
        };
    }

    /** Handler that fires a network event in onMessage after passing data through. */
    private static ProtoHandler<Integer, Integer> eventFireHandler(String tag, List<String> log) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) throws Throwable {
                log.add(tag + "Msg");
                context.fireEvent(String.class, "test-event");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                return ProtoStatus.Next;
            }
        };
    }

    private static ProtoHandler<Integer, Integer> reverseEventFireHandler(String tag, List<String> log) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) throws Throwable {
                log.add(tag + "Msg");
                context.fireEventReverse(String.class, "test-reverse-event");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                return ProtoStatus.Next;
            }
        };
    }

    /** Handler that records received network events via onEvent. */
    private static ProtoHandler<Integer, Integer> eventRecordHandler(String tag, List<String> log) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                log.add(tag + "Evt");
                return true;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                return ProtoStatus.Next;
            }
        };
    }

    /**
     * Routing selector that picks the given branch name when RCV data is present,
     * and returns null (defer) during onActive (no data yet).
     */
    private static ProtoRoutingDataSelector<Integer, Integer> selectBranchOnData(String branchName) {
        return (ctx, rcvUp, rcvDown) -> rcvUp.queueSize() > 0 ? branchName : null;
    }

    // -----------------------------------------------------------------
    // DATA PROPAGATION
    // -----------------------------------------------------------------

    /**
     * Basic data flow through a three-segment pipeline: pre → Router(branch) → post.
     * RCV direction: pre.dec → branch.dec → post.dec → SoContext inbound 42.
     * SND direction (branch-internal, triggered by branch RCV tail): branch.enc.
     * SND direction (main doSndLife after post): post.enc → Router(→branch.enc) → pre.enc.
     * All decoders and encoders must be visited, and 42 must arrive at SoContext.
     */
    @Test
    public void rcv_data_flows_through_pre_branch_and_post() throws Throwable {
        List<String> preDecLog = new ArrayList<>(), preDecErr = new ArrayList<>();
        List<String> preEncLog = new ArrayList<>(), preEncErr = new ArrayList<>();
        List<String> brDecLog = new ArrayList<>(), brDecErr = new ArrayList<>();
        List<String> brEncLog = new ArrayList<>(), brEncErr = new ArrayList<>();
        List<String> postDecLog = new ArrayList<>(), postDecErr = new ArrayList<>();
        List<String> postEncLog = new ArrayList<>(), postEncErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextDuplex("pre", doNextHandler("PreDec", preDecLog, preDecErr), doNextHandler("PreEnc", preEncLog, preEncErr))//
                .nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextDuplex("brH", doNextHandler("BrDec", brDecLog, brDecErr), doNextHandler("BrEnc", brEncLog, brEncErr))))//
                .nextDuplex("post", doNextHandler("PostDec", postDecLog, postDecErr), doNextHandler("PostEnc", postEncLog, postEncErr))//
                .build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        List<Object> inbound = new ArrayList<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, p -> {
            if (p.getData() != null) {
                inbound.add(p.getData());
            }
        });

        channel.receiveData(42);

        // RCV direction: pre.dec → branch.dec → post.dec
        Assert.assertFalse("pre.dec should have run", preDecLog.isEmpty());
        Assert.assertFalse("branch.dec should have run", brDecLog.isEmpty());
        Assert.assertFalse("post.dec should have run", postDecLog.isEmpty());
        // SND direction encoders should also run
        Assert.assertFalse("branch.enc should have run (branch-internal SndLife)", brEncLog.isEmpty());
        Assert.assertFalse("pre.enc should have run", preEncLog.isEmpty());
        Assert.assertFalse("post.enc should have run", postEncLog.isEmpty());
        // Data must reach SoContext
        Assert.assertEquals("SoContext should receive 42", 1, inbound.size());
        Assert.assertEquals(42, inbound.get(0));
    }

    @Test
    public void user_event_upstream_crosses_from_branch_to_parent_before_router() throws Throwable {
        List<String> preDecLog = new ArrayList<>();
        List<String> preDecErr = new ArrayList<>();
        List<String> brDecLog = new ArrayList<>();
        List<String> brDecErr = new ArrayList<>();
        List<String> postDecLog = new ArrayList<>();
        List<String> postDecErr = new ArrayList<>();
        List<String> eventLog = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextDuplex("pre", doNextHandler("PreDec", preDecLog, preDecErr), doNextHandler("PreEnc", new ArrayList<>(), new ArrayList<>())).nextEncoder("preEvt", eventRecordHandler("Pre", eventLog)).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextDecoder("brFire", reverseEventFireHandler("Br", brDecLog)).nextDecoder("brNext", doNextHandler("BrNext", brDecLog, brDecErr)))).nextDecoder("postEvt", eventRecordHandler("Post", eventLog)).nextDuplex("post", doNextHandler("PostDec", postDecLog, postDecErr), doNextHandler("PostEnc", new ArrayList<>(), new ArrayList<>())).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(42);

        Assert.assertTrue(eventLog.contains("PreEvt"));
        Assert.assertFalse(eventLog.contains("PostEvt"));
    }

    // -----------------------------------------------------------------
    // RCV ERROR PROPAGATION
    // -----------------------------------------------------------------

    /**
     * Gap #3: branch decoder throws in onMessage; no handler in the branch clears the error.
     * Flow:
     * branch.dec.onMessage throws
     * → branch error chain (errNext): error NOT cleared
     * → cr.error != null → doRcvRoute throws into main pipeline
     * → Router.onError re-dispatches to branch error chain (errNext again)
     * → branch still doesn't clear → Router doesn't call eh.clear()
     * → post.dec.onError called (error present in main pipeline)
     * → triggerRcv fires inbound error to SoContext
     * Pipeline: Router(branch: [branchThrow, branchErrPass]) → [post(errNext dec / plain enc)]
     */
    @Test
    public void rcv_branch_error_not_cleared_propagates_to_post_and_socontext() throws Throwable {
        List<String> brThrowLog = new ArrayList<>(), brThrowErr = new ArrayList<>();
        List<String> brErrLog = new ArrayList<>(), brErrErr = new ArrayList<>();
        List<String> postLog = new ArrayList<>(), postErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> {
                    c.nextDecoder("brThrow", doThrowHandler("BrThrow", brThrowLog, brThrowErr));
                    c.nextDecoder("brErr", errNextHandler("BrErr", brErrLog, brErrErr));
                }))//
                .nextDuplex("post", errNextHandler("Post", postLog, postErr), doNextHandler("Post", postLog, postErr))//
                .build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveData(42);

        // Branch throw handler's onMessage ran and threw
        Assert.assertFalse("branch.throw onMessage should run", brThrowLog.isEmpty());
        // Branch passthrough error handler was called (at least in the branch error-mode pass)
        Assert.assertFalse("branch passthrough onError should be called", brErrErr.isEmpty());
        // post.dec.onError was called — error reached the main pipeline handlers after Router
        Assert.assertFalse("post.onError should be called", postErr.isEmpty());
        // SoContext received an inbound error
        Assert.assertEquals("SoContext should receive one inbound error", 1, inboundErrors.size());
    }

    /**
     * Branch decoder throws in onMessage, but a subsequent branch handler clears the error via eh.clear().
     * Flow:
     * branch.throw.onMessage throws
     * → branch.clear.onError calls eh.clear() → branchCtx.rcvError = null
     * → cr.error == null → doRcvRoute does NOT throw
     * → main pipeline continues normally (post.onError NOT called)
     * → SoContext receives NO inbound error
     * Pipeline: Router(branch: [branchThrow, branchClear]) → [post(errNext dec / plain enc)]
     */
    @Test
    public void rcv_branch_error_cleared_does_not_reach_post_or_socontext() throws Throwable {
        List<String> brClearLog = new ArrayList<>();
        List<String> postLog = new ArrayList<>(), postErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> {
            c.nextDecoder("brThrow", doThrowHandler("BrThrow", new ArrayList<>(), new ArrayList<>()));
            c.nextDecoder("brClear", errClearHandler("BrClear", brClearLog));
        })).nextDuplex("post", errNextHandler("Post", postLog, postErr), doNextHandler("Post", postLog, postErr)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveData(42);

        // Branch error-clear handler must have called eh.clear()
        Assert.assertTrue("branch errClear handler should have run", brClearLog.contains("BrClearErrClear"));
        // post.dec.onError must NOT be called — main pipeline was not affected
        Assert.assertTrue("post.onError must NOT be called", postErr.isEmpty());
        // SoContext must receive NO inbound error
        Assert.assertTrue("no inbound error should reach SoContext", inboundErrors.isEmpty());
    }

    /**
     * Gap #1: onReceiveError → Router.onError dispatches to branch error chain.
     * Branch error handler does NOT clear the error → Router does NOT call eh.clear()
     * → error continues in main pipeline → SoContext receives inbound error.
     * Pipeline: Router(branch: [branchErrPass])
     */
    @Test
    public void rcv_main_error_forwarded_to_branch_not_cleared_reaches_socontext() throws Throwable {
        List<String> brLog = new ArrayList<>(), brErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextDecoder("brH", errNextHandler("Br", brLog, brErr)))).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(1); // activate branch "a" so selectedRoute != null

        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveError(new SoException("rcv-err"));

        // Branch error handler was invoked
        Assert.assertFalse("branch onError should be called", brErr.isEmpty());
        // Error was not cleared → must reach SoContext
        Assert.assertEquals("SoContext should receive one inbound error", 1, inboundErrors.size());
        Assert.assertEquals("rcv-err", inboundErrors.get(0).getMessage());
    }

    /**
     * Gap #1: onReceiveError → Router.onError dispatches to branch.
     * Branch CLEARS the error via eh.clear() → Router calls eh.clear() on main
     * → main error cleared → SoContext receives NO inbound error.
     * Pipeline: Router(branch: [branchErrClear])
     */
    @Test
    public void rcv_main_error_forwarded_to_branch_cleared_stops_at_router() throws Throwable {
        List<String> brClearLog = new ArrayList<>();

        ProtoInitializer init = ProtoHelper//
                .typed(Integer.class, Integer.class)//
                .nextRouteAsStatic("router", selectBranchOnData("a"), r -> {
                    r.branch("a", c -> {
                        c.nextDecoder("brH", errClearHandler("Br", brClearLog));
                    });
                }).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(1); // activate branch "a"

        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveError(new SoException("rcv-err"));

        // Branch cleared the error
        Assert.assertTrue("branch errClear handler should have run", brClearLog.contains("BrErrClear"));
        // SoContext must receive NO inbound error
        Assert.assertTrue("no inbound error should reach SoContext", inboundErrors.isEmpty());
    }

    /**
     * Gap #2: onReceiveError arrives before any route is selected (selectedRoute == null).
     * Router.onError returns Next immediately without dispatching to any branch.
     * Error passes through to post.dec.onError and then to SoContext — nothing is discarded.
     * Pipeline: Router(branch: [branchH]) → [post(errNext dec / plain enc)]
     */
    @Test
    public void rcv_main_error_before_route_selection_passes_through_to_socontext() throws Throwable {
        List<String> brLog = new ArrayList<>(), brErr = new ArrayList<>();
        List<String> postLog = new ArrayList<>(), postErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextDecoder("brH", errNextHandler("Br", brLog, brErr)))).nextDuplex("post", errNextHandler("Post", postLog, postErr), doNextHandler("Post", postLog, postErr)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        // Intentionally skip onReceive — selectedRoute stays null

        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveError(new SoException("pre-route-err"));

        // Branch handlers must NOT be called (route was never selected)
        Assert.assertTrue("branch must NOT be invoked when route is not selected", brErr.isEmpty());
        // post.dec.onError was called (error passed through Router to the next handler)
        Assert.assertFalse("post.onError should be called", postErr.isEmpty());
        // Error reached SoContext
        Assert.assertEquals("SoContext should receive the error", 1, inboundErrors.size());
        Assert.assertEquals("pre-route-err", inboundErrors.get(0).getMessage());
    }

    // -----------------------------------------------------------------
    // SND ERROR PROPAGATION
    // -----------------------------------------------------------------

    /**
     * onSendError → Router.onError (SND direction) dispatches to branch SND chain.
     * Branch SND error handler does NOT clear → Router does not clear main → SoContext outbound error.
     * Pipeline: Router(branch: [branchSndErrPass])
     */
    @Test
    public void snd_main_error_forwarded_to_branch_not_cleared_reaches_socontext() throws Throwable {
        List<String> brLog = new ArrayList<>(), brErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextEncoder("brH", errNextHandler("BrEnc", brLog, brErr)))).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(1); // activate branch "a"

        List<Throwable> outboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isOutbound() && p.getError() != null, SubscribeMode.SYNC, p -> outboundErrors.add(p.getError()));

        channel.sendError(new SoException("snd-err"));

        // Branch SND error handler was invoked
        Assert.assertFalse("branch SND onError should be called", brErr.isEmpty());
        // SoContext received an outbound error
        Assert.assertEquals("SoContext should receive one outbound error", 1, outboundErrors.size());
        Assert.assertEquals("snd-err", outboundErrors.get(0).getMessage());
    }

    /**
     * onSendError → Router.onError (SND direction) dispatches to branch.
     * Branch SND handler CLEARS the error → Router clears main → SoContext receives no outbound error.
     * Pipeline: Router(branch: [branchSndErrClear])
     */
    @Test
    public void snd_main_error_forwarded_to_branch_cleared_stops_at_router() throws Throwable {
        List<String> brClearLog = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextEncoder("brH", errClearHandler("BrEnc", brClearLog)))).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(1); // activate branch "a"

        List<Throwable> outboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isOutbound() && p.getError() != null, SubscribeMode.SYNC, p -> outboundErrors.add(p.getError()));

        channel.sendError(new SoException("snd-err"));

        // Branch SND error-clear handler must have cleared the error
        Assert.assertTrue("branch SND errClear handler should have run", brClearLog.contains("BrEncErrClear"));
        // SoContext must receive NO outbound error
        Assert.assertTrue("no outbound error should reach SoContext", outboundErrors.isEmpty());
    }

    // -----------------------------------------------------------------
    // EVENT PROPAGATION
    // -----------------------------------------------------------------

    /**
     * Network event fired from inside a branch (RCV context: branch decoder onMessage).
     * Since the event reaches the end of the branch's RCV chain, fireEventUpward
     * crosses into the parent pipeline starting at parentNextStackName (the handler
     * immediately after the Router in the main pipeline).
     * Pipeline: Router(branch: [branchFireEvt dec]) → [post(eventRecord dec / plain enc)]
     */
    @Test
    public void rcv_user_event_from_branch_crosses_to_post_handler() throws Throwable {
        List<String> evtLog = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> c.nextDecoder("brH", eventFireHandler("Br", evtLog)))).nextDuplex("post", eventRecordHandler("Post", evtLog), errNextHandler("Post", new ArrayList<>(), new ArrayList<>())).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(42);

        // Branch decoder ran and fired the event
        Assert.assertTrue("branch decoder should have run and fired event", evtLog.contains("BrMsg"));
        // post handler (after Router in main pipeline) must receive the event exactly once
        Assert.assertEquals("post should receive network event exactly once", 1, evtLog.stream().filter("PostEvt"::equals).count());
    }

    /**
     * Network event fired from the MIDDLE of a branch chain (not the last handler).
     * The event propagates forward within the branch to the next handler (branchRecord),
     * but does NOT cross the branch boundary — because the chain end was not reached
     * by the originating call to context.fireEvent().
     * Crossing only occurs when the event reaches the end of the branch chain, which only
     * happens when the firing handler IS the last handler (or is reached from the last).
     * Pipeline: Router(branch: [branchFire dec, branchRecord dec]) → [postRecord dec]
     * Expected: branchRecord receives event (within branch); post does NOT.
     */
    @Test
    public void rcv_user_event_from_branch_middle_propagates_within_branch_only() throws Throwable {
        List<String> evtLog = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextRouteAsStatic("router", selectBranchOnData("a"), r -> r.branch("a", c -> {
            c.nextDecoder("brFire", eventFireHandler("BrFire", evtLog));
            c.nextDecoder("brRecord", eventRecordHandler("BrRecord", evtLog));
        })).nextDuplex("post", eventRecordHandler("Post", evtLog), errNextHandler("Post", new ArrayList<>(), new ArrayList<>())).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        channel.receiveData(42);

        // Event fired by brFire
        Assert.assertTrue("brFire should have run", evtLog.contains("BrFireMsg"));
        // brRecord (next in branch) should receive the event (within-branch delivery)
        Assert.assertTrue("brRecord should receive network event within branch", evtLog.contains("BrRecordEvt"));
        // post (after Router in main pipeline) must NOT receive the event —
        // crossing only occurs when the event is fired from the last handler in the branch chain
        Assert.assertFalse("post must NOT receive event when fired from middle of branch", evtLog.contains("PostEvt"));
    }

    // -----------------------------------------------------------------
    // NESTED ROUTING ERROR PROPAGATION
    // -----------------------------------------------------------------

    /**
     * Nested routing: L1 routes to "inner" branch containing an L2 router,
     * whose leaf branch throws in onMessage. Error propagates through nested layers back to main.
     * Flow:
     * leaf.dec.onMessage throws
     * → leaf branch cr.error != null → L2 doRcvRoute throws into L1 branch (inner)
     * → inner branch cr.error != null → L1 doRcvRoute throws into main pipeline
     * → outerPost.dec.onError called
     * → SoContext receives inbound error
     * Pipeline: [outerPre] → L1Router(branch: [L2Router(branch: [leafThrow])]) → [outerPost(errNext)]
     */
    @Test
    public void nested_rcv_error_propagates_from_inner_branch_to_main_pipeline() throws Throwable {
        List<String> outerPostLog = new ArrayList<>(), outerPostErr = new ArrayList<>();

        ProtoInitializer init = ProtoHelper.typed(Integer.class, Integer.class).nextDuplex("outerPre", doNextHandler("OPre", new ArrayList<>(), new ArrayList<>()), doNextHandler("OPre", new ArrayList<>(), new ArrayList<>())).nextRouteAsStatic("L1", selectBranchOnData("inner"), r -> r.branch("inner", branch -> branch.nextRouteAsStatic("L2", selectBranchOnData("leaf"), r2 -> r2.branch("leaf", c2 -> c2.nextDecoder("leafH", doThrowHandler("Leaf", new ArrayList<>(), new ArrayList<>())))))).nextDuplex("outerPost", errNextHandler("OPost", outerPostLog, outerPostErr), doNextHandler("OPost", outerPostLog, outerPostErr)).build();

        VrtChannel channel = (VrtChannel) new NetManager().connectSync(new VrtSocketAddress(1), init, new VrtSoConfig());
        List<Throwable> inboundErrors = new ArrayList<>();
        channel.subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

        channel.receiveData(42);

        // Error propagated through nested layers to outerPost in the main pipeline
        Assert.assertFalse("outerPost.onError must be called", outerPostErr.isEmpty());
        // Error reached SoContext
        Assert.assertEquals("SoContext should receive inbound error from nested branch", 1, inboundErrors.size());
    }
}

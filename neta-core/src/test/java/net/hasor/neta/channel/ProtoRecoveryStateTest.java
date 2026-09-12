/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;

import java.lang.reflect.Field;
import org.junit.Assert;
import org.junit.Test;

public class ProtoRecoveryStateTest {
    @Test
    public void emptyQueriesShouldNotAllocateBranchMaps() throws Exception {
        ProtoRecoveryState state = new ProtoRecoveryState();

        Assert.assertFalse(state.hasRecovery());
        Assert.assertFalse(state.hasRecovery(true, "owner", "branch"));
        Assert.assertFalse(state.hasRecovery(false, "owner", "branch"));
        Assert.assertNull(state.activeRecovery(true, "owner"));
        Assert.assertNull(state.activeRecovery(false, "owner"));

        assertNullField(state, "pendingRcvRecoveryBranches");
        assertNullField(state, "pendingSndRecoveryBranches");
        assertNullField(state, "activeRcvRecoveryBranches");
        assertNullField(state, "activeSndRecoveryBranches");
    }

    @Test
    public void branchRecoveryShouldMoveFromPendingToActive() {
        ProtoRecoveryState state = new ProtoRecoveryState();
        ProtoRecoveryState source = new ProtoRecoveryState();
        source.setupSource("owner", "branch");

        state.registerRecovery(source, true);
        Assert.assertTrue(state.hasRecovery());
        Assert.assertTrue(state.hasRecovery(true, "owner", "branch"));

        Assert.assertEquals(ProtoRecoveryState.RECOVERY_RCV, state.beginRecovery());
        Assert.assertEquals("branch", state.activeRecovery(true, "owner"));
        Assert.assertTrue(state.hasRecovery(true, "owner", "branch"));

        state.endRecovery();
        Assert.assertNull(state.activeRecovery(true, "owner"));
        Assert.assertFalse(state.hasRecovery());
    }

    private static void assertNullField(ProtoRecoveryState state, String name) throws Exception {
        Field field = ProtoRecoveryState.class.getDeclaredField(name);
        field.setAccessible(true);
        Assert.assertNull(field.get(state));
    }
}

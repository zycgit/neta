/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import org.junit.Test;

/**
 * Tests for {@link ProtoConfig} including DEFAULT immutability, getter/setter.
 * @author test
 */
public class ProtoConfigTest {

    @Test
    public void defaultValues() {
        ProtoConfig cfg = new ProtoConfig();
        assert cfg.getRcvSlotSize() == -1;
        assert cfg.getSndSlotSize() == -1;
    }

    @Test
    public void settersAndGetters() {
        ProtoConfig cfg = new ProtoConfig();
        cfg.setRcvSlotSize(100);
        cfg.setSndSlotSize(200);
        assert cfg.getRcvSlotSize() == 100;
        assert cfg.getSndSlotSize() == 200;
    }

    @Test(expected = UnsupportedOperationException.class)
    public void defaultInstance_setRcvSlotSize_throwsUnsupported() {
        ProtoConfig.DEFAULT.setRcvSlotSize(10);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void defaultInstance_setSndSlotSize_throwsUnsupported() {
        ProtoConfig.DEFAULT.setSndSlotSize(10);
    }

    @Test
    public void defaultInstance_readOnly() {
        assert ProtoConfig.DEFAULT.getRcvSlotSize() == -1;
        assert ProtoConfig.DEFAULT.getSndSlotSize() == -1;
    }
}

/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.launcher;
import java.util.HashMap;
import java.util.Set;
import net.hasor.tconsole.TelAttribute;

/**
 * 基于 HashMap 的 TelAttribute 接口实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public class AttributeObject extends HashMap<String, Object> implements TelAttribute {
    @Override
    public Object getAttribute(String key) {
        return super.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        super.put(key, value);
    }

    @Override
    public Set<String> getAttributeNames() {
        return super.keySet();
    }
}

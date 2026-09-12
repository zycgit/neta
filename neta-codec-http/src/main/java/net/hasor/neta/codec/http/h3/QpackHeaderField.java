/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
/**
 * 表示 QPACK 头压缩中使用的单个 header field（名称-值对）。
 * @see QpackStaticTable
 * @see QpackDynamicTable
 */
public class QpackHeaderField {
    private final String name;
    private final String value;

    /**
     * 创建一个 header field。
     * @param name 名称
     * @param value 值
     */
    public QpackHeaderField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    /**
     * 返回名称。
     */
    public String name() {
        return name;
    }

    /**
     * 返回值。
     */
    public String value() {
        return value;
    }

    /**
     * 返回该 header field 的大小，定义见 RFC 9204 第 3.2.1 节。
     * 大小 = 名称长度 + 值长度 + 32（固定开销）。
     */
    public int size() {
        return name.length() + value.length() + 32;
    }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}

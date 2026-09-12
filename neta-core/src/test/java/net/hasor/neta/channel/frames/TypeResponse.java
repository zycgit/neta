/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.frames;
/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TypeResponse {
    private String header;
    private String message;

    public TypeResponse(String header, String message) {
        this.header = header;
        this.message = message;
    }

    public String getHeader() {
        return this.header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public String getMessage() {
        return this.message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "TypeResponse{'" + message + "'}";
    }
}

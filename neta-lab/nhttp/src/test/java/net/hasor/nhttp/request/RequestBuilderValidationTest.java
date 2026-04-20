/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.request;

import static org.junit.Assert.fail;

import org.junit.Test;

public class RequestBuilderValidationTest {
    @Test
    public void testBuildWithoutUrlRejected() throws Exception {
        expectThrows(IllegalStateException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new Request.Builder().build();
            }
        });
    }

    @Test
    public void testAddQueryParameterBeforeUrlRejected() throws Exception {
        expectThrows(IllegalStateException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new Request.Builder().addQueryParameter("page", "1");
            }
        });
    }

    @Test
    public void testBlankHeaderNameRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new Request.Builder().url("http://127.0.0.1/validation").addHeader(" ", "value");
            }
        });
    }

    @Test
    public void testInvalidMethodTokenRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new Request.Builder().url("http://127.0.0.1/validation").method("bad method");
            }
        });
    }

    @Test
    public void testNullHttpUrlRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new Request.Builder().url((HttpUrl) null);
            }
        });
    }

    @Test
    public void testBlankFormFieldNameRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                FormBody.builder().addField(" ", "value").build();
            }
        });
    }

    @Test
    public void testBlankMultipartFileNameRejected() throws Exception {
        expectThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() throws Exception {
                MultipartPart.builder().file("file", "", TextBody.builder().text("value").build()).build();
            }
        });
    }

    @Test
    public void testStreamBodyWithoutSourceRejected() throws Exception {
        expectThrows(NullPointerException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                StreamBody.builder().build();
            }
        });
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            fail("expected exception: " + type.getName());
        } catch (Throwable e) {
            if (!type.isInstance(e)) {
                throw new AssertionError("expected exception: " + type.getName() + ", but was: " + e.getClass().getName(), e);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
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
package net.hasor.neta.channel;

import java.util.List;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

public class AbstractStackTest {
    protected static ProtoHandler<Integer, Integer> doExitHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoExit");

                dst.offerMessage(src.takeMessage(src.queueSize()));

                return ProtoStatus.Stop;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrExit");
                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doRetryHandler(String tag, List<String> recordFinish, List<String> recordFailed, int retryCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRetry");

                Integer retry = context.flash("retryCnt");
                if (retry == null) {
                    retry = retryCnt;
                } else {
                    retry--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));

                context.flash("retryCnt", retry);

                if (retry > 0) {
                    return ProtoStatus.Retry;
                } else {
                    context.flash("retryCnt", null);
                    return ProtoStatus.Next;
                }
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrRetry");

                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doThrowHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoThrow");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                throw new IllegalArgumentException();
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrThrow");

                return ProtoStatus.Next;
            }
        };
    }

    public static ProtoHandler<Integer, Integer> doNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrNext");

                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> errExitHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoExit");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrExit");
                return ProtoStatus.Stop;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> errRetryHandler(String tag, List<String> recordFinish, List<String> recordFailed, int retryCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRetry");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrRetry");

                Integer retry = context.flash("retryCnt");
                if (retry == null) {
                    retry = retryCnt;
                } else {
                    retry--;
                }

                context.flash("retryCnt", retry);

                if (retry > 0) {
                    return ProtoStatus.Retry;
                } else {
                    context.flash("retryCnt", null);
                    return ProtoStatus.Next;
                }
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> errThrowHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoThrow");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
                recordFailed.add(tag + "ErrThrow");
                throw e;
            }
        };
    }

    public static ProtoHandler<Integer, Integer> errNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrNext");

                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doCopyHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrNext");

                return ProtoStatus.Next;
            }
        };
    }
}
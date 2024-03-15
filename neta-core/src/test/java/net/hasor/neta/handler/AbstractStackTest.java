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
import net.hasor.neta.channel.ProtoContext;

import java.util.List;

public class AbstractStackTest {
    protected static ProtoHandler<Integer, Integer> doExitHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoExit");

                dst.offerMessage(src.takeMessage(src.queueSize()));

                return ProtoStatus.Exit;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrExit");
                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doRestartHandler(String tag, List<String> recordFinish, List<String> recordFailed, int restartCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRestart");

                Integer restart = context.flash("restartCnt");
                if (restart == null) {
                    restart = restartCnt;
                } else {
                    restart--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));

                context.flash("restartCnt", restart);

                if (restart > 0) {
                    return ProtoStatus.Restart;
                } else {
                    context.flash("restartCnt", null);
                    return ProtoStatus.Next;
                }
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrRestart");

                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doAgainHandler(String tag, List<String> recordFinish, List<String> recordFailed, int againCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoAgain");

                Integer again = context.flash("againCnt");
                if (again == null) {
                    again = againCnt;
                } else {
                    again--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));

                context.flash("againCnt", again);

                if (again > 0) {
                    return ProtoStatus.Again;
                } else {
                    context.flash("againCnt", null);
                    return ProtoStatus.Next;
                }
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrAgain");

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

    protected static ProtoHandler<Integer, Integer> doNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
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

    protected static ProtoHandler<Integer, Integer> doInterruptHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoInterrupt");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Interrupt;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrInterrupt");

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
                return ProtoStatus.Exit;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> errRestartHandler(String tag, List<String> recordFinish, List<String> recordFailed, int restartCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRestart");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrRestart");

                Integer restart = context.flash("restartCnt");
                if (restart == null) {
                    restart = restartCnt;
                } else {
                    restart--;
                }

                context.flash("restartCnt", restart);

                if (restart > 0) {
                    return ProtoStatus.Restart;
                } else {
                    context.flash("restartCnt", null);
                    return ProtoStatus.Next;
                }
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> errAgainHandler(String tag, List<String> recordFinish, List<String> recordFailed, int againCnt) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoAgain");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrAgain");

                Integer again = context.flash("againCnt");
                if (again == null) {
                    again = againCnt;
                } else {
                    again--;
                }

                context.flash("againCnt", again);

                if (again > 0) {
                    return ProtoStatus.Again;
                } else {
                    context.flash("againCnt", null);
                    return ProtoStatus.Next;
                }
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

    protected static ProtoHandler<Integer, Integer> errNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
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

    protected static ProtoHandler<Integer, Integer> errInterruptHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoInterrupt");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrInterrupt");

                return ProtoStatus.Interrupt;
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

    protected static ProtoHandler<Integer, Integer> doNotCopyHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrNext");
                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoDuplexer<Integer, Integer, Integer, Integer> doProtoLayer(boolean rcvSend, boolean sndSend) {
        return (context, isRcv, rcvUp, rcvDown, sndUp, sndDown) -> {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
                if (rcvSend) {
                    sndDown.offerMessage(888);
                }
            } else {
                sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
                if (sndSend) {
                    sndDown.offerMessage(999);
                }
            }
            return ProtoStatus.Next;
        };
    }

    protected static ProtoHandler<Integer, Integer> doCopyUsingBlackHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));

                if (src.hasMore() && !dst.hasSlot()) {
                    recordFinish.add(tag + "DoBack");
                    return ProtoStatus.Back;
                } else {
                    recordFinish.add(tag + "DoNext");
                    return ProtoStatus.Next;
                }
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrNext");

                return ProtoStatus.Next;
            }
        };
    }

    protected static ProtoHandler<Integer, Integer> doCopyAndSkipHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new ProtoHandler<Integer, Integer>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Integer> src, ProtoSndQueue<Integer> dst) {
                dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));
                recordFinish.add(tag + "Skip");
                return ProtoStatus.Skip;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                recordFailed.add(tag + "ErrSkip");

                return ProtoStatus.Skip;
            }
        };
    }
}
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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.SoChannel;

import java.util.concurrent.atomic.AtomicInteger;

public class AbstractPipeTest {
    protected static final Logger logger = Logger.getLogger(AbstractPipeTest.class);

    protected static PipeHandler<Integer, Integer> doExitHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doExitHandler(doHandler) -> " + markFinish.get());

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();

                return PipeStatus.Exit;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doExitHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doRestartHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed, int restartCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doRestartHandler(doHandler) -> " + markFinish.get());

                Integer restart = context.flash("restartCnt");
                if (restart == null) {
                    restart = restartCnt;
                } else {
                    restart--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();

                context.flash("restartCnt", restart);

                if (restart > 0) {
                    return PipeStatus.Restart;
                } else {
                    context.flash("restartCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doRestartHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doAgainHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed, int againCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doAgainHandler(doHandler) -> " + markFinish.get());

                Integer again = context.flash("againCnt");
                if (again == null) {
                    again = againCnt;
                } else {
                    again--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();

                context.flash("againCnt", again);

                if (again > 0) {
                    return PipeStatus.Again;
                } else {
                    context.flash("againCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doRetryHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doRetryHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed, int retryCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doRetryHandler(doHandler) -> " + markFinish.get());

                Integer retry = context.flash("retryCnt");
                if (retry == null) {
                    retry = retryCnt;
                } else {
                    retry--;
                }

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();

                context.flash("retryCnt", retry);

                if (retry > 0) {
                    return PipeStatus.Retry;
                } else {
                    context.flash("retryCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doRetryHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doThrowHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doThrowHandler(doHandler) -> " + markFinish.get());

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();
                throw new IllegalArgumentException();
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doNextHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doNextHandler(String tag, AtomicInteger markFinish, AtomicInteger markFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                logger.info(tag + ", doNextHandler(doHandler) -> " + markFinish.get());

                dst.offerMessage(src.takeMessage(src.queueSize()));
                markFinish.incrementAndGet();
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                logger.info(tag + ", doNextHandler(doError) -> " + markFailed.get());

                markFailed.incrementAndGet();
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeReceiveListener<Object> onReceiveListener() {
        return new PipeReceiveListener<Object>() {
            @Override
            public void onReceive(SoChannel<?> channel, Object data) {
                logger.info("onReceiveListener(onReceive) -> " + data);
            }

            @Override
            public void onError(SoChannel<?> channel, Throwable e) {
                logger.info("onReceiveListener(onError) -> " + e.getMessage());
            }
        };
    }

}
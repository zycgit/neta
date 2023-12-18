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
import net.hasor.neta.channel.PipeContext;

import java.util.List;

public class AbstractPipeTest {
    protected static PipeHandler<Integer, Integer> doExitHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoExit");

                dst.offerMessage(src.takeMessage(src.queueSize()));

                return PipeStatus.Exit;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrExit");
                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doRestartHandler(String tag, List<String> recordFinish, List<String> recordFailed, int restartCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
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
                    return PipeStatus.Restart;
                } else {
                    context.flash("restartCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrRestart");

                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doAgainHandler(String tag, List<String> recordFinish, List<String> recordFailed, int againCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
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
                    return PipeStatus.Again;
                } else {
                    context.flash("againCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrAgain");

                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doRetryHandler(String tag, List<String> recordFinish, List<String> recordFailed, int retryCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
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
                    return PipeStatus.Retry;
                } else {
                    context.flash("retryCnt", null);
                    return PipeStatus.Next;
                }
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrRetry");

                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doThrowHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoThrow");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                throw new IllegalArgumentException();
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrThrow");

                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrNext");

                return PipeStatus.Next;
            }
        };
    }

    //

    protected static PipeHandler<Integer, Integer> errExitHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoExit");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrExit");
                return PipeStatus.Exit;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> errRestartHandler(String tag, List<String> recordFinish, List<String> recordFailed, int restartCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRestart");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrRestart");

                Integer restart = context.flash("restartCnt");
                if (restart == null) {
                    restart = restartCnt;
                } else {
                    restart--;
                }

                context.flash("restartCnt", restart);

                if (restart > 0) {
                    return PipeStatus.Restart;
                } else {
                    context.flash("restartCnt", null);
                    return PipeStatus.Next;
                }
            }
        };
    }

    protected static PipeHandler<Integer, Integer> errAgainHandler(String tag, List<String> recordFinish, List<String> recordFailed, int againCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoAgain");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrAgain");

                Integer again = context.flash("againCnt");
                if (again == null) {
                    again = againCnt;
                } else {
                    again--;
                }

                context.flash("againCnt", again);

                if (again > 0) {
                    return PipeStatus.Again;
                } else {
                    context.flash("againCnt", null);
                    return PipeStatus.Next;
                }
            }
        };
    }

    protected static PipeHandler<Integer, Integer> errRetryHandler(String tag, List<String> recordFinish, List<String> recordFailed, int retryCnt) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoRetry");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrRetry");

                Integer retry = context.flash("retryCnt");
                if (retry == null) {
                    retry = retryCnt;
                } else {
                    retry--;
                }

                context.flash("retryCnt", retry);

                if (retry > 0) {
                    return PipeStatus.Retry;
                } else {
                    context.flash("retryCnt", null);
                    return PipeStatus.Next;
                }
            }
        };
    }

    protected static PipeHandler<Integer, Integer> errThrowHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoThrow");
                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) throws Throwable {
                recordFailed.add(tag + "ErrThrow");
                throw e;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> errNextHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(src.queueSize()));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrNext");

                return PipeStatus.Next;
            }
        };
    }

    //

    protected static PipeHandler<Integer, Integer> doCopyHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");

                dst.offerMessage(src.takeMessage(Math.min(src.queueSize(), dst.slotSize())));
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrNext");

                return PipeStatus.Next;
            }
        };
    }

    protected static PipeHandler<Integer, Integer> doNotCopyHandler(String tag, List<String> recordFinish, List<String> recordFailed) {
        return new PipeHandler<Integer, Integer>() {
            @Override
            public PipeStatus doHandler(PipeContext context, PipeRcvQueue<Integer> src, PipeSndQueue<Integer> dst) {
                recordFinish.add(tag + "DoNext");
                return PipeStatus.Next;
            }

            @Override
            public PipeStatus doError(PipeContext context, Throwable e, PipeExceptionHandler eh) {
                recordFailed.add(tag + "ErrNext");
                return PipeStatus.Next;
            }
        };
    }

}
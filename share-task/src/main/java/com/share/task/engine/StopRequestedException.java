package com.share.task.engine;

/** 手工终止信号：引擎捕获后把实例置为 STOPPED，不再重试。 */
public class StopRequestedException extends RuntimeException {

    public StopRequestedException(String message) {
        super(message);
    }
}

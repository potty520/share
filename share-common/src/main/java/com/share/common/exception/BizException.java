package com.share.common.exception;

import com.share.common.api.ErrorCode;
import lombok.Getter;

/** 业务异常：由 GlobalExceptionHandler 统一转换为 Result。 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = ErrorCode.BUSINESS.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.code = errorCode.getCode();
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }
}

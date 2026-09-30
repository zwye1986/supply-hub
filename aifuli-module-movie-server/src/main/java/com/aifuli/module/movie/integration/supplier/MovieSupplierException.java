package com.aifuli.module.movie.integration.supplier;

/** 供应商协议或网络失败；保留异常链，不允许伪装成空供给。 */
public class MovieSupplierException extends RuntimeException {
    /** 明确的协议错误，不携带完整报文或凭证。 */
    public MovieSupplierException(String message) {
        super(message);
    }

    /** 包装传输失败时保留原始原因。 */
    public MovieSupplierException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.example.mapaiserver.asr;

/**
 * Vendor ASR 结果回调。Handler 只依赖这个抽象，不依赖具体厂商协议细节。
 */
public interface AsrVendorListener {

    void onStarted();

    void onResult(String text, boolean isFinal);

    void onFinished();

    void onError(String errorMessage);
}


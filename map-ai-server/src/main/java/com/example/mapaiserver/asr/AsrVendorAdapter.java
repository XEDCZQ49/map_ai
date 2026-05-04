package com.example.mapaiserver.asr;

/**
 * 统一 ASR 厂商适配接口。
 */
public interface AsrVendorAdapter {

    void start(AsrVendorListener listener) throws Exception;

    void sendAudio(byte[] audioBytes) throws Exception;

    void stop() throws Exception;

    void close();
}


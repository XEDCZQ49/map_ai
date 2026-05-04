package com.example.mapaiserver.asr;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.utils.Constants;

import java.nio.ByteBuffer;

/**
 * DashScope Java SDK 适配层：屏蔽 WebSocket 协议细节，避免手拼 JSON 带来的协议字段错误。
 */
public class DashScopeSdkAsrVendorAdapter implements AsrVendorAdapter {

    private final String apiKey;
    private final String model;
    private final String websocketBaseUrl;

    private Recognition recognizer;
    private AsrVendorListener listener;

    public DashScopeSdkAsrVendorAdapter(String apiKey, String model, String websocketBaseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.websocketBaseUrl = websocketBaseUrl;
    }

    @Override
    public synchronized void start(AsrVendorListener listener) throws Exception {
        this.listener = listener;
        if (websocketBaseUrl != null && !websocketBaseUrl.isBlank()) {
            // 使用配置覆盖 SDK 默认 ws 地址，便于后续切换地域。
            Constants.baseWebsocketApiUrl = websocketBaseUrl;
        }

        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(16000)
                .build();

        recognizer = new Recognition();
        recognizer.call(param, new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result == null || result.getSentence() == null) return;
                String text = result.getSentence().getText();
                if (text == null || text.isBlank()) return;
                if (DashScopeSdkAsrVendorAdapter.this.listener != null) {
                    DashScopeSdkAsrVendorAdapter.this.listener.onResult(text, result.isSentenceEnd());
                }
            }

            @Override
            public void onComplete() {
                if (DashScopeSdkAsrVendorAdapter.this.listener != null) {
                    DashScopeSdkAsrVendorAdapter.this.listener.onFinished();
                }
            }

            @Override
            public void onError(Exception e) {
                if (DashScopeSdkAsrVendorAdapter.this.listener != null) {
                    DashScopeSdkAsrVendorAdapter.this.listener.onError(
                            e == null ? "ASR SDK 未知错误" : e.getMessage()
                    );
                }
            }
        });

        if (this.listener != null) {
            this.listener.onStarted();
        }
    }

    @Override
    public synchronized void sendAudio(byte[] audioBytes) throws Exception {
        if (recognizer == null || audioBytes == null || audioBytes.length == 0) return;
        recognizer.sendAudioFrame(ByteBuffer.wrap(audioBytes));
    }

    @Override
    public synchronized void stop() throws Exception {
        if (recognizer != null) {
            recognizer.stop();
        }
    }

    @Override
    public synchronized void close() {
        if (recognizer == null) return;
        try {
            recognizer.getDuplexApi().close(1000, "bye");
        } catch (Exception ignored) {
        } finally {
            recognizer = null;
        }
    }
}


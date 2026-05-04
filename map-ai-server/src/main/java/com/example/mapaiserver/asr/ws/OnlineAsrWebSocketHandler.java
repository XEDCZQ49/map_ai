package com.example.mapaiserver.asr.ws;

import com.example.mapaiserver.asr.AsrVendorAdapter;
import com.example.mapaiserver.asr.AsrVendorListener;
import com.example.mapaiserver.asr.DashScopeSdkAsrVendorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OnlineAsrWebSocketHandler extends BinaryWebSocketHandler {

    private static final String ATTR_BRIDGE = "bridge";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.dashscope.api-key:${QWEN_KEY:}}")
    private String qwenKey;

    @Value("${asr.realtime.model:fun-asr-realtime}")
    private String asrRealtimeModel;

    @Value("${asr.realtime.ws-url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}")
    private String asrRealtimeWsUrl;

    private enum SessionState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        CLOSED
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(ATTR_BRIDGE, new SessionBridge(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionBridge bridge = getBridge(session);
        if (bridge == null) return;
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String signal = root.path("signal").asText("");
            if ("start".equalsIgnoreCase(signal)) {
                bridge.start();
                return;
            }
            if ("end".equalsIgnoreCase(signal)) {
                bridge.stop();
            }
        } catch (Exception e) {
            bridge.sendError("控制消息解析失败: " + e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionBridge bridge = getBridge(session);
        if (bridge == null) return;
        byte[] bytes = new byte[message.getPayload().remaining()];
        message.getPayload().get(bytes);
        bridge.onAudio(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = getBridge(session);
        if (bridge != null) {
            bridge.close();
        }
        session.getAttributes().clear();
    }

    private SessionBridge getBridge(WebSocketSession session) {
        return (SessionBridge) session.getAttributes().get(ATTR_BRIDGE);
    }

    private AsrVendorAdapter newVendorAdapter() {
        return new DashScopeSdkAsrVendorAdapter(qwenKey, asrRealtimeModel, asrRealtimeWsUrl);
    }

    /**
     * 每个前端 websocket 连接对应一个 SessionBridge，内部状态机控制 ASR 生命周期。
     */
    private final class SessionBridge {
        private final WebSocketSession frontend;
        private final Queue<byte[]> pendingAudio = new ArrayDeque<>();
        private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);

        private AsrVendorAdapter vendorAdapter;

        private SessionBridge(WebSocketSession frontend) {
            this.frontend = frontend;
        }

        private void log(String msg) {
            System.out.println("[ASR-SESSION] " + msg + " state=" + state.get());
        }

        private synchronized void start() {
            SessionState current = state.get();
            log("receive start");
            if (current == SessionState.RUNNING || current == SessionState.STARTING || current == SessionState.CLOSED) {
                return;
            }
            if (qwenKey == null || qwenKey.isBlank()) {
                sendError("QWEN_KEY 未配置");
                return;
            }

            state.set(SessionState.STARTING);
            pendingAudio.clear();
            closeVendorAdapter();

            vendorAdapter = newVendorAdapter();
            try {
                vendorAdapter.start(new AsrVendorListener() {
                    @Override
                    public void onStarted() {
                        state.set(SessionState.RUNNING);
                        log("vendor started");
                        flushPendingAudio();
                    }

                    @Override
                    public void onResult(String text, boolean isFinal) {
                        sendResult(text, isFinal);
                    }

                    @Override
                    public void onFinished() {
                        state.set(SessionState.IDLE);
                        log("vendor finished");
                        closeVendorAdapter();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        // request timeout 是上游在长时间无音频时的正常结束信号，这里不再回传前端错误弹窗。
                        if (isTimeoutError(errorMessage)) {
                            state.set(SessionState.IDLE);
                            closeVendorAdapter();
                            return;
                        }
                        sendError("ASR 上游错误: " + errorMessage);
                        state.set(SessionState.IDLE);
                        log("vendor error: " + errorMessage);
                        closeVendorAdapter();
                    }
                });
            } catch (Exception e) {
                state.set(SessionState.IDLE);
                closeVendorAdapter();
                log("start failed: " + e.getMessage());
                sendError("ASR 启动失败: " + e.getMessage());
            }
        }

        private synchronized void onAudio(byte[] bytes) {
            SessionState current = state.get();
            if (current == SessionState.CLOSED || current == SessionState.STOPPING) return;
            if (current == SessionState.STARTING) {
                pendingAudio.add(bytes);
                return;
            }
            if (current != SessionState.RUNNING || vendorAdapter == null) {
                // 前端可能先发音频后发 start，先缓存，等 start 完成后再发送。
                pendingAudio.add(bytes);
                return;
            }
            try {
                vendorAdapter.sendAudio(bytes);
            } catch (Exception e) {
                sendError("音频发送失败: " + e.getMessage());
            }
        }

        private synchronized void flushPendingAudio() {
            if (state.get() != SessionState.RUNNING || vendorAdapter == null) return;
            while (!pendingAudio.isEmpty()) {
                byte[] chunk = pendingAudio.poll();
                try {
                    vendorAdapter.sendAudio(chunk);
                } catch (Exception e) {
                    sendError("缓存音频发送失败: " + e.getMessage());
                    break;
                }
            }
        }

        private synchronized void stop() {
            SessionState current = state.get();
            log("receive end");
            if (current == SessionState.CLOSED || current == SessionState.IDLE) return;
            state.set(SessionState.STOPPING);
            pendingAudio.clear();
            if (vendorAdapter != null) {
                try {
                    vendorAdapter.stop();
                } catch (Exception e) {
                    sendError("ASR 停止失败: " + e.getMessage());
                } finally {
                    closeVendorAdapter();
                }
            }
            state.set(SessionState.IDLE);
            log("stopped");
        }

        private synchronized void close() {
            state.set(SessionState.CLOSED);
            pendingAudio.clear();
            closeVendorAdapter();
        }

        private synchronized void closeVendorAdapter() {
            if (vendorAdapter == null) return;
            vendorAdapter.close();
            vendorAdapter = null;
        }

        private boolean isTimeoutError(String errorMessage) {
            if (errorMessage == null) return false;
            String lower = errorMessage.toLowerCase();
            return lower.contains("request timeout after 23 seconds")
                    || lower.contains("\"statuscode\":44")
                    || lower.contains("client_error");
        }

        private void sendResult(String text, boolean isFinal) {
            if (text == null || text.isBlank()) return;
            ObjectNode root = objectMapper.createObjectNode();
            root.put("result", text);
            root.put("final", isFinal);
            sendToFrontend(root.toString());
        }

        private void sendError(String message) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("error", message);
            sendToFrontend(root.toString());
        }

        private void sendToFrontend(String payload) {
            if (!frontend.isOpen()) return;
            synchronized (frontend) {
                try {
                    frontend.sendMessage(new TextMessage(payload));
                } catch (Exception ignored) {
                }
            }
        }
    }
}

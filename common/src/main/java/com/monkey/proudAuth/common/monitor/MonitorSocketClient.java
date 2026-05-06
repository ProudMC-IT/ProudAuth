package com.monkey.proudAuth.common.monitor;

import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class MonitorSocketClient {

    private final HttpClient httpClient;
    private final ProudAuthConsoleLogger logger;
    private final Consumer<String> incomingMessageHandler;

    private WebSocket webSocket;
    private String sessionId;

    public MonitorSocketClient(ProudAuthConsoleLogger logger, Consumer<String> incomingMessageHandler) {
        this.logger = logger;
        this.incomingMessageHandler = incomingMessageHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public CompletableFuture<Void> connect(String sessionId, MonitorIdentity identity) {
        this.sessionId = sessionId;
        close();

        return httpClient.newWebSocketBuilder()
                .header("X-ProudAuth-Network", identity.networkId())
                .header("X-ProudAuth-Proxy", identity.proxyId())
                .header("X-ProudAuth-Session", sessionId)
                .buildAsync(URI.create(ProudAuthMonitorConstants.AGENT_WS_URL), new Listener())
                .thenAccept(socket -> {
                    this.webSocket = socket;
                    send("agent_hello", Map.of(
                            "networkId", identity.networkId(),
                            "proxyId", identity.proxyId(),
                            "sessionId", sessionId,
                            "plugin", "ProudAuth",
                            "platform", "Velocity"
                    ));
                });
    }

    public boolean connected() {
        return webSocket != null;
    }

    public void send(String type, Object payload) {
        WebSocket socket = webSocket;

        if (socket == null || sessionId == null) {
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("sessionId", sessionId);
        message.put("payload", payload);

        socket.sendText(MonitorJsonWriter.write(message), true);
    }

    public void close() {
        WebSocket socket = webSocket;

        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
            } catch (Exception ignored) {
            }
        }

        webSocket = null;
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);

            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);

                try {
                    incomingMessageHandler.accept(message);
                } catch (Exception exception) {
                    logger.error("Errore durante gestione messaggio monitor.", exception);
                }
            }

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            MonitorSocketClient.this.webSocket = null;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            MonitorSocketClient.this.webSocket = null;
            logger.warn("Connessione monitor chiusa: " + error.getMessage());
        }
    }
}
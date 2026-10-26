package shed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Local, bounded fill-in-the-middle requests. No model installation or process startup. */
final class OllamaCompletionClient implements AutoCloseable {
    static final int MAX_PREFIX = 8192, MAX_SUFFIX = 4096, MAX_COMPLETION = 4096, MAX_RESPONSE_BYTES = 65536;
    private final InlineCompletionSettings settings;
    private final HttpClient client;

    OllamaCompletionClient(InlineCompletionSettings settings) {
        this.settings = settings;
        client = HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
    }

    CompletableFuture<String> complete(String prefix, String suffix) {
        if (!settings.enabled()) return CompletableFuture.failedFuture(new IllegalStateException("Inline completion is disabled"));
        if (prefix == null || suffix == null || prefix.length() > MAX_PREFIX || suffix.length() > MAX_SUFFIX)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Inline completion context exceeds its limit"));
        var body = Map.of("model", settings.model(), "prompt", prefix, "suffix", suffix, "stream", false,
            "options", Map.of("num_predict", 128, "temperature", 0));
        var request = HttpRequest.newBuilder(settings.endpoint()).timeout(settings.timeout())
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MiniJson.stringify(body))).build();
        CompletableFuture<String> result = new CompletableFuture<>();
        var wire = client.sendAsync(request, ignored -> new BoundedBody());
        wire.whenComplete((response, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            try {
                if (response.statusCode() != 200) throw new IOException("Ollama returned HTTP " + response.statusCode());
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
                Map<String, Object> value = MiniJson.asObject(MiniJson.parseStrict(json));
                if (value == null || !Boolean.TRUE.equals(value.get("done")) || !(value.get("response") instanceof String text))
                    throw new IOException("Ollama did not return a completed suggestion");
                if (text.length() > MAX_COMPLETION || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                    throw new IOException("Ollama returned an unsupported suggestion");
                result.complete(text.replace("\r\n", "\n").replace('\r', '\n'));
            } catch (Exception invalid) { result.completeExceptionally(invalid); }
        });
        // Covers the complete body, including a server that sends headers but then stalls.
        result.orTimeout(settings.timeout().toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((text, error) -> { if (error != null) wire.cancel(true); });
        return result;
    }

    @Override public void close() { client.shutdownNow(); }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Ollama response exceeds its limit")); return;
                }
                byte[] next = new byte[buffer.remaining()]; buffer.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}

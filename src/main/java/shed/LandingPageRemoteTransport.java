package shed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class LandingPageRemoteTransport {
    static final int MAX_BYTES = 1024 * 1024;

    byte[] fetch(URI endpoint, int timeoutMillis, AsyncJobService.JobToken token) throws IOException, InterruptedException {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("remote landing source must use HTTPS");
        }
        if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().timeout(Duration.ofMillis(timeoutMillis)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("landing source returned HTTP " + response.statusCode());
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_BYTES) throw new IOException("landing source exceeds the 1 MiB size limit");
            byte[] content = input.readNBytes(MAX_BYTES + 1);
            if (content.length > MAX_BYTES) throw new IOException("landing source exceeds the 1 MiB size limit");
            if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
            return content;
        }
    }
}

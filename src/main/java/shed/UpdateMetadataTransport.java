package shed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class UpdateMetadataTransport {
    record Response(byte[] body, String signature) { }

    Response fetch(URI endpoint, int timeoutMillis, AsyncJobService.JobToken token) throws IOException, InterruptedException {
        if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().timeout(Duration.ofMillis(timeoutMillis))
            .header("Accept", "application/vnd.shed.update.v1+text").build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("metadata endpoint returned HTTP " + response.statusCode());
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > UpdateMetadataVerifier.MAX_METADATA_BYTES) throw new IOException("metadata exceeds the size limit");
            byte[] body = input.readNBytes(UpdateMetadataVerifier.MAX_METADATA_BYTES + 1);
            if (body.length > UpdateMetadataVerifier.MAX_METADATA_BYTES) throw new IOException("metadata exceeds the size limit");
            if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
            String signature = response.headers().firstValue("X-Shed-Update-Signature").orElse("");
            return new Response(body, signature);
        }
    }
}

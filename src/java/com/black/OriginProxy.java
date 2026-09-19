package com.black;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.eclipse.jgit.lib.Repository;

/**
 * Forwards git smart-HTTP traffic to the upstream origin (GitHub/GitLab) on
 * behalf of the authenticated client. The client's {@code Authorization: Basic}
 * header is passed through verbatim so upstream authenticates as the same user.
 *
 * <p>Used to proxy receive-pack (push) traffic: blackgit first applies its own
 * authorization (per-user push rights), then relays the request to origin and
 * streams the response back to the client.
 */
public final class OriginProxy {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private OriginProxy() {
    }

    /** Reads remote.origin.url from the local cache; null when not configured. */
    public static String originUrl(Repository repo) {
        try {
            return repo.getConfig().getString("remote", "origin", "url");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Performs a GET on origin (used for info/refs advertisement).
     *
     * @return the origin response body
     * @throws GitProtocolException when origin returns a 4xx/5xx
     */
    public static byte[] forwardGet(String originUrl, String suffix, String authz)
            throws Exception {
        URI uri = URI.create(originUrl + suffix);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", authz)
                .header("User-Agent", "blackgit-proxy")
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            String body = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            throw new GitProtocolException(
                    "origin info/refs " + resp.statusCode() + ": " + body);
        }
        Log.logger.info("origin GET {} -> {} ({} bytes)", uri, resp.statusCode(),
                resp.body().length);
        return resp.body();
    }

    /**
     * Performs a POST on origin (used for git-receive-pack).
     *
     * @return the origin response body
     */
    public static HttpResponse<byte[]> forwardPost(String originUrl, String suffix,
                                                   byte[] body, String authz,
                                                   String contentType) throws Exception {
        URI uri = URI.create(originUrl + suffix);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", authz)
                .header("Content-Type", contentType)
                .header("User-Agent", "blackgit-proxy")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        Log.logger.info("origin POST {} -> {} ({} bytes request, {} bytes response)",
                uri, resp.statusCode(), body.length, resp.body().length);
        return resp;
    }
}

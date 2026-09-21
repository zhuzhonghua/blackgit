package com.blackhttp.githttp;

import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

final class GitResponse {
    final HttpResponseStatus status;
    final String contentType;
    final SpooledBuffer body;
    /** Value for the WWW-Authenticate header; null when the header is not needed. */
    final String wwwAuthenticate;

    private GitResponse(HttpResponseStatus status, String contentType, SpooledBuffer body,
                        String wwwAuthenticate) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.wwwAuthenticate = wwwAuthenticate;
    }

    static GitResponse ok(String contentType, SpooledBuffer body) {
        return new GitResponse(HttpResponseStatus.OK, contentType, body, null);
    }

    /** Echoes an upstream (origin) response verbatim, including its status code. */
    static GitResponse raw(HttpResponseStatus status, String contentType, byte[] data) {
        SpooledBuffer spool = new SpooledBuffer(data.length);
        try {
            spool.write(data, 0, data.length);
        } catch (java.io.IOException e) {
            spool = new SpooledBuffer(0);
        }
        return new GitResponse(status, contentType, spool, null);
    }

    static GitResponse error(HttpResponseStatus status, String message) {
        return error(status, message, null);
    }

    /** 401 with a WWW-Authenticate challenge so git prompts for credentials. */
    static GitResponse unauthorized(String message) {
        return error(HttpResponseStatus.UNAUTHORIZED, message,
                "Basic realm=\"blackgit\", charset=\"UTF-8\"");
    }

    private static GitResponse error(HttpResponseStatus status, String message,
                                     String wwwAuthenticate) {
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SpooledBuffer spool = new SpooledBuffer(bytes.length);
        try {
            spool.write(bytes, 0, bytes.length);
            return new GitResponse(status, "text/plain; charset=utf-8", spool, wwwAuthenticate);
        } catch (java.io.IOException e) {
            return new GitResponse(status, "text/plain; charset=utf-8",
                    new SpooledBuffer(0), wwwAuthenticate);
        }
    }
}

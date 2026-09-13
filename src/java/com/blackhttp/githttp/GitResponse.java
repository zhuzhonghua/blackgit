package com.blackhttp.githttp;

import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

final class GitResponse {
    final HttpResponseStatus status;
    final String contentType;
    final SpooledBuffer body;

    private GitResponse(HttpResponseStatus status, String contentType, SpooledBuffer body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    static GitResponse ok(String contentType, SpooledBuffer body) {
        return new GitResponse(HttpResponseStatus.OK, contentType, body);
    }

    static GitResponse error(HttpResponseStatus status, String message) {
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SpooledBuffer spool = new SpooledBuffer(bytes.length);
        try {
            spool.write(bytes, 0, bytes.length);
            return new GitResponse(status, "text/plain; charset=utf-8", spool);
        } catch (java.io.IOException e) {
            return new GitResponse(status, "text/plain; charset=utf-8",
                    new SpooledBuffer(0));
        }
    }
}
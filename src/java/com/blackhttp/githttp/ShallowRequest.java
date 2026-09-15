package com.blackhttp.githttp;

import org.eclipse.jgit.lib.ObjectId;

import java.util.List;

/**
 * The depth/shallow parameters a client asked for in an upload-pack fetch
 * request. Captured from the wire so the server can log, and later forward,
 * the same shallow semantics when fetching from an upstream repository.
 */
final class ShallowRequest {
    static final ShallowRequest NONE = new ShallowRequest(0, 0, List.of(), List.of(), false);

    /** Clone/fetch depth; {@code 0} means unbounded. */
    final int depth;
    /** {@code --shallow-since} epoch timestamp; {@code 0} means not requested. */
    final long deepenSince;
    /** {@code --shallow-exclude} revisions, in wire order. */
    final List<String> deepenNots;
    /** Shallow boundary commits the client already holds. */
    final List<ObjectId> clientShallows;
    /** Whether the request was a protocol v2 fetch. */
    final boolean protocolV2;

    ShallowRequest(int depth, long deepenSince, List<String> deepenNots,
                   List<ObjectId> clientShallows, boolean protocolV2) {
        this.depth = depth;
        this.deepenSince = deepenSince;
        this.deepenNots = List.copyOf(deepenNots);
        this.clientShallows = List.copyOf(clientShallows);
        this.protocolV2 = protocolV2;
    }

    /** True if the client asked for a shallow (or deepening) fetch. */
    boolean isShallow() {
        return depth > 0 || deepenSince > 0 || !deepenNots.isEmpty() || !clientShallows.isEmpty();
    }

    /** A log-friendly one-liner, e.g. {@code depth=1 shallows=0}. */
    String summary() {
        if (!isShallow()) {
            return "shallow=false";
        }
        StringBuilder sb = new StringBuilder("shallow=true");
        if (depth > 0) {
            sb.append(" depth=").append(depth);
        }
        if (deepenSince > 0) {
            sb.append(" deepen-since=").append(deepenSince);
        }
        if (!deepenNots.isEmpty()) {
            sb.append(" deepen-not=").append(deepenNots);
        }
        sb.append(" client-shallows=").append(clientShallows.size());
        return sb.toString();
    }
}
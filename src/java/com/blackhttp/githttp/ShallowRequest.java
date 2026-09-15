package com.blackhttp.githttp;

import org.eclipse.jgit.lib.ObjectId;

import java.util.List;

/**
 * The depth/shallow/filter parameters a client asked for in an upload-pack
 * request body. Captured from the wire so the server can log, and later
 * forward, the same shallow semantics when fetching from an upstream
 * repository.
 */
final class ShallowRequest {
    static final ShallowRequest NONE =
            new ShallowRequest(0, 0, List.of(), List.of(), false, null, 0, 0, null, List.of());

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
    /** Protocol v2 command ({@code fetch}, {@code ls-refs}); {@code null} for v0/v1. */
    final String command;
    /** Number of {@code want} lines in the request body. */
    final int wantCount;
    /** Number of {@code have} lines in the request body. */
    final int haveCount;
    /** {@code --filter} spec such as {@code blob:none}; {@code null} if absent. */
    final String filterSpec;
    /** All decoded pkt-line payloads of the request body (for debug logging). */
    final List<String> requestLines;

    ShallowRequest(int depth, long deepenSince, List<String> deepenNots,
                   List<ObjectId> clientShallows, boolean protocolV2, String command,
                   int wantCount, int haveCount, String filterSpec, List<String> requestLines) {
        this.depth = depth;
        this.deepenSince = deepenSince;
        this.deepenNots = List.copyOf(deepenNots);
        this.clientShallows = List.copyOf(clientShallows);
        this.protocolV2 = protocolV2;
        this.command = command;
        this.wantCount = wantCount;
        this.haveCount = haveCount;
        this.filterSpec = filterSpec;
        this.requestLines = List.copyOf(requestLines);
    }

    /** True if the client asked for a shallow (or deepening) fetch. */
    boolean isShallow() {
        return depth > 0 || deepenSince > 0 || !deepenNots.isEmpty() || !clientShallows.isEmpty();
    }

    /** A log-friendly one-liner covering the whole request body, e.g.
     *  {@code shallow=true depth=1 client-shallows=0 command=fetch wants=1 haves=0 filter=blob:none}. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        if (isShallow()) {
            sb.append("shallow=true");
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
        } else {
            sb.append("shallow=false");
        }
        if (command != null) {
            sb.append(" command=").append(command);
        }
        sb.append(" wants=").append(wantCount)
                .append(" haves=").append(haveCount);
        if (filterSpec != null) {
            sb.append(" filter=").append(filterSpec);
        }
        return sb.toString();
    }
}
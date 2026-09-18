package com.black;

import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Passively inspects a git upload-pack request body (protocol v0/v1 or v2,
 * both pkt-line framed over the stateless HTTP transport) and extracts the
 * depth/shallow negotiation parameters. The body is not consumed: callers read
 * the request once here and again to let JGit negotiate normally.
 */
public final class FetchRequestParser {
    private static final String PACKET_DEEPEN = "deepen ";
    private static final String PACKET_DEEPEN_SINCE = "deepen-since ";
    private static final String PACKET_DEEPEN_NOT = "deepen-not ";
    private static final String PACKET_SHALLOW = "shallow ";
    private static final String PACKET_FILTER = "filter ";
    private static final String PACKET_WANT = "want ";
    private static final String PACKET_HAVE = "have ";
    private static final String PACKET_COMMAND = "command=";

    private FetchRequestParser() {
    }

    public static ShallowRequest parse(InputStream in, boolean protocolV2) {
        int depth = 0;
        long deepenSince = 0;
        int wantCount = 0;
        int haveCount = 0;
        String command = null;
        String filterSpec = null;
        List<String> deepenNots = new ArrayList<>();
        List<ObjectId> shallows = new ArrayList<>();
        List<ObjectId> wants = new ArrayList<>();
        final List<String> lines;
        try {
            lines = packetLines(in);
        } catch (Exception e) {
            Log.logger.debug("failed to read fetch request body: {}", e.toString());
            return ShallowRequest.NONE;
        }
        for (String line : lines) {
            if (line.startsWith(PACKET_COMMAND)) {
                command = line.substring(PACKET_COMMAND.length()).trim();
            } else if (line.startsWith(PACKET_DEEPEN)) {
                depth = positiveInt(line, PACKET_DEEPEN);
            } else if (line.startsWith(PACKET_DEEPEN_SINCE)) {
                deepenSince = longValue(line, PACKET_DEEPEN_SINCE);
            } else if (line.startsWith(PACKET_DEEPEN_NOT)) {
                deepenNots.add(line.substring(PACKET_DEEPEN_NOT.length()).trim());
            } else if (line.startsWith(PACKET_SHALLOW) && line.length() >= PACKET_SHALLOW.length() + 40) {
                try {
                    shallows.add(ObjectId.fromString(
                            line.substring(PACKET_SHALLOW.length()).trim()));
                } catch (IllegalArgumentException e) {
                    Log.logger.debug("ignoring malformed shallow line: {}", line);
                }
            } else if (line.startsWith(PACKET_FILTER)) {
                filterSpec = line.substring(PACKET_FILTER.length()).trim();
            } else if (line.startsWith(PACKET_WANT)) {
                wantCount++;
                // "want <sha> [<capabilities>]" (v0/v1) or "want <sha>" (v2)
                String rest = line.substring(PACKET_WANT.length()).trim();
                String sha = rest.split("\\s+")[0];
                try {
                    wants.add(ObjectId.fromString(sha));
                } catch (IllegalArgumentException e) {
                    Log.logger.debug("ignoring malformed want line: {}", line);
                }
            } else if (line.startsWith(PACKET_HAVE)) {
                haveCount++;
            }
        }
        return new ShallowRequest(depth, deepenSince, deepenNots, shallows, protocolV2,
                command, wantCount, haveCount, filterSpec, lines, wants);
    }

    private static int positiveInt(String line, String prefix) {
        try {
            return Integer.parseInt(line.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longValue(String line, String prefix) {
        try {
            return Long.parseLong(line.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Decodes the pkt-line framed body into payload lines (flush/delim excluded). */
    private static List<String> packetLines(InputStream in) throws IOException {
        byte[] body = in.readAllBytes();
        List<String> lines = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= body.length) {
            int len = hex(body, pos);
            pos += 4;
            if (len == 0) {
                break; // flush-pkt
            }
            if (len == 1) {
                continue; // delimiter-pkt is the literal "0001", no payload bytes
            }
            if (len < 4 || pos + (len - 4) > body.length) {
                break; // truncated
            }
            String payload = new String(body, pos, len - 4, StandardCharsets.UTF_8);
            pos += len - 4;
            if (payload.endsWith("\n")) {
                payload = payload.substring(0, payload.length() - 1);
            }
            if (!payload.isEmpty()) {
                lines.add(payload);
            }
        }
        return lines;
    }

    private static int hex(byte[] buf, int off) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 4) | Character.digit((char) buf[off + i], 16);
        }
        return v;
    }
}
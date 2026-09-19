package com.black;

import java.util.Map;

/** Minimal JSON serializer for the lock list (no Jackson dependency). */
public final class FileLocksJson {
    private FileLocksJson() {}

    public static String toJson(Map<String, FileLocks.Lock> locks) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, FileLocks.Lock> e : locks.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(e.getKey())).append("\":{");
            FileLocks.Lock l = e.getValue();
            sb.append("\"user\":\"").append(esc(l.user)).append("\"");
            if (l.since != null) {
                sb.append(",\"since\":\"").append(esc(l.since)).append("\"");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

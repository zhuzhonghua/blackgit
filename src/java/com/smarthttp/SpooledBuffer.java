package com.smarthttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An OutputStream that keeps the first {@code memoryLimit} bytes in memory and
 * spills anything beyond that to a temporary file, so large git pack bodies are
 * never fully resident in RAM. Because everything before the spill point is
 * copied into the file on the first spill and the memory buffer is reset, the
 * content at any time lives entirely in memory or entirely on disk.
 */
public final class SpooledBuffer extends OutputStream {
    private static final int BLOCK = 1 << 16;

    private final int memoryLimit;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream(BLOCK);
    private Path spoolFile;
    private OutputStream file;
    private long size;

    public SpooledBuffer(int memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        long toMemory = Math.max(0, memoryLimit - size);
        int mem = (int) Math.min(toMemory, len);
        if (mem > 0) {
            memory.write(b, off, mem);
        }
        int rest = len - mem;
        if (rest > 0) {
            ensureFile().write(b, off + mem, rest);
        }
        size += len;
    }

    private OutputStream ensureFile() throws IOException {
        if (file == null) {
            spoolFile = Files.createTempFile("blackgit-spool-", ".dat");
            file = Files.newOutputStream(spoolFile);
            memory.writeTo(file);
            memory = new ByteArrayOutputStream(BLOCK);
        }
        return file;
    }

    public long size() {
        return size;
    }

    public InputStream input() throws IOException {
        if (file == null) {
            return new ByteArrayInputStream(memory.toByteArray());
        }
        return Files.newInputStream(spoolFile);
    }

    @Override
    public void close() throws IOException {
        if (file != null) {
            file.close();
            file = null;
        }
        if (spoolFile != null) {
            try {
                Files.deleteIfExists(spoolFile);
            } catch (IOException ignored) {
                spoolFile.toFile().deleteOnExit();
            }
            spoolFile = null;
        }
        memory = new ByteArrayOutputStream(BLOCK);
        size = 0;
    }
}
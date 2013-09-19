package com.turn.ttorrent.testutil;

import uk.co.itstherules.external.ApacheFileUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class TempFiles {

    private static final File TEMP_DIRECTORY = new File(System.getProperty("tmp.dir"), "ttorrent-test");

    private static final Random RANDOM = new Random();

    private final File currentTempDir;
    private final List<File> filesToDelete = new ArrayList<File>();
    private final Thread shutdownHook;
    private volatile boolean insideShutdownHook;

    public TempFiles() {
        currentTempDir = TEMP_DIRECTORY;
        if (!currentTempDir.isDirectory() && !currentTempDir.mkdirs()) {
            throw new IllegalStateException("Temp directory is not a directory, was deleted by some process: "
                    + currentTempDir.getAbsolutePath());
        }

        shutdownHook = new Thread(new Runnable() {
            public void run() {
                insideShutdownHook = true;
                cleanup();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private File createTempDir(String prefix, String suffix) throws IOException {
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? ".tmp" : suffix;

        do {
            int count = RANDOM.nextInt();
            final File f = new File(currentTempDir, prefix + count + suffix);
            if (!f.exists() && f.mkdirs()) {
                return f.getCanonicalFile();
            }
        } while (true);

    }

    private File createTempFile(String prefix, String suffix) throws IOException {
        final File file = createTempDir(prefix, suffix);
        file.delete();
        file.createNewFile();
        return file;
    }

    public final File createTempFile() throws IOException {
        File tempFile = createTempFile("test", null);
        registerAsTempFile(tempFile);
        return tempFile;
    }

    public void registerAsTempFile(final File tempFile) {
        filesToDelete.add(tempFile);
    }

    public final File createTempFile(int size) throws IOException {
        File tempFile = createTempFile();
        int bufLen = Math.min(8 * 1024, size);
        if (bufLen == 0) return tempFile;
        final OutputStream fos = new BufferedOutputStream(new FileOutputStream(tempFile));
        try {
            byte[] buf = new byte[bufLen];
            RANDOM.nextBytes(buf);

            int numWritten = 0;
            for (int i = 0; i < size / buf.length; i++) {
                fos.write(buf);
                numWritten += buf.length;
            }

            if (size > numWritten) {
                fos.write(buf, 0, size - numWritten);
            }
        } finally {
            fos.close();
        }
        return tempFile;
    }

    public final File createTempDir() throws IOException {
        File f = createTempDir("test", "");
        registerAsTempFile(f);
        return f;
    }

    public File getCurrentTempDir() {
        return currentTempDir;
    }

    public void cleanup() {
        try {
            for (File file : filesToDelete) {
                ApacheFileUtils.deleteQuietly(file);
            }

            filesToDelete.clear();
            ApacheFileUtils.deleteQuietly(TEMP_DIRECTORY);
        } finally {
            if (!insideShutdownHook) {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            }
        }
    }
}
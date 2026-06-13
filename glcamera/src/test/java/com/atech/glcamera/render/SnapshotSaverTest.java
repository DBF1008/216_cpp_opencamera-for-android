package com.atech.glcamera.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for the snapshot save thread boundary.
 *
 * <p>Guards against the original defect, where the snapshot encode + file write ran inline
 * on the GL render thread (in {@link GLByteFlowRender#onDrawFrame}) and stalled the live
 * preview. These tests assert that {@link SnapshotSaver} performs that work on a dedicated
 * background worker (never the caller's thread), serialized on a single thread so bursts of
 * captures cannot pile up.
 *
 * <p>A fake {@link SnapshotSaver.Encoder} is injected so the tests run on a plain host JVM
 * via the existing {@code junit} dependency, without the Android {@code Bitmap} stack or a
 * device/emulator.
 */
public class SnapshotSaverTest {

    @Test
    public void encodeAndCallbackRunOffTheCallingThread() throws Exception {
        final Thread callingThread = Thread.currentThread();
        final AtomicReference<Thread> encodeThread = new AtomicReference<>();
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        final AtomicReference<String> savedPath = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        SnapshotSaver.Encoder encoder = new SnapshotSaver.Encoder() {
            @Override
            public boolean encode(byte[] rgba, int width, int height, File out) throws IOException {
                encodeThread.set(Thread.currentThread());
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(rgba);
                }
                return true;
            }
        };
        SnapshotSaver saver = new SnapshotSaver(encoder);
        File target = File.createTempFile("snapshot", ".jpg");

        try {
            saver.save(new byte[]{1, 2, 3, 4}, 1, 1, target.getPath(), new SnapshotSaver.Callback() {
                @Override
                public void onSaved(String path) {
                    callbackThread.set(Thread.currentThread());
                    savedPath.set(path);
                    done.countDown();
                }
            });

            assertTrue("save did not complete within timeout", done.await(5, TimeUnit.SECONDS));
            assertNotNull("encode never ran", encodeThread.get());
            // Core regression: the heavy work must not run on the thread that requested it
            // (which in production is the GL render thread driving the preview).
            assertNotSame(callingThread, encodeThread.get());
            assertNotSame(callingThread, callbackThread.get());
            assertEquals(target.getAbsolutePath(), savedPath.get());
            assertTrue("encoded file should exist", target.exists());
        } finally {
            saver.release();
            target.delete();
        }
    }

    @Test
    public void burstOfSavesRunsSeriallyOnOneWorker() throws Exception {
        final int count = 5;
        final CountDownLatch done = new CountDownLatch(count);
        final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        final Set<Thread> workerThreads = Collections.synchronizedSet(new HashSet<Thread>());

        SnapshotSaver.Encoder encoder = new SnapshotSaver.Encoder() {
            @Override
            public boolean encode(byte[] rgba, int width, int height, File out) {
                workerThreads.add(Thread.currentThread());
                order.add((int) rgba[0]);
                return true;
            }
        };
        SnapshotSaver saver = new SnapshotSaver(encoder);
        File target = File.createTempFile("snapshot-serial", ".jpg");

        try {
            for (int i = 0; i < count; i++) {
                saver.save(new byte[]{(byte) i}, 1, 1, target.getPath(), new SnapshotSaver.Callback() {
                    @Override
                    public void onSaved(String path) {
                        done.countDown();
                    }
                });
            }

            assertTrue("not all saves completed within timeout", done.await(5, TimeUnit.SECONDS));
            // A single background worker handles every capture (no unbounded thread growth).
            assertEquals(1, workerThreads.size());
            assertNotSame(Thread.currentThread(), workerThreads.iterator().next());
            // Work is processed in submission order.
            assertEquals(Arrays.asList(0, 1, 2, 3, 4), order);
        } finally {
            saver.release();
            target.delete();
        }
    }
}

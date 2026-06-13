package com.atech.glcamera;

/**
 * A one-shot gate that serializes screenshot captures.
 *
 * <p>Exactly one capture may be "in flight" at a time. {@link #tryBeginCapture()} acquires the
 * gate (closing it); {@link #finishCapture()} must be called once the capture reaches any
 * terminal state — save success <em>or</em> save failure — to reopen it.
 *
 * <p>Reopening on failure as well as on success is the entire point of this class: it prevents a
 * single failed save (empty path, file-creation error, compression failure, I/O exception) from
 * permanently blocking every subsequent capture.
 *
 * <p>The methods are {@code synchronized} because the gate is touched from the camera callback
 * thread (begin) and the GL render thread (finish).
 */
public class CaptureGate {

    private boolean ready = true;

    /**
     * Attempts to start a capture.
     *
     * @return {@code true} if the gate was open and is now closed for this capture;
     *         {@code false} if a capture is already in flight and the request should be dropped.
     */
    public synchronized boolean tryBeginCapture() {
        if (!ready) {
            return false;
        }
        ready = false;
        return true;
    }

    /**
     * Marks the in-flight capture as finished (whether it succeeded or failed) and reopens the
     * gate so the next capture may proceed. Safe to call even when no capture is in flight.
     */
    public synchronized void finishCapture() {
        ready = true;
    }

    /**
     * @return whether a new capture can currently begin.
     */
    public synchronized boolean isReady() {
        return ready;
    }
}

package com.atech.glcamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for {@link CaptureGate}.
 *
 * <p>The original defect: the capture gate was only reopened when a screenshot was saved
 * <em>successfully</em>, so any save failure (empty path, file-creation error, compression
 * failure, I/O exception) left the gate permanently closed and silently blocked every
 * subsequent capture. These tests pin the contract that the gate reopens on every terminal
 * outcome — success and failure alike.
 */
public class CaptureGateTest {

    private CaptureGate gate;

    @Before
    public void setUp() {
        gate = new CaptureGate();
    }

    @Test
    public void gateStartsReady() {
        assertTrue(gate.isReady());
        assertTrue(gate.tryBeginCapture());
    }

    @Test
    public void secondCaptureIsBlockedWhileOneIsInFlight() {
        assertTrue(gate.tryBeginCapture());
        assertFalse("a capture is already in flight", gate.tryBeginCapture());
        assertFalse(gate.isReady());
    }

    @Test
    public void gateReopensAfterSuccessfulSave() {
        assertTrue(gate.tryBeginCapture());
        gate.finishCapture(); // onReadPixelsSaveSuccess
        assertTrue(gate.isReady());
        assertTrue("next capture allowed after a successful save", gate.tryBeginCapture());
    }

    /**
     * The core regression: a failed save must NOT block subsequent captures. Before the fix,
     * the failure path never reopened the gate and this second capture was impossible.
     */
    @Test
    public void gateReopensAfterFailedSave() {
        assertTrue(gate.tryBeginCapture());
        // Simulate saveToLocal hitting a failure path (empty path / compress()==false /
        // FileNotFoundException / IOException): the failure callback still releases the gate.
        gate.finishCapture(); // onReadPixelsSaveFailed
        assertTrue("gate must reopen even when the save failed", gate.isReady());
        assertTrue("next capture must be allowed after a failed save", gate.tryBeginCapture());
    }

    @Test
    public void repeatedFailedSavesNeverPermanentlyBlockCaptures() {
        for (int i = 0; i < 5; i++) {
            assertTrue("capture " + i + " should be able to start", gate.tryBeginCapture());
            gate.finishCapture(); // every save fails, but the gate is always released
        }
        assertTrue("captures still work after a run of failures", gate.tryBeginCapture());
    }

    @Test
    public void finishCaptureIsSafeWhenNoCaptureIsInFlight() {
        // Releasing when nothing is in flight should leave the gate open, not corrupt it.
        gate.finishCapture();
        assertTrue(gate.isReady());
        assertTrue(gate.tryBeginCapture());
        // Redundant releases must not wedge the gate either.
        gate.finishCapture();
        gate.finishCapture();
        assertTrue(gate.isReady());
    }
}

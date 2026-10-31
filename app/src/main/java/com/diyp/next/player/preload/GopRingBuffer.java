package com.diyp.next.player.preload;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * In-memory circular GOP buffer for IPTV MPEG-TS streams.
 * Capacity: 10 MB (roughly 5-20s of a 4-16 Mbps multicast TS stream), so the latest
 * keyframe is virtually always retained and channel switching can start from it.
 * Tracks PAT (PID 0) and Keyframes (H.264 IDR/SPS, H.265 VPS/SPS/IDR, TS random_access_indicator).
 * Buffers are pooled (at most 3, one per live preload worker) to avoid repeated multi-MB allocations.
 */
public class GopRingBuffer {
    public static final int CAPACITY = 10 * 1024 * 1024; // 10 MB
    private static final int TS_PACKET_SIZE = 188;
    private static final int MAX_POOLED = 3;

    private static final ArrayDeque<GopRingBuffer> POOL = new ArrayDeque<>(MAX_POOLED);
    private static boolean poolEnabled = true;

    private final byte[] buffer = new byte[CAPACITY];
    private final Object lock = new Object();

    private long generation = 0;
    private long totalWritten = 0;
    private long lastInspectedPos = 0;
    private long lastPatPos = -1;
    private long lastIFramePos = -1;
    private long currentKeyframePatPos = -1;
    private long prevKeyframePatPos = -1;

    private volatile boolean hasKeyframe = false;
    private volatile boolean isClosed = false;

    private long readPos = -1; // -1 means playback hasn't started yet

    public GopRingBuffer() {}

    /**
     * Obtains a buffer from the pool (lazily allocated on first use) and resets its state.
     */
    public static synchronized GopRingBuffer obtain() {
        poolEnabled = true;
        GopRingBuffer recycled = POOL.pollLast();
        GopRingBuffer rb = (recycled != null) ? recycled : new GopRingBuffer();
        rb.resetForReuse();
        return rb;
    }

    /**
     * Drops all pooled buffers, e.g. when preloading is stopped entirely.
     */
    public static synchronized void clearPool() {
        POOL.clear();
        poolEnabled = false;
    }

    private static synchronized void recycle(GopRingBuffer rb) {
        if (!poolEnabled || POOL.size() >= MAX_POOLED) {
            return;
        }
        POOL.offerLast(rb);
    }

    private void resetForReuse() {
        synchronized (lock) {
            generation++;
            totalWritten = 0;
            lastInspectedPos = 0;
            lastPatPos = -1;
            lastIFramePos = -1;
            currentKeyframePatPos = -1;
            prevKeyframePatPos = -1;
            hasKeyframe = false;
            isClosed = false;
            readPos = -1;
        }
    }

    public long getGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    /**
     * Returns true if this buffer still belongs to the given generation and is not closed.
     */
    public boolean isOwnedBy(long gen) {
        synchronized (lock) {
            return !isClosed && gen == generation;
        }
    }

    /**
     * Freezes the buffer without returning it to the pool (e.g. stream ended unexpectedly).
     */
    public void close() {
        synchronized (lock) {
            isClosed = true;
            lock.notifyAll();
        }
    }

    /**
     * Freezes the buffer and hands it back to the pool so the array can be reused.
     */
    public void release() {
        close();
        recycle(this);
    }

    /**
     * Producer: Write incoming TS stream data into circular buffer.
     * Writes from a stale generation (worker already released the buffer) are ignored.
     */
    public void write(byte[] data, int offset, int length, long gen) {
        if (data == null || length <= 0) return;

        synchronized (lock) {
            if (isClosed || gen != generation) return;

            // Copy data into ring buffer
            for (int i = 0; i < length; i++) {
                buffer[(int) ((totalWritten + i) % CAPACITY)] = data[offset + i];
            }
            totalWritten += length;

            // Inspect newly added TS packets
            inspectTsPackets();

            // Notify waiting consumer thread (ExoPlayer loader)
            lock.notifyAll();
        }
    }

    /**
     * Parse TS packets in buffer to locate PAT and Keyframe NAL units.
     */
    private void inspectTsPackets() {
        while (totalWritten - lastInspectedPos >= TS_PACKET_SIZE) {
            byte sync = getByte(lastInspectedPos);
            if (sync != 0x47) {
                // Re-synchronize to next 0x47 sync byte
                lastInspectedPos++;
                continue;
            }

            // Quick check next packet sync byte to confirm alignment
            if (totalWritten - lastInspectedPos >= TS_PACKET_SIZE * 2) {
                if (getByte(lastInspectedPos + TS_PACKET_SIZE) != 0x47) {
                    lastInspectedPos++;
                    continue;
                }
            }

            parseTsPacket(lastInspectedPos);
            lastInspectedPos += TS_PACKET_SIZE;
        }
    }

    private void parseTsPacket(long p) {
        byte b1 = getByte(p + 1);
        byte b2 = getByte(p + 2);
        byte b3 = getByte(p + 3);

        int pid = ((b1 & 0x1F) << 8) | (b2 & 0xFF);
        boolean pusi = (b1 & 0x40) != 0; // Payload Unit Start Indicator

        // 1. Check PAT (Program Association Table) on PID 0
        if (pid == 0) {
            lastPatPos = p;
        }

        // 2. Check TS Adaptation Field
        int adaptCtrl = (b3 >> 4) & 0x03;
        int payloadOffset = 4;

        if (adaptCtrl == 2) {
            // Adaptation field only, no payload
            return;
        } else if (adaptCtrl == 3) {
            int adaptLen = getByte(p + 4) & 0xFF;
            if (adaptLen > 0) {
                byte adaptFlags = getByte(p + 5);
                // Bit 6: random_access_indicator
                if ((adaptFlags & 0x40) != 0) {
                    onKeyframeDetected(p);
                }
            }
            payloadOffset = 5 + adaptLen;
        }

        // 3. Check Video PES Packet Header & NAL units when PUSI is set
        if (pusi && payloadOffset + 9 < TS_PACKET_SIZE) {
            if (getByte(p + payloadOffset) == 0x00 &&
                getByte(p + payloadOffset + 1) == 0x00 &&
                getByte(p + payloadOffset + 2) == 0x01) {

                int streamId = getByte(p + payloadOffset + 3) & 0xFF;
                // Video stream ID: 0xE0 - 0xEF
                if (streamId >= 0xE0 && streamId <= 0xEF) {
                    int pesHdrDataLen = getByte(p + payloadOffset + 8) & 0xFF;
                    int esOffset = payloadOffset + 9 + pesHdrDataLen;

                    // Scan for NAL start codes within ES payload of this TS packet
                    for (int k = esOffset; k + 4 < TS_PACKET_SIZE; k++) {
                        int nalByteIdx = -1;
                        if (getByte(p + k) == 0x00 && getByte(p + k + 1) == 0x00) {
                            if (getByte(p + k + 2) == 0x01) {
                                nalByteIdx = k + 3;
                            } else if (getByte(p + k + 2) == 0x00 && k + 4 < TS_PACKET_SIZE && getByte(p + k + 3) == 0x01) {
                                nalByteIdx = k + 4;
                            }
                        }

                        if (nalByteIdx != -1 && nalByteIdx < TS_PACKET_SIZE) {
                            int nalByte = getByte(p + nalByteIdx) & 0xFF;
                            int h264Type = nalByte & 0x1F;
                            int h265Type = (nalByte >> 1) & 0x3F;

                            // H.264 IDR (5), SPS (7)
                            // H.265 IDR (19, 20), IRAP (16-21), VPS (32), SPS (33)
                            if (h264Type == 5 || h264Type == 7 ||
                                h265Type == 19 || h265Type == 20 ||
                                (h265Type >= 16 && h265Type <= 21) ||
                                h265Type == 32 || h265Type == 33) {
                                onKeyframeDetected(p);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void onKeyframeDetected(long packetPos) {
        lastIFramePos = packetPos;
        long targetPat;
        // Prioritize starting from the nearest PAT packet preceding this keyframe
        if (lastPatPos >= 0 && lastPatPos <= packetPos && (packetPos - lastPatPos) < 1_500_000) {
            targetPat = lastPatPos;
        } else {
            targetPat = packetPos;
        }

        if (currentKeyframePatPos < 0) {
            currentKeyframePatPos = targetPat;
        } else if (targetPat - currentKeyframePatPos > 100_000) {
            // New distinct GOP keyframe detected! (At least 100KB apart to avoid NAL header repeats)
            prevKeyframePatPos = currentKeyframePatPos;
            currentKeyframePatPos = targetPat;
        } else {
            // Same GOP keyframe cluster (e.g. adaptation flag followed by SPS/PPS/IDR NAL)
            if (targetPat < currentKeyframePatPos) {
                currentKeyframePatPos = targetPat;
            }
        }
        hasKeyframe = true;
    }

    private byte getByte(long pos) {
        return buffer[(int) (pos % CAPACITY)];
    }

    /**
     * Returns true if buffer contains at least one validated keyframe.
     */
    public boolean hasKeyframe() {
        return hasKeyframe;
    }

    /**
     * Switch buffer to playback mode starting from the optimal keyframe.
     * Implements Dual Keyframe Depth Alignment:
     * If the latest keyframe has < 450 KB (~450ms) of buffered data and the previous
     * keyframe is still in the circular buffer, start from the previous keyframe
     * to guarantee >= 450ms of stream data for instant, seamless playback startup (<20ms).
     */
    public void startPlaybackFromKeyframe() {
        synchronized (lock) {
            if (hasKeyframe && currentKeyframePatPos >= 0) {
                long availableSinceLatest = totalWritten - currentKeyframePatPos;
                long targetStart = currentKeyframePatPos;

                // Scheme 3: Dual Keyframe Depth Alignment
                if (availableSinceLatest < 450_000 && prevKeyframePatPos >= 0
                        && (totalWritten - prevKeyframePatPos <= CAPACITY)) {
                    targetStart = prevKeyframePatPos;
                }

                // Ensure selected keyframe hasn't been overwritten in ring buffer
                if (totalWritten - targetStart <= CAPACITY) {
                    readPos = targetStart;
                } else {
                    readPos = Math.max(0, totalWritten - CAPACITY);
                }
            } else {
                readPos = Math.max(0, totalWritten - CAPACITY);
            }
            lock.notifyAll();
        }
    }

    /**
     * Consumer: Read data from ring buffer into destination array.
     * Blocks if buffer is temporarily exhausted and writer is still active.
     * Returns -1 if the buffer was recycled by another worker (stale generation).
     */
    public int read(byte[] out, int offset, int length, long gen) throws IOException {
        if (out == null || length <= 0) return 0;

        synchronized (lock) {
            if (gen != generation) {
                return -1;
            }

            if (readPos < 0) {
                startPlaybackFromKeyframe();
            }

            while (readPos >= totalWritten && !isClosed) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for stream data");
                }
            }

            if (isClosed && readPos >= totalWritten) {
                return -1; // End of stream
            }

            int available = (int) (totalWritten - readPos);
            if (available <= 0) {
                return isClosed ? -1 : 0;
            }

            int toRead = Math.min(length, available);
            for (int i = 0; i < toRead; i++) {
                out[offset + i] = buffer[(int) ((readPos + i) % CAPACITY)];
            }
            readPos += toRead;
            return toRead;
        }
    }

    public boolean isClosed() {
        return isClosed;
    }
}

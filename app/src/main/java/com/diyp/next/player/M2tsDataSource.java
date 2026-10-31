package com.diyp.next.player;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stream adapter DataSource for MPEG-2 Transport Streams encapsulated in BDAV 192-byte format (.m2ts / .mts).
 *
 * Background:
 * Standard MPEG-TS packets are 188 bytes beginning with sync byte 0x47.
 * BDAV / AVCHD (.m2ts / .mts) packets are 192 bytes = 4 bytes TP_extra_header (timecode) + 188 bytes MPEG-TS payload.
 * ExoPlayer's TsExtractor sniffs TS streams using a 188-byte stride, so raw M2TS causes UnrecognizedInputFormatException.
 *
 * M2tsDataSource transparently strips the 4-byte header of every 192-byte packet on the fly,
 * presenting a standard 188-byte MPEG-TS stream directly to ExoPlayer's TsExtractor.
 * It accurately maps virtual seek positions to physical stream offsets with zero buffering delay or transcoding overhead.
 */
@OptIn(markerClass = UnstableApi.class)
public final class M2tsDataSource implements DataSource {

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        public Factory(@NonNull DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new M2tsDataSource(upstreamFactory.createDataSource());
        }
    }

    private final DataSource upstream;
    private final byte[] scratch = new byte[4];
    private int bytesRemainingInCurrentPacket = 0;
    private boolean isEof = false;
    private boolean opened = false;

    public M2tsDataSource(@NonNull DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        long virtualPosition = dataSpec.position;
        long packetIndex = virtualPosition / 188L;
        int rem = (int) (virtualPosition % 188L);

        // Physical offset in BDAV stream:
        // Each BDAV packet is 192 bytes: 4 bytes TP_extra_header + 188 bytes TS payload.
        long physicalPosition = packetIndex * 192L + 4L + rem;
        bytesRemainingInCurrentPacket = 188 - rem;
        isEof = false;

        long physicalLength = C.LENGTH_UNSET;
        if (dataSpec.length != C.LENGTH_UNSET) {
            long endVirtualPosition = virtualPosition + dataSpec.length;
            long endPacketIndex = endVirtualPosition / 188L;
            int endRem = (int) (endVirtualPosition % 188L);
            long endPhysicalPosition = endPacketIndex * 192L + (endRem == 0 ? 0L : (4L + endRem));
            physicalLength = Math.max(0L, endPhysicalPosition - physicalPosition);
        }

        DataSpec upstreamSpec = dataSpec.buildUpon()
                .setPosition(physicalPosition)
                .setLength(physicalLength)
                .build();

        long upstreamOpenResult = upstream.open(upstreamSpec);
        opened = true;

        if (dataSpec.length != C.LENGTH_UNSET) {
            return dataSpec.length;
        }

        if (upstreamOpenResult == C.LENGTH_UNSET) {
            return C.LENGTH_UNSET;
        }

        // Map physical remaining bytes to virtual remaining bytes
        long totalPhysicalBytes = physicalPosition + upstreamOpenResult;
        long totalPackets = totalPhysicalBytes / 192L;
        long remainingPhysicalInLastPacket = totalPhysicalBytes % 192L;
        long totalVirtualBytes = totalPackets * 188L + Math.max(0L, remainingPhysicalInLastPacket - 4L);
        return Math.max(0L, totalVirtualBytes - virtualPosition);
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (isEof) {
            return C.RESULT_END_OF_INPUT;
        }

        int totalBytesRead = 0;
        while (totalBytesRead < length) {
            if (bytesRemainingInCurrentPacket == 0) {
                discardHeader();
                if (isEof) {
                    break;
                }
            }

            int toRead = Math.min(length - totalBytesRead, bytesRemainingInCurrentPacket);
            int bytesRead = upstream.read(buffer, offset + totalBytesRead, toRead);
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                isEof = true;
                break;
            }

            bytesRemainingInCurrentPacket -= bytesRead;
            totalBytesRead += bytesRead;

            // If upstream delivered fewer bytes than requested, avoid blocking and return accumulated bytes
            if (bytesRead < toRead) {
                break;
            }
        }

        if (totalBytesRead == 0 && isEof) {
            return C.RESULT_END_OF_INPUT;
        }
        return totalBytesRead;
    }

    private void discardHeader() throws IOException {
        int bytesToDiscard = 4;
        while (bytesToDiscard > 0) {
            int read = upstream.read(scratch, 0, bytesToDiscard);
            if (read == C.RESULT_END_OF_INPUT) {
                isEof = true;
                bytesRemainingInCurrentPacket = 0;
                return;
            }
            if (read > 0) {
                bytesToDiscard -= read;
            }
        }
        bytesRemainingInCurrentPacket = 188;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        Map<String, List<String>> headers = upstream.getResponseHeaders();
        return headers != null ? headers : Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        try {
            if (opened) {
                opened = false;
                upstream.close();
            }
        } finally {
            bytesRemainingInCurrentPacket = 0;
            isEof = false;
        }
    }
}

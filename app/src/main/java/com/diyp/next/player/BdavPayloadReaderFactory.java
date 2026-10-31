package com.diyp.next.player;

import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * Custom TsPayloadReader.Factory for BDAV / Blu-ray M2TS (.m2ts / .mts) streams.
 *
 * In Blu-ray / BDAV TS containers, elementary audio stream types in PMT include:
 *  - 0x81: Dolby Digital (AC-3)
 *  - 0x82: HDMV DTS
 *  - 0x84: BDAV Dolby Digital Plus (E-AC-3)
 *  - 0x85: DTS-HD High Resolution Audio (HRA)
 *  - 0x86: DTS-HD Master Audio (MA) [in standard TS, 0x86 is SCTE-35 splice info]
 *  - 0x8A: DTS
 *  - 0xA1: BDAV Secondary E-AC-3
 *  - 0xA2: BDAV Secondary DTS-HD
 *
 * Standard DefaultTsPayloadReaderFactory either drops these (0x84, 0x85, 0xA1, 0xA2),
 * misidentifies 0x86 as SCTE-35 splice info descriptor, or requires explicit flags (0x82).
 * This factory intercepts these BDAV stream types and remaps them to standard AC-3 / DTS
 * stream types so DefaultTsPayloadReaderFactory constructs the corresponding PES reader
 * with full version compatibility.
 */
@OptIn(markerClass = UnstableApi.class)
public final class BdavPayloadReaderFactory implements TsPayloadReader.Factory {

    private final DefaultTsPayloadReaderFactory defaultFactory;

    public BdavPayloadReaderFactory(int defaultTsPayloadReaderFlags) {
        this.defaultFactory = new DefaultTsPayloadReaderFactory(defaultTsPayloadReaderFlags);
    }

    @NonNull
    @Override
    public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
        return defaultFactory.createInitialPayloadReaders();
    }

    @Nullable
    @Override
    public TsPayloadReader createPayloadReader(int streamType, @NonNull TsPayloadReader.EsInfo esInfo) {
        switch (streamType) {
            case 0x84: // BDAV Dolby Digital Plus (E-AC-3)
            case 0xA1: // BDAV Secondary E-AC-3
                // Remap to standard AC-3 so defaultFactory creates the matching reader
                return defaultFactory.createPayloadReader(TsExtractor.TS_STREAM_TYPE_AC3, esInfo);

            case 0x82: // HDMV DTS
            case 0x85: // DTS-HD High Resolution Audio (HRA)
            case 0x86: // DTS-HD Master Audio (MA) - in standard TS this is SCTE-35 splice info!
            case 0xA2: // BDAV Secondary DTS-HD
                // Remap to standard DTS (0x8A) so defaultFactory creates the matching DtsReader
                return defaultFactory.createPayloadReader(TsExtractor.TS_STREAM_TYPE_DTS, esInfo);

            default:
                return defaultFactory.createPayloadReader(streamType, esInfo);
        }
    }
}

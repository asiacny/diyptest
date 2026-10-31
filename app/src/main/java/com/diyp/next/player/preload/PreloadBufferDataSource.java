package com.diyp.next.player.preload;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Media3 DataSource implementation backed by GopRingBuffer and PreloadWorker.
 * Supplies instant I-frame and PAT packets directly from RAM to ExoPlayer,
 * then seamlessly continues streaming live incoming packets from the established connection.
 */
@OptIn(markerClass = UnstableApi.class)
public class PreloadBufferDataSource extends BaseDataSource {
    private final PreloadWorker worker;
    private DataSpec dataSpec;
    private boolean opened = false;
    private boolean released = false;

    public PreloadBufferDataSource(PreloadWorker worker) {
        super(/* isNetwork= */ true);
        this.worker = worker;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        transferInitializing(dataSpec);

        if (worker == null || worker.isAborted()) {
            throw new IOException("PreloadWorker is invalid or aborted");
        }

        // Start playback from the nearest keyframe / PAT
        worker.getRingBuffer().startPlaybackFromKeyframe();
        opened = true;
        transferStarted(dataSpec);

        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }

        if (worker == null) {
            return C.RESULT_END_OF_INPUT;
        }

        int bytesRead = worker.getRingBuffer().read(buffer, offset, length);
        if (bytesRead > 0) {
            bytesTransferred(bytesRead);
            return bytesRead;
        } else if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT;
        }
        return 0;
    }

    @Nullable
    @Override
    public Uri getUri() {
        if (dataSpec != null) {
            return dataSpec.uri;
        }
        return (worker != null && worker.getStreamUrl() != null) ? Uri.parse(worker.getStreamUrl()) : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        if (released) {
            return;
        }
        released = true;
        if (opened) {
            opened = false;
            transferEnded();
        }
        if (worker != null) {
            PreloadChannelManager.getInstance().reclaim(worker);
        }
    }
}

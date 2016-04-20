/*
 * Copyright (c) 2016 Mario Guggenberger <mg@protyposis.net>
 *
 * This file is part of ITEC MediaPlayer.
 *
 * ITEC MediaPlayer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ITEC MediaPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ITEC MediaPlayer.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.aau.itec.android.mediaplayer;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

/**
 * Created by Mario on 20.04.2016.
 */
class MediaCodecVideoDecoder extends MediaCodecDecoder {

    private Surface mVideoSurface;

    public MediaCodecVideoDecoder(MediaExtractor extractor, boolean passive, int trackIndex,
                                  OnDecoderEventListener listener, Surface videoSurface)
            throws IOException {
        super(extractor, passive, trackIndex, listener);
        mVideoSurface = videoSurface;
        reconfigureCodec();
    }

    @Override
    protected void configureCodec(MediaCodec codec, MediaFormat format) {
        codec.configure(format, mVideoSurface, null, 0);
    }

    public int getVideoWidth() {
        MediaFormat format = getFormat();
        return format != null ? (int)(format.getInteger(MediaFormat.KEY_HEIGHT)
                * format.getFloat(MediaExtractor.MEDIA_FORMAT_EXTENSION_KEY_DAR)) : 0;
    }

    public int getVideoHeight() {
        MediaFormat format = getFormat();
        return format != null ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
    }

    /**
     * Releases all data belonging to a frame and optionally renders it to the configured surface.
     */
    public void releaseFrame(FrameInfo frameInfo, boolean render) {
        getCodec().releaseOutputBuffer(frameInfo.buffer, render); // render picture
        releaseFrameInfo(frameInfo);
    }

    /**
     * Releases all data belonging to a frame and optionally renders it to the configured surface.
     */
    @TargetApi(21)
    public void releaseFrame(FrameInfo frameInfo, long renderOffsetUs) {
        long renderTimestampNs = System.nanoTime() + (renderOffsetUs * 1000);
        getCodec().releaseOutputBuffer(frameInfo.buffer, renderTimestampNs); // render picture
        releaseFrameInfo(frameInfo);
    }

    @Override
    protected FrameInfo seekTo(MediaPlayer.SeekMode seekMode, long seekTargetTimeUs,
                               MediaExtractor extractor, MediaCodec codec) throws IOException {
        /* Android API compatibility:
         * Use millisecond precision to stay compatible with VideoView API that works
         * in millisecond precision only. Else, exact seek matches are missed if frames
         * are positioned at fractions of a millisecond. */
        long presentationTimeMs = -1;
        long seekTargetTimeMs = seekTargetTimeUs / 1000;

        FrameInfo frameInfo = super.seekTo(seekMode, seekTargetTimeUs, extractor, codec);

        if(seekMode == MediaPlayer.SeekMode.FAST) {
            Log.d(TAG, "fast seek to " + seekTargetTimeUs + " arrived at " + frameInfo.presentationTimeUs);
        }
        else if (seekMode == MediaPlayer.SeekMode.FAST_EXACT) {
            releaseFrame(frameInfo, false);
            fastSeek(seekTargetTimeUs, extractor, codec);

            frameInfo = decodeFrame(true, true);
            Log.d(TAG, "fast_exact seek to " + seekTargetTimeUs + " arrived at " + frameInfo.presentationTimeUs);

            if(frameInfo.presentationTimeUs < seekTargetTimeUs) {
                Log.d(TAG, "presentation is behind...");
            }

            return frameInfo;
        }
        else if (seekMode == MediaPlayer.SeekMode.PRECISE || seekMode == MediaPlayer.SeekMode.EXACT) {
            /* NOTE
             * This code seeks one frame too far, except if the seek time equals the
             * frame PTS:
             * (F1.....)(F2.....)(F3.....) ... (Fn.....)
             * A frame is shown for an interval, e.g. (1/fps seconds). Now if the seek
             * target time is somewhere in frame 2's interval, we end up with frame 3
             * because we need to decode it to know if the seek target time lies in
             * frame 2's interval (because we don't know the frame rate of the video,
             * and neither if it's a fixed frame rate or a variable one - even when
             * deriving it from the PTS series we cannot be sure about it). This means
             * we always end up one frame too far, because the MediaCodec does not allow
             * to go back, except when starting at a sync frame.
             *
             * Solution for fixed frame rate could be to subtract the frame interval
             * time (1/fps secs) from the seek target time.
             *
             * Solution for variable frame rate and unknown frame rate: go back to sync
             * frame and re-seek to the now known exact PTS of the desired frame.
             * See EXACT mode handling below.
             */
            int frameSkipCount = 0;
            long lastPTS = -1;

            presentationTimeMs = frameInfo.presentationTimeUs / 1000;

            while(presentationTimeMs < seekTargetTimeMs) {
                if(frameSkipCount == 0) {
                    Log.d(TAG, "skipping frames...");
                }
                frameSkipCount++;

                if(isOutputEos()) {
                    /* When the end of stream is reached while seeking, the seek target
                     * time is set to the last frame's PTS, else the seek skips the last
                     * frame which then does not get rendered, and it might end up in a
                     * loop trying to reach the unreachable target time. */
                    seekTargetTimeUs = frameInfo.presentationTimeUs;
                    seekTargetTimeMs = seekTargetTimeUs / 1000;
                }

                if(frameInfo.endOfStream) {
                    Log.d(TAG, "end of stream reached, seeking to last frame");
                    releaseFrame(frameInfo, false);
                    return seekTo(seekMode, lastPTS);
                }

                lastPTS = frameInfo.presentationTimeUs;
                releaseFrame(frameInfo, false);

                frameInfo = decodeFrame(true, true);
                presentationTimeMs = frameInfo.presentationTimeUs / 1000;
            }

            Log.d(TAG, "frame new position:         " + frameInfo.presentationTimeUs);
            Log.d(TAG, "seeking finished, skipped " + frameSkipCount + " frames");

            if(seekMode == MediaPlayer.SeekMode.EXACT && presentationTimeMs > seekTargetTimeMs) {
                if(frameSkipCount == 0) {
                    // In a single stream, the initiating seek always seeks before or directly
                    // to the requested frame, and this case never happens. With DASH, when the seek
                    // target is very near a segment border, it can happen that a wrong segment
                    // (the following one) is determined as target seek segment, which means the
                    // target of the initiating seek is too far, and we cannot go back either because
                    // it is the first frame of the segment
                    // TODO avoid this case by fixing DASH seek (fix segment calculation or reissue
                    // seek to previous segment when this case is detected)
                    Log.w(TAG, "this should never happen");
                } else {
                    /* If the current frame's PTS it after the seek target time, we're
                     * one frame too far into the stream. This is because we do not know
                     * the frame rate of the video and therefore can't decide for a frame
                     * if its interval covers the seek target time of if there's already
                     * another frame coming. We know after the next frame has been
                     * decoded though if we're too far into the stream, and if so, and if
                     * EXACT mode is desired, we need to take the previous frame's PTS
                     * and repeat the seek with that PTS to arrive at the desired frame.
                     */
                    Log.d(TAG, "exact seek: repeat seek for previous frame at " + lastPTS);
                    releaseFrame(frameInfo, false);
                    return seekTo(seekMode, lastPTS);
                }
            }
        }

        if(presentationTimeMs == seekTargetTimeMs) {
            Log.d(TAG, "exact seek match!");
        }
//
//        if (mAudioExtractor != null && mAudioExtractor != mExtractor) {
//            mAudioExtractor.seekTo(mBufferInfo.presentationTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
//        }

        return frameInfo;
    }

    private long fastSeek(long targetTime, MediaExtractor extractor, MediaCodec codec) throws IOException {
        codec.flush();
        extractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        if(extractor.getSampleTime() == targetTime) {
            Log.d(TAG, "skip fastseek, already there");
            return targetTime;
        }

        // 1. Queue first sample which should be the sync/I frame
        skipToNextSample();
        queueSampleToCodec(false);

        // 2. Then, fast forward to target frame
        /* 2.1 Search for the best candidate frame, which is the one whose
         *     right/positive/future distance is minimized
         */
        extractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        /* Specifies how many frames we continue to check after the first candidate,
         * to account for DTS picture reordering (this value is arbitrarily chosen) */
        int maxFrameLookahead = 20;

        long candidatePTS = 0;
        long candidateDistance = Long.MAX_VALUE;
        int lookaheadCount = 0;

        while (extractor.advance() && lookaheadCount < maxFrameLookahead) {
            long distance = targetTime - extractor.getSampleTime();
            if (distance >= 0 && distance < candidateDistance) {
                candidateDistance = distance;
                candidatePTS = extractor.getSampleTime();
                //Log.d(TAG, "candidate " + candidatePTS + " d=" + candidateDistance);
            }
            if (distance < 0) {
                lookaheadCount++;
            }
        }
        targetTime = candidatePTS; // set best candidate frame as exact seek target

        // 2.2 Fast forward to chosen candidate frame
        extractor.seekTo(targetTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (extractor.getSampleTime() != targetTime) {
            extractor.advance();
        }
        Log.d(TAG, "exact fastseek match:       " + extractor.getSampleTime());

        return targetTime;
    }
}

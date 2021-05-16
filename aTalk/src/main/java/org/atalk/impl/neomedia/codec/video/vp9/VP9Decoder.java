/*
 * aTalk, android VoIP and Instant Messaging client
 * Copyright 2014 Eng Chong Meng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atalk.impl.neomedia.codec.video.vp9;

import org.atalk.android.R;
import org.atalk.android.aTalkApp;
import org.atalk.android.util.java.awt.Dimension;
import org.atalk.impl.neomedia.codec.AbstractCodec2;
import org.atalk.impl.neomedia.codec.FFmpeg;
import org.atalk.impl.neomedia.codec.video.AVFrame;
import org.atalk.impl.neomedia.codec.video.AVFrameFormat;
import org.atalk.impl.neomedia.codec.video.VPX;
import org.atalk.service.neomedia.codec.Constants;

import javax.media.Buffer;
import javax.media.Format;
import javax.media.ResourceUnavailableException;
import javax.media.format.VideoFormat;

import timber.log.Timber;

/**
 * Implements a VP9 decoder.
 *
 * @author Eng Chong Meng
 */
public class VP9Decoder extends AbstractCodec2
{
    /**
     * The decoder interface to use
     */
    private static final int INTERFACE = VPX.INTERFACE_VP9_DEC;

    /**
     * Default output formats
     */
    private static final VideoFormat[] SUPPORTED_OUTPUT_FORMATS
            = new VideoFormat[]{new AVFrameFormat(FFmpeg.PIX_FMT_YUV420P)};

    /**
     * Pointer to a native vpx_codec_dec_cfg structure containing the decoder configuration
     */
    private final long cfg = 0;

    /**
     * Pointer to the libvpx codec context to be used
     */
    private long context = 0;

    /**
     * Pointer to a native vpx_image structure, containing a decoded frame.
     * When doProcess() is called, this is either 0, or it has the address of
     * the next unprocessed image from the decoder.
     */
    private long img = 0;

    /**
     * Iterator for the frames in the decoder context. Can be re-initialized by setting its only element to 0.
     */
    private final long[] iter = new long[1];

    /**
     * Whether there are unprocessed frames left from a previous call to VP9.codec_decode()
     */
    private boolean leftoverFrames = false;

    /**
     * The last known height of the video output by this <tt>VPXDecoder</tt>. Used to detect changes in the output size.
     */
    private int mWidth;

    /**
     * The last known width of the video output by this <tt>VPXDecoder</tt>. Used to detect changes in the output size.
     */
    private int mHeight;

    /**
     * Initializes a new <tt>VPXDecoder</tt> instance.
     */
    public VP9Decoder()
    {
        super("VP9 VPX Decoder", VideoFormat.class, SUPPORTED_OUTPUT_FORMATS);
        inputFormats = new VideoFormat[]{new VideoFormat(Constants.VP9)};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doClose()
    {
        Timber.d("Closing decoder");
        if (context != 0) {
            VPX.codec_destroy(context);
            VPX.free(context);
        }
        if (cfg != 0)
            VPX.free(cfg);
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceUnavailableException if initialization failed
     */
    @Override
    protected void doOpen()
            throws ResourceUnavailableException
    {
        context = VPX.codec_ctx_malloc();
        //cfg = VPX.codec_dec_cfg_malloc();
        long flags = 0; //VPX.CODEC_USE_XMA;

        // The cfg NULL pointer is passed to vpx_codec_dec_init(). This is to allow the algorithm
        // to determine the stream configuration (width/height) and allocate memory automatically.
        int ret = VPX.codec_dec_init(context, INTERFACE, 0, flags);
        if (ret != VPX.CODEC_OK)
            throw new RuntimeException("Failed to initialize decoder, libvpx error:\n"
                    + VPX.codec_err_to_string(ret));

        Timber.d("VP9 decoder opened successfully");
    }

    int frameErrors = 0;

    /**
     * {@inheritDoc}
     *
     * Decodes a VP9 frame contained in <tt>inputBuffer</tt> into <tt>outputBuffer</tt> (in <tt>AVFrameFormat</tt>)
     *
     * @param inputBuffer input <tt>Buffer</tt>
     * @param outputBuffer output <tt>Buffer</tt>
     * @return <tt>BUFFER_PROCESSED_OK</tt> if <tt>inBuffer</tt> has been successfully processed
     */
    @Override
    protected int doProcess(Buffer inputBuffer, Buffer outputBuffer)
    {
        if (!leftoverFrames) {
            /*
             * All frames from the decoder context have been processed. Decode the next VP9
             * frame, and fill outputBuffer with the first decoded frame.
             */
            byte[] buf_data = (byte[]) inputBuffer.getData();
            int buf_offset = inputBuffer.getOffset();
            int buf_size = inputBuffer.getLength();

            int ret = VPX.codec_decode(context,
                    buf_data, buf_offset, buf_size,
                    0, VPX.DL_BEST_QUALITY);

            // if ((frameErrors++ % 50) == 0 || frameErrors < 10)
            //     Timber.d("VP9: Decode a frame: %s %s %s", bytesToHex(buf_data, 32), buf_offset, buf_size);

            if (ret != VPX.CODEC_OK) {
                if ((frameErrors++ % 100) == 0)
                    Timber.w("VP9: Discarding frame with decode error: %s %s %s %s", VPX.codec_err_to_string(ret),
                            buf_data, buf_offset, buf_size);
                if (frameErrors == 1)
                    aTalkApp.showToastMessage(R.string.service_gui_CALL_NO_MATCHING_FORMAT_H, VPX.codec_err_to_string(ret));
                outputBuffer.setDiscard(true);
                return BUFFER_PROCESSED_OK;
            }

            //decode has just been called, reset iterator
            iter[0] = 0;
            img = VPX.codec_get_frame(context, iter);
        }

        // Timber.d("VP9: Decoded image frame: %s %s", leftoverFrames, img);
        if (img == 0) {
            outputBuffer.setDiscard(true);
            return BUFFER_PROCESSED_OK;
        }

         // Fill outputBuffer with the newly decoded or leftover frame data.
        updateOutputFormat(
                VPX.img_get_d_w(img),
                VPX.img_get_d_h(img),
                ((VideoFormat) inputBuffer.getFormat()).getFrameRate());
        outputBuffer.setFormat(outputFormat);

        AVFrame avframe = makeAVFrame(img);
        outputBuffer.setData(avframe);

        // YUV420p format, 12 bits per pixel
        outputBuffer.setLength(mWidth * mHeight * 3 / 2);
        outputBuffer.setTimeStamp(inputBuffer.getTimeStamp());

        /*
         * outputBuffer is all setup now. Check the decoder context for more decoded frames.
         */
        img = VPX.codec_get_frame(context, iter);
        if (img == 0) //no more frames
        {
            leftoverFrames = false;
            return BUFFER_PROCESSED_OK;
        }
        else {
            leftoverFrames = true;
            return INPUT_BUFFER_NOT_CONSUMED;
        }
    }

    /**
     * Get matching outputs for a specified input <tt>Format</tt>.
     *
     * @param inputFormat input <tt>Format</tt>
     * @return array of matching outputs or null if there are no matching outputs.
     */
    @Override
    protected Format[] getMatchingOutputFormats(Format inputFormat)
    {
        VideoFormat inputVideoFormat = (VideoFormat) inputFormat;

        return new Format[]{
                new AVFrameFormat(
                        inputVideoFormat.getSize(),
                        inputVideoFormat.getFrameRate(),
                        FFmpeg.PIX_FMT_YUV420P)
        };
    }

    /**
     * Allocates a new AVFrame and set its data fields to the data fields from the
     * <tt>vpx_image_t</tt> pointed to by <tt>img</tt>. Also set its 'linesize' according to <tt>img</tt>.
     *
     * @param img pointer to a <tt>vpx_image_t</tt> whose data will be used
     * @return an AVFrame instance with its data fields set to the fields from <tt>img</tt>
     */
    private AVFrame makeAVFrame(long img)
    {
        AVFrame avframe = new AVFrame();
        long p0 = VPX.img_get_plane0(img);
        long p1 = VPX.img_get_plane1(img);
        long p2 = VPX.img_get_plane2(img);

        //p0, p1, p2 are pointers, while avframe_set_data uses offsets
        FFmpeg.avframe_set_data(avframe.getPtr(),
                p0,
                p1 - p0,
                p2 - p1);

        FFmpeg.avframe_set_linesize(avframe.getPtr(),
                VPX.img_get_stride0(img),
                VPX.img_get_stride1(img),
                VPX.img_get_stride2(img));

        return avframe;
    }

    /**
     * Sets the <tt>Format</tt> of the media data to be input for processing in this <tt>Codec</tt>.
     *
     * @param format the <tt>Format</tt> of the media data to be input for processing in this <tt>Codec</tt>
     * @return the <tt>Format</tt> of the media data to be input for processing in this <tt>Codec</tt>
     * if <tt>format</tt> is compatible with this <tt>Codec</tt>; otherwise, <tt>null</tt>
     */
    @Override
    public Format setInputFormat(Format format)
    {
        Format setFormat = super.setInputFormat(format);
        if (setFormat != null)
            reset();
        return setFormat;
    }

    /**
     * Changes the output format, if necessary, according to the new dimensions given via <tt>width</tt> and <tt>height</tt>.
     *
     * @param width new width
     * @param height new height
     * @param frameRate frame rate
     */
    private void updateOutputFormat(int width, int height, float frameRate)
    {
        if ((width > 0) && (height > 0)
                && ((mWidth != width) || (mHeight != height))) {
            mWidth = width;
            mHeight = height;
            outputFormat = new AVFrameFormat(
                    new Dimension(width, height),
                    frameRate,
                    FFmpeg.PIX_FMT_YUV420P);
        }
    }
}

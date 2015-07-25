/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import android.media.*;
import android.os.Process;
import android.util.Log;
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.RetainableByteBufferCache;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class AudioRecorder implements Runnable
{
    private static final String LOG_TAG = "AudioRecorder";
    private static final Logger s_logger = Logger.getLogger( "org.jsl.collider.Collider" );

    private final SessionManager m_sessionManager;
    private final String m_audioFormat;
    private final AudioRecord m_audioRecord;
    private final int m_frameSize;

    private final AudioPlayer m_audioPlayer;
    private final LinkedList<RetainableByteBuffer> m_list;

    private final Thread m_thread;
    private final RetainableByteBufferCache m_byteBufferCache;
    private final ReentrantLock m_lock;
    private final Condition m_cond;
    private int m_state;

    private static final int IDLE  = 0;
    private static final int START = 1;
    private static final int RUN   = 2;
    private static final int STOP  = 3;
    private static final int SHTDN = 4; /* shutdown */
    private static final int ESTPD = 5; /* encoder stopped */

    private AudioRecorder(
            SessionManager sessionManager,
            AudioRecord audioRecord,
            String audioFormat,
            int frameSize,
            boolean repeat )
    {
        m_sessionManager = sessionManager;
        m_audioRecord = audioRecord;
        m_audioFormat = audioFormat;
        m_frameSize = frameSize;
        if (repeat)
        {
            m_audioPlayer = AudioPlayer.create( audioFormat );
            m_list = new LinkedList<RetainableByteBuffer>();
        }
        else
        {
            m_audioPlayer = null;
            m_list = null;
        }
        m_thread = new Thread( this, LOG_TAG + " [" + audioFormat + "]" );
        m_byteBufferCache = new RetainableByteBufferCache( /* Take a buffer large enough for 8 audio frame messages */
            true, 8*Protocol.AudioFrame.getMessageSize(frameSize), 16 );
        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
        m_state = IDLE;
        m_thread.start();
    }

    public String getAudioFormat()
    {
        return m_audioFormat;
    }

    public void run()
    {
        Log.i( LOG_TAG, "run [" + m_audioFormat + "]: frameSize=" + m_frameSize );
        android.os.Process.setThreadPriority( Process.THREAD_PRIORITY_URGENT_AUDIO );
        RetainableByteBuffer byteBuffer = m_byteBufferCache.get();
        try
        {
            for (;;)
            {
                m_lock.lock();
                try
                {
                    while (m_state == IDLE)
                        m_cond.await();

                    if (m_state == START)
                    {
                        m_audioRecord.startRecording();
                    }
                    else if (m_state == STOP)
                    {
                        m_audioRecord.stop();
                        m_state = IDLE;

                        if (m_list != null)
                        {
                            int msgs = 0;
                            for (RetainableByteBuffer msg : m_list)
                            {
                                m_audioPlayer.write( msg );
                                msg.release();
                                msgs++;
                            }
                            m_list.clear();
                            Log.i( LOG_TAG, "Replayed " + msgs + " frames." );
                        }
                        continue;
                    }
                    else if (m_state == SHTDN)
                        break;
                }
                finally
                {
                    m_lock.unlock();
                }

                int position = byteBuffer.position();
                if ((byteBuffer.limit() - position) < Protocol.AudioFrame.getMessageSize(m_frameSize))
                {
                    byteBuffer.release();
                    byteBuffer = m_byteBufferCache.get();
                    position = 0;

                    if (BuildConfig.DEBUG && (byteBuffer.position() != position))
                        throw new AssertionError();
                }

                byteBuffer.position( position + Protocol.AudioFrame.getDataOffs() );
                if (BuildConfig.DEBUG && (byteBuffer.remaining() < m_frameSize))
                    throw new AssertionError();

                final int bytesReady = m_audioRecord.read( byteBuffer.getNioByteBuffer(), m_frameSize );
                if (bytesReady == m_frameSize)
                {
                    byteBuffer.position( position );
                    byteBuffer.limit( position + Protocol.AudioFrame.getMessageSize(m_frameSize) );

                    final RetainableByteBuffer msg = byteBuffer.slice();
                    m_sessionManager.send( msg );

                    if (m_list != null)
                    {
                        /* Audio player expects just an audion frame
                         * without message header.
                         */
                        msg.position( Protocol.AudioFrame.getDataOffs() );
                        m_list.add( msg.slice() );
                    }

                    msg.release();
                    byteBuffer.position( byteBuffer.limit() );
                }
                else
                {
                    Log.e( LOG_TAG, "readSize=" + m_frameSize + " bytesReady=" + bytesReady );
                    break;
                }
            }
        }
        catch (final InterruptedException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
            Thread.currentThread().interrupt();
        }

        m_audioRecord.stop();
        m_audioRecord.release();
        byteBuffer.release();

        Log.i( LOG_TAG, "run [" + m_audioFormat + "]: done" );
    }

    public void startRecording()
    {
        Log.d( LOG_TAG, "startRecording" );
        m_lock.lock();
        try
        {
            if (m_state == IDLE)
            {
                m_state = START;
                m_cond.signal();
            }
            else if (m_state == STOP)
                m_state = RUN;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void stopRecording()
    {
        Log.d( LOG_TAG, "stopRecording" );
        m_lock.lock();
        try
        {
            if (m_state != IDLE)
                m_state = STOP;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void shutdown()
    {
        Log.d( LOG_TAG, "shutdown" );
        m_lock.lock();
        try
        {
            if (m_state == IDLE)
                m_cond.signal();
            m_state = SHTDN;
        }
        finally
        {
            m_lock.unlock();
        }

        try
        {
            m_thread.join();
        }
        catch (final InterruptedException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }

        if (m_audioPlayer != null)
            m_audioPlayer.stopAndWait();

        m_byteBufferCache.clear( s_logger );
    }

    public static AudioRecorder create( SessionManager sessionManager, boolean repeat )
    {
        final int rates [] = { 11025, 16000, 22050, 44100 };
        for (int sampleRate : rates)
        {
            final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            final int minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT );

            if ((minBufferSize != AudioRecord.ERROR) &&
                (minBufferSize != AudioRecord.ERROR_BAD_VALUE))
            {
                final AudioRecord audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize*10 );

                final String audioFormat = ("PCM:" + sampleRate);

                /* Let's read not more than 1/2 sec, to reduce the latency,
                 * also would be nice if frameSize will be an even number.
                 */
                final int frameSize = (sampleRate * (Short.SIZE / Byte.SIZE) / 2) & (Integer.MAX_VALUE - 1);
                return new AudioRecorder( sessionManager, audioRecord, audioFormat, frameSize, repeat );
            }
        }
        return null;
    }
}
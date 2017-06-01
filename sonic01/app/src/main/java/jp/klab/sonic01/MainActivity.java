/**
 *
 * sonic01
 *
 * 端末のマイクから音声を録音しスピーカーで再生する
 * Android 標準の AudioRecord, AudioTrack を使用
 *
 * サンプリング周波数 44.1kHz
 * 量子化ビット数 16
 * モノラル
 *
 */

package jp.klab.sonic01;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;

public class MainActivity extends AppCompatActivity
        implements Runnable, View.OnClickListener, Handler.Callback {
    private static final String TAG = "SNC";

    private static final int SAMPLE_RATE = 44100;
    private static final int BLOCK_NUMBER = 300;

    private static final int MSG_RECORD_START = 100;
    private static final int MSG_RECORD_END   = 110;
    private static final int MSG_PLAY_END     = 130;

    private Handler mHandler;
    private AudioRecord mAudioRecord = null;
    private AudioTrack mAudioTrack = null;

    private Button mButton01;
    private ProgressBar mProgressBar;

    private boolean mInRecording = false;
    private boolean mStop = false;
    private int mBufferSizeInShort;

    private short mPlayBuf[];
    private short mRecordBuf[];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mHandler = new Handler(this);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mButton01 = (Button)findViewById(R.id.button01);
        mButton01.setOnClickListener(this);
        mProgressBar = (ProgressBar)findViewById(R.id.progressBar);
        mProgressBar.setMax(BLOCK_NUMBER);
        mProgressBar.setProgress(0);

        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT);

        mBufferSizeInShort = bufferSizeInBytes / 2;
        // 録音用バッファ
        mRecordBuf = new short[mBufferSizeInShort];
        // 再生用バッファ
        mPlayBuf = new short[mBufferSizeInShort * BLOCK_NUMBER];

        // 録音用
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                        SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        bufferSizeInBytes);
        // 再生用
        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                                        SAMPLE_RATE,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        bufferSizeInBytes,
                                        AudioTrack.MODE_STREAM);
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mStop = true;
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
        }
        if (mAudioRecord != null) {
            if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
                Log.d(TAG, "cleanup mAudioRecord");
                mAudioRecord.stop();
            }
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                Log.d(TAG, "cleanup mAudioTrack");
                mAudioTrack.stop();
                mAudioTrack.flush();
            }
            mAudioTrack = null;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == (View)mButton01) {
            // 集音開始 or 終了
            if (!mInRecording) {
                mInRecording = true;
                new Thread(this).start();
            } else {
                mInRecording = false;
            }
        }
        return;
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RECORD_START:
                Log.d(TAG, "MSG_RECORD_START");
                mButton01.setText("STOP");
                break;
            case MSG_RECORD_END:
                Log.d(TAG, "MSG_RECORD_END");
                mButton01.setEnabled(false);
                break;
            case MSG_PLAY_END:
                Log.d(TAG, "MSG_PLAY_END");
                mButton01.setEnabled(true);
                mButton01.setText("START");
                break;
        }
        return true;
    }

    @Override
    public void run() {
        mHandler.sendEmptyMessage(MSG_RECORD_START);
        // 集音開始
        mAudioRecord.startRecording();
        int count = 0;
        while (mInRecording && !mStop) {
            mAudioRecord.read(mRecordBuf, 0, mBufferSizeInShort);
            // 再生用バッファはリングバッファとして扱う
            if (count * mBufferSizeInShort >= mPlayBuf.length) {
                count = 0;
                mProgressBar.setProgress(0);
            }
            // 再生用バッファへ集音したデータをアペンド
            System.arraycopy(mRecordBuf, 0, mPlayBuf, count * mBufferSizeInShort, mBufferSizeInShort);
            mProgressBar.setProgress(++count);
        }
        // 集音終了
        mAudioRecord.stop();
        mProgressBar.setProgress(0);
        mHandler.sendEmptyMessage(MSG_RECORD_END);
        if (mStop) {
            return;
        }
        // 再生
        mAudioTrack.setPlaybackRate(SAMPLE_RATE);
        mAudioTrack.play();
        mAudioTrack.write(mPlayBuf, 0, count * mBufferSizeInShort);
        mAudioTrack.stop();
        mAudioTrack.flush();
        mHandler.sendEmptyMessage(MSG_PLAY_END);
    }
}

/**
 *
 * sonic03
 *
 * サイン波信号を生成して鳴らす
 *
 * サンプリング周波数 44.1kHz
 * 量子化ビット数 16
 * モノラル
 *
 */

package jp.klab.sonic03;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.ToggleButton;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity
        implements Runnable, View.OnClickListener, Handler.Callback {
    private static final String TAG = "SNC";

    private static final int SAMPLE_RATE = 44100;
    private static final float SEC_PER_SAMPLEPOINT = 1.0f / SAMPLE_RATE;
    private static final int AMP = 4000;

    private static final int [] FREQS =
            new int[] {200, 400, 800, 1600, 2000, 4000, 8000, 10000, 12000, 14000};
    private static final int DO = 262 * 2;
    private static final int RE = 294 * 2;
    private static final int MI = 330 * 2;
    private static final int FA = 349 * 2;
    private static final int SO = 392 * 2;
    private static final int RA = 440 * 2;
    private static final int SI = 494 * 2;

    private static final int MSG_PLAY_START = 120;
    private static final int MSG_PLAY_END   = 130;

    private Handler mHandler;
    private AudioTrack mAudioTrack = null;
    private ToggleButton mButtons[];
    private int mIdxButtonPushed = -1;
    private int mBufferSizeInShort;
    private short mPlayBuf[];

    // 1秒分のサイン波データを生成
    private void createSineWave(int freq, int amplitude, boolean doClear) {
        if (doClear) {
            Arrays.fill(mPlayBuf, (short) 0);
        }
        for (int i = 0; i < SAMPLE_RATE; i++) {
            float currentSec = i * SEC_PER_SAMPLEPOINT; // 現在位置の経過秒数
            // y(t) = A * sin(2π * f * t)
            double val = amplitude * Math.sin(2.0 * Math.PI * freq * currentSec);
            mPlayBuf[i] += (short)val;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mHandler = new Handler(this);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mButtons = new ToggleButton[11];
        (mButtons[0] = (ToggleButton)findViewById(R.id.button01)).setOnClickListener(this);
        (mButtons[1] = (ToggleButton)findViewById(R.id.button02)).setOnClickListener(this);
        (mButtons[2] = (ToggleButton)findViewById(R.id.button03)).setOnClickListener(this);
        (mButtons[3] = (ToggleButton)findViewById(R.id.button04)).setOnClickListener(this);
        (mButtons[4] = (ToggleButton)findViewById(R.id.button05)).setOnClickListener(this);
        (mButtons[5] = (ToggleButton)findViewById(R.id.button06)).setOnClickListener(this);
        (mButtons[6] = (ToggleButton)findViewById(R.id.button07)).setOnClickListener(this);
        (mButtons[7] = (ToggleButton)findViewById(R.id.button08)).setOnClickListener(this);
        (mButtons[8] = (ToggleButton)findViewById(R.id.button09)).setOnClickListener(this);
        (mButtons[9] = (ToggleButton)findViewById(R.id.button10)).setOnClickListener(this);
        (mButtons[10] = (ToggleButton)findViewById(R.id.button11)).setOnClickListener(this);

        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT);
        // 再生用バッファ
        mPlayBuf = new short[SAMPLE_RATE]; // 1秒分のバッファを確保

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
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
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
        for (int i = 0; i < mButtons.length; i++) {
            if (mButtons[i] == (ToggleButton)v) {
                mIdxButtonPushed = i;
                break;
            }
        }
        if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
            mAudioTrack.stop();
            mAudioTrack.flush();
        }
        if (mButtons[mIdxButtonPushed].isChecked()) {
            new Thread(this).start();
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_PLAY_START:
                Log.d(TAG, "MSG_PLAY_START");
                break;
            case MSG_PLAY_END:
                Log.d(TAG, "MSG_PLAY_END");
                mButtons[mIdxButtonPushed].setChecked(false);
                break;
        }
        return true;
    }

    @Override
    public void run() {
        mHandler.sendEmptyMessage(MSG_PLAY_START);
        if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
            mAudioTrack.stop();
            mAudioTrack.flush();
        }
        mAudioTrack.play();
        if (mIdxButtonPushed != 10) {
            createSineWave(FREQS[mIdxButtonPushed], AMP, true);
            for (int i = 0; i < 5; i++) { // 5秒程度鳴らす
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE);
            }
        } else { // SONG
            for (int i = 0; i < 2; i++) {
                mAudioTrack.play();
                createSineWave(DO, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(RE, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(MI, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(FA, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(MI, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(RE, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(DO, AMP, true);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE);

                // 和音
                createSineWave(DO, AMP, true);
                createSineWave(MI, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(RE, AMP, true);
                createSineWave(FA, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(MI, AMP, true);
                createSineWave(SO, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(FA, AMP, true);
                createSineWave(RA, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(MI, AMP, true);
                createSineWave(SO, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(RE, AMP, true);
                createSineWave(FA, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE / 2);
                createSineWave(DO, AMP, true);
                createSineWave(MI, AMP, false);
                mAudioTrack.write(mPlayBuf, 0, SAMPLE_RATE);
            }
        }
        mAudioTrack.stop();
        mAudioTrack.flush();
        mHandler.sendEmptyMessage(MSG_PLAY_END);
    }
}

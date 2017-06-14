/**
 * sonic09
 *
 * 文字列をサイン波信号に置き換えて出力する
 * 32ビットCRC付与, 超音波モード追加
 * 対向の受信プログラムは sonic10
 *
 */

package jp.klab.sonic09;

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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.ToggleButton;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.zip.CRC32;

public class MainActivity extends AppCompatActivity
        implements Runnable, View.OnClickListener,
        Switch.OnCheckedChangeListener, Handler.Callback {
    private static final String TAG = "SNC";

    private static final int SAMPLE_RATE = 44100;
    private static final float SEC_PER_SAMPLEPOINT = 1.0f / SAMPLE_RATE;
    private static final int FREQ_BASE_LOW = 500;
    private static final int FREQ_BASE_HIGH = 14000;
    private static final int AMP_SMALL = 5000;
    private static final int AMP_LARGE = 28000;

    private static final int ELMS_1SEC = SAMPLE_RATE;
    private static final int ELMS_UNITSEC = SAMPLE_RATE/10;
    private static final int ELMS_MAX = 256;

    private static final int MSG_PLAY_START   = 120;
    private static final int MSG_PLAY_END     = 130;

    private int FREQ_STEP = 10;
    private int AMP;
    private int FREQ_BASE;
    private int FREQ_OUT;
    private int FREQ_IN;

    private Handler mHandler;
    private AudioTrack mAudioTrack = null;
    private ToggleButton mButton01;
    private EditText mEditText01;
    private Switch mSwitch01;

    private short mSigIn[] = new short[SAMPLE_RATE];
    private short mSigOut[] = new short[SAMPLE_RATE];
    private short mSignals[][] = new short[ELMS_MAX][ELMS_UNITSEC];
    private String mText;

    // サイン波データを生成
    private void createSineWave(short[] buf, int freq, int amplitude, boolean doClear) {
        if (doClear) {
            Arrays.fill(buf, (short) 0);
        }
        for (int i = 0; i < buf.length; i++) {
            float currentSec = i * SEC_PER_SAMPLEPOINT; // 現在位置の経過秒数
            double val = amplitude * Math.sin(2.0 * Math.PI * freq * currentSec);
            buf[i] += (short)val;
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
        mButton01 = (ToggleButton)findViewById(R.id.button01);
        mButton01.setOnClickListener(this);
        mEditText01 = (EditText)findViewById(R.id.editText01);
        mSwitch01 = (Switch)findViewById(R.id.switch01);
        mSwitch01.setOnCheckedChangeListener(this);

        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT);
        setParams(false);

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
        if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
            mAudioTrack.stop();
            mAudioTrack.flush();
        }
        if (mButton01.isChecked()) {
            mText = mEditText01.getText().toString();
            if (mText.length() > 0) {
                new Thread(this).start();
            } else {
                mButton01.setChecked(false);
            }
        }
    }
    @Override
    public void onCheckedChanged(CompoundButton b, boolean isChecked) {
        setParams(isChecked);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_PLAY_START:
                Log.d(TAG, "MSG_PLAY_START");
                break;
            case MSG_PLAY_END:
                Log.d(TAG, "MSG_PLAY_END");
                mButton01.setChecked(false);
                break;
        }
        return true;
    }

    @Override
    public void run() {
        mHandler.sendEmptyMessage(MSG_PLAY_START);
        byte[] strByte = null;
        try {
            strByte = mText.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
        }
        mAudioTrack.play();
        mAudioTrack.write(mSigIn, 0, ELMS_1SEC); // 先端符丁
        // データ本体の CRC32 を計算して発信
        CRC32 crc = new CRC32();
        crc.reset();
        crc.update(strByte, 0, strByte.length);
        long crcVal = crc.getValue();
        //Log.d(TAG, "crc=" + Long.toHexString(crcVal));
        byte crcData[] = new byte[4];
        for (int i = 0; i < 4; i++) {
            crcData[i] = (byte)(crcVal >> (24-i*8));
            valueToWave((short) (crcData[i] & 0xFF));
        }
        // データ本体
        for (int i = 0; i < strByte.length; i++) {
            valueToWave(strByte[i]);
        }
        mAudioTrack.write(mSigOut, 0, ELMS_1SEC); // 終端符丁

        mAudioTrack.stop();
        mAudioTrack.flush();
        mHandler.sendEmptyMessage(MSG_PLAY_END);
    }

    // 指定されたバイト値を音声信号に置き換えて再生する
    private void valueToWave(short val) {
        //Log.d(TAG, "val=" + val);
        mAudioTrack.write(mSignals[val], 0, ELMS_UNITSEC);
    }

    private void setParams(boolean useUltrasonic) {
        AMP = (useUltrasonic) ? AMP_LARGE : AMP_SMALL;
        FREQ_BASE = (useUltrasonic) ? FREQ_BASE_HIGH : FREQ_BASE_LOW;
        FREQ_OUT = FREQ_BASE - 100;
        FREQ_IN = FREQ_OUT + 20;
        // 先端・終端符丁の信号データを生成
        createSineWave(mSigIn, FREQ_IN, AMP, true);
        createSineWave(mSigOut, FREQ_OUT, AMP, true);
        // 256種類の信号データを生成
        for (int i = 0; i < ELMS_MAX; i++) {
            createSineWave(mSignals[i], (short) (FREQ_BASE + FREQ_STEP * i), AMP, true);
        }

    }
}

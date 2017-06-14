/**
 * sonic08
 *
 * 端末のマイクから集音した信号をバイトデータに変換する
 * 伝送効率改善版
 * FFT 処理に JTransforms ライブラリを利用
 * 対向の送信プログラムは sonic07
 *
 */

package jp.klab.sonic08;

import org.jtransforms.fft.DoubleFFT_1D;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity
        implements Runnable, View.OnClickListener, Handler.Callback {
    private static final String TAG = "SNC";

    private static final int SAMPLE_RATE = 44100;
    private static final short THRESHOLD_SILENCE = 0x00ff;
    private static final int FREQ_BASE = 400;
    private static final int FREQ_STEP = 20;
    private static final int FREQ_MAX = 400 + 255 * 20;
    private static final int UNITSIZE = SAMPLE_RATE/10; // 100msec分

    private static final int MSG_RECORD_START = 100;
    private static final int MSG_RECORD_END   = 110;
    private static final int MSG_DATA_RECV    = 120;

    private Handler mHandler;
    private AudioRecord mAudioRecord = null;

    private Button mButton01;
    private TextView mTextView02;

    private boolean mInRecording = false;
    private boolean mStop = false;
    private int mBufferSizeInShort;

    private short mRecordBuf[];
    private short mTestBuf[];
    private DoubleFFT_1D mFFT;
    private double mFFTBuffer[];
    private int mFFTSize;

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
        mTextView02 = (TextView)findViewById(R.id.textView02);
        mTextView02.setOnClickListener(this);

        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT);

        mBufferSizeInShort = bufferSizeInBytes / 2;
        // 集音用バッファ
        mRecordBuf = new short[mBufferSizeInShort];

        // FFT 処理用
        mTestBuf =  new short[UNITSIZE];
        mFFTSize = UNITSIZE;
        mFFT = new DoubleFFT_1D(mFFTSize);
        mFFTBuffer = new double[mFFTSize];

        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                        SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        bufferSizeInBytes);
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
        } else if (v == (View)mTextView02) {
            // 表示データをクリア
            mTextView02.setText("");
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
                mButton01.setText("START");
                break;
            case MSG_DATA_RECV:
                //Log.d(TAG, "MSG_DATA_RECV");
                byte[] ch = new byte[] {(byte)msg.arg1};
                try {
                    // 受信データを表示
                    String s = new String(ch, "UTF-8");
                    s = mTextView02.getText() + s;
                    mTextView02.setText(s);
                } catch (UnsupportedEncodingException e) {
                }
                break;
        }
        return true;
    }

    @Override
    public void run() {
        int dataCount = 0;
        boolean bSilence = false;
        mHandler.sendEmptyMessage(MSG_RECORD_START);
        // 集音開始
        mAudioRecord.startRecording();
        while (mInRecording && !mStop) {
            // 音声データ読み込み
            mAudioRecord.read(mRecordBuf, 0, mBufferSizeInShort);
            bSilence = true;
            for (int i = 0; i < mBufferSizeInShort; i++) {
                short s = mRecordBuf[i];
                if (s > THRESHOLD_SILENCE) {
                    bSilence = false;
                }
            }
            if (bSilence) { // 静寂
                dataCount = 0;
                continue;
            }
            int copyLength = 0;
            // データを mTestBuf へ順次アペンド
            if (dataCount < mTestBuf.length) {
                // mTestBuf の残領域に応じてコピーするサイズを決定
                int remain = mTestBuf.length - dataCount;
                if (remain > mBufferSizeInShort) {
                    copyLength = mBufferSizeInShort;
                } else {
                    copyLength = remain;
                }
                System.arraycopy(mRecordBuf, 0, mTestBuf, dataCount, copyLength);
                dataCount += copyLength;
            }
            if (dataCount >= mTestBuf.length) {
                // 100ms 分溜まったら FFT にかける
                int freq = doFFT(mTestBuf);
                // 待ってた範囲の周波数かチェック
                if (freq >= FREQ_BASE && freq <= FREQ_MAX) {
                    int val = (int) ((freq - FREQ_BASE) / FREQ_STEP);
                    if (val >= 0 && val <= 255) {
                        Message msg = new Message();
                        msg.what = MSG_DATA_RECV;
                        msg.arg1 = val;
                        mHandler.sendMessage(msg);
                    } else {
                        freq = -1;
                    }
                } else {
                    freq = -1;
                }
                dataCount = 0;
                if (freq == -1) {
                    continue;
                }
                // mRecordBuf の途中までを mTestBuf へコピーして FFT した場合は
                // mRecordBuf の残データを mTestBuf 先頭へコピーした上で継続
                if (copyLength < mBufferSizeInShort) {
                    int startPos = copyLength;
                    copyLength = mBufferSizeInShort - copyLength;
                    System.arraycopy(mRecordBuf, startPos, mTestBuf, 0, copyLength);
                    dataCount += copyLength;
                }
            }
        }
        // 集音終了
        mAudioRecord.stop();
        mHandler.sendEmptyMessage(MSG_RECORD_END);
    }

    private int doFFT(short[] data) {
        for (int i = 0; i < mFFTSize; i++) {
            mFFTBuffer[i] = (double)data[i];
        }
        // FFT 実行
        mFFT.realForward(mFFTBuffer);

        // 処理結果の複素数配列からピーク周波数成分の要素番号を得る
        double maxAmp = 0;
        int index = 0;
        for (int i = 0; i < mFFTSize/2; i++) {
            double a = mFFTBuffer[i*2]; // 実部
            double b = mFFTBuffer[i*2 + 1]; // 虚部
            // a+ib の絶対値 √ a^2 + b^2 = r が振幅値
            double r = Math.sqrt(a*a + b*b);
            if (r > maxAmp) {
                maxAmp = r;
                index = i;
            }
        }
        // ピーク周波数を求める
        int freq = index * SAMPLE_RATE / mFFTSize;
        //Log.d(TAG, "peak=" + freq + "Hz");
        return freq;
    }
}

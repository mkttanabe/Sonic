/**
 *
 * sonic11
 *
 * サイン波音声信号による半二重式での双方向通信のしくみの試作
 * 二台の機器間で「しりとり」を行う
 * FFT 処理に JTransforms ライブラリを利用
 *
 */

package jp.klab.sonic11;

import org.jtransforms.fft.DoubleFFT_1D;
import android.graphics.Color;
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
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CRC32;

public class MainActivity extends AppCompatActivity
        implements Runnable, View.OnClickListener,
            Switch.OnCheckedChangeListener, Handler.Callback {
    private static final String TAG = "SNC";

    private static final int SAMPLE_RATE = 44100;
    private static final float SEC_PER_SAMPLEPOINT = 1.0f / SAMPLE_RATE;
    private static final short THRESHOLD_SILENCE = 0x00ff;
    private static final int THRESHOLD_COUNT_SILENCE = 25; // 無音区間判定用
    private static final int FREQ_BASE_LOW = 500;
    private static final int FREQ_BASE_HIGH = 14000;
    private static final int AMP_SMALL = 28000;
    private static final int AMP_LARGE = 28000;

    private static final int ELMS_1SEC = SAMPLE_RATE;
    private static final int ELMS_UNITSEC = SAMPLE_RATE/10;
    private static final int ELMS_MAX = 256;
    private static final int FREQ_STEP = 10;
    private static final int UNITSIZE = SAMPLE_RATE/10; // 100msec分

    private static final int MSG_RECORD_START    = 100;
    private static final int MSG_RECORD_END      = 110;
    private static final int MSG_DATA_RECV       = 130;
    private static final int MSG_DATA_SEND_START = 140;
    private static final int MSG_RECV_OK         = 200;
    private static final int MSG_RECV_NG         = 210;

    private int AMP;
    private int FREQ_BASE;
    private int FREQ_OUT;
    private int FREQ_IN;
    private int FREQ_MAX = FREQ_BASE + FREQ_STEP * 255;

    private Handler mHandler;
    private AudioRecord mAudioRecord = null;
    private AudioTrack mAudioTrack = null;

    private Button mButton01;
    private TextView mTextView01;
    private TextView mTextView02;
    private TextView mTextView03;
    private TextView mTextView04;
    private Switch mSwitch01;

    private short mSigIn[] = new short[SAMPLE_RATE];
    private short mSigOut[] = new short[SAMPLE_RATE];
    private short mSignals[][] = new short[ELMS_MAX][SAMPLE_RATE/10];

    private boolean mInRecording = false;
    private boolean mStop = false;
    private int mBufferSizeInShort;

    private short mRecordBuf[];
    private short mTestBuf[];
    private DoubleFFT_1D mFFT;
    private double mFFTBuffer[];
    private int mFFTSize;
    private byte mValueCount = -1;
    private long mCrc32Val = 0;
    private ArrayList<Byte> mDataArrayList = new ArrayList<Byte>();
    private int mLastFreq;
    private String mRecvWord = "";
    private String mSendWord = "";
    private boolean mRecvOK;

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

        mButton01 = (Button)findViewById(R.id.button01);
        mButton01.setOnClickListener(this);
        mTextView01 = (TextView)findViewById(R.id.textView01);
        mTextView01.setOnClickListener(this);
        mTextView02 = (TextView)findViewById(R.id.textView02);
        mTextView02.setOnClickListener(this);
        mTextView03 = (TextView)findViewById(R.id.textView03);
        mTextView03.setTextColor(Color.RED);
        mTextView04 = (TextView)findViewById(R.id.textView04);
        mSwitch01 = (Switch)findViewById(R.id.switch01);
        mSwitch01.setOnCheckedChangeListener(this);

        int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT);

        mBufferSizeInShort = bufferSizeInBytes / 2;
        // 集音用バッファ
        mRecordBuf = new short[mBufferSizeInShort];

        // FFT 処理用
        mTestBuf = new short[UNITSIZE];
        mFFTSize = UNITSIZE;
        mFFT = new DoubleFFT_1D(mFFTSize);
        mFFTBuffer = new double[mFFTSize];

        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                        SAMPLE_RATE,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        bufferSizeInBytes);

        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes,
                AudioTrack.MODE_STREAM);

        setParams(false);
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
        } else if (v == (View)mTextView01) {
            // しりとりの開始・再開
            // すでに集音中なら自分で自分の音を拾わないように
            // スレッドを一旦終了
            boolean running = mInRecording;
            if (mInRecording) {
                mStop = true;
                try {
                    while (mInRecording) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                }
            }
            // ランダムに選んだ英単語を音で発信
            mSendWord = words.getWord();
            doSendWord(mSendWord);
            if (running) {
                // 集音スレッド再稼働
                mInRecording = true;
                mStop = false;
                new Thread(this).start();
            }
        } else if (v == (View)mTextView02) {
            mTextView02.setText("");
            mTextView03.setText("");
        }
        return;
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean isChecked) {
        if (b == (CompoundButton)mSwitch01) {
            setParams(isChecked);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RECORD_START:
                Log.d(TAG, "MSG_RECORD_START");
                mButton01.setText("STOP");
                mSwitch01.setEnabled(false);
                break;
            case MSG_RECORD_END:
                Log.d(TAG, "MSG_RECORD_END");
                mButton01.setText("START");
                mSwitch01.setEnabled(true);
                break;
            case MSG_DATA_RECV:
                //Log.d(TAG, "MSG_DATA_RECV");
                if (mRecvWord.length() <= 0) {
                    mTextView02.setText("RECV: ");
                }
                byte[] ch = new byte[] {(byte)msg.arg1};
                try {
                    // 受信データを表示
                    String s = new String(ch, "UTF-8");
                    mRecvWord += s;
                    s = mTextView02.getText() + s;
                    mTextView02.setText(s);
                } catch (UnsupportedEncodingException e) {
                }
                mTextView03.setText("");
                break;
            case MSG_DATA_SEND_START:
                mTextView04.setText("SEND: " + mSendWord);
                break;
            case MSG_RECV_OK:
                mTextView03.setText("OK!");
                break;
            case MSG_RECV_NG:
                mTextView03.setText("NG!");
                break;
        }
        return true;
    }

    @Override
    public void run() {
        int dataCount = 0;
        boolean bSilence = false;
        long countSilence = 0;
        mHandler.sendEmptyMessage(MSG_RECORD_START);
        // 集音開始
        mAudioRecord.startRecording();
        while (mInRecording && !mStop) {
            // 音声信号読み込み
            mAudioRecord.read(mRecordBuf, 0, mBufferSizeInShort);
            bSilence = true;
            for (int i = 0; i < mBufferSizeInShort; i++) {
                short s = mRecordBuf[i];
                if (s > THRESHOLD_SILENCE) {
                    bSilence = false;
                    break;
                }
            }
            if (bSilence) { // 静寂
                dataCount = 0;
                countSilence++;
            } else {
                countSilence = 0;
            }
            // 単語を受信ずみかつ一定期間以上静寂が続いたら送信にまわる
            if (countSilence > THRESHOLD_COUNT_SILENCE &&
                    mLastFreq == FREQ_OUT && mRecvWord.length() > 0) {
                if (mRecvOK) { // 直前の受信・復号結果が正常だった
                    if (!mRecvWord.equals("?")) {
                        // 受信した単語のしりとり語を取得
                        mSendWord = words.getWord(mRecvWord);
                    }
                } else {
                    // 再送要求
                    mSendWord = "?";
                }
                Log.d(TAG, "mSendWord=" + mSendWord);
                // 単語を発信
                doSendWord(mSendWord);
                continue;
            }

            int copyLength = 0;
            // データを mTestBuf へ順次アペンド
            if (dataCount < UNITSIZE) {
                // mTestBuf の残領域に応じてコピーするサイズを決定
                int remain = UNITSIZE - dataCount;
                if (remain > mBufferSizeInShort) {
                    copyLength = mBufferSizeInShort;
                } else {
                    copyLength = remain;
                }
                System.arraycopy(mRecordBuf, 0, mTestBuf, dataCount, copyLength);
                dataCount += copyLength;
            }
            if (dataCount >= UNITSIZE) {
                // 100ms 分溜まったら FFT にかける
                int freq = doFFT(mTestBuf);
                mLastFreq = freq;
                if (mValueCount < 0) {
                    // データ終了
                    if (freq == FREQ_OUT && mCrc32Val != 0) {
                        byte check [] = new byte[mDataArrayList.size()];
                        for (int i = 0; i < check.length; i++) {
                            check[i] = mDataArrayList.get(i);
                        }
                        //Log.d(TAG, "mRecvWord=" + mRecvWord);
                        CRC32 crc = new CRC32();
                        crc.reset();
                        crc.update(check, 0, check.length);
                        long crcVal = crc.getValue();
                        //Log.d(TAG, "crc check=" +  Long.toHexString(crcVal));
                        mRecvOK = false;
                        if (crcVal == mCrc32Val) {
                            mRecvOK = true;
                            mHandler.sendEmptyMessage(MSG_RECV_OK);
                        } else {
                            mHandler.sendEmptyMessage(MSG_RECV_NG);
                        }
                        mCrc32Val = 0;
                    }
                }
                // 待ってた範囲の周波数かチェック
                if (freq >= FREQ_BASE && freq <= FREQ_MAX) {
                    int val = (int) ((freq - FREQ_BASE) / FREQ_STEP);
                    if (val >= 0 && val <= 255) {
                        if (mValueCount > 4) {
                            mDataArrayList.add((byte)val);
                            Message msg = new Message();
                            msg.what = MSG_DATA_RECV;
                            msg.arg1 = val;
                            mHandler.sendMessage(msg);
                        }
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
        mInRecording = false;
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
        byte val = (byte)((freq-FREQ_BASE)/FREQ_STEP);

        if (freq == FREQ_IN) { // 先端符丁
            mValueCount = 0;
            mCrc32Val = 0;
            if (!mDataArrayList.isEmpty()) {
                mDataArrayList.clear();
            }
            mRecvWord = "";
            return freq;
        } else if (freq == FREQ_OUT) { // 終端符丁
            mValueCount = -1;
            return freq;
        }

        // 先端符丁直後の 4バイトは32ビットCRC
        if (mValueCount >= 0 &&  mValueCount < 4) {
            mCrc32Val |=  (val & 0xFF);
            if (mValueCount != 3) {
                mCrc32Val <<= 8;
            } else {
                Log.d(TAG, "mCrc32Val=" + Long.toHexString(mCrc32Val));
            }
        }
        mValueCount++;
        return freq;
    }

    // 指定されたバイト値を音声信号に置き換えて再生する
    private void valueToWave(short val) {
        //Log.d(TAG, "val=" + val);
        mAudioTrack.write(mSignals[val], 0, ELMS_UNITSEC);
    }

    private void doSendWord(String str) {
        mSendWord = str;
        mHandler.sendEmptyMessage(MSG_DATA_SEND_START);
        Log.d(TAG, "mSendWord=" + mSendWord);
        byte[] strByte = null;
        try {
            strByte = mSendWord.getBytes("UTF-8");
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
        mLastFreq = -1;
        mRecvWord = "";
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
        FREQ_MAX = FREQ_BASE + FREQ_STEP * 255;
    }
}

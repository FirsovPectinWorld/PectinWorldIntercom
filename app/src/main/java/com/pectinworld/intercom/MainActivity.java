package com.pectinworld.intercom;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;

import android.media.MediaFormat;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;

import android.media.MediaCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;

import android.media.audiofx.AcousticEchoCanceler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private LinearLayout loginBlock, intercomBlock;
    private EditText passwordInput;
    private Button loginButton, btnContactOne, btnContactTwo, btnGeneralCall;
    private TextView welcomeText;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "PectinWorldPrefs";
    private static final String KEY_USER_ROLE = "UserRole";

    private static final String PASS_VLADIMIR = "v";
    private static final String PASS_GALINA = "g";
    private static final String PASS_SERGEY = "s";

    private static final String SERVER_HOST = "90.171.130.20";
    private static final int SERVER_PORT = 64738;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private volatile boolean isSfxRunning = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AcousticEchoCanceler echoCanceler;

    private MediaCodec opusEncoder;
    private MediaCodec opusDecoder;
    private int mySession = -1;

    private boolean audioStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }

        loginBlock = findViewById(R.id.loginBlock);
        intercomBlock = findViewById(R.id.intercomBlock);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);
        welcomeText = findViewById(R.id.welcomeText);
        btnContactOne = findViewById(R.id.btnContactOne);
        btnContactTwo = findViewById(R.id.btnContactTwo);
        btnGeneralCall = findViewById(R.id.btnGeneralCall);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedRole = prefs.getString(KEY_USER_ROLE, null);

        if (savedRole != null) {
            setupIntercomUI(savedRole);
        } else {
            loginButton.setOnClickListener(v -> handleLogin());
        }
    }

    private void handleLogin() {
        String enteredPassword = passwordInput.getText().toString().trim();
        String detectedRole = null;

        if (enteredPassword.equals(PASS_VLADIMIR)) detectedRole = "Владимир";
        else if (enteredPassword.equals(PASS_GALINA)) detectedRole = "Галина";
        else if (enteredPassword.equals(PASS_SERGEY)) detectedRole = "Сергей";

        if (detectedRole != null) {
            prefs.edit().putString(KEY_USER_ROLE, detectedRole).apply();
            setupIntercomUI(detectedRole);
        } else {
            Toast.makeText(this, "Неверный пароль доступа!", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupIntercomUI(String role) {
        loginBlock.setVisibility(View.GONE);
        intercomBlock.setVisibility(View.VISIBLE);
        welcomeText.setText("Привет, " + role + "!");

        if (role.equals("Владимир")) {
            btnContactOne.setText("📞 Вызвать Галину");
            btnContactTwo.setText("📞 Вызвать Сергея");
        } else if (role.equals("Галина")) {
            btnContactOne.setText("📞 Вызвать Владимира");
            btnContactTwo.setText("📞 Вызвать Сергея");
        } else if (role.equals("Сергей")) {
            btnContactOne.setText("📞 Вызвать Владимира");
            btnContactTwo.setText("📞 Вызвать Галину");
        }

        btnContactOne.setOnClickListener(v -> startVoiceCommunication("Room_One"));
        btnContactTwo.setOnClickListener(v -> startVoiceCommunication("Room_Two"));
        btnGeneralCall.setOnClickListener(v -> startVoiceCommunication("Root"));
    }

    private void startVoiceCommunication(String targetRoom) {
        if (isSfxRunning) {
            stopVoiceCommunication();
            Toast.makeText(this, "Связь отключена", Toast.LENGTH_SHORT).show();
            return;
        }

        isSfxRunning = true;
        Toast.makeText(this, "Подключение к Интеркому...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            SSLSocket tcpSocket = null;
            DataOutputStream dos = null;
            DataInputStream dis = null;

            try {
                // SSL/TLS соединение
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                tcpSocket = (SSLSocket) sc.getSocketFactory().createSocket(SERVER_HOST, SERVER_PORT);
                tcpSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                tcpSocket.startHandshake();

                dos = new DataOutputStream(tcpSocket.getOutputStream());
                dis = new DataInputStream(tcpSocket.getInputStream());

                // Отправка Version (Тип 0)
                ByteArrayOutputStream vOs = new ByteArrayOutputStream();
                vOs.write(0x08);
                writeVarIntStream(vOs, 66047);
                vOs.write(0x28);
                writeVarIntLongStream(vOs, 281496451547136L);
                vOs.write(0x12);
                byte[] relBytes = "1.5.0".getBytes("UTF-8");
                writeVarIntStream(vOs, relBytes.length);
                vOs.write(relBytes);
                vOs.write(0x1A);
                byte[] osBytes = "Win32".getBytes("UTF-8");
                writeVarIntStream(vOs, osBytes.length);
                vOs.write(osBytes);
                vOs.write(0x22);
                byte[] osv = "Android".getBytes("UTF-8");
                writeVarIntStream(vOs, osv.length);
                vOs.write(osv);
                byte[] verBody = vOs.toByteArray();
                dos.writeShort(0);
                dos.writeInt(verBody.length);
                dos.write(verBody);
                dos.flush();

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT);

// 1. Меняем MIC на VOICE_COMMUNICATION для принудительной активации VoIP-движка Android
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        AUDIO_FORMAT,
                        minBufSize
                );

                // БЫЛО:
// audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, ...);

// СТАЛО:
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL, // Переключаем на телефонный поток
                        SAMPLE_RATE,
                        CHANNEL_OUT,
                        AUDIO_FORMAT,
                        minBufSize,
                        AudioTrack.MODE_STREAM
                );

// 2. Красивая, чистая инициализация эхоподавителя без дублирования
                if (audioRecord != null && AcousticEchoCanceler.isAvailable()) {
                    // Освобождаем старый, если он вдруг был инициализирован ранее
                    if (echoCanceler != null) {
                        try { echoCanceler.release(); } catch (Exception e) {}
                    }

                    // Создаем эхоподавитель, привязанный к сессии нашего микрофона
                    echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());

                    if (echoCanceler != null) {
                        echoCanceler.setEnabled(true);
                        Log.d("AUDIO2", "Аппаратное эхоподавление (AEC) УСПЕШНО ВКЛЮЧЕНО. Экземпляр: " + echoCanceler.getEnabled());
                    } else {
                        Log.e("AUDIO2", "Не удалось создать экземпляр AcousticEchoCanceler, хотя система заявила о поддержке");
                    }
                } else {
                    Log.w("AUDIO2", "Аппаратное эхоподавление (AEC) НЕ поддерживается устройством или audioRecord == null");
                }

                audioStarted = false;

                // Основной TCP цикл
                while (isSfxRunning) {
                    int msgType = dis.readUnsignedShort();
                    int msgLen = dis.readInt();

                    byte[] msgBody = new byte[msgLen];
                    dis.readFully(msgBody);

                    Log.d("AUDIO3", "Пришел пакет: " + msgType + " (длина: " + msgLen + ")");

                    if (msgType == 0) { // Version reply -> Отправляем валидный Authenticate
                        String role = prefs.getString(KEY_USER_ROLE, "Unknown");
                        String username;
                        switch (role) {
                            case "Владимир": username = "Vladimir"; break;
                            case "Галина": username = "Galina"; break;
                            case "Сергей": username = "Sergey"; break;
                            default: username = "Unknown";
                        }
                        String serverPassword = "PectinWorldIntercom1970";
                        ByteArrayOutputStream aOs = new ByteArrayOutputStream();

                        // Поле 1: name (тег 1, wire type 2 -> 0x0A)
                        aOs.write(0x0A);
                        byte[] uBytes = username.getBytes("UTF-8");
                        writeVarIntStream(aOs, uBytes.length);
                        aOs.write(uBytes);

                        // Поле 2: password (тег 2, wire type 2 -> 0x12)
                        aOs.write(0x12);
                        byte[] pBytes = serverPassword.getBytes("UTF-8");
                        writeVarIntStream(aOs, pBytes.length);
                        aOs.write(pBytes);

                        // Поле 3: tokens (тег 3, wire type 2 -> 0x1A) - Передаем пароль как токен для обхода ACL
                        aOs.write(0x1A);
                        byte[] tBytes = serverPassword.getBytes("UTF-8");
                        writeVarIntStream(aOs, tBytes.length);
                        aOs.write(tBytes);

                        byte[] authBody = aOs.toByteArray();

                        synchronized (dos) {
                            dos.writeShort(2); // Authenticate
                            dos.writeInt(authBody.length);
                            dos.write(authBody);
                            dos.flush();

                            dos.writeShort(3); // Ping
                            dos.writeInt(0);
                            dos.flush();
                        }
                        Log.d("AUDIO2", "Валидный пакет Authenticate с токеном доступа отправлен.");
                    }
                    else if (msgType == 13) { // Ping reply
                        synchronized (dos) {
                            dos.writeShort(13);
                            dos.writeInt(msgLen);
                            dos.write(msgBody);
                            dos.flush();
                        }
                    }
                    else if (msgType == 23) { // SuggestConfig
                        Log.d("AUDIO2", "Получен пакет SuggestConfig (Тип 23).");
                    }
                    else if (msgType == 15) { // CryptSetup
                        Log.d("AUDIO", "CryptSetup received, sending minimal response");
                        byte[] keyReply = new byte[32];
                        Arrays.fill(keyReply, (byte) 0);
                        synchronized (dos) {
                            dos.writeShort(15);
                            dos.writeInt(keyReply.length);
                            dos.write(keyReply);
                            dos.flush();
                        }
                    }
                    else if (msgType == 1) { // UDPTunnel
                        handleIncomingAudio(msgBody);
                    }
                    else if (msgType == 5 && !audioStarted) { // ServerSync
                        try {
                            if (msgBody.length > 1 && msgBody[0] == 0x08) {
                                int[] off = {1};
                                mySession = readVarIntFromBytes(msgBody, off);
                                Log.d("AUDIO2", "=== ServerSync === Сессия успешно определена: " + mySession);
                            }
                        } catch (Exception e) {
                            Log.e("AUDIO2", "Ошибка разбора сессии", e);
                        }

                        audioStarted = true;
                        initOpusCodecs();
                        audioRecord.startRecording();

                        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (audioManager != null) {
                            // Устанавливаем режим "Внутри звонка". Это принудительно переключит аудио-тракт в телефонный режим
                            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        }

                        audioTrack.play();

                        final DataOutputStream finalDos = dos;
                        final String finalTargetRoom = targetRoom;
                        final int finalSession = mySession;

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(250); // Даем серверу время завершить синхронизацию

                                    int targetChannelId = finalTargetRoom.equals("Root") ? 0 : (finalTargetRoom.equals("Room_One") ? 1 : 2);
                                    Log.d("AUDIO2", "Отправка UserState (Сессия: " + finalSession + ") в канал: " + targetChannelId);

                                    ByteArrayOutputStream joinOs = new ByteArrayOutputStream();

                                    // === ПОЛЕ 1: session (тег 1, wire type 0 -> 0x08) ===
                                    if (finalSession != -1) {
                                        joinOs.write(0x08);
                                        int sessVal = finalSession;
                                        while ((sessVal & 0xFFFFFF80) != 0L) {
                                            joinOs.write((sessVal & 0x7F) | 0x80);
                                            sessVal >>>= 7;
                                        }
                                        joinOs.write(sessVal & 0x7F);
                                    }

                                    // === ПОЛЕ 5: channel_id (тег 5, wire type 0 -> 0x28) ===
                                    joinOs.write(0x28);
                                    int chanVal = targetChannelId;
                                    while ((chanVal & 0xFFFFFF80) != 0L) {
                                        joinOs.write((chanVal & 0x7F) | 0x80);
                                        chanVal >>>= 7;
                                    }
                                    joinOs.write(chanVal & 0x7F);

                                    byte[] joinBody = joinOs.toByteArray();

                                    synchronized (finalDos) {
                                        finalDos.writeShort(9); // Type 9: UserState
                                        finalDos.writeInt(joinBody.length);
                                        finalDos.write(joinBody);
                                        finalDos.flush();
                                    }
                                    Log.d("AUDIO2", "[Успех] Валидный UserState отправлен. Длина: " + joinBody.length);

                                    startAudioSendingLoop(finalDos, finalTargetRoom);

                                } catch (Exception e) {
                                    Log.e("AUDIO2", "Ошибка отправки UserState", e);
                                }
                            }
                        }).start();
                    }
                }

            } catch (Exception e) {
                Log.e("AUDIO2", "Error in voice thread", e);
            } finally {
                stopVoiceCommunication();
                try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}
            }
        }).start();
    }

    private void stopAudio() {
        try {
            Log.d("AUDIO2", "Вызван метод stopAudio(). Начало освобождения ресурсов...");

            // 1. Очищаем эхоподавитель в самом начале остановки
            if (echoCanceler != null) {
                try {
                    echoCanceler.setEnabled(false);
                    echoCanceler.release();
                } catch (Exception e) {
                    Log.e("AUDIO2", "Ошибка при освобождении echoCanceler: " + e.getMessage());
                }
                echoCanceler = null;
                Log.d("AUDIO2", "AcousticEchoCanceler успешно освобожден");
            }

            // 2. Останавливаем и освобождаем микрофон (AudioRecord)
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                    audioRecord.release();
                } catch (Exception e) {
                    Log.e("AUDIO2", "Ошибка при остановке audioRecord: " + e.getMessage());
                }
                audioRecord = null;
                Log.d("AUDIO2", "AudioRecord успешно остановлен и сброшен");
            }

            // 3. Останавливаем и освобождаем динамик (AudioTrack)
            if (audioTrack != null) {
                try {
                    if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop();
                    }
                    audioTrack.release();
                } catch (Exception e) {
                    Log.e("AUDIO2", "Ошибка при остановке audioTrack: " + e.getMessage());
                }
                audioTrack = null;
                Log.d("AUDIO2", "AudioTrack успешно остановлен и сброшен");
            }

            // 4. Корректно закрываем Медиа-Кодеки Opus
            // Освобождаем Энкодер (Передача)
            if (opusEncoder != null) {
                try {
                    opusEncoder.stop();
                    opusEncoder.release();
                } catch (Exception e) {
                    Log.e("AUDIO2", "Ошибка при остановке opusEncoder: " + e.getMessage());
                }
                opusEncoder = null;
                Log.d("AUDIO2", "opusEncoder успешно закрыт");
            }

            // Освобождаем Декодер (Прием)
            if (opusDecoder != null) {
                try {
                    opusDecoder.stop();
                    opusDecoder.release();
                } catch (Exception e) {
                    Log.e("AUDIO2", "Ошибка при остановке opusDecoder: " + e.getMessage());
                }
                opusDecoder = null;
                Log.d("AUDIO2", "opusDecoder успешно закрыт");
            }

            // Сбрасываем флаг активности звукового движка
            audioStarted = false;
            Log.d("AUDIO2", "Все аудио-ресурсы интеркома успешно зачищены.");

            // Добавить в конец метода stopAudio() внутри блока try:
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL); // Возвращаем стандартный режим Android
            }

        } catch (Exception e) {
            Log.e("AUDIO2", "Критическая ошибка в общем блоке stopAudio: " + e.getMessage(), e);
        }
    }

    private void writeProtobufVarInt(ByteArrayOutputStream os, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                os.write(value);
                return;
            } else {
                os.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    private int diagnosticCounter = 0;

    private void handleIncomingAudio(byte[] msgBody) {
        try {
            diagnosticCounter++;
            boolean logThis = (diagnosticCounter % 100 == 0);

            int[] off = {0};
            int header = msgBody[off[0]++] & 0xFF;

            int session = readMumbleVarIntFromBytes(msgBody, off);
            int seq = readMumbleVarIntFromBytes(msgBody, off);
            int opusLen = readMumbleVarIntFromBytes(msgBody, off);

            if (opusDecoder == null) return;

            if (opusLen <= 0 || off[0] + opusLen > msgBody.length) {
                return;
            }

            byte[] opusFrame = new byte[opusLen];
            System.arraycopy(msgBody, off[0], opusFrame, 0, opusLen);

            // 1. Подаем фрейм в декодер
            int inputBufferIndex = opusDecoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = opusDecoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(opusFrame);
                opusDecoder.queueInputBuffer(inputBufferIndex, 0, opusFrame.length, 0, 0);
            }

            // 2. Достаем раскодированный PCM с обработкой системных статусов Android
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = opusDecoder.dequeueOutputBuffer(bufferInfo, 10000);

            if (logThis) {
                Log.d("AUDIO_TRACK", "Первичный статус dequeueOutputBuffer: " + outputBufferIndex);
            }

            // Обрабатываем мета-статусы, если они пришли вместо индекса буфера
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = opusDecoder.getOutputFormat();
                if (logThis) {
                    Log.d("AUDIO_TRACK", "Декодер Opus изменил формат вывода: " + newFormat);
                }
            }

            // Запускаем цикл разбора (обрабатываем как текущий буфер, так и последующие)
            while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = opusDecoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] pcmFrame = new byte[bufferInfo.size];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        outputBuffer.get(pcmFrame);

                        if (audioTrack != null) {
                            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                Log.w("AUDIO_TRACK", "Принудительный старт AudioTrack во время воспроизведения");
                                audioTrack.play();
                            }

                            // Запись звука напрямую в динамик Android
                            int written = audioTrack.write(pcmFrame, 0, pcmFrame.length);
                            if (logThis) {
                                Log.d("AUDIO_TRACK", "==> ЗВУК В ДИНАМИКЕ! Выведено байт: " + written + " из " + pcmFrame.length);
                            }
                        }
                    }
                    opusDecoder.releaseOutputBuffer(outputBufferIndex, false);
                }

                // Запрашиваем следующий буфер без задержки
                outputBufferIndex = opusDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            Log.e("AUDIO_TRACK", "Критический сбой в handleIncomingAudio", e);
        }
    }

    private int readVarIntFromBytes(byte[] data, int[] offset) {
        int o = offset[0];
        int value = 0;
        int shift = 0;
        while (true) {
            int b = data[o++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
            if ((b & 0x80) == 0) break;
        }
        offset[0] = o;
        return value;
    }

    private void initOpusCodecs() {
        try {
            // =================================================================
            // 1. НАСТРОЙКА ЭНКОДЕРА (Запись микрофона и отправка в сеть)
            // =================================================================
            android.media.MediaFormat encFormat = android.media.MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1);
            encFormat.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 32000);
            encFormat.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 2);

            opusEncoder = MediaCodec.createEncoderByType("audio/opus");
            opusEncoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            opusEncoder.start();

            // =================================================================
            // 2. НАСТРОЙКА ДЕКОДЕРА (Прием из сети и вывод в динамик)
            // =================================================================
            android.media.MediaFormat decFormat = android.media.MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1);

            // Строго 19 байт структуры OpusHead (без лишнего мусора)
            byte[] csd0 = {
                    'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', // 0-7: Магическая сигнатура
                    1,                                      // 8: Версия спецификации (всегда 1)
                    1,                                      // 9: Количество каналов (1 - моно)
                    0, 0,                                   // 10-11: Pre-skip (в семплах) - ставим 0
                    (byte) 0x80, (byte) 0xBB, 0, 0,         // 12-15: Частота 48000 Гц (0xBB80 в Little Endian)
                    0, 0,                                   // 16-17: Output gain (0)
                    0                                       // 18: Mapping family (0 - моно/стерео стандарт)
            };

            // Для csd-1 в Android используется задержка пре-скипа в наносекундах, представленная как long (8 байт)
            byte[] csd1 = new byte[8]; // заполнен нулями по умолчанию

            decFormat.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0));
            decFormat.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd1));

            opusDecoder = MediaCodec.createDecoderByType("audio/opus");
            opusDecoder.configure(decFormat, null, null, 0);
            opusDecoder.start();

            Log.d("AUDIO2", "Opus codecs initialized успешно (Энкодер и Декодер запущены)");
        } catch (Exception e) {
            Log.e("AUDIO2", "Init error: " + e.getMessage(), e);
        }
    }

    private void writeVarIntLongStream(ByteArrayOutputStream os, long value) throws IOException {
        if (value < 0x80) {
            // 7-bit positive number (0xxxxxxx)
            os.write((byte) value);
        } else if (value < 0x4000) {
            // 14-bit positive number (10xxxxxx + 1 byte)
            os.write((byte) ((value >> 8) | 0x80));
            os.write((byte) (value & 0xFF));
        } else if (value < 0x200000) {
            // 21-bit positive number (110xxxxx + 2 bytes)
            os.write((byte) ((value >> 16) | 0xC0));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        } else if (value < 0x10000000) {
            // 28-bit positive number (1110xxxx + 3 bytes)
            os.write((byte) ((value >> 24) | 0xE0));
            os.write((byte) ((value >> 16) & 0xFF));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        } else {
            // Универсальный 32-битный вариант (111100__ + 4 bytes)
            os.write((byte) 0xF0);
            os.write((byte) ((value >> 24) & 0xFF));
            os.write((byte) ((value >> 16) & 0xFF));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        }
    }

    private void startAudioSendingLoop(final DataOutputStream dos, String roomName) {
        Log.d("AUDIO2", "Audio sending loop STARTED (Strict TCP Verbatim Tunnel)");
        new Thread(() -> {
            int frameSize = 1920; // 20ms фрейм для 48000Hz 16bit Mono
            byte[] audioBuffer = new byte[frameSize];
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int sequenceNumber = 0;

            try {
                while (isSfxRunning && audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {

                    // Гарантированное накопление ровно 1920 байт с микрофона
                    int offset = 0;
                    while (offset < frameSize && isSfxRunning) {
                        int read = audioRecord.read(audioBuffer, offset, frameSize - offset);
                        if (read > 0) {
                            offset += read;
                        } else {
                            try { Thread.sleep(5); } catch (InterruptedException e) {}
                        }
                    }
                    if (!isSfxRunning) break;
                    int readBytes = offset;

                    // Подаем накопленный PCM-буфер в Opus-кодер
                    int inputBufferIndex = opusEncoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = opusEncoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(audioBuffer, 0, readBytes);
                        opusEncoder.queueInputBuffer(inputBufferIndex, 0, readBytes, System.nanoTime() / 1000, 0);
                    }

                    int outputBufferIndex = opusEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                    while (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = opusEncoder.getOutputBuffer(outputBufferIndex);
                        byte[] opusData = new byte[bufferInfo.size];
                        outputBuffer.get(opusData);

                        // === СБОРКА АУДИО-ПА КЕТА ПО СПЕЦИФИКАЦИИ MUMBLE VOICE CORRECT ===
                        ByteArrayOutputStream pcmPayloadOs = new ByteArrayOutputStream();

                        // 1. Заголовок (1 байт): Кодек Opus (нормальный разговор, не шепот/крик) -> тип 4 (0x80)
                        pcmPayloadOs.write(0x80);

                        // 2. ИСПОЛЬЗУЕМ СТРОГО writeMumbleVoiceVarInt ДЛЯ ГОЛОСА!
                        // Пишем Sequence Number
                        writeVarIntLongStream(pcmPayloadOs, (long) (sequenceNumber++));
                        writeVarIntLongStream(pcmPayloadOs, (long) (opusData.length));

                        // 3. Добавляем сырые байты кодека
                        pcmPayloadOs.write(opusData);

                        byte[] rawAudioPayload = pcmPayloadOs.toByteArray();

                        // --- ОТПРАВКА В TCP СОКЕТ ---
                        synchronized (dos) {
                            dos.writeShort(1); // Type 1: UDPTunnel (Аудио через TCP)
                            dos.writeInt(rawAudioPayload.length); // Длина всего payload
                            dos.write(rawAudioPayload); // Байты аудио-пакета
                            dos.flush();
                        }

                        if (sequenceNumber % 50 == 0) {
                            Log.d("AUDIO2", "Отправлено 50 аудио-пакетов. Последний seq=" + (sequenceNumber - 1) + ", len=" + rawAudioPayload.length);
                        }

                        opusEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        outputBufferIndex = opusEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    }
                }
            } catch (Exception e) {
                Log.e("AUDIO2", "Критическая ошибка в аудио-петле отправки", e);
            }
        }).start();
    }

    private void writeVarIntStream(ByteArrayOutputStream os, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                os.write(value);
                return;
            } else {
                os.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    private int readMumbleVarIntFromBytes(byte[] data, int[] offset) {
        int o = offset[0];
        int b = data[o++] & 0xFF;
        int v = 0;

        if ((b & 0x80) == 0) {
            // 7-битное число
            v = b & 0x7F;
        } else if ((b & 0xC0) == 0x80) {
            // 14-битное число
            v = ((b & 0x3F) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xE0) == 0xC0) {
            // 21-битное число
            v = ((b & 0x1F) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xF0) == 0xE0) {
            // 28-битное число
            v = ((b & 0x0F) << 24) | ((data[o++] & 0xFF) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xF0) == 0xF0) {
            // 32-битное число (4 байта далее)
            v = ((data[o++] & 0xFF) << 24) | ((data[o++] & 0xFF) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        }

        offset[0] = o;
        return v;
    }

    private void stopVoiceCommunication() {
        isSfxRunning = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
    }

    @Override
    protected void onDestroy() {

        stopAudio();
        super.onDestroy();
        stopVoiceCommunication();
    }
}
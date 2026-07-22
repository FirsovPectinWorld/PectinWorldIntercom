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
import android.widget.SeekBar;
import android.widget.Toast;
import java.util.Arrays;
import java.util.HashMap;

import android.media.MediaFormat;
import androidx.appcompat.app.AppCompatActivity;
import java.nio.ByteBuffer;
import android.media.MediaCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.os.Build;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AUDIO2_SERVICE";

    private boolean ringtoneStarted = false;

    private LinearLayout loginBlock, intercomBlock;
    private EditText passwordInput;
    private Button loginButton, btnGeneralCall;
    private SeekBar seekContactOne, seekContactTwo;
    private TextView welcomeText, txtContactOne, txtContactTwo;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "PectinWorldPrefs";
    private static final String KEY_USER_ROLE = "UserRole";

    private static final String PASS_VLADIMIR = "v";
    private static final String PASS_GALINA = "g";
    private static final String PASS_SERGEY = "s";

    // ЦВЕТА СЛАЙДЕРОВ ДЛЯ РАЗЛИЧНЫХ СОСТОЯНИЙ СВЯЗИ
    private static final int COLOR_NEUTRAL = 0xFFFBC02D;  // 1. Покой: Желтый (Яркий Amber/Yellow)
    private static final int COLOR_INCOMING = 0xFFD32F2F; // 2. Вызов: Красный (Мягкий красный)
    private static final int COLOR_ACTIVE = 0xFFCDDC39;   // 3. Разговор: Лимонный (Lime/Light Green)
    private static final int COLOR_OUTGOING = 0xFF29B6F6; // 4. Исходящий вызов (Светло-голубой)
    private static final int COLOR_THUMB = 0xFF1B5E20;    // 5. Ползунок: Наш фирменный темно-зеленый

    private static final String SERVER_HOST = "95.214.62.90";
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
    private int diagnosticCounter = 0;

    DataOutputStream dos = null;

    // Глобальный массив состояний (все 6 слайдеров системы)
    // По умолчанию все в состоянии 0 (Покой)
    private int[] sliderMatrix = new int[6];

    // Четкие индексы для каждого слайдера в системе
    private static final int IDX_VLADIMIR_GALINA = 0; // Слайдер на экране Владимира к Галине
    private static final int IDX_VLADIMIR_SERGEY = 1; // Слайдер на экране Владимира к Сергею

    private static final int IDX_GALINA_VLADIMIR = 2; // Слайдер на экране Галины к Владимиру
    private static final int IDX_GALINA_SERGEY    = 3; // Слайдер на экране Галины к Сергею

    private static final int IDX_SERGEY_VLADIMIR = 4; // Слайдер на экране Сергея к Владимиру
    private static final int IDX_SERGEY_GALINA   = 5; // Слайдер на экране Сергея к Галине

    private static final int ST_IDLE = 0;      // Покой
    private static final int ST_OUTGOING = 1;  // Я вызываю
    private static final int ST_INCOMING = 2;  // Меня вызывают
    private static final int ST_CONNECTED = 3; // В разговоре

    // ДИНАМИЧЕСКИЙ ПУЛ ДЕКОДЕРОВ
    private HashMap<Integer, MediaCodec> decoderPool = new HashMap<>();

    private int mySession = -1;
    private boolean audioStarted = false;
    private String currentLoggedInRole = "Unknown";

    public static final String ACTION_SEND_EXIT = "com.pectinworld.intercom.SEND_EXIT";

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
        seekContactOne = findViewById(R.id.seekContactOne);
        seekContactTwo = findViewById(R.id.seekContactTwo);
        txtContactOne = findViewById(R.id.txtContactOne);
        txtContactTwo = findViewById(R.id.txtContactTwo);
        btnGeneralCall = findViewById(R.id.btnGeneralCall);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedRole = prefs.getString(KEY_USER_ROLE, null);

        if (savedRole != null) {
            currentLoggedInRole = savedRole;
            startIntercomServiceWithRole(savedRole);
            setupIntercomUI(savedRole);
        } else {
            //Log.d("AUDIO2", "Сервис не запущен: ожидаем авторизации пользователя.");
            loginButton.setOnClickListener(v -> handleLogin());
        }

        //Log.d("INTERCOM_MAIN", "==> [ЛОГ ACTIVITY] onCreate вызван. Проверяем входящий Intent...");
        if (getIntent() != null) {
            String action = getIntent().getStringExtra("LAUNCH_ACTION");
            //Log.d("INTERCOM_MAIN", "[ЛОГ ACTIVITY] onCreate Intent Action: " + action);
            if ("INCOMING_CALL".equals(action)) {
                String caller = getIntent().getStringExtra("CALLER_NAME");
                //Log.d("INTERCOM_MAIN", "[ЛОГ ACTIVITY] Найдена команда звонка в onCreate от: " + caller);
                handleIncomingCallFromIntent(caller);
            }
        } else {
            //Log.d("INTERCOM_MAIN", "[ЛОГ ACTIVITY] onCreate Intent равен null.");
        }

        // Позволяет Activity открываться поверх заблокированного экрана
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }


        // АВТОМАТИЧЕСКИЙ ЗАПРОС РАЗРЕШЕНИЯ НА УВЕДОМЛЕНИЯ (Защита от сброса Android Studio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                //Log.w("INTERCOM_MAIN", "Разрешение POST_NOTIFICATIONS отсутствует. Запрашиваем...");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            } else {
                //Log.d("INTERCOM_MAIN", "Разрешение POST_NOTIFICATIONS уже есть.");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        //Log.d("INTERCOM_MAIN", "==> [ЛОГ ACTIVITY] onNewIntent вызван! Приложение было свернуто, но проснулось.");
        if (intent != null) {
            String action = intent.getStringExtra("LAUNCH_ACTION");
            //Log.d("INTERCOM_MAIN", "[ЛОГ ACTIVITY] onNewIntent Action: " + action);
            if ("INCOMING_CALL".equals(action)) {
                String caller = intent.getStringExtra("CALLER_NAME");
                //Log.d("INTERCOM_MAIN", "[ЛОГ ACTIVITY] Найдена команда звонка в onNewIntent от: " + caller);
                handleIncomingCallFromIntent(caller);
            }
        }
    }

    private void handleIncomingCallFromIntent(String callerName) {
        //Log.d(TAG, "[МЕТОД UI] Вызов handleIncomingCallFromIntent для абонента: " + callerName);
        if (welcomeText != null) {
            welcomeText.setText("🚨 ВХОДЯЩИЙ ВЫЗОВ ОТ: " + getGenitiveName(callerName));
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String lowerCaller = callerName.toLowerCase().trim();
                    //Log.d(TAG, "[МЕТОД UI_ПОТОК] Проверка ролей. Моя текущая роль: " + currentLoggedInRole);

                    // Если звонит Галина
                    if (lowerCaller.contains("галина") || lowerCaller.contains("galina")) {
                        if (seekContactOne != null) {
                            //Log.d(TAG, "[МЕТОД UI_ПОТОК] Выставляем Слайдер 1 (Галина) в режим ВЫЗОВА (50%, Красный)");
                            seekContactOne.setTag(COLOR_INCOMING);
                            setSliderTrackColor(seekContactOne, COLOR_INCOMING);
                            seekContactOne.setProgress(50);
                        }
                    }
                    // Если звонит Сергей
                    else if (lowerCaller.contains("сергей") || lowerCaller.contains("sergey")) {
                        if (seekContactTwo != null) {
                            //Log.d(TAG, "[МЕТОД UI_ПОТОК] Выставляем Слайдер 2 (Сергей) в режим ВЫЗОВА (50%, Красный)");
                            seekContactTwo.setTag(COLOR_INCOMING);
                            setSliderTrackColor(seekContactTwo, COLOR_INCOMING);
                            seekContactTwo.setProgress(50);
                        }
                    }
                    // Если звонит Владимир (логика на телефонах Галины или Сергея)
                    else if (lowerCaller.contains("владимир") || lowerCaller.contains("vladimir")) {
                        if (currentLoggedInRole != null && (currentLoggedInRole.equalsIgnoreCase("Галина") || currentLoggedInRole.equalsIgnoreCase("Сергей"))) {
                            if (seekContactOne != null) {
                                //Log.d(TAG, "[МЕТОД UI_ПОТОК] Мне звонит Владимир. Слайдер 1 в режим ВЫЗОВА (50%, Красный)");
                                seekContactOne.setTag(COLOR_INCOMING);
                                setSliderTrackColor(seekContactOne, COLOR_INCOMING);
                                seekContactOne.setProgress(50);
                            }
                        }
                    }
                } catch (Exception e) {
                    //Log.e(TAG, "[МЕТОД UI_ПОТОК] Ошибка при установке состояния вызова", e);
                }
            }
        });
        Toast.makeText(this, "Входящий вызов: " + callerName, Toast.LENGTH_LONG).show();
    }

    // НОВЫЙ МЕТОД: Склонение имен для красивого вывода
    private String getGenitiveName(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase().trim();
        if (lower.equals("владимир")) return "ВЛАДИМИРА";
        if (lower.equals("галина")) return "ГАЛИНЫ";
        if (lower.equals("сергей")) return "СЕРГЕЯ";
        return name.toUpperCase(); // Для неизвестных имен
    }

    private void handleLogin() {
        String enteredPassword = passwordInput.getText().toString().trim();
        String detectedRole = null;

        if (enteredPassword.equals(PASS_VLADIMIR)) detectedRole = "Владимир";
        else if (enteredPassword.equals(PASS_GALINA)) detectedRole = "Галина";
        else if (enteredPassword.equals(PASS_SERGEY)) detectedRole = "Сергей";

        if (detectedRole != null) {
            currentLoggedInRole = detectedRole;
            prefs.edit().putString(KEY_USER_ROLE, detectedRole).commit();
            startIntercomServiceWithRole(detectedRole);
            setupIntercomUI(detectedRole);
        } else {
            Toast.makeText(this, "Неверный пароль доступа!", Toast.LENGTH_SHORT).show();
        }
    }

    private void startIntercomServiceWithRole(String role) {
        Intent serviceIntent = new Intent(this, IntercomService.class);
        serviceIntent.putExtra("USER_ROLE_EXTRA", role);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        //Log.d("AUDIO2", "Команда на запуск IntercomService отправлена для роли: " + role);
    }

    private void setupIntercomUI(String role) {
        loginBlock.setVisibility(View.GONE);
        intercomBlock.setVisibility(View.VISIBLE);
        welcomeText.setText("Привет, " + role + "!");

        if (role.equals("Владимир")) {
            txtContactOne.setText("👉 Свайп вправо: Вызвать Галину");
            txtContactTwo.setText("👉 Свайп вправо: Вызвать Сергея");
        } else if (role.equals("Галина")) {
            txtContactOne.setText("👉 Свайп вправо: Вызвать Владимира");
            txtContactTwo.setText("👉 Свайп вправо: Вызвать Сергея");
        } else if (role.equals("Сергей")) {
            txtContactOne.setText("👉 Свайп вправо: Вызвать Владимира");
            txtContactTwo.setText("👉 Свайп вправо: Вызвать Галину");
        }

        seekContactOne.setMax(100);
        seekContactOne.setProgress(0);
        seekContactOne.setTag(COLOR_NEUTRAL); // Состояние покоя (будет использоваться в логике жестов)
        setSliderTrackColor(seekContactOne, COLOR_NEUTRAL); // Красим дорожку в желтый, ползунок в темно-зеленый

        seekContactTwo.setMax(100);
        seekContactTwo.setProgress(0);
        seekContactTwo.setTag(COLOR_NEUTRAL); // Состояние покоя
        setSliderTrackColor(seekContactTwo, COLOR_NEUTRAL); // Красим дорожку в желтый, ползунок в темно-зеленый

        btnGeneralCall.setEnabled(false);
        btnGeneralCall.setAlpha(0.5f);

        // НАСТРОЙКА СЛАЙДЕРА №1
        seekContactOne.setOnSeekBarChangeListener(commonSliderListener);

        // НАСТРОЙКА СЛАЙДЕРА №2
        seekContactTwo.setOnSeekBarChangeListener(commonSliderListener);

        setSliderTrackColor(seekContactOne, COLOR_NEUTRAL);
        setSliderTrackColor(seekContactTwo, COLOR_NEUTRAL);
    }

    SeekBar.OnSeekBarChangeListener commonSliderListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            int progress = seekBar.getProgress();
            int currentStatus = (seekBar.getTag() != null) ? (int) seekBar.getTag() : COLOR_NEUTRAL;

            // ОПРЕДЕЛЯЕМ СОБЕСЕДНИКА ДИНАМИЧЕСКИ (6 НАПРАВЛЕНИЙ)
            String target = "";
            int viewId = seekBar.getId();

            if ("Владимир".equals(currentLoggedInRole)) {
                if (viewId == R.id.seekContactOne) {
                    target = "Галина";   // 1. Владимир -> Галина
                } else if (viewId == R.id.seekContactTwo) {
                    target = "Сергей";   // 2. Владимир -> Сергей
                }
            }
            else if ("Галина".equals(currentLoggedInRole)) {
                if (viewId == R.id.seekContactOne) {
                    target = "Владимир";  // 3. Галина -> Владимир
                } else if (viewId == R.id.seekContactTwo) {
                    target = "Сергей";    // 4. Галина -> Сергей
                }
            }
            else if ("Сергей".equals(currentLoggedInRole)) {
                if (viewId == R.id.seekContactOne) {
                    target = "Владимир";  // 5. Сергей -> Владимир
                } else if (viewId == R.id.seekContactTwo) {
                    target = "Галина";    // 6. Сергей -> Галина
                }
            }

            // Страховка на случай непредвиденных ID
            if (target.isEmpty()) {
                target = "Галина";
            }

            if (progress <= 5) {
                // Log.d(TAG, "[СЛАЙДЕР] Сброс/Отбой разговора. Текущий статус: " + currentStatus);

                if (currentStatus == COLOR_INCOMING || currentStatus == COLOR_OUTGOING || currentStatus == COLOR_ACTIVE) {
                    final String finalTarget = target;

                    // !!! ВОТ ЭТИ ДВЕ СТРОЧКИ: Жестко глушим звук при локальном сбросе
                    //Intent stopIntent = new Intent("com.pectinworld.intercom.STOP_CALL_EFFECTS");
                    //sendBroadcast(stopIntent);

                    new Thread(() -> {
                        try {
                            sendCallPacket("COMMAND_CALL_REJECT:" + currentLoggedInRole + "-" + finalTarget);
                            // Log.d(TAG, "[ОТБОЙ ТЕСТ] Отправлен REJECT через изолированный сокет для: " + finalTarget);
                        } catch (Exception e) {
                            // Log.e(TAG, "[ОТБОЙ ТЕСТ] Ошибка отправки пакета: ", e);
                        }
                    }).start();
                }
            }
            else if (progress >= 95) {
                seekBar.setProgress(100);

                if (currentStatus == COLOR_INCOMING) {
                    seekBar.setTag(COLOR_ACTIVE);
                    setSliderTrackColor(seekBar, COLOR_ACTIVE);

                    //Intent stopIntent = new Intent("com.pectinworld.intercom.STOP_CALL_EFFECTS");
                    //sendBroadcast(stopIntent);

                    sendAcceptPacket(target);
                    //startVoiceCommunication("Room_One");

                    // =================================================================
                    // АВТОМАТИЧЕСКИЙ СБРОС ВТОРОГО ВЫЗОВА (ЕСЛИ ОН БЫЛ)
                    // =================================================================
                    // Определяем, какой слайдер сейчас НЕ активен
                    SeekBar secondarySeekBar = null;
                    String secondaryTarget = "";

                    if (seekBar.getId() == R.id.seekContactOne) {
                        // Если Галина ответила Владимиру (слайдер 1), то второй — это Сергей
                        secondarySeekBar = findViewById(R.id.seekContactTwo);
                        secondaryTarget = "Владимир".equals(currentLoggedInRole) ? "Сергей" : ("Галина".equals(currentLoggedInRole) ? "Сергей" : "Галина");
                    } else if (seekBar.getId() == R.id.seekContactTwo) {
                        // If Галина ответила Сергею (слайдер 2), то второй — Владимир
                        secondarySeekBar = findViewById(R.id.seekContactOne);
                        secondaryTarget = "Владимир".equals(currentLoggedInRole) ? "Галина" : ("Галина".equals(currentLoggedInRole) ? "Владимир" : "Владимир");
                    }

                    if (secondarySeekBar != null) {
                        int secStatus = (secondarySeekBar.getTag() != null) ? (int) secondarySeekBar.getTag() : COLOR_NEUTRAL;

                        // Если по второму слайдеру тоже шел входящий звонок — гасим его!
                        if (secStatus == COLOR_INCOMING) {
                            //Log.d(TAG, "[АВТО-ОТБОЙ] Гасим параллельный вызов от: " + secondaryTarget);

                            // 1. Сбрасываем визуальное состояние второго слайдера на экране
                            secondarySeekBar.setProgress(0);
                            secondarySeekBar.setTag(COLOR_NEUTRAL);
                            setSliderTrackColor(secondarySeekBar, COLOR_NEUTRAL);

                            // 2. Шлем второму вызывающему REJECT в сеть, чтобы его телефон успокоился
                            final String finalSecTarget = secondaryTarget;
                            new Thread(() -> {
                                try {
                                    sendCallPacket("COMMAND_CALL_REJECT:" + currentLoggedInRole + "-" + finalSecTarget);
                                    //Log.d(TAG, "[АВТО-ОТБОЙ] Отправлен REJECT через сокет для: " + finalSecTarget);
                                } catch (Exception e) {
                                    //Log.e(TAG, "[АВТО-ОТБОЙ] Ошибка отправки авто-отбоя: ", e);
                                }
                            }).start();
                        }
                    }
                    // =================================================================

                } else {
                    seekBar.setTag(COLOR_OUTGOING);
                    setSliderTrackColor(seekBar, COLOR_OUTGOING);

                    sendCallPacket(target);
                }
            }
            else {
                if (currentStatus == COLOR_INCOMING) {
                    seekBar.setProgress(50);
                    setSliderTrackColor(seekBar, COLOR_INCOMING);
                } else if (currentStatus == COLOR_ACTIVE || currentStatus == COLOR_OUTGOING) {
                    seekBar.setProgress(100);
                    setSliderTrackColor(seekBar, currentStatus);
                } else {
                    seekBar.setProgress(0);
                    setSliderTrackColor(seekBar, COLOR_NEUTRAL);
                }
            }
        }
    };

    private void startVoiceCommunication(String targetRoom) {
        if (isSfxRunning) {
            stopAudio();
            Toast.makeText(this, "Связь отключена", Toast.LENGTH_SHORT).show();
            return;
        }

        isSfxRunning = true;
        Toast.makeText(this, "Подключение к Интеркому...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            SSLSocket tcpSocket = null;
            DataInputStream dis = null;

            try {
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

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        AUDIO_FORMAT,
                        minBufSize
                );

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        SAMPLE_RATE,
                        CHANNEL_OUT,
                        AUDIO_FORMAT,
                        minBufSize,
                        AudioTrack.MODE_STREAM
                );

                if (audioRecord != null && AcousticEchoCanceler.isAvailable()) {
                    if (echoCanceler != null) {
                        try { echoCanceler.release(); } catch (Exception e) {}
                    }
                    echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                    if (echoCanceler != null) {
                        echoCanceler.setEnabled(true);
                        //Log.d("AUDIO2", "Аппаратное эхоподавление (AEC) УСПЕШНО ВКЛЮЧЕНО.");
                    }
                }

                audioStarted = false;

                while (isSfxRunning && dis != null) {
                    int msgType = dis.readUnsignedShort();
                    int msgLen = dis.readInt();

                    byte[] msgBody = new byte[msgLen];
                    dis.readFully(msgBody);

                    try {
                        if (msgType == 0) {
                            String role = currentLoggedInRole;
                            //Log.d("AUDIO2", "MainActivity считывает роль для отправки Authenticate: " + role);
                            String username;
                            switch (role) {
                                case "Владимир": username = "Vladimir"; break;
                                case "Галина": username = "Galina"; break;
                                case "Сергей": username = "Sergey"; break;
                                default:
                                    String androidId = android.provider.Settings.Secure.getString(
                                            getContentResolver(), android.provider.Settings.Secure.ANDROID_ID
                                    );
                                    if (androidId == null || androidId.isEmpty()) {
                                        androidId = String.valueOf((int)(Math.random() * 10000));
                                    }
                                    username = "Device_" + androidId.substring(0, Math.min(androidId.length(), 6));
                            }
                            String serverPassword = "PectinWorldIntercom1970";
                            ByteArrayOutputStream aOs = new ByteArrayOutputStream();

                            aOs.write(0x0A);
                            byte[] uBytes = username.getBytes("UTF-8");
                            writeVarIntStream(aOs, uBytes.length);
                            aOs.write(uBytes);

                            aOs.write(0x12);
                            byte[] pBytes = serverPassword.getBytes("UTF-8");
                            writeVarIntStream(aOs, pBytes.length);
                            aOs.write(pBytes);

                            aOs.write(0x1A);
                            byte[] tBytes = serverPassword.getBytes("UTF-8");
                            writeVarIntStream(aOs, tBytes.length);
                            aOs.write(tBytes);

                            byte[] authBody = aOs.toByteArray();

                            synchronized (dos) {
                                dos.writeShort(2);
                                dos.writeInt(authBody.length);
                                dos.write(authBody);
                                dos.flush();

                                dos.writeShort(3);
                                dos.writeInt(0);
                                dos.flush();
                            }
                            //Log.d("AUDIO2", "Валидный пакет Authenticate отправлен.");
                        }
                        else if (msgType == 11) { // Текстовое сообщение во время активного звонка
                            try {
                                int idx = 0;
                                String incomingText = "";
                                while (idx < msgBody.length) {
                                    int key = msgBody[idx++] & 0xFF;
                                    int wireType = key & 0x07;
                                    int tag = key >> 3;
                                    if (wireType == 0) {
                                        while ((msgBody[idx++] & 0x80) != 0) {}
                                    } else if (wireType == 2) {
                                        int len = 0;
                                        int shift = 0;
                                        while (true) {
                                            int b = msgBody[idx++] & 0xFF;
                                            len |= (b & 0x7F) << shift;
                                            if ((b & 0x80) == 0) break;
                                            shift += 7;
                                        }

                                        if (idx + len <= msgBody.length) {
                                            String extractedStr = new String(msgBody, idx, len, "UTF-8");
                                            // Если в любом теге прилетела команда выхода — фиксируем её
                                            if (extractedStr.contains("COMMAND_EXIT")) {
                                                incomingText = extractedStr;
                                            }
                                        }
                                        idx += len;
                                    } else {
                                        break;
                                    }
                                }

                                if (incomingText.contains("COMMAND_EXIT")) {
                                    //Log.d("AUDIO2", "Получена команда автовыхода от собеседника!");
                                    stopAudio();
                                }
                            } catch (Exception e) {
                                //Log.e("AUDIO2", "Ошибка разбора текстового пакета типа 9 в Activity", e);
                            }
                        }
                        else if (msgType == 13) {
                            synchronized (dos) {
                                dos.writeShort(13);
                                dos.writeInt(msgLen);
                                dos.write(msgBody);
                                dos.flush();
                            }
                        }
                        else if (msgType == 15) {
                            byte[] keyReply = new byte[32];
                            Arrays.fill(keyReply, (byte) 0);
                            synchronized (dos) {
                                dos.writeShort(15);
                                dos.writeInt(keyReply.length);
                                dos.write(keyReply);
                                dos.flush();
                            }
                        }
                        else if (msgType == 1) {
                            handleIncomingAudio(msgBody);
                        }
                        else if (msgType == 5 && !audioStarted) {
                            try {
                                if (msgBody.length > 1 && msgBody[0] == 0x08) {
                                    int[] off = {1};
                                    mySession = readVarIntFromBytes(msgBody, off);
                                    //Log.d("AUDIO2", "=== ServerSync === Сессия определена: " + mySession);
                                }
                            } catch (Exception e) {
                                //Log.e("AUDIO2", "Ошибка разбора сессии", e);
                            }

                            audioStarted = true;
                            initEncoder();
                            audioRecord.startRecording();

                            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                            if (audioManager != null) {
                                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            }

                            audioTrack.play();

                            final DataOutputStream finalDos = dos;
                            final String finalTargetRoom = targetRoom;
                            final int finalSession = mySession;

                            new Thread(() -> {
                                try {
                                    Thread.sleep(250);

                                    int targetChannelId = finalTargetRoom.equals("Root") ? 0 : (finalTargetRoom.equals("Room_One") ? 1 : 2);
                                    ByteArrayOutputStream joinOs = new ByteArrayOutputStream();

                                    if (finalSession != -1) {
                                        joinOs.write(0x08);
                                        int sessVal = finalSession;
                                        while ((sessVal & 0xFFFFFF80) != 0L) {
                                            joinOs.write((sessVal & 0x7F) | 0x80);
                                            sessVal >>>= 7;
                                        }
                                        joinOs.write(sessVal & 0x7F);
                                    }

                                    joinOs.write(0x28);
                                    int chanVal = targetChannelId;
                                    while ((chanVal & 0xFFFFFF80) != 0L) {
                                        joinOs.write((chanVal & 0x7F) | 0x80);
                                        chanVal >>>= 7;
                                    }
                                    joinOs.write(chanVal & 0x7F);

                                    byte[] joinBody = joinOs.toByteArray();

                                    synchronized (finalDos) {
                                        finalDos.writeShort(9);
                                        finalDos.writeInt(joinBody.length);
                                        finalDos.write(joinBody);
                                        finalDos.flush();
                                    }
                                    startAudioSendingLoop(finalDos, finalTargetRoom);

                                } catch (Exception e) {
                                    //Log.e("AUDIO2", "Ошибка отправки UserState", e);
                                }
                            }).start();
                        }
                    } catch (Exception internalPackEx) {
                        //Log.e("AUDIO2", "Ошибка обработки пакета типа: " + msgType, internalPackEx);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in voice thread", e);
            } finally {
                stopAudio();
                try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}
            }
        }).start();
    }

    private void initEncoder() {
        try {
            android.media.MediaFormat encFormat = android.media.MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1);
            encFormat.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 32000);
            encFormat.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 2);

            opusEncoder = MediaCodec.createEncoderByType("audio/opus");
            opusEncoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            opusEncoder.start();
            //Log.d("AUDIO2", "Opus энкодер успешно запущен");
        } catch (Exception e) {
            //Log.e("AUDIO2", "Ошибка старта энкодера: " + e.getMessage());
        }
    }

    private MediaCodec getOrCreateDecoderForSession(int session) {
        if (decoderPool.containsKey(session)) {
            return decoderPool.get(session);
        }
        try {
            android.media.MediaFormat decFormat = android.media.MediaFormat.createAudioFormat("audio/opus", SAMPLE_RATE, 1);
            byte[] csd0 = {
                    'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0, 0,
                    (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0
            };
            byte[] csd1 = new byte[8];
            decFormat.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0));
            decFormat.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd1));

            MediaCodec decoder = MediaCodec.createDecoderByType("audio/opus");
            decoder.configure(decFormat, null, null, 0);
            decoder.start();

            decoderPool.put(session, decoder);
            //Log.d("AUDIO2", "---> Создан персональный декодер Opus для сессии #" + session);
            return decoder;
        } catch (Exception e) {
            //Log.e("AUDIO2", "Не удалось создать декодер для сессии " + session, e);
            return null;
        }
    }

    private void handleIncomingAudio(byte[] msgBody) {
        try {
            diagnosticCounter++;
            int[] off = {0};
            int header = msgBody[off[0]++] & 0xFF;

            int session = readMumbleVarIntFromBytes(msgBody, off);
            int seq = readMumbleVarIntFromBytes(msgBody, off);
            int opusLen = readMumbleVarIntFromBytes(msgBody, off);

            if (session == mySession) return;

            MediaCodec currentDecoder = getOrCreateDecoderForSession(session);
            if (currentDecoder == null) return;

            if (opusLen <= 0 || off[0] + opusLen > msgBody.length) return;

            byte[] opusFrame = new byte[opusLen];
            System.arraycopy(msgBody, off[0], opusFrame, 0, opusLen);

            int inputBufferIndex = currentDecoder.dequeueInputBuffer(0);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = currentDecoder.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(opusFrame);
                currentDecoder.queueInputBuffer(inputBufferIndex, 0, opusFrame.length, 0, 0);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = currentDecoder.dequeueOutputBuffer(bufferInfo, 0);

            while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = currentDecoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] pcmFrame = new byte[bufferInfo.size];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        outputBuffer.get(pcmFrame);

                        if (audioTrack != null) {
                            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.play();
                            }
                            synchronized (audioTrack) {
                                audioTrack.write(pcmFrame, 0, pcmFrame.length);
                            }
                        }
                    }
                    currentDecoder.releaseOutputBuffer(outputBufferIndex, false);
                }
                outputBufferIndex = currentDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (Exception e) {
            //Log.e("AUDIO_TRACK", "Критический сбой в handleIncomingAudio", e);
        }
    }

    private void startAudioSendingLoop(final DataOutputStream dos, String roomName) {
        //Log.d("AUDIO2", "Audio sending loop STARTED");
        new Thread(() -> {
            int frameSizeInBytes = 1920;
            byte[] audioBuffer = new byte[frameSizeInBytes];
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int sequenceNumber = 0;

            try {
                while (isSfxRunning && audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {

                    int offset = 0;
                    while (offset < frameSizeInBytes && isSfxRunning) {
                        int read = audioRecord.read(audioBuffer, offset, frameSizeInBytes - offset);
                        if (read > 0) {
                            offset += read;
                        } else {
                            try { Thread.sleep(2); } catch (InterruptedException e) {}
                        }
                    }
                    if (!isSfxRunning) break;

                    if (opusEncoder == null) continue;

                    int inputBufferIndex = opusEncoder.dequeueInputBuffer(0);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = opusEncoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(audioBuffer, 0, frameSizeInBytes);
                        opusEncoder.queueInputBuffer(inputBufferIndex, 0, frameSizeInBytes, System.nanoTime() / 1000, 0);
                    }

                    int outputBufferIndex = opusEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    while (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = opusEncoder.getOutputBuffer(outputBufferIndex);
                        byte[] opusData = new byte[bufferInfo.size];
                        outputBuffer.get(opusData);

                        ByteArrayOutputStream pcmPayloadOs = new ByteArrayOutputStream();
                        pcmPayloadOs.write(0x80);

                        writeVarIntLongStream(pcmPayloadOs, (long) (sequenceNumber++));
                        writeVarIntLongStream(pcmPayloadOs, (long) (opusData.length));
                        pcmPayloadOs.write(opusData);

                        byte[] rawAudioPayload = pcmPayloadOs.toByteArray();

                        synchronized (dos) {
                            dos.writeShort(1);
                            dos.writeInt(rawAudioPayload.length);
                            dos.write(rawAudioPayload);
                            dos.flush();
                        }

                        opusEncoder.releaseOutputBuffer(outputBufferIndex, false);
                        outputBufferIndex = opusEncoder.dequeueOutputBuffer(bufferInfo, 0);
                    }
                }
            } catch (Exception e) {
                //Log.e("AUDIO2", "Критическая ошибка в аудио-петле отправки", e);
            }
        }).start();
    }

    private void stopAudio() {
        try {
            //Log.d("AUDIO2", "Вызван метод stopAudio(). Освобождение всех ресурсов...");
            isSfxRunning = false;

            if (echoCanceler != null) {
                try {
                    echoCanceler.setEnabled(false);
                    echoCanceler.release();
                } catch (Exception ignored) {}
                echoCanceler = null;
            }

            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                    audioRecord.release();
                } catch (Exception ignored) {}
                audioRecord = null;
            }

            if (audioTrack != null) {
                try {
                    if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop();
                    }
                    audioTrack.release();
                } catch (Exception ignored) {}
                audioTrack = null;
            }

            if (opusEncoder != null) {
                try {
                    opusEncoder.stop();
                    opusEncoder.release();
                } catch (Exception ignored) {}
                opusEncoder = null;
            }

            for (int sessionKey : decoderPool.keySet()) {
                MediaCodec dec = decoderPool.get(sessionKey);
                if (dec != null) {
                    try {
                        dec.stop();
                        dec.release();
                    } catch (Exception ignored) {}
                }
            }
            decoderPool.clear();

            audioStarted = false;

            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
            //Log.d("AUDIO2", "Очистка аудио-движка завершена успешно.");

            runOnUiThread(() -> {
                if (seekContactOne != null) {
                    seekContactOne.setProgress(0);
                    seekContactOne.setTag(COLOR_NEUTRAL);
                    setSliderTrackColor(seekContactOne, COLOR_NEUTRAL);
                }
                if (seekContactTwo != null) {
                    seekContactTwo.setProgress(0);
                    seekContactTwo.setTag(COLOR_NEUTRAL);
                    setSliderTrackColor(seekContactTwo, COLOR_NEUTRAL);
                }
            });
        } catch (Exception e) {
            //Log.e("AUDIO2", "Ошибка в stopAudio: " + e.getMessage());
        }
    }

    // НОВЫЙ МЕТОД: Отправка команды в сеть и создание локального эха для себя
    private void sendCallPacket(String targetName) {
        String finalPayload;
        if (targetName.contains(":")) {
            // Если прилетела уже готовая команда (ACCEPT/REJECT)
            finalPayload = targetName;
        } else {
            // Если прилетело просто имя, собираем чистую команду вызова
            finalPayload = "COMMAND_CALL_START:" + currentLoggedInRole + "-" + targetName;
        }

        Log.d(TAG, "[UI] Передаем команду в Сервис: " + finalPayload);

        // 1. Отправляем команду в сеть (чтобы ее услышал собеседник)
        Intent cmdIntent = new Intent("com.pectinworld.intercom.SEND_COMMAND");
        cmdIntent.putExtra("PAYLOAD", finalPayload);
        sendBroadcast(cmdIntent);

        // 2. Мгновенно применяем команду к себе (Сервер Mumble не пришлет нам наше же сообщение)
        simulateNetworkEchoLocally(finalPayload);
    }

    // НОВЫЙ МЕТОД: Централизованное обновление Матрицы без ожидания сервера
    private void simulateNetworkEchoLocally(String payload) {
        String action = null;
        String addressData = null;

        // Разбираем собственную отправленную команду так же, как это делает парсер сети
        if (payload.startsWith("COMMAND_CALL_START:")) {
            action = "com.pectinworld.intercom.INCOMING_CALL";
            addressData = payload.substring("COMMAND_CALL_START:".length()).trim();
        } else if (payload.startsWith("COMMAND_CALL_ACCEPT:")) {
            action = "com.pectinworld.intercom.CALL_ACCEPTED";
            addressData = payload.substring("COMMAND_CALL_ACCEPT:".length()).trim();
        } else if (payload.startsWith("COMMAND_CALL_REJECT:")) {
            action = "com.pectinworld.intercom.CALL_REJECTED";
            addressData = payload.substring("COMMAND_CALL_REJECT:".length()).trim();
        } else if (payload.startsWith("COMMAND_EXIT:")) {
            action = "com.pectinworld.intercom.CALL_REJECTED";
            addressData = payload.substring("COMMAND_EXIT:".length()).trim();
        }

        // Если команда валидна, кидаем Broadcast в собственный incomingCallReceiver
        if (action != null && addressData != null) {
            String sender = "";
            String receiver = "";

            if (addressData.contains("-")) {
                String[] parts = addressData.split("-");
                if (parts.length == 2) {
                    sender = parts[0].trim();
                    receiver = parts[1].trim();
                }
            } else {
                sender = addressData.trim();
                receiver = "ALL";
            }

            if (!sender.isEmpty()) {
                Intent matrixIntent = new Intent(action);
                matrixIntent.putExtra("SENDER_NAME", sender);
                matrixIntent.putExtra("RECEIVER_NAME", receiver);
                sendBroadcast(matrixIntent); // Эмулируем прилет пакета из сети
            }
        }
    }

        private void sendAcceptPacket(String target) {
            // Жестко и мгновенно глушим звук у себя через Broadcast
            //Intent stopIntent = new Intent("com.pectinworld.intercom.STOP_CALL_EFFECTS");
            //sendBroadcast(stopIntent);

            sendCallPacket("COMMAND_CALL_ACCEPT:" + currentLoggedInRole + "-" + target);
        }

        private void sendRejectPacket(String target) {
            // Жестко и мгновенно глушим звук у себя через Broadcast
            //Intent stopIntent = new Intent("com.pectinworld.intercom.STOP_CALL_EFFECTS");
            //sendBroadcast(stopIntent);

            sendCallPacket("COMMAND_CALL_REJECT:" + currentLoggedInRole + "-" + target);
        }

    private void sendExitCommandToServer(final DataOutputStream finalDos) {
        if (finalDos == null) return;

        new Thread(() -> {
            try {
                String msg = "COMMAND_EXIT";
                byte[] msgBytes = msg.getBytes("UTF-8");

                ByteArrayOutputStream txOs = new ByteArrayOutputStream();
                txOs.write(0x18); txOs.write(1);
                txOs.write(0x2A); txOs.write(msgBytes.length); txOs.write(msgBytes);

                byte[] txBody = txOs.toByteArray();

                synchronized (finalDos) {
                    finalDos.writeShort(11);
                    finalDos.writeInt(txBody.length);
                    finalDos.write(txBody);
                    finalDos.flush();
                }
                //Log.d("AUDIO2", "Скрытая команда COMMAND_EXIT отправлена в канал.");
            } catch (Exception e) {
                //Log.e("AUDIO2", "Ошибка отправки команды выхода", e);
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

    private void writeVarIntLongStream(ByteArrayOutputStream os, long value) throws IOException {
        if (value < 0x80) {
            os.write((byte) value);
        } else if (value < 0x4000) {
            os.write((byte) ((value >> 8) | 0x80));
            os.write((byte) (value & 0xFF));
        } else if (value < 0x200000) {
            os.write((byte) ((value >> 16) | 0xC0));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        } else if (value < 0x10000000) {
            os.write((byte) ((value >> 24) | 0xE0));
            os.write((byte) ((value >> 16) & 0xFF));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        } else {
            os.write((byte) 0xF0);
            os.write((byte) ((value >> 24) & 0xFF));
            os.write((byte) ((value >> 16) & 0xFF));
            os.write((byte) ((value >> 8) & 0xFF));
            os.write((byte) (value & 0xFF));
        }
    }

    private int readMumbleVarIntFromBytes(byte[] data, int[] offset) {
        int o = offset[0];
        int b = data[o++] & 0xFF;
        int v = 0;

        if ((b & 0x80) == 0) {
            v = b & 0x7F;
        } else if ((b & 0xC0) == 0x80) {
            v = ((b & 0x3F) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xE0) == 0xC0) {
            v = ((b & 0x1F) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xF0) == 0xE0) {
            v = ((b & 0x0F) << 24) | ((data[o++] & 0xFF) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        } else if ((b & 0xF0) == 0xF0) {
            v = ((data[o++] & 0xFF) << 24) | ((data[o++] & 0xFF) << 16) | ((data[o++] & 0xFF) << 8) | (data[o++] & 0xFF);
        }

        offset[0] = o;
        return v;
    }

    private void setSliderTrackColor(SeekBar seekBar, int color) {
        if (seekBar == null) return;
        // Красим дорожку слайдера в цвет состояния (желтый, красный или лимонный)
        seekBar.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        // Ползунок (круг) ВСЕГДА железно красим в наш фирменный темно-зеленый
        seekBar.getThumb().setColorFilter(COLOR_THUMB, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private final android.content.BroadcastReceiver incomingCallReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            String sender = intent.getStringExtra("SENDER_NAME");
            String receiver = intent.getStringExtra("RECEIVER_NAME");

            if (sender != null && receiver != null) {
                if ("com.pectinworld.intercom.INCOMING_CALL".equals(action)) {
                    updateMatrixByNames(sender, receiver, "START");
                }
                else if ("com.pectinworld.intercom.CALL_ACCEPTED".equals(action)) {
                    updateMatrixByNames(sender, receiver, "ACCEPT");
                }
                else if ("com.pectinworld.intercom.CALL_REJECTED".equals(action)) {
                    if ("ALL".equalsIgnoreCase(receiver)) {
                        // Одиночный сброс (COMMAND_EXIT) сбрасывает все связи этого человека
                        updateMatrixByNames(sender, "Владимир", "REJECT");
                        updateMatrixByNames(sender, "Галина", "REJECT");
                        updateMatrixByNames(sender, "Сергей", "REJECT");
                    } else {
                        updateMatrixByNames(sender, receiver, "REJECT");
                    }
                }
            }

            // После любого изменения матрицы — запускаем перерасчет UI и Звука!
            syncInterfaceAndAudioWithMatrix();
        }
    };

    // 1. Печать состояния матрицы в логcat
    private void logSliderMatrix(String triggerSource) {
        String[] states = {"IDLE", "OUTGOING", "INCOMING", "CONNECTED"};
        Log.d(TAG, String.format(
                "[%s] ВГ:%s | ВС:%s || ГВ:%s | ГС:%s || СВ:%s | СГ:%s",
                triggerSource,
                states[sliderMatrix[0]], states[sliderMatrix[1]],
                states[sliderMatrix[2]], states[states[sliderMatrix[3]] != null ? sliderMatrix[3] : 0],
                states[sliderMatrix[4]], states[sliderMatrix[5]]
        ));
    }

    // 2. Универсальный определитель индексов и переключатель матрицы
    private void updateMatrixByNames(String realSender, String realReceiver, String action) {
        if (realSender == null || realReceiver == null) return;

        String f = realSender.trim().toLowerCase();
        String t = realReceiver.trim().toLowerCase();

        int idxFromTo = -1;
        int idxToFrom = -1;

        if (f.contains("владимир") && t.contains("галина")) { idxFromTo = 0; idxToFrom = 2; }
        else if (f.contains("владимир") && t.contains("сергей")) { idxFromTo = 1; idxToFrom = 4; }
        else if (f.contains("галина") && t.contains("владимир")) { idxFromTo = 2; idxToFrom = 0; }
        else if (f.contains("галина") && t.contains("сергей")) { idxFromTo = 3; idxToFrom = 5; }
        else if (f.contains("сергей") && t.contains("владимир")) { idxFromTo = 4; idxToFrom = 1; }
        else if (f.contains("сергей") && t.contains("галина")) { idxFromTo = 5; idxToFrom = 3; }

        if (idxFromTo == -1) return;

        if ("START".equalsIgnoreCase(action)) {
            sliderMatrix[idxFromTo] = 1; // Инициатор -> OUTGOING
            sliderMatrix[idxToFrom] = 2; // Получатель -> INCOMING
        }
        else if ("ACCEPT".equalsIgnoreCase(action)) {
            sliderMatrix[idxFromTo] = 3; // Оба -> CONNECTED
            sliderMatrix[idxToFrom] = 3;
        }
        else if ("REJECT".equalsIgnoreCase(action) || "EXIT".equalsIgnoreCase(action)) {
            sliderMatrix[idxFromTo] = 0; // Сброс -> IDLE
            sliderMatrix[idxToFrom] = 0;
        }

        logSliderMatrix("СЕТЬ:" + action);
    }

    // 3. Центральный Мозг пересчета графики и звука
    private void syncInterfaceAndAudioWithMatrix() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int stateSliderOne = 0;
                int stateSliderTwo = 0;

                String me = (currentLoggedInRole != null) ? currentLoggedInRole.trim().toLowerCase() : "unknown";

                // Вытаскиваем состояния НАШИХ ползунков
                if (me.contains("владимир")) {
                    stateSliderOne = sliderMatrix[0]; // ВГ
                    stateSliderTwo = sliderMatrix[1]; // ВС
                } else if (me.contains("галина")) {
                    stateSliderOne = sliderMatrix[2]; // ГВ
                    stateSliderTwo = sliderMatrix[3]; // ГС
                } else if (me.contains("сергей")) {
                    stateSliderOne = sliderMatrix[4]; // СВ
                    stateSliderTwo = sliderMatrix[5]; // СГ
                }

                // Красим ползунки
                updateSliderUIByState(seekContactOne, stateSliderOne);
                updateSliderUIByState(seekContactTwo, stateSliderTwo);

                // =========================================================================
                // ОБНОВЛЕНИЕ ТЕКСТА (ВХОДЯЩИЙ ВЫЗОВ ИЛИ ПРИВЕТСТВИЕ)
                // =========================================================================
                String callerName = null;
                if (me.contains("владимир")) {
                    if (stateSliderOne == 2) callerName = "Галина";
                    else if (stateSliderTwo == 2) callerName = "Сергей";
                } else if (me.contains("галина")) {
                    if (stateSliderOne == 2) callerName = "Владимир";
                    else if (stateSliderTwo == 2) callerName = "Сергей";
                } else if (me.contains("сергей")) {
                    if (stateSliderOne == 2) callerName = "Владимир";
                    else if (stateSliderTwo == 2) callerName = "Галина";
                }

                if (welcomeText != null) {
                    if (callerName != null) {
                        welcomeText.setText("🚨 ВХОДЯЩИЙ ВЫЗОВ ОТ: " + getGenitiveName(callerName));
                    } else {
                        // Если входящих нет (уже идет разговор, сброс или покой) — возвращаем стандартное приветствие
                        welcomeText.setText("Привет, " + currentLoggedInRole + "!");
                    }
                }

                // Логируем ВСЮ матрицу для проверки её реального наполнения
                Log.d("AUDIO2_SERVICE", "[МАТРИЦА СЫРЫЕ ДАННЫЕ] " + java.util.Arrays.toString(sliderMatrix));

                // =========================================================================
                // ГОЛОС (КОДЕКИ)
                // =========================================================================
                boolean needVoice = (stateSliderOne == 3 || stateSliderTwo == 3);
                if (needVoice) {
                    // ИСПОЛЬЗУЕМ isSfxRunning для мгновенной защиты от двойного запуска
                    if (!isSfxRunning) {
                        Log.d("AUDIO2_SERVICE", "[МОЗГ ГОЛОС] Беседа активна. Включаем аудио.");
                        startVoiceCommunication("Room_One");
                    }
                } else {
                    if (isSfxRunning) {
                        Log.d("AUDIO2_SERVICE", "[МОЗГ ГОЛОС] Беседа завершена. Выключаем аудио.");
                        stopAudio();
                    }
                }
            }
        });
    }

    private void updateSliderUIByState(SeekBar seekBar, int state) {
        if (seekBar == null) return;
        switch (state) {
            case 0:
                seekBar.setTag(COLOR_NEUTRAL);
                setSliderTrackColor(seekBar, COLOR_NEUTRAL);
                seekBar.setProgress(0);
                break;
            case 1:
                seekBar.setTag(COLOR_OUTGOING);
                setSliderTrackColor(seekBar, COLOR_OUTGOING);
                seekBar.setProgress(100);
                break;
            case 2:
                seekBar.setTag(COLOR_INCOMING);
                setSliderTrackColor(seekBar, COLOR_INCOMING);
                seekBar.setProgress(50);
                break;
            case 3:
                seekBar.setTag(COLOR_ACTIVE);
                setSliderTrackColor(seekBar, COLOR_ACTIVE);
                seekBar.setProgress(100);
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //Log.d(TAG, "[АКТИВИТИ] Выполняется onResume. Регистрируем incomingCallReceiver...");

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("com.pectinworld.intercom.INCOMING_CALL"); // 1. Нам звонят
        filter.addAction("com.pectinworld.intercom.CALL_ACCEPTED");  // 2. Наш вызов приняли (Лимонный)
        filter.addAction("com.pectinworld.intercom.CALL_REJECTED");  // 3. Наш вызов отклонили (Сброс в желтый)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(incomingCallReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(incomingCallReceiver, filter);
            }
            //Log.d(TAG, "[АКТИВИТИ] Ресивер incomingCallReceiver со всеми экшенами успешно зарегистрирован.");
        } catch (Exception e) {
            //Log.e(TAG, "[АКТИВИТИ] Ошибка при регистрации ресивера", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(incomingCallReceiver);
            //Log.d(TAG, "[АКТИВИТИ] Ресивер отписан в onPause.");
        } catch (Exception e) {
            //Log.e(TAG, "[АКТИВИТИ] Ошибка при отписке ресивера", e);
        }
    }

    @Override
    protected void onDestroy() {
        stopAudio();
        super.onDestroy();
    }
}
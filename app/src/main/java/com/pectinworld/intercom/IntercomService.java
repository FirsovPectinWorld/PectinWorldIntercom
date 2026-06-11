package com.pectinworld.intercom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.io.ByteArrayOutputStream; // ВОТ ЭТОГО НЕ ХВАТАЛО
import java.util.Arrays;

public class IntercomService extends Service {
    private static final String TAG = "AUDIO2_SERVICE";
    private static final String CHANNEL_ID = "IntercomServiceChannel";

    private boolean isRunning = false;
    private SSLSocket tcpSocket;
    private DataInputStream dis;
    private DataOutputStream dos;

    private String cachedUserRole = "Unknown";

    // Константы сервера (перенеси свои актуальные данные сюда)
    private static final String SERVER_HOST = "90.171.130.20"; // Твой IP сервера
    private static final int SERVER_PORT = 64738;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Сервис создан");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand вызван");
        if (intent != null && intent.hasExtra("USER_ROLE_EXTRA")) {
            cachedUserRole = intent.getStringExtra("USER_ROLE_EXTRA");
        } else {
            // На всякий случай подстраховка: если интент пустой (система перезапустила сервис),
            // читаем напрямую из ПРАВИЛЬНОГО файла настроек MainActivity
            android.content.SharedPreferences servicePrefs = getSharedPreferences("PectinWorldPrefs", MODE_PRIVATE);
            cachedUserRole = servicePrefs.getString("UserRole", "Unknown");
        }

        try {
            // Показываем постоянное уведомление, переводя сервис в статус Foreground
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Интерком PectinWorld")
                    .setContentText("Приложение активно и ожидает вызовы")
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call) // Системная иконка трубки
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            // Запуск в режиме переднего плана
            startForeground(101, notification);
            Log.d(TAG, "startForeground успешно выполнен");

            if (!isRunning) {
                isRunning = true;
                startNetworkLoop();
            }

        } catch (Exception e) {
            // ЕСЛИ ТУТ ИДЕТ СБОЙ — МЫ УВИДИМ ЕГО В LOGCAT КРАСНЫМ ЦВЕТОМ!
            Log.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА ИНИЦИАЛИЗАЦИИ СЕРВИСА В onStartCommand", e);
        }

        return START_STICKY;
    }

    private void startNetworkLoop() {
        new Thread(() -> {
            // Поток пинга запускаем ОДИН раз на всю жизнь сервиса
            new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(15000);
                        // Проверяем, что сокет не просто существует, но и физически открыт
                        if (dos != null && tcpSocket != null && !tcpSocket.isClosed() && tcpSocket.isConnected()) {
                            synchronized (dos) {
                                dos.writeShort(13); // Ping
                                dos.writeInt(0);
                                dos.flush();
                            }
                            Log.d(TAG, "--> Отправлен фоновый Ping для поддержания соединения");
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка отправки фонового Ping: " + e.getMessage());
                    }
                }
            }).start();

            // ЦИКЛ АВТОПОДДКЛЮЧЕНИЯ: если вылетит ошибка, мы вернемся сюда и подключимся заново!
            while (isRunning) {
                try {
                    Log.d(TAG, "Подключение к серверу в фоновом режиме...");

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

                    // Отправка пакета Version (Тип 0)
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

                    Log.d(TAG, "Пакет Version отправлен. Вход в цикл ожидания пакетов...");

                    // ВНУТРЕННИЙ ЦИКЛ ЧТЕНИЯ СЛУШАЕТ СЕРВЕР ПОКА СОКЕТ ЖИВ
                    while (isRunning && tcpSocket != null && !tcpSocket.isClosed()) {
                        int msgType = dis.readUnsignedShort();
                        int msgLen = dis.readInt();

                        byte[] msgBody = new byte[msgLen];
                        dis.readFully(msgBody);

                        try {
                            if (msgType == 0) { // Authenticate
                                String username;
                                switch (cachedUserRole) {
                                    case "Владимир": username = "Vladimir_Service"; break;
                                    case "Галина": username = "Galina_Service"; break;
                                    case "Сергей": username = "Sergey_Service"; break;
                                    default: username = "Unknown_Service";
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
                                Log.d(TAG, "Валидный пакет Authenticate отправлен из сервиса для: " + username);
                            }
                            else if (msgType == 11) { // TextMessage (Команды управления)
                                String incomingText = new String(msgBody, "UTF-8");
                                if (incomingText.contains("COMMAND_CALL_START")) {
                                    String callerName = "Галина";
                                    if (incomingText.contains(":")) callerName = incomingText.split(":")[1];
                                    Intent callIntent = new Intent("INTERCOM_EVENT");
                                    callIntent.putExtra("action", "INCOMING_CALL");
                                    callIntent.putExtra("caller", callerName);
                                    sendBroadcast(callIntent);
                                }
                                else if (incomingText.contains("COMMAND_EXIT")) {
                                    Intent exitIntent = new Intent("INTERCOM_EVENT");
                                    exitIntent.putExtra("action", "STOP_AUDIO");
                                    sendBroadcast(exitIntent);
                                }
                            }
                            else if (msgType == 13) { // Входящий Ping
                                synchronized (dos) {
                                    dos.writeShort(13);
                                    dos.writeInt(msgLen);
                                    dos.write(msgBody);
                                    dos.flush();
                                }
                            }
                            else if (msgType == 15) { // CryptSetup
                                byte[] keyReply = new byte[32];
                                Arrays.fill(keyReply, (byte) 0);
                                synchronized (dos) {
                                    dos.writeShort(15);
                                    dos.writeInt(keyReply.length);
                                    dos.write(keyReply);
                                    dos.flush();
                                }
                            }
                            else if (msgType == 1) { // Аудио
                                Intent audioIntent = new Intent("INTERCOM_AUDIO");
                                audioIntent.putExtra("body", msgBody);
                                sendBroadcast(audioIntent);
                            }
                            else if (msgType == 5) { // ServerSync
                                Log.d(TAG, "=== ServerSync === Синхронизация успешна.");
                                Intent syncIntent = new Intent("INTERCOM_EVENT");
                                syncIntent.putExtra("action", "SERVER_CONNECTED");
                                sendBroadcast(syncIntent);
                            }
                        } catch (Exception internalPackEx) {
                            Log.e(TAG, "Ошибка обработки пакета: " + internalPackEx.getMessage());
                        }
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Соединение разорвано или ошибка сокета: " + e.getMessage());
                } finally {
                    // Если вышли из цикла чтения (сервер закрыл сокет), аккуратно подчищаем старые стримы
                    try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}
                    dos = null;
                    dis = null;
                }

                // ПАУЗА ПЕРЕД СЛЕДУЮЩЕЙ ПОПЫТКОЙ ПОДКЛЮЧЕНИЯ (чтобы не спамить сервер в случае отсутствия сети)
                if (isRunning) {
                    try {
                        Log.d(TAG, "Ожидание 5 секунд перед восстановлением связи...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Канал Интеркома",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void writeVarIntStream(ByteArrayOutputStream os, int value) {
        long val = value & 0xFFFFFFFFL;
        if ((val & 0xFFFFFF80L) == 0L) {
            os.write((int) val);
        } else {
            while ((val & 0xFFFFFF80L) != 0L) {
                os.write((int) ((val & 0x7FL) | 0x80L));
                val >>>= 7;
            }
            os.write((int) val);
        }
    }

    private void writeVarIntLongStream(ByteArrayOutputStream os, long value) {
        long val = value;
        if ((val & 0xFFFFFF80L) == 0L) {
            os.write((int) val);
        } else {
            while ((val & 0xFFFFFF80L) != 0L) {
                os.write((int) ((val & 0x7FL) | 0x80L));
                val >>>= 7;
            }
            os.write((int) val);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        // Закрываем сокеты при уничтожении сервиса
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}
        Log.d(TAG, "Сервис уничтожен");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
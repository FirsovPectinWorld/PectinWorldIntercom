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
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class IntercomService extends Service {
    private static final String TAG = "AUDIO2_SERVICE";
    private static final String CHANNEL_ID = "IntercomServiceChannel";

    private android.media.Ringtone currentRingtone;
    private android.app.NotificationManager notificationManager;

    public static final String ACTION_STOP_CALL_EFFECTS = "com.pectinworld.intercom.STOP_CALL_EFFECTS";

    private boolean isRunning = false;
    private SSLSocket tcpSocket;
    private DataInputStream dis;
    private DataOutputStream dos;

    private String cachedUserRole = "Unknown";

    private static final String SERVER_HOST = "90.171.130.20";
    private static final int SERVER_PORT = 64738;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        notificationManager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Регистрируем ресивер только для остановки звуков
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_STOP_CALL_EFFECTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(callCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(callCommandReceiver, filter);
        }
    }

    private final android.content.BroadcastReceiver callCommandReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP_CALL_EFFECTS.equals(intent.getAction())) {
                //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова - 1.");
                stopCallEffects();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Log.d(TAG, "onStartCommand вызван");

        if (intent != null) {
            // 1. Вытаскиваем action из интента, чтобы компилятор больше не ругался
            String action = intent.getAction();

            // 2. Сначала проверяем, не прилетел ли интент на отправку EXIT
            if ("com.pectinworld.intercom.SEND_EXIT".equals(action)) {
                String target = intent.getStringExtra("TARGET_NAME");
                sendExitCommandDirectly(target);
            }

            // 3. Отдельно проверяем, прислали ли нам обновление роли
            if (intent.hasExtra("USER_ROLE_EXTRA")) {
                cachedUserRole = intent.getStringExtra("USER_ROLE_EXTRA");
            } else if (cachedUserRole == null) {
                // Если роли нет в интенте и она ещё не кэширована, берём из настроек
                android.content.SharedPreferences servicePrefs = getSharedPreferences("PectinWorldPrefs", MODE_PRIVATE);
                cachedUserRole = servicePrefs.getString("UserRole", "Unknown");
            }
        } else if (cachedUserRole == null) {
            // Если интент пришёл null (сервис перезапустился системой), восстанавливаем роль
            android.content.SharedPreferences servicePrefs = getSharedPreferences("PectinWorldPrefs", MODE_PRIVATE);
            cachedUserRole = servicePrefs.getString("UserRole", "Unknown");
        }

        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Интерком PectinWorld")
                    .setContentText("Приложение активно и ожидает вызовы")
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();

            startForeground(101, notification);
            //Log.d(TAG, "startForeground успешно выполнен");

            if (!isRunning) {
                isRunning = true;
                startNetworkLoop();
            }

        } catch (Exception e) {
            //Log.e(TAG, "КРИТИЧЕСКАЯ ОШИБКА ИНИЦИАЛИЗАЦИИ СЕРВИСА В onStartCommand", e);
        }

        return START_STICKY;
    }

    private void startNetworkLoop() {
        new Thread(() -> {
            // Отдельный поток для Пинга
            new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(15000);
                        if (dos != null && tcpSocket != null && !tcpSocket.isClosed() && tcpSocket.isConnected()) {
                            synchronized (dos) {
                                dos.writeShort(13);
                                dos.writeInt(0);
                                dos.flush();
                            }
                            //Log.d(TAG, "--> Отправлен фоновый Ping для поддержания соединения");
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        //Log.e(TAG, "Ошибка отправки фонового Ping: " + e.getMessage());
                    }
                }
            }).start();

            while (isRunning) {
                try {
                    //Log.d(TAG, "Подключение к серверу в фоновом режиме...");

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

                    ByteArrayOutputStream vOs = new ByteArrayOutputStream();
                    vOs.write(0x08); writeVarIntStream(vOs, 66047);
                    vOs.write(0x28); writeVarIntLongStream(vOs, 281496451547136L);
                    vOs.write(0x12); byte[] relBytes = "1.5.0".getBytes("UTF-8"); writeVarIntStream(vOs, relBytes.length); vOs.write(relBytes);
                    vOs.write(0x1A); byte[] osBytes = "Win32".getBytes("UTF-8"); writeVarIntStream(vOs, osBytes.length); vOs.write(osBytes);
                    vOs.write(0x22); byte[] osv = "Android".getBytes("UTF-8"); writeVarIntStream(vOs, osv.length); vOs.write(osv);
                    byte[] verBody = vOs.toByteArray();

                    dos.writeShort(0);
                    dos.writeInt(verBody.length);
                    dos.write(verBody);
                    dos.flush();

                    //Log.d(TAG, "Пакет Version отправлен. Вход в цикл ожидания пакетов...");

                    while (isRunning && tcpSocket != null && !tcpSocket.isClosed()) {
                        int msgType = dis.readUnsignedShort();
                        int msgLen = dis.readInt();

                        byte[] msgBody = new byte[msgLen];
                        dis.readFully(msgBody);

                        try {
                            if (msgType == 0) {
                                String username;
                                switch (cachedUserRole) {
                                    case "Владимир": username = "Vladimir_Service"; break;
                                    case "Галина": username = "Galina_Service"; break;
                                    case "Сергей": username = "Sergey_Service"; break;
                                    default: username = "Unknown_Service";
                                }

                                String serverPassword = "PectinWorldIntercom1970";
                                ByteArrayOutputStream aOs = new ByteArrayOutputStream();

                                aOs.write(0x0A); byte[] uBytes = username.getBytes("UTF-8"); writeVarIntStream(aOs, uBytes.length); aOs.write(uBytes);
                                aOs.write(0x12); byte[] pBytes = serverPassword.getBytes("UTF-8"); writeVarIntStream(aOs, pBytes.length); aOs.write(pBytes);
                                aOs.write(0x1A); byte[] tBytes = serverPassword.getBytes("UTF-8"); writeVarIntStream(aOs, tBytes.length); aOs.write(tBytes);

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
                                //Log.d(TAG, "Валидный пакет Authenticate отправлен из сервиса для: " + username);
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
                                Intent audioIntent = new Intent("INTERCOM_AUDIO");
                                audioIntent.putExtra("body", msgBody);
                                sendBroadcast(audioIntent);
                            }
                            else if (msgType == 5) {
                                //Log.d(TAG, "=== ServerSync === Синхронизация успешна.");
                                Intent syncIntent = new Intent("INTERCOM_EVENT");
                                syncIntent.putExtra("action", "SERVER_CONNECTED");
                                sendBroadcast(syncIntent);
                            }
                            else if (msgType == 9) { // Текстовое сообщение (Mumble TextMessage)
                                //Log.d(TAG, "=== СЕРВИС: Получен пакет TextMessage (Тип 9) ===");
                                String incomingText = "";
                                //Log.d(TAG, "Размер тела пакета Тип 9: " + msgBody.length + " байт");

                                try {
                                    int idx = 0;
                                    while (idx < msgBody.length) {
                                        int key = msgBody[idx++] & 0xFF;
                                        int wireType = key & 0x07;
                                        int tag = key >> 3;

                                        if (wireType == 0) { // Varint
                                            while ((msgBody[idx++] & 0x80) != 0) {}
                                        } else if (wireType == 2) { // Length-delimited (Строки/Массивы)
                                            int len = 0;
                                            int shift = 0;
                                            while (true) {
                                                int b = msgBody[idx++] & 0xFF;
                                                len |= (b & 0x7F) << shift;
                                                if ((b & 0x80) == 0) break;
                                                shift += 7;
                                            }

                                            if (idx + len <= msgBody.length) {
                                                // Извлекаем абсолютно любую строку, пришедшую в поле text
                                                String extractedStr = new String(msgBody, idx, len, "UTF-8").trim();
                                                //Log.d(TAG, "Найден текстовый блок (Тег " + tag + "): '" + extractedStr + "'");

                                                if (!extractedStr.isEmpty()) {
                                                    incomingText = extractedStr;
                                                }
                                            }
                                            idx += len;
                                        } else if (wireType == 5) { // 32-bit
                                            idx += 4;
                                        } else if (wireType == 1) { // 64-bit
                                            idx += 8;
                                        } else {
                                            break;
                                        }
                                    }

                                } catch (Exception e) {
                                    //Log.e(TAG, "Ошибка безопасного извлечения текста из пакета 9", e);
                                }

                                if (!incomingText.isEmpty()) {

                                    //String myRole = (cachedUserRole != null) ? cachedUserRole.trim() : "Unknown";

                                    // === НАЧАЛО ОБРАБОТКИ ВСЕХ КОМАНД ВЫЗОВА В СЕРВИСЕ ===
                                    String incomingCmd = null;
                                    String addressData = null;

                                    Log.d(TAG, "[СЕТЬ РАЗГОВОР] Прилетел пакет: " + incomingText);

                                    if (incomingText.contains("COMMAND_CALL_START:")) {
                                        incomingCmd = "START";
                                        addressData = incomingText.replace("COMMAND_CALL_START:", "").trim();
                                    } else if (incomingText.contains("COMMAND_CALL_ACCEPT:")) {
                                        incomingCmd = "ACCEPT";
                                        //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова - 5.");
                                        stopCallEffects();
                                        addressData = incomingText.replace("COMMAND_CALL_ACCEPT:", "").trim();
                                    } else if (incomingText.contains("COMMAND_CALL_REJECT:")) {
                                        incomingCmd = "REJECT";
                                        //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова - 6.");
                                        stopCallEffects();
                                        addressData = incomingText.replace("COMMAND_CALL_REJECT:", "").trim();
                                    } else if (incomingText.contains("COMMAND_EXIT:")) {
                                        // КТО-ТО ПОВЕСИЛ ТРУБКУ ИЛИ СБРОСИЛ РАЗГОВОР
                                        incomingCmd = "EXIT";
                                        addressData = incomingText.replace("COMMAND_EXIT:", "").trim();
                                    } else if (incomingText.contains("-") && !incomingText.contains(":")) {
                                        incomingCmd = "START";
                                        addressData = incomingText.trim();
                                    }

                                    if (incomingCmd != null && addressData != null) {
                                        String sender = "";
                                        String receiver = "";
                                        boolean isTargetMe = false;
                                        String myRole = (cachedUserRole != null) ? cachedUserRole.trim() : "Unknown";

                                        // Для команд START, ACCEPT, REJECT у нас формат "От-Кому"
                                        if (addressData.contains("-")) {
                                            String[] parts = addressData.split("-");
                                            if (parts.length == 2) {
                                                sender = parts[0].trim();
                                                receiver = parts[1].trim();
                                                isTargetMe = myRole.equalsIgnoreCase(receiver);
                                            }
                                        } else {
                                            // Для COMMAND_EXIT у нас формат просто "ИмяОтрезавшегося" (например, "Владимир")
                                            // Если этот пакет пришел НЕ от меня, значит, это мой собеседник вышел из связи!
                                            sender = addressData.trim();
                                            isTargetMe = !myRole.equalsIgnoreCase(sender);
                                        }

                                        //Log.d(TAG, "[СЕРВИС] Команда: " + incomingCmd + " | От: " + sender + " | Предназначено мне: " + isTargetMe + " | Я: " + myRole);
                                        Log.d(TAG, "[ТРЕКЕР EXIT] Команда: " + incomingCmd + " | Извлечен Sender: '" + sender + "' | Receiver: '" + receiver + "' | Моя роль в сервисе: '" + myRole + "' | Итог isTargetMe: " + isTargetMe);
                                        if (isTargetMe) {
                                            if (incomingCmd.equals("START")) {
                                                //Log.d(TAG, "[СЕРВИС] Нам звонят. Стреляем бродкастом INCOMING_CALL.");
                                                Intent callIntent = new Intent("com.pectinworld.intercom.INCOMING_CALL");
                                                callIntent.putExtra("CALLER_NAME", sender);
                                                callIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                                sendBroadcast(callIntent);

                                                showIncomingCallNotification(sender);
                                                //Log.d(TAG, "ВЫЗЫВАЕМ playNotificationSound()");
                                                playNotificationSound();

                                            } else if (incomingCmd.equals("ACCEPT")) {
                                                //Log.d(TAG, "[СЕРВИС] Наш вызов приняли! Стреляем бродкастом CALL_ACCEPTED.");
                                                Intent acceptIntent = new Intent("com.pectinworld.intercom.CALL_ACCEPTED");
                                                acceptIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                                sendBroadcast(acceptIntent);


                                                //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова. - 2");
                                                stopCallEffects();

                                            } else if (incomingCmd.equals("REJECT") || incomingCmd.equals("EXIT")) {
                                                // И REJECT (отказ на этапе вызова), и EXIT (сброс во время разговора)
                                                // делают для нас одно и то же — закрывают сессию связи
                                                //Log.d(TAG, "[СЕРВИС] Собеседник завершил связь (" + incomingCmd + "). Стреляем CALL_REJECTED.");
                                                Intent rejectIntent = new Intent("com.pectinworld.intercom.CALL_REJECTED");
                                                rejectIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                                                sendBroadcast(rejectIntent);

                                                //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова. - 3");
                                                stopCallEffects();
                                            }
                                        }
                                    }
                                    // === КОНЕЦ ОБРАБОТКИ КОМАНД СЕРВИСОМ ===

                                    // 3. ПРОЧИЕ СЕРВИСНЫЕ ИЛИ ТЕКСТОВЫЕ ПАКЕТЫ
                                    else {
                                        //Log.d(TAG, "[ИНФО] Получен пакет общего типа (игнорируем или логируем): " + incomingText);
                                    }
                                }
                            }
                        } catch (Exception internalPackEx) {
                            //Log.e(TAG, "Ошибка обработки пакета: " + internalPackEx.getMessage());
                        }
                    }

                } catch (Exception e) {
                    //Log.e(TAG, "Соединение разорвано или ошибка сокета: " + e.getMessage());
                } finally {
                    try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}
                    dos = null;
                    dis = null;
                }

                if (isRunning) {
                    try {
                        //Log.d(TAG, "Ожидание 5 секунд перед восстановлением связи...");
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }).start();
    }

    public void sendExitCommandDirectly(String targetName) {
        new Thread(() -> {
            try {
                if (dos != null && tcpSocket != null && !tcpSocket.isClosed() && tcpSocket.isConnected()) {
                    String myRole = (cachedUserRole != null) ? cachedUserRole.trim() : "Unknown";
                    String callMessage = "COMMAND_EXIT:" + myRole + "-" + targetName;
                    byte[] msgStringBytes = callMessage.getBytes("UTF-8");

                    ByteArrayOutputStream txOs = new ByteArrayOutputStream();
                    txOs.write(0x18); txOs.write(1); // channel_id = 1
                    txOs.write(0x2A);
                    // Используем твой стандартный varint для длины
                    int length = msgStringBytes.length;
                    while ((length & 0xFFFFFF80) != 0L) {
                        txOs.write((length & 0x7F) | 0x80);
                        length >>>= 7;
                    }
                    txOs.write(length & 0x7F);
                    txOs.write(msgStringBytes);

                    byte[] txBody = txOs.toByteArray();

                    // Отправляем пакет типа 11 через живой synchronized стрим сервиса
                    synchronized (dos) {
                        dos.writeShort(11);
                        dos.writeInt(txBody.length);
                        dos.write(txBody);
                        dos.flush();
                    }
                    Log.d(TAG, "[СЕРВИС] Пакет EXIT успешно отправлен напрямую: " + callMessage);
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка прямой отправки EXIT: " + e.getMessage());
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

    private void showIncomingCallNotification(String callerName) {
        //Log.d(TAG, "==> [ЛОГ ВЫЗОВА] Метод showIncomingCallNotification ЗАПУЩЕН для: " + callerName);
        try {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.putExtra("LAUNCH_ACTION", "INCOMING_CALL");
            openIntent.putExtra("CALLER_NAME", callerName);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int pendingFlags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                    : android.app.PendingIntent.FLAG_UPDATE_CURRENT;

            android.app.PendingIntent openPendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    1001,
                    openIntent,
                    pendingFlags
            );

            Intent answerIntent = new Intent(this, MainActivity.class);
            answerIntent.putExtra("LAUNCH_ACTION", "INCOMING_CALL");
            answerIntent.putExtra("CALLER_NAME", callerName);
            answerIntent.putExtra("AUTO_ANSWER", true);
            answerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            android.app.PendingIntent answerPendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    1002,
                    answerIntent,
                    pendingFlags
            );

            // КРИТИЧЕСКИ ВАЖНО: Меняем ID канала на абсолютно новый (например, V3),
            // так как старые ID каналов Android кэширует в системе и не меняет их важность из кода!
            String callChannelId = "IntercomHeadsUpChannel_V3";
            NotificationManager manager = getSystemService(NotificationManager.class);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                //Log.d(TAG, "[ЛОГ ВЫЗОВА] Создание абсолютно нового канала V3...");
                NotificationChannel callChannel = new NotificationChannel(
                        callChannelId,
                        "Входящие вызовы (Интерком)",
                        NotificationManager.IMPORTANCE_HIGH // Принудительный Heads-up баннер
                );
                callChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                callChannel.enableVibration(true);
                callChannel.enableLights(true);

                if (manager != null) {
                    manager.createNotificationChannel(callChannel);
                    //Log.d(TAG, "[ЛОГ ВЫЗОВА] Канал V3 успешно зарегистрирован.");
                }
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, callChannelId)
                    // Меняем иконку на стандартную иконку приложения, она никогда не заблокируется системой
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle("📞 Входящий вызов")
                    .setContentText("Вас вызывает: " + callerName)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(openPendingIntent)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_menu_call, "ОТВЕТИТЬ", answerPendingIntent);

            if (manager != null) {
                //Log.d(TAG, "[ЛОГ ВЫЗОВА] Публикация баннера через manager.notify(2)...");
                manager.notify(2, builder.build());
                //Log.d(TAG, "[ЛОГ ВЫЗОВА] Баннер успешно отправлен в систему.");
            }

        } catch (Exception e) {
            //Log.e(TAG, "[ЛОГ ВЫЗОВА] Ошибка создания всплывающего баннера", e);
        }
    }

    // Метод для остановки звука и скрытия баннера
    private void stopCallEffects() {
        //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова.");
        try {
            if (currentRingtone != null && currentRingtone.isPlaying()) {
                currentRingtone.stop();
                //Log.d(TAG, "[СЕРВИС] Рингтон остановлен.");
            }
            if (notificationManager != null) {
                notificationManager.cancel(2); // ID 2 — это ID твоего баннера входящего вызова
                //Log.d(TAG, "[СЕРВИС] Баннер уведомления удален.");
            }
        } catch (Exception e) {
            //Log.e(TAG, "Ошибка при остановке эффектов вызова", e);
        }
    }

    private void playNotificationSound() {
        try {
            android.net.Uri alert = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
            if (alert == null) {
                alert = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            }

            if (alert != null) {
                currentRingtone = android.media.RingtoneManager.getRingtone(getApplicationContext(), alert);
                if (currentRingtone != null) {
                    currentRingtone.play();
                    //Log.d(TAG, "Звуковой сигнал вызова успешно запущен.");
                }
            }
        } catch (Exception e) {
            //Log.e(TAG, "Не удалось воспроизвести звуковой сигнал звонка", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        try {unregisterReceiver(callCommandReceiver);} catch (Exception e) {}
        //Log.d(TAG, "[СЕРВИС] Получена команда на остановку эффектов вызова. - 4");
        stopCallEffects();
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception e) {}

        //Log.d(TAG, "Сервис уничтожен");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
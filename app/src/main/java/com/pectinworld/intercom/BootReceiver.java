package com.pectinworld.intercom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Проверяем, что пришло именно сообщение о завершении загрузки устройства
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            Log.d("UploadTest", "[BOOT] Устройство загрузилось! Запускаем PectinWorld Intercom...");

            // Создаем интент для запуска главного окна приложения
            Intent activityIntent = new Intent(context, MainActivity.class);
            // Флаг NEW_TASK обязателен, если мы запускаем Activity не из другой Activity
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                context.startActivity(activityIntent);
                Log.d("UploadTest", "[BOOT] Главное окно успешно вызвано.");
            } catch (Exception e) {
                Log.e("UploadTest", "[BOOT] Ошибка при запуске после перезагрузки: " + e.getMessage());
            }
        }
    }
}
package com.pectinworld.intercom;

import android.util.Log;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.File;
import java.io.IOException;

public class FileUploader {

    private static final String TAG = "UploadTest";

    // Создаем интерфейс для передачи результата обратно в интерфейс приложения
    public interface UploadCallback {
        void onSuccess();
        void onError(String errorMsg);
    }

    // Добавили аргумент UploadCallback callback
    public static void uploadFileToServer(File file, String originalFileName, String targets, UploadCallback callback) {
        String serverUrl = "http://95.214.62.90:8080/upload";

        // Увеличим таймауты для больших файлов (например, для 300 МБ)
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                .build();

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("targets", targets)
                .addFormDataPart("file", originalFileName,
                        RequestBody.create(MediaType.parse("application/octet-stream"), file))
                .build();

        Request request = new Request.Builder()
                .url(serverUrl)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "❌ Ошибка при отправке файла: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.d(TAG, "✅ Файл успешно загружен!");
                    if (callback != null) callback.onSuccess();
                } else {
                    Log.e(TAG, "❌ Сервер вернул ошибку: " + response.code());
                    if (callback != null) callback.onError("Код сервера " + response.code());
                }
            }
        });
    }
}
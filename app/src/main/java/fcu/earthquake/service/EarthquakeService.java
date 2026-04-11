package fcu.earthquake.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import fcu.earthquake.MainActivity;
import fcu.earthquake.R;
import fcu.earthquake.model.EarthquakeData;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class EarthquakeService extends Service {
    private static final String TAG = "EarthquakeService";
    private static final String CHANNEL_ID = "EarthquakeChannel";
    private OkHttpClient client;
    private WebSocket webSocket;
    private FusedLocationProviderClient fusedLocationClient;

    private double epiDistance; // 儲存計算後的震央距離

    @Override
    public void onCreate() {
        super.onCreate();
        client = new OkHttpClient();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        startForeground(1, getStickyNotification("系統監測中..."));
        connectWebSocket();
    }

    private void connectWebSocket() {
        Request request = new Request.Builder().url("ws://10.0.2.2:8080").build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                EarthquakeData data = new Gson().fromJson(text, EarthquakeData.class);
                if ("earthquake".equals(data.getType())) {
                    processEarthquake(data);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> connectWebSocket(), 5000);
            }
        });
    }

    private void processEarthquake(EarthquakeData data) {
        // 權限檢查以避免 SecurityException
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission missing");
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            double uLat = (location != null) ? location.getLatitude() : 25.0330;
            double uLon = (location != null) ? location.getLongitude() : 121.5654;

            // 1. 先計算強度 (此方法內會計算距離並存儲到 epiDistance)
            int intensity = calculateLocalIntensity(data, uLat, uLon);
            
            // 2. 計算倒數 (直接讀取存儲的距離，並修正時鐘同步導致的 61 秒問題)
            // 使用 3.5 km/s 作為 S 波傳遞速度
            int seconds = calculateArrivalSeconds(data, uLat, uLon, 3.5);

            showNotification(intensity, seconds);
            
            // 通知 Activity (如果已開啟)
            Intent intent = new Intent("EARTHQUAKE_EVENT");
            intent.putExtra("data", new Gson().toJson(data));
            sendBroadcast(intent);
        });
    }

    private void showNotification(int intensity, int seconds) {
        String content = String.format("%d級地震，%d秒後抵達", intensity, seconds);
        NotificationManager nm = getSystemService(NotificationManager.class);
        
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("地震預警")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        nm.notify(2, notification);
    }

    private int calculateLocalIntensity(EarthquakeData data, double uLat, double uLon) {
        // 核心邏輯：在此計算一次距離並儲存，避免重複運算
        this.epiDistance = calculateEpiDistance(data.getLatitude(), data.getLongitude(), uLat, uLon);
        
        double dHypoLocal = Math.sqrt(Math.pow(this.epiDistance, 2) + Math.pow(data.getDepthKm(), 2));
        double localPga = data.getPgaGal() * Math.pow(data.getDepthKm() / dHypoLocal, 2);
        
        if (localPga < 0.8) return 0;
        if (localPga < 2.5) return 1;
        if (localPga < 8.0) return 2;
        if (localPga < 25.0) return 3;
        if (localPga < 80.0) return 4;
        if (localPga < 140.0) return 5;
        return 6;
    }

    public int calculateArrivalSeconds(EarthquakeData data, double userLat, double userLon, double waveSpeedKmPerSec) {
        double epiDistance = calculateEpiDistance(
                data.getLatitude(),
                data.getLongitude(),
                userLat,
                userLon
        );

        double dHypo = Math.sqrt(epiDistance * epiDistance + data.getDepthKm() * data.getDepthKm());

        return (int) Math.ceil(dHypo / waveSpeedKmPerSec);
//        this.arrivalSeconds = (int) Math.ceil(dHypo / waveSpeedKmPerSec);
    }

    private double calculateEpiDistance(double latE, double lonE, double latU, double lonU) {
        double dLat = Math.toRadians(latU - latE);
        double dLon = Math.toRadians(lonU - lonE);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(latE)) * Math.cos(Math.toRadians(latU)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Earthquake Monitoring", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    private Notification getStickyNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("地震預警系統")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

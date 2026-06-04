package fcu.earthquake.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
    public static final String ACTION_EARTHQUAKE = "EARTHQUAKE_EVENT";
    public static final String ACTION_CONNECTION = "CONNECTION_STATUS";
    public static boolean isServiceConnected = false;
    
    // 儲存最新的地震資訊與計算結果供全域同步
    public static EarthquakeData lastEarthquakeData = null;
    public static int lastIntensity = -1;
    public static int lastRemainingSeconds = -1;
    public static long lastUpdateTimeMillis = 0;
    
    private OkHttpClient client;
    private Gson gson;
    private WebSocket webSocket;
    private FusedLocationProviderClient fusedLocationClient;
    private MediaPlayer mediaPlayer;

    private double epiDistance; // 儲存計算後的震央距離

    @Override
    public void onCreate() {
        super.onCreate();
        client = new OkHttpClient();
        gson = new Gson();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, getStickyNotification("系統監測中..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, getStickyNotification("系統監測中..."));
        }
        connectWebSocket();
    }

    private void connectWebSocket() {
        Request request = new Request.Builder().url("ws://127.0.0.1:8080").build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket Connected");
                broadcastConnectionStatus(true);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "Received message: " + text);
                try {
                    EarthquakeData data = gson.fromJson(text, EarthquakeData.class);
                    if (data != null && data.getType() != null && data.getType().equalsIgnoreCase("earthquake")) {
                        processEarthquake(data);
                    } else {
                        Log.w(TAG, "Received unknown message type: " + (data != null ? data.getType() : "null"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "JSON parsing error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket Failure: " + t.getMessage());
                broadcastConnectionStatus(false);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> connectWebSocket(), 5000);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                broadcastConnectionStatus(false);
            }
        });
    }

    private void broadcastConnectionStatus(boolean connected) {
        isServiceConnected = connected;
        Intent intent = new Intent(ACTION_CONNECTION);
        intent.setPackage(getPackageName());
        intent.putExtra("connected", connected);
        sendBroadcast(intent);
    }

    private void processEarthquake(EarthquakeData data) {
        Log.d(TAG, "Processing earthquake: " + data.getLocation());
        lastEarthquakeData = data; // 儲存資料
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted in Service");
            // 就算沒權限，也用預設位置跑，確保功能不中斷
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            Log.d(TAG, "Got last location: " + (location != null ? location.getLatitude() + "," + location.getLongitude() : "null"));
            double uLat = (location != null) ? location.getLatitude() : 25.0330;
            double uLon = (location != null) ? location.getLongitude() : 121.5654;

            int intensity = calculateLocalIntensity(data, uLat, uLon);
            int seconds = calculateArrivalSeconds(data, 3.5);
            
            lastEarthquakeData = data;
            lastIntensity = intensity;
            lastRemainingSeconds = seconds;
            lastUpdateTimeMillis = System.currentTimeMillis();
            
            Log.d(TAG, "Calculated Intensity: " + intensity + ", Seconds: " + seconds);

            showNotification(intensity, seconds);
            playAnnouncement(intensity, seconds);
            
            Intent intent = new Intent(ACTION_EARTHQUAKE);
            intent.setPackage(getPackageName());
            intent.putExtra("data", new Gson().toJson(data));
            intent.putExtra("intensity", intensity);
            intent.putExtra("seconds", seconds);
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast sent for earthquake");

            // 啟動全螢幕警告視窗 (Whoscall 模式)
            launchWarningWindow(data, intensity, seconds);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get location", e);
            // 失敗也用預設跑
            double uLat = 25.0330;
            double uLon = 121.5654;
            int intensity = calculateLocalIntensity(data, uLat, uLon);
            int seconds = calculateArrivalSeconds(data, 3.5);
            showNotification(intensity, seconds);
            playAnnouncement(intensity, seconds);
            Intent intent = new Intent(ACTION_EARTHQUAKE);
            intent.setPackage(getPackageName());
            intent.putExtra("data", new Gson().toJson(data));
            intent.putExtra("intensity", intensity);
            intent.putExtra("seconds", seconds);
            sendBroadcast(intent);
        });
    }

    private void launchWarningWindow(EarthquakeData data, int intensity, int seconds) {
        Intent intent = new Intent(this, fcu.earthquake.WarningActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT 
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("data", new Gson().toJson(data));
        intent.putExtra("intensity", intensity);
        intent.putExtra("seconds", seconds);
        startActivity(intent);
    }

    /**
     * 更新推播通知文字，使其包含口語化的「幾十幾秒」
     */
    private void showNotification(int intensity, int seconds) {
        String intensityText = convertToChineseNumber(intensity);
        String secondsText = convertToChineseNumber(seconds);
        
        String content = String.format("%s級地震，%s秒後抵達", intensityText, secondsText);
        
        NotificationManager nm = getSystemService(NotificationManager.class);
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("地震預警")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(2, notification);
    }

    /**
     * 將數字轉換為中文口語文字 (例如: 57 -> 五十七, 12 -> 十二)
     */
    private String convertToChineseNumber(int n) {
        if (n == 0) return "零";
        String[] units = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        
        StringBuilder sb = new StringBuilder();
        
        if (n >= 100) {
            int h = n / 100;
            sb.append(units[h]).append("百");
            n %= 100;
            if (n > 0 && n < 10) sb.append("零");
        }
        
        if (n >= 10) {
            int t = n / 10;
            // 處理「十二」而不是「一十二」
            if (t > 1 || sb.length() > 0) sb.append(units[t]);
            sb.append("十");
            n %= 10;
        }
        
        if (n > 0) {
            sb.append(units[n]);
        }
        
        return sb.toString();
    }

    private int calculateLocalIntensity(EarthquakeData data, double uLat, double uLon) {
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

    public int calculateArrivalSeconds(EarthquakeData data, double waveSpeedKmPerSec) {
        double dHypo = Math.sqrt(this.epiDistance * this.epiDistance + data.getDepthKm() * data.getDepthKm());
        double totalTravelTime = dHypo / waveSpeedKmPerSec;

        String[] formats = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss"
        };

        for (String format : formats) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.TAIWAN);
                Date originDate = sdf.parse(data.getOriginTime());
                if (originDate != null) {
                    long t0 = originDate.getTime();
                    long tNow = System.currentTimeMillis();
                    double elapsed = (tNow - t0) / 1000.0;
                    int remainSec = (int) Math.round(totalTravelTime - elapsed);
                    Log.d(TAG, "Parsed time with format " + format + ", remainSec: " + remainSec);
                    return Math.max(0, Math.min((int) Math.ceil(totalTravelTime), remainSec));
                }
            } catch (Exception e) {
                // Try next format
            }
        }
        
        Log.w(TAG, "All time formats failed for: " + data.getOriginTime());
        return (int) Math.ceil(totalTravelTime);
    }

    private double calculateEpiDistance(double latE, double lonE, double latU, double lonU) {
        double dLat = Math.toRadians(latU - latE);
        double dLon = Math.toRadians(lonU - lonE);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(latE)) * Math.cos(Math.toRadians(latU)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void playAnnouncement(int intensity, int seconds) {
        List<Integer> resIds = new ArrayList<>();
        resIds.addAll(getNumberResources(intensity));
        resIds.add(R.raw.intensity);
        resIds.addAll(getNumberResources(seconds));
        resIds.add(R.raw.second_arrive);
        playVoiceSequence(resIds);
    }

    private void playVoiceSequence(List<Integer> resIds) {
        if (resIds == null || resIds.isEmpty()) return;
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> playNext(resIds, 0));
    }

    private void playNext(List<Integer> resIds, int index) {
        if (index >= resIds.size()) return;
        mediaPlayer = MediaPlayer.create(this, resIds.get(index));
        if (mediaPlayer == null) {
            playNext(resIds, index + 1);
            return;
        }
        mediaPlayer.setOnCompletionListener(mp -> {
            mp.release();
            playNext(resIds, index + 1);
        });
        mediaPlayer.start();
    }

    private List<Integer> getNumberResources(int n) {
        List<Integer> res = new ArrayList<>();
        if (n <= 0) {
            res.add(R.raw.zero);
            return res;
        }
        if (n >= 100) {
            int hundreds = (n / 100);
            if (hundreds == 2) res.add(R.raw.two_hundred);
            else res.add(R.raw.one_hundred);
            n %= 100;
        }
        if (n == 0) return res;

        if (n >= 20) {
            // 20, 21, 30...
            res.add(getTensRes((n / 10) * 10)); // 播放 "二", "三"...
            int units = n % 10;
            if (units == 0) {
                res.add(R.raw.ten); // 如果是 30, 補上 "十"
            } else {
                res.add(getXUnitsRes(units)); // 如果是 32, 補上 "十二" (組合起來就是 三+十二 = 三十二)
            }
        } else if (n >= 11) {
            // 11-19: 直接用 x1...x9 (十一...十九)
            res.add(getXUnitsRes(n - 10));
        } else if (n == 10) {
            res.add(R.raw.ten);
        } else {
            res.add(getUnitsRes(n));
        }
        return res;
    }

    private int getUnitsRes(int n) {
        switch (n) {
            case 1: return R.raw.one;
            case 2: return R.raw.two;
            case 3: return R.raw.three;
            case 4: return R.raw.four;
            case 5: return R.raw.five;
            case 6: return R.raw.six;
            case 7: return R.raw.seven;
            case 8: return R.raw.eight;
            case 9: return R.raw.nine;
            default: return R.raw.zero;
        }
    }

    private int getXUnitsRes(int n) {
        switch (n) {
            case 1: return R.raw.x1;
            case 2: return R.raw.x2;
            case 3: return R.raw.x3;
            case 4: return R.raw.x4;
            case 5: return R.raw.x5;
            case 6: return R.raw.x6;
            case 7: return R.raw.x7;
            case 8: return R.raw.x8;
            case 9: return R.raw.x9;
            default: return R.raw.x1;
        }
    }

    private int getTensRes(int n) {
        switch (n) {
            case 10: return R.raw.ten;
            case 20: return R.raw.twenty;
            case 30: return R.raw.thirty;
            case 40: return R.raw.fourty;
            case 50: return R.raw.fifty;
            case 60: return R.raw.sixty;
            case 70: return R.raw.seventy;
            case 80: return R.raw.eighty;
            case 90: return R.raw.ninety;
            default: return R.raw.ten;
        }
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceConnected = false;
        if (webSocket != null) webSocket.close(1000, "Service destroyed");
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

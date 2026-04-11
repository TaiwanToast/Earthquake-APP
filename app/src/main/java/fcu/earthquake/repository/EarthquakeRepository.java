package fcu.earthquake.repository;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;

import fcu.earthquake.model.EarthquakeData;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class EarthquakeRepository {
    private static final String TAG = "EarthquakeRepository";
    private final OkHttpClient client;
    private final Gson gson;
    private final MutableLiveData<EarthquakeData> earthquakeData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private WebSocket webSocket;
    private String currentUrl;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean shouldReconnect = true;

    public EarthquakeRepository() {
        client = new OkHttpClient();
        gson = new Gson();
    }

    public void startWebSocket(String url) {
        this.currentUrl = url;
        this.shouldReconnect = true;
        connect();
    }

    private void connect() {
        if (!shouldReconnect) return;
        
        Request request = new Request.Builder().url(currentUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                isConnected.postValue(true);
                Log.d(TAG, "WebSocket Connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    EarthquakeData data = gson.fromJson(text, EarthquakeData.class);
                    if ("earthquake".equals(data.getType())) {
                        earthquakeData.postValue(data);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing WebSocket message", e);
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                isConnected.postValue(false);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                isConnected.postValue(false);
                Log.e(TAG, "WebSocket failure: " + t.getMessage());
                if (shouldReconnect) {
                    reconnectHandler.postDelayed(() -> connect(), 5000); // Retry every 5s
                }
            }
        });
    }

    public LiveData<EarthquakeData> getEarthquakeData() {
        return earthquakeData;
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    public void stopWebSocket() {
        shouldReconnect = false;
        reconnectHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "App closed");
        }
    }
}

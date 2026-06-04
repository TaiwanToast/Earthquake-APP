package fcu.earthquake.viewmodel;

import android.app.Application;
import android.media.MediaPlayer;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import fcu.earthquake.R;
import fcu.earthquake.model.EarthquakeData;

public class EarthquakeViewModel extends AndroidViewModel {
    private static final String TAG = "EarthquakeViewModel";
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<EarthquakeData> earthquakeData = new MutableLiveData<>();
    private final MutableLiveData<Integer> countdown = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> showSafetyCheck = new MutableLiveData<>(false);
    private final MutableLiveData<List<ReportPoint>> reportPoints = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> userCity = new MutableLiveData<>("載入中...");
    private final MutableLiveData<Integer> localIntensity = new MutableLiveData<>(0);
    private Thread countdownThread;
    private MediaPlayer mediaPlayer;

    public static class ReportPoint {
        public double lat;
        public double lon;
        public boolean isSafe;
        public ReportPoint(double lat, double lon, boolean isSafe) {
            this.lat = lat;
            this.lon = lon;
            this.isSafe = isSafe;
        }
    }

    public EarthquakeViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<EarthquakeData> getEarthquakeData() {
        return earthquakeData;
    }

    public void setEarthquakeData(EarthquakeData data) {
        earthquakeData.postValue(data);
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected.postValue(connected);
    }

    public LiveData<Integer> getCountdown() {
        return countdown;
    }

    public LiveData<Boolean> getShowSafetyCheck() {
        return showSafetyCheck;
    }

    public LiveData<List<ReportPoint>> getReportPoints() {
        return reportPoints;
    }
    
    public LiveData<String> getUserCity() {
        return userCity;
    }
    
    public LiveData<Integer> getLocalIntensity() {
        return localIntensity;
    }
    
    public void setLocalIntensity(int intensity) {
        localIntensity.postValue(intensity);
    }
    
    public void setUserCity(String city) {
        userCity.postValue(city);
    }

    public String getCurrentTaipeiTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        return sdf.format(new Date());
    }
    
    public void calculateLocalIntensity(EarthquakeData data, double userLat, double userLon) {
        // 直接使用已存儲的距離，避免重複計算
        double dEpi = this.calculateEpiDistance(data.getLatitude(), data.getLongitude(), userLat, userLon);
        double dHypoLocal = Math.sqrt(Math.pow(dEpi, 2) + Math.pow(data.getDepthKm(), 2));
        double dHypoEpicenter = data.getDepthKm();

        double localPga = data.getPgaGal() * Math.pow(dHypoEpicenter / dHypoLocal, 2);
        
        int intensity = 0;
        if (localPga < 0.8) intensity = 0;
        else if (localPga < 2.5) intensity = 1;
        else if (localPga < 8.0) intensity = 2;
        else if (localPga < 25.0) intensity = 3;
        else if (localPga < 80.0) intensity = 4;
        else if (localPga < 140.0) intensity = 5;
        else if (localPga < 250.0) intensity = 5;
        else if (localPga < 440.0) intensity = 6;
        else intensity = 7;

        localIntensity.postValue(intensity);
    }

    private double calculateEpiDistance(double latE, double lonE, double latU, double lonU) {
        double phiE = Math.toRadians(latE);
        double phiU = Math.toRadians(latU);
        double dPhi = Math.toRadians(latU - latE);
        double dLambda = Math.toRadians(lonU - lonE);
        double R = 6371.0; // km

        double a = Math.pow(Math.sin(dPhi / 2), 2)
                + Math.cos(phiE) * Math.cos(phiU)
                * Math.pow(Math.sin(dLambda / 2), 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public int calculateArrivalSeconds(EarthquakeData data, double userLat, double userLon, double waveSpeedKmPerSec) {
        double epiDistance = calculateEpiDistance(
                data.getLatitude(),
                data.getLongitude(),
                userLat,
                userLon
        );

        double dHypo = Math.sqrt(epiDistance * epiDistance + data.getDepthKm() * data.getDepthKm());
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
                    return Math.max(0, Math.min((int) Math.ceil(totalTravelTime), remainSec));
                }
            } catch (Exception e) {
                // Try next
            }
        }

        return (int) Math.ceil(totalTravelTime);
    }

    public void startCountdown(int seconds) {
        if (countdownThread != null && countdownThread.isAlive()) {
            countdownThread.interrupt();
        }

        countdown.setValue(seconds);
        countdownThread = new Thread(() -> {
            try {
                int current = seconds;
                while (current > 0) {
                    if (current <= 10) {
                        playVoice(current);
                    }
                    Thread.sleep(1000);
                    current--;
                    countdown.postValue(current);
                }
                playRawResource(R.raw.arrive);
                Thread.sleep(2000); 
                showSafetyCheck.postValue(true);
            } catch (InterruptedException e) {
            }
        });
        countdownThread.start();
    }

    private void playVoice(int second) {
        int resId;
        switch (second) {
            case 10: resId = R.raw.ten; break;
            case 9: resId = R.raw.nine; break;
            case 8: resId = R.raw.eight; break;
            case 7: resId = R.raw.seven; break;
            case 6: resId = R.raw.six; break;
            case 5: resId = R.raw.five; break;
            case 4: resId = R.raw.four; break;
            case 3: resId = R.raw.three; break;
            case 2: resId = R.raw.two; break;
            case 1: resId = R.raw.one; break;
            default: return;
        }
        playRawResource(resId);
    }

    private synchronized void playRawResource(int resId) {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaPlayer = MediaPlayer.create(getApplication(), resId);
        mediaPlayer.start();
    }

    public void onSafetyReported(boolean safe, double lat, double lon) {
        showSafetyCheck.setValue(false);
        List<ReportPoint> current = new ArrayList<>(reportPoints.getValue());
        current.add(new ReportPoint(lat, lon, safe));
        reportPoints.postValue(current);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (countdownThread != null) {
            countdownThread.interrupt();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
    }
}

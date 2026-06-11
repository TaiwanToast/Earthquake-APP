package fcu.earthquake;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.gson.Gson;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import fcu.earthquake.databinding.ActivityMainBinding;
import fcu.earthquake.model.EarthquakeData;
import fcu.earthquake.service.EarthquakeService;
import fcu.earthquake.viewmodel.EarthquakeViewModel;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    private EarthquakeViewModel viewModel;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private Marker earthquakeMarker;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location lastUserLocation;

    private final BroadcastReceiver earthquakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "earthquakeReceiver received broadcast!");
            String json = intent.getStringExtra("data");
            if (json != null) {
                EarthquakeData data = new Gson().fromJson(json, EarthquakeData.class);
                int intensity = intent.getIntExtra("intensity", -1);
                int seconds = intent.getIntExtra("seconds", -1);
                Log.d(TAG, "Broadcast data: intensity=" + intensity + ", seconds=" + seconds);
                handleEarthquakeUpdate(data, intensity, seconds);
            } else {
                Log.e(TAG, "Received broadcast but data is null");
            }
        }
    };

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            viewModel.setConnected(connected);
        }
    };

    private final BroadcastReceiver mapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String json = intent.getStringExtra("data");
            if (EarthquakeService.ACTION_MAP_UPDATE.equals(action)) {
                viewModel.updatePointsFromJson(json, false);
            } else if (EarthquakeService.ACTION_MAP_INIT.equals(action)) {
                viewModel.updatePointsFromJson(json, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(EarthquakeViewModel.class);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initMap();
        setupObservers();
        setupListeners();
        setupNavigation();
        if (hasRequiredPermissions()) {
            startLocationUpdates();
            startEarthquakeService();
        } else {
            requestRequiredPermissions();
        }

        checkOverlayPermission();
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請開啟懸浮視窗權限以顯示地震警報", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private boolean hasRequiredPermissions() {
        boolean fineLoc = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean postNotif = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotif = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return fineLoc && postNotif;
    }

    private void requestRequiredPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        ActivityCompat.requestPermissions(this, permissions, LOCATION_PERMISSION_REQUEST_CODE);
    }

    private void startEarthquakeService() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Intent serviceIntent = new Intent(this, EarthquakeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    private void initMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK);
        binding.mapView.setMultiTouchControls(true);
        binding.mapView.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        
        GeoPoint startPoint = new GeoPoint(23.6, 121.0);
        binding.mapView.getController().setZoom(7.5);
        binding.mapView.getController().setCenter(startPoint);
    }

    private void setupObservers() {
        viewModel.getIsConnected().observe(this, connected -> {
            if (connected) {
                binding.statusText.setText(R.string.status_connected);
                binding.statusIndicator.setBackgroundResource(R.drawable.bg_circle_green);
            } else {
                binding.statusText.setText(R.string.status_disconnected);
                binding.statusIndicator.setBackgroundResource(R.drawable.bg_circle_red);
            }
        });

        viewModel.getUserCity().observe(this, city -> {
            binding.cityTag.setText(city);
        });

        // 移除會導致無限迴圈與數值重置的 getEarthquakeData Observer
        // 地震資訊更新將統一由 BroadcastReceiver 觸發 handleEarthquakeUpdate

        viewModel.getLocalIntensity().observe(this, intensity -> {
            binding.intensityLargeText.setText(String.valueOf(intensity));
            if (intensity >= 4) {
                binding.intensityLargeText.setTextColor(getColor(R.color.warning_red));
            } else {
                binding.intensityLargeText.setTextColor(getColor(R.color.safe_green));
            }
        });

        viewModel.getCountdown().observe(this, seconds -> {
            binding.countdownLargeText.setText(String.valueOf(seconds));
        });

        viewModel.getShowSafetyCheck().observe(this, show -> {
            binding.safetyCheckScreen.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        viewModel.getShowHelpNeeded().observe(this, show -> {
            binding.helpNeededScreen.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        viewModel.getReportPoints().observe(this, points -> {
            if (points != null) {
                for (EarthquakeViewModel.ReportPoint p : points) {
                    Marker m = new Marker(binding.mapView);
                    m.setPosition(new GeoPoint(p.lat, p.lon));
                    m.setIcon(getDrawable(p.isSafe ? android.R.drawable.presence_online : android.R.drawable.presence_busy));
                    m.setTitle(p.isSafe ? "安全" : "需要協助");
                    binding.mapView.getOverlays().add(m);
                }
                binding.mapView.invalidate();
            }
        });
    }

    private void handleEarthquakeUpdate(EarthquakeData data, int intensity, int seconds) {
        Log.d(TAG, "handleEarthquakeUpdate: intensity=" + intensity + ", seconds=" + seconds);
        if (data != null) {
            runOnUiThread(() -> {
                binding.updateTimeText.setText("更新於 " + viewModel.getCurrentTaipeiTime());
                binding.publishTimeText.setText(data.getOriginTime() + " 發表");
                binding.statusBanner.setText(R.string.earthquake_warning);
                binding.statusBanner.setBackgroundColor(getColor(R.color.warning_red));
                
                updateMapMarker(data.getLatitude(), data.getLongitude(), data.getLocation());
                
                if (intensity != -1 && seconds != -1) {
                    viewModel.setLocalIntensity(intensity);
                    viewModel.startCountdown(seconds);
                } else {
                    double userLat = (lastUserLocation != null) ? lastUserLocation.getLatitude() : 25.0330;
                    double userLon = (lastUserLocation != null) ? lastUserLocation.getLongitude() : 121.5654;
                    viewModel.calculateLocalIntensity(data, userLat, userLon);
                    int remainingSeconds = viewModel.calculateArrivalSeconds(data, userLat, userLon, 3.5);
                    viewModel.startCountdown(remainingSeconds);
                }
                viewModel.setEarthquakeData(data);
            });
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        lastUserLocation = location;
                        updateCityFromLocation(location);
                    }
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                lastUserLocation = location;
                updateCityFromLocation(location);
            }
        });
    }

    private void updateCityFromLocation(Location location) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder service is not available");
            return;
        }
        new Thread(() -> {
            Geocoder geocoder = new Geocoder(this, Locale.TAIWAN);
            try {
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String city = addresses.get(0).getAdminArea();
                    if (city == null) city = addresses.get(0).getLocality();
                    if (city != null) {
                        viewModel.setUserCity(city);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Geocoder error: " + e.getMessage(), e);
            }
        }).start();
    }

    private void setupListeners() {
        binding.btnSafeLarge.setOnClickListener(v -> {
            double lat = (lastUserLocation != null) ? lastUserLocation.getLatitude() : 25.0330;
            double lon = (lastUserLocation != null) ? lastUserLocation.getLongitude() : 121.5654;
            viewModel.onSafetyReported(true, lat, lon);
            Toast.makeText(this, R.string.report_safe, Toast.LENGTH_SHORT).show();
            switchToHome();
        });

        binding.btnNeedHelpLarge.setOnClickListener(v -> {
            double lat = (lastUserLocation != null) ? lastUserLocation.getLatitude() : 25.0330;
            double lon = (lastUserLocation != null) ? lastUserLocation.getLongitude() : 121.5654;
            viewModel.onSafetyReported(false, lat, lon);
            Toast.makeText(this, R.string.report_need_help, Toast.LENGTH_LONG).show();
        });

        binding.btnHelpReceived.setOnClickListener(v -> {
            viewModel.onHelpReceived();
            switchToHome();
        });
    }

    private void setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                switchToHome();
                return true;
            } else if (id == R.id.nav_map) {
                switchToMap();
                return true;
            }
            return false;
        });
    }

    private void switchToHome() {
        binding.monitorView.setVisibility(View.VISIBLE);
        binding.mapView.setVisibility(View.GONE);
        binding.safetyCheckScreen.setVisibility(View.GONE);
        binding.helpNeededScreen.setVisibility(View.GONE);
    }

    private void switchToMap() {
        binding.monitorView.setVisibility(View.GONE);
        binding.mapView.setVisibility(View.VISIBLE);
        binding.safetyCheckScreen.setVisibility(View.GONE);
        binding.helpNeededScreen.setVisibility(View.GONE);
        viewModel.fetchServerMapData(); // 切換分頁時主動同步歷史資料
    }

    private void updateMapMarker(double lat, double lon, String location) {
        GeoPoint point = new GeoPoint(lat, lon);
        if (earthquakeMarker == null) {
            earthquakeMarker = new Marker(binding.mapView);
            binding.mapView.getOverlays().add(earthquakeMarker);
        }
        earthquakeMarker.setPosition(point);
        earthquakeMarker.setTitle(location);
        binding.mapView.getController().animateTo(point);
        binding.mapView.invalidate();
    }


    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter earthquakeFilter = new IntentFilter(EarthquakeService.ACTION_EARTHQUAKE);
        IntentFilter connectionFilter = new IntentFilter(EarthquakeService.ACTION_CONNECTION);
        IntentFilter mapFilter = new IntentFilter();
        mapFilter.addAction(EarthquakeService.ACTION_MAP_INIT);
        mapFilter.addAction(EarthquakeService.ACTION_MAP_UPDATE);

        // 使用 ContextCompat 註冊廣播接收器，會自動處理不同 Android 版本的標籤要求
        // 由於這些廣播僅在 App 內部傳遞，因此使用 RECEIVER_NOT_EXPORTED 以確保安全性
        ContextCompat.registerReceiver(this, earthquakeReceiver, earthquakeFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, connectionReceiver, connectionFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, mapReceiver, mapFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(earthquakeReceiver);
        unregisterReceiver(connectionReceiver);
        unregisterReceiver(mapReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startLocationUpdates();
                startEarthquakeService();
            } else {
                Toast.makeText(this, "需要位置與通知權限以運行預警服務", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        binding.mapView.onResume();
        // 主動同步一次連線狀態
        viewModel.setConnected(EarthquakeService.isServiceConnected);

        // 同步最新的地震資訊，確保從背景回來後資訊持續
        if (EarthquakeService.lastEarthquakeData != null) {
            EarthquakeData data = EarthquakeService.lastEarthquakeData;
            int intensity = EarthquakeService.lastIntensity;
            
            // 使用全域統一的基準時間計算剩餘秒數
            long timePassed = (System.currentTimeMillis() - EarthquakeService.lastUpdateTimeMillis) / 1000;
            int remainingSeconds = (int) Math.max(0, EarthquakeService.lastRemainingSeconds - timePassed);
            
            // 只有在地震尚未結束時才自動觸發更新介面
            if (remainingSeconds > 0) {
                handleEarthquakeUpdate(data, intensity, remainingSeconds);
            } else {
                // 如果地震已結束，僅顯示資訊但不啟動倒數
                viewModel.setEarthquakeData(data);
                viewModel.setLocalIntensity(intensity);
                binding.statusBanner.setText(R.string.no_earthquake_info);
                binding.statusBanner.setBackgroundColor(getColor(R.color.safe_green));
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        binding.mapView.onPause();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}

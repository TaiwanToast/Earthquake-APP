package fcu.earthquake;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.Gson;
import fcu.earthquake.databinding.ActivityWarningBinding;
import fcu.earthquake.model.EarthquakeData;

public class WarningActivity extends AppCompatActivity {
    private ActivityWarningBinding binding;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int seconds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 讓 Activity 可以在鎖屏上顯示並點亮螢幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        binding = ActivityWarningBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 從全域靜態 Model 取得統一資訊
        if (fcu.earthquake.service.EarthquakeService.lastEarthquakeData != null) {
            EarthquakeData data = fcu.earthquake.service.EarthquakeService.lastEarthquakeData;
            int intensity = fcu.earthquake.service.EarthquakeService.lastIntensity;

            // 計算從發震至今經過的時間差，同步所有介面
            long timePassed = (System.currentTimeMillis() - fcu.earthquake.service.EarthquakeService.lastUpdateTimeMillis) / 1000;
            seconds = (int) Math.max(0, fcu.earthquake.service.EarthquakeService.lastRemainingSeconds - (int)timePassed);
            
            binding.locationText.setText(data.getLocation());
            binding.intensityText.setText(String.valueOf(intensity));
            binding.countdownText.setText(String.valueOf(seconds));
        }
        
        binding.btnDismiss.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        startCountdown();
    }

    private void startCountdown() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seconds > 0) {
                    seconds--;
                    binding.countdownText.setText(String.valueOf(seconds));
                    handler.postDelayed(this, 1000);
                } else {
                    binding.countdownText.setText("0");
                    binding.warningTitle.setText("地震抵達");
                }
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}

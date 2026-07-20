/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2026 by Patrick Zedler
 */

package xyz.zedler.patrick.grocy.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import com.journeyapps.barcodescanner.BarcodeResult;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.databinding.ActivityKitchenBinding;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.scanner.ZXingScanCaptureManager;
import xyz.zedler.patrick.grocy.util.PictureUtil;
import xyz.zedler.patrick.grocy.viewmodel.KitchenViewModel;

public class KitchenActivity extends AppCompatActivity implements ZXingScanCaptureManager.BarcodeListener {

    private static final String TAG = "GROCY_KITCHEN";
    private static final String PREF_LED_ENABLED = "kitchen_led_enabled";
    private static final long BARCODE_DEBOUNCE_MS = 2000;

    private enum Mode {
        CONSUME, PURCHASE
    }

    private ActivityKitchenBinding binding;
    private KitchenViewModel viewModel;
    private Mode currentMode = Mode.CONSUME;
    private ZXingScanCaptureManager capture;
    private String lastProcessedBarcode;
    private long lastProcessedTime;
    private ToneGenerator toneGenerator;
    private SharedPreferences sharedPrefs;
    private final Handler resumeHandler = new Handler(Looper.getMainLooper());
    private Runnable resumeRunnable;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityKitchenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.getRoot().setKeepScreenOn(true);

        viewModel = new ViewModelProvider(this).get(KitchenViewModel.class);
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Initial state
        binding.toggleGroupMode.check(R.id.button_consume);

        binding.toggleGroupMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.button_consume) {
                    setMode(Mode.CONSUME);
                } else if (checkedId == R.id.button_purchase) {
                    setMode(Mode.PURCHASE);
                }
            }
        });

        binding.buttonPrintList.setOnClickListener(v ->
                Toast.makeText(this, R.string.kitchen_print_list_selected, Toast.LENGTH_SHORT).show()
        );

        binding.buttonGrocy.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });

        // LED Toggle (Default OFF, manual control only)
        boolean ledEnabled = sharedPrefs.getBoolean(PREF_LED_ENABLED, false);
        binding.buttonLed.setChecked(ledEnabled);
        binding.buttonLed.setOnClickListener(v -> {
            boolean checked = binding.buttonLed.isChecked();
            Log.d(TAG, "DIAG: LED toggle changed to: " + checked);
            sharedPrefs.edit().putBoolean(PREF_LED_ENABLED, checked).apply();
            setTorch(checked);
        });

        // Scanner setup
        capture = new ZXingScanCaptureManager(this, binding.barcodeView, this);
        binding.barcodeView.setStatusText("");

        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);

        setupObservers();
        updateModeText();
    }

    private void setupObservers() {
        viewModel.getScanStatusLive().observe(this, status -> binding.textScanStatus.setText(status));
        viewModel.getLastBarcodeLive().observe(this, barcode -> binding.textLastBarcode.setText(barcode));
        viewModel.getSuccessMessageLive().observe(this, msg -> binding.statusText.setText(msg));

        viewModel.getEventHandler().observeEvent(this, event -> {
            if (event.getType() == Event.CONSUME_SUCCESS) {
                playSuccessBeep();
                scheduleResume(800);
            } else if (event.getType() == Event.PURCHASE_SUCCESS) {
                playSuccessBeep();
                scheduleResume(800);
            } else if (event.getType() == Event.TRANSACTION_SUCCESS) {
                playCreationBong();
                scheduleResume(1200);
            } else if (event.getType() == Event.ERROR) {
                playFailureSound();
                scheduleResume(1200);
            } else if (event.getType() == Event.CONTINUE_SCANNING) {
                scheduleResume(1200);
            }
        });
    }

    private void scheduleResume(long delay) {
        if (isFinishing() || isDestroyed()) return;

        if (resumeRunnable != null) {
            resumeHandler.removeCallbacks(resumeRunnable);
        }

        resumeRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) {
                try {
                    Log.d(TAG, "DIAG: Resuming scanning. LED will remain: " + (binding.buttonLed.isChecked() ? "ON" : "OFF"));
                    capture.onResume();
                    capture.decode();
                    setTorch(binding.buttonLed.isChecked());
                    binding.textScanStatus.setText(R.string.kitchen_ready_to_scan);
                } catch (Exception e) {
                    Log.e(TAG, "Error resuming scanner", e);
                }
            }
        };
        resumeHandler.postDelayed(resumeRunnable, delay);
    }

    private void setTorch(boolean on) {
        try {
            if (on) {
                binding.barcodeView.setTorchOn();
            } else {
                binding.barcodeView.setTorchOff();
            }
        } catch (Exception e) {
            Log.w(TAG, "DIAG: Torch control failed: " + e.getMessage());
        }
    }

    private void playSuccessBeep() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200);
        }
    }

    private void playCreationBong() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 400);
        }
    }

    private void playFailureSound() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            capture.onResume();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    boolean shouldBeOn = sharedPrefs.getBoolean(PREF_LED_ENABLED, false);
                    Log.d(TAG, "DIAG: Camera ready. Restoring LED to: " + shouldBeOn);
                    setTorch(shouldBeOn);
                    capture.decode();
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        setTorch(false);
        if (resumeRunnable != null) {
            resumeHandler.removeCallbacks(resumeRunnable);
        }
        try {
            capture.onPause();
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause", e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        setTorch(false);
        if (resumeRunnable != null) {
            resumeHandler.removeCallbacks(resumeRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setTorch(false);
        if (resumeRunnable != null) {
            resumeHandler.removeCallbacks(resumeRunnable);
        }
        try {
            capture.onDestroy();
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        executor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        try {
            capture.onRequestPermissionsResult(requestCode, permissions, grantResults);
        } catch (Exception e) {
            Log.e(TAG, "Error in onRequestPermissionsResult", e);
        }
    }

    @Override
    public void onBarcodeResult(BarcodeResult result) {
        if (result != null && result.getText() != null && !result.getText().isEmpty()) {
            String barcode = result.getText();
            long currentTime = System.currentTimeMillis();

            if (currentMode == Mode.CONSUME || currentMode == Mode.PURCHASE) {
                if (viewModel.isBusy()) {
                    Log.d(TAG, "DIAG: Barcode ignored: request in progress");
                } else if (barcode.equals(lastProcessedBarcode) && (currentTime - lastProcessedTime < BARCODE_DEBOUNCE_MS)) {
                    Log.d(TAG, "DIAG: Barcode ignored: debounce active for " + barcode);
                    scheduleResume(BARCODE_DEBOUNCE_MS - (currentTime - lastProcessedTime));
                } else {
                    lastProcessedBarcode = barcode;
                    lastProcessedTime = currentTime;
                    
                    setTorch(false); // Off during processing to prevent glare

                    Bitmap bitmap = result.getBitmap();
                    if (bitmap != null) {
                        executor.execute(() -> {
                            byte[] bytes = PictureUtil.convertBitmapToByteArray(bitmap);
                            new Handler(Looper.getMainLooper()).post(() -> 
                                viewModel.lookupBarcode(barcode, bytes, currentMode == Mode.CONSUME)
                            );
                        });
                    } else {
                        viewModel.lookupBarcode(barcode, null, currentMode == Mode.CONSUME);
                    }
                }
            }
        } else {
            capture.decode();
        }
    }

    private void setMode(Mode mode) {
        currentMode = mode;
        updateModeText();
        binding.statusText.setText(R.string.kitchen_status_ready);
    }

    private void updateModeText() {
        String modeName = getString(currentMode == Mode.CONSUME
                ? R.string.kitchen_consume
                : R.string.kitchen_purchase);
        binding.textMode.setText(getString(R.string.kitchen_mode, modeName));
    }
}

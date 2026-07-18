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
import com.journeyapps.barcodescanner.BarcodeResult;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.databinding.ActivityKitchenBinding;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.scanner.ZXingScanCaptureManager;
import xyz.zedler.patrick.grocy.viewmodel.KitchenViewModel;

public class KitchenActivity extends AppCompatActivity implements ZXingScanCaptureManager.BarcodeListener {

    private static final String TAG = "GROCY_KITCHEN";
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
    private final Handler resumeHandler = new Handler(Looper.getMainLooper());
    private Runnable resumeRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityKitchenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.getRoot().setKeepScreenOn(true);

        viewModel = new ViewModelProvider(this).get(KitchenViewModel.class);

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
                // Success: schedule resume after 800ms
                scheduleResume(800);
            } else if (event.getType() == Event.CONTINUE_SCANNING) {
                // Known events that are not CONSUME_SUCCESS (Unknown barcode or API failure)
                // Schedule resume after 1200ms
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
                    capture.onResume(); // Unfreezes the preview
                    capture.decode();   // Restarts detection
                    binding.textScanStatus.setText(R.string.kitchen_ready_to_scan);
                } catch (Exception e) {
                    Log.e(TAG, "Error resuming scanner", e);
                }
            }
        };
        resumeHandler.postDelayed(resumeRunnable, delay);
    }

    private void playSuccessBeep() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            capture.onResume();
            scheduleResume(500);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
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
        if (resumeRunnable != null) {
            resumeHandler.removeCallbacks(resumeRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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

            if (currentMode == Mode.CONSUME) {
                if (Boolean.TRUE.equals(viewModel.getIsProcessingLive().getValue())) {
                    Log.d(TAG, "Barcode ignored: request in progress");
                } else if (barcode.equals(lastProcessedBarcode) && (currentTime - lastProcessedTime < BARCODE_DEBOUNCE_MS)) {
                    Log.d(TAG, "Barcode ignored: debounce active for " + barcode);
                    // Even if ignored, we must resume detection if it's currently frozen
                    scheduleResume(BARCODE_DEBOUNCE_MS - (currentTime - lastProcessedTime));
                } else {
                    lastProcessedBarcode = barcode;
                    lastProcessedTime = currentTime;
                    viewModel.processBarcode(barcode);
                }
            } else {
                binding.textLastBarcode.setText(barcode);
                binding.textScanStatus.setText(R.string.kitchen_barcode_received_purchase);
                // Unfreeze in PURCHASE mode too
                scheduleResume(1000);
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

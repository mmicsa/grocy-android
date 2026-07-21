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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import com.journeyapps.barcodescanner.BarcodeResult;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.IWoyouService;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.databinding.ActivityKitchenBinding;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.scanner.ZXingScanCaptureManager;
import xyz.zedler.patrick.grocy.util.PictureUtil;
import xyz.zedler.patrick.grocy.util.PrinterUtil;
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
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable resumeRunnable;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private IWoyouService printerService;
    private boolean isPrinterBound = false;
    private String currentPrinterError = null;

    private final ICallback printerCallback = new ICallback.Stub() {
        @Override
        public void onRunResult(boolean success) throws RemoteException {
            Log.d(TAG, "DIAG: Printer ICallback.onRunResult(success=" + success + ")");
            if (success) {
                if (currentPrinterError == null) {
                    uiHandler.post(() -> binding.statusText.setText("Printer command accepted"));
                }
            } else {
                uiHandler.post(() -> binding.statusText.setText("Printer error: Command execution failed"));
            }
        }

        @Override
        public void onReturnString(String result) throws RemoteException {
            Log.d(TAG, "DIAG: Printer ICallback.onReturnString(result=" + result + ")");
        }

        @Override
        public void onRaiseException(int code, String msg) throws RemoteException {
            Log.e(TAG, "DIAG: Printer ICallback.onRaiseException(code=" + code + ", msg=" + msg + ")");
            uiHandler.post(() -> binding.statusText.setText("Printer error " + code + ": " + msg));
        }

        @Override
        public void onPrintResult(int code, String msg) throws RemoteException {
            Log.d(TAG, "DIAG: Printer ICallback.onPrintResult(code=" + code + ", msg=" + msg + ")");
            if (code == 0) {
                if (currentPrinterError == null) {
                    uiHandler.post(() -> binding.statusText.setText("Printer command accepted"));
                }
            } else {
                uiHandler.post(() -> binding.statusText.setText("Printer error " + code + ": " + msg));
            }
        }
    };

    private final BroadcastReceiver printerStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "DIAG: Received Printer Broadcast: " + action);

            if ("woyou.aidlservice.jiuv5.OUT_OF_PAPER_ACTION".equals(action)) {
                currentPrinterError = getString(R.string.kitchen_printer_state_out_of_paper);
            } else if ("woyou.aidlservice.jiuv5.COVER_OPEN_ACTION".equals(action)) {
                currentPrinterError = getString(R.string.kitchen_printer_state_cover_open);
            } else if ("woyou.aidlservice.jiuv5.OVER_HEATING_ACITON".equals(action)) {
                currentPrinterError = getString(R.string.kitchen_printer_state_overheating);
            } else if ("woyou.aidlservice.jiuv5.ERROR_ACTION".equals(action)) {
                currentPrinterError = "Hardware failure";
            } else if ("woyou.aidlservice.jiuv5.NORMAL_ACTION".equals(action)) {
                currentPrinterError = null;
            }

            uiHandler.post(() -> {
                if (currentPrinterError != null) {
                    binding.statusText.setText(getString(R.string.kitchen_printer_error, currentPrinterError));
                } else {
                    binding.statusText.setText("Printer state: Normal");
                }
            });
        }
    };

    private final ServiceConnection printerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            printerService = IWoyouService.Stub.asInterface(service);
            isPrinterBound = true;
            Log.d(TAG, "Printer service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            printerService = null;
            isPrinterBound = false;
            Log.d(TAG, "Printer service disconnected");
        }
    };

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

        binding.buttonPrintList.setOnClickListener(v -> {
            if (viewModel.isBusy()) return;
            
            Drawable icon = binding.buttonPrintList.getIcon();
            if (icon instanceof AnimatedVectorDrawable) {
                ((AnimatedVectorDrawable) icon).start();
            }
            binding.statusText.setText(R.string.kitchen_status_fetching_list);
            
            viewModel.fetchShoppingList(itemNames -> {
                if (itemNames != null) {
                    printShoppingList(itemNames);
                } else {
                    binding.statusText.setText(R.string.kitchen_error_fetch_list);
                    if (icon instanceof AnimatedVectorDrawable) {
                        ((AnimatedVectorDrawable) icon).stop();
                    }
                }
            });
        });

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

        bindPrinterService();
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

    private void bindPrinterService() {
        Intent intent = new Intent();
        intent.setPackage("woyou.aidlservice.jiuiv5");
        intent.setAction("woyou.aidlservice.jiuiv5.IWoyouService");
        try {
            boolean result = bindService(intent, printerConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "DIAG: Printer bind result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind to printer service", e);
        }
    }

    private void printShoppingList(List<String> itemNames) {
        if (printerService == null) {
            binding.statusText.setText(getString(R.string.kitchen_printer_error, getString(R.string.kitchen_printer_unavailable)));
            return;
        }

        Drawable icon = binding.buttonPrintList.getIcon();
        executor.execute(() -> {
            try {
                Log.d(TAG, "DIAG: Binder call START -> updatePrinterState");
                int state = printerService.updatePrinterState();
                Log.d(TAG, "DIAG: Binder call END -> updatePrinterState: " + state);

                if (state >= 4 && state <= 9) {
                    currentPrinterError = getPrinterErrorName(state);
                    uiHandler.post(() -> {
                        binding.statusText.setText(getString(R.string.kitchen_printer_error, currentPrinterError));
                        if (icon instanceof AnimatedVectorDrawable) {
                            ((AnimatedVectorDrawable) icon).stop();
                        }
                    });
                    return;
                } else if (state != 1) {
                    Log.w(TAG, "Printer state inconclusive: " + state + "; attempting print anyway");
                }

                PrinterUtil.setBold(printerService, true);

                PrinterUtil.printHeading(printerService, "================================\n", null);
                PrinterUtil.printHeading(printerService, "         SHOPPING LIST          \n", null);
                PrinterUtil.printHeading(printerService, "================================\n", null);
                
                if (itemNames.isEmpty()) {
                    PrinterUtil.printBody(printerService, "\n(No items)\n", null);
                } else {
                    printerService.printText("\n", null);
                    for (String name : itemNames) {
                        PrinterUtil.printBody(printerService, "[ ] " + name + "\n\n", null);
                    }
                }

                String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new java.util.Date());
                
                printerService.printText("\n--------------------------------\n", null);
                PrinterUtil.printSmall(printerService, "Items: " + itemNames.size() + "\n", null);
                PrinterUtil.printSmall(printerService, "Printed: " + timestamp + "\n", null);
                PrinterUtil.printHeading(printerService, "================================\n", printerCallback);
                
                printerService.lineWrap(4, printerCallback);
                
                PrinterUtil.resetFormatting(printerService);

                uiHandler.post(() -> {
                    if (currentPrinterError == null) {
                        binding.statusText.setText(R.string.kitchen_status_list_printed);
                    }
                    if (icon instanceof AnimatedVectorDrawable) {
                        ((AnimatedVectorDrawable) icon).stop();
                    }
                });

            } catch (RemoteException e) {
                Log.e(TAG, "Printer Binder call failed", e);
                uiHandler.post(() -> {
                    binding.statusText.setText(getString(R.string.kitchen_printer_error, getString(R.string.kitchen_printer_remote_exception)));
                    if (icon instanceof AnimatedVectorDrawable) {
                        ((AnimatedVectorDrawable) icon).stop();
                    }
                });
            }
        });
    }

    private String getPrinterErrorName(int state) {
        switch (state) {
            case 1: return "Normal";
            case 2: return getString(R.string.kitchen_printer_state_updating);
            case 3: return getString(R.string.kitchen_printer_state_exception);
            case 4: return getString(R.string.kitchen_printer_state_out_of_paper);
            case 5: return getString(R.string.kitchen_printer_state_overheating);
            case 6: return getString(R.string.kitchen_printer_state_cover_open);
            case 7: return getString(R.string.kitchen_printer_state_cutter_abnormal);
            case 8: return getString(R.string.kitchen_printer_state_cutter_recovery);
            case 9: return getString(R.string.kitchen_printer_state_black_mark);
            default: return "Unknown (" + state + ")";
        }
    }

    private void scheduleResume(long delay) {
        if (isFinishing() || isDestroyed()) return;

        if (resumeRunnable != null) {
            uiHandler.removeCallbacks(resumeRunnable);
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
        uiHandler.postDelayed(resumeRunnable, delay);
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

        IntentFilter filter = new IntentFilter();
        filter.addAction("woyou.aidlservice.jiuv5.NORMAL_ACTION");
        filter.addAction("woyou.aidlservice.jiuv5.OUT_OF_PAPER_ACTION");
        filter.addAction("woyou.aidlservice.jiuv5.COVER_OPEN_ACTION");
        filter.addAction("woyou.aidlservice.jiuv5.ERROR_ACTION");
        filter.addAction("woyou.aidlservice.jiuv5.OVER_HEATING_ACITON");
        registerReceiver(printerStatusReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        setTorch(false);
        if (resumeRunnable != null) {
            uiHandler.removeCallbacks(resumeRunnable);
        }
        try {
            capture.onPause();
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause", e);
        }

        try {
            unregisterReceiver(printerStatusReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver not registered");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        setTorch(false);
        if (resumeRunnable != null) {
            uiHandler.removeCallbacks(resumeRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setTorch(false);
        if (resumeRunnable != null) {
            uiHandler.removeCallbacks(resumeRunnable);
        }
        if (isPrinterBound) {
            try {
                unbindService(printerConnection);
            } catch (Exception e) {
                Log.w(TAG, "Error unbinding printer service", e);
            }
            isPrinterBound = false;
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
            final String barcode = result.getText();
            final long currentTime = System.currentTimeMillis();
            final boolean isConsume = (currentMode == Mode.CONSUME);

            if (viewModel.isBusy()) {
                Log.d(TAG, "DIAG: Barcode ignored: request in progress");
            } else if (barcode.equals(lastProcessedBarcode) && (currentTime - lastProcessedTime < BARCODE_DEBOUNCE_MS)) {
                Log.d(TAG, "DIAG: Barcode ignored: debounce active for " + barcode);
                scheduleResume(BARCODE_DEBOUNCE_MS - (currentTime - lastProcessedTime));
            } else {
                lastProcessedBarcode = barcode;
                lastProcessedTime = currentTime;
                
                setTorch(false);

                Bitmap bitmap = result.getBitmap();
                if (bitmap != null) {
                    executor.execute(() -> {
                        byte[] bytes = PictureUtil.convertBitmapToByteArray(bitmap);
                        new Handler(Looper.getMainLooper()).post(() -> 
                            viewModel.lookupBarcode(barcode, bytes, isConsume)
                        );
                    });
                } else {
                    viewModel.lookupBarcode(barcode, null, isConsume);
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

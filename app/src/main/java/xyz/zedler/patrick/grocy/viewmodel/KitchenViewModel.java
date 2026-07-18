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

package xyz.zedler.patrick.grocy.viewmodel;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.repository.InventoryRepository;
import xyz.zedler.patrick.grocy.util.GrocycodeUtil;
import xyz.zedler.patrick.grocy.util.GrocycodeUtil.Grocycode;

public class KitchenViewModel extends BaseViewModel {

    private static final String TAG = "GROCY_KITCHEN";

    private final InventoryRepository repository;
    private final GrocyApi grocyApi;
    private final DownloadHelper dlHelper;

    private List<Product> products;
    private List<ProductBarcode> barcodes;

    private final MutableLiveData<String> scanStatusLive = new MutableLiveData<>();
    private final MutableLiveData<String> lastBarcodeLive = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isProcessingLive = new MutableLiveData<>(false);
    private final MutableLiveData<String> successMessageLive = new MutableLiveData<>();

    public KitchenViewModel(@NonNull Application application) {
        super(application);
        repository = new InventoryRepository(application);
        grocyApi = new GrocyApi(application);
        dlHelper = new DownloadHelper(application, TAG, isProcessingLive::setValue, getOfflineLive());
        loadData();
    }

    private void loadData() {
        repository.loadFromDatabase(data -> {
            this.products = data.getProducts();
            this.barcodes = data.getBarcodes();
        }, error -> onError(error, TAG));
    }

    public void processBarcode(String barcode) {
        if (Boolean.TRUE.equals(isProcessingLive.getValue())) {
            return;
        }

        lastBarcodeLive.setValue(barcode);
        scanStatusLive.setValue(getString(R.string.kitchen_looking_up));

        Product product = resolveProduct(barcode);
        if (product == null) {
            scanStatusLive.setValue(getString(R.string.kitchen_unknown_barcode));
            sendEvent(Event.CONTINUE_SCANNING);
            return;
        }

        consumeProduct(product, barcode);
    }

    private Product resolveProduct(String barcode) {
        if (products == null || barcodes == null) return null;

        Product product = null;
        Grocycode grocycode = GrocycodeUtil.getGrocycode(barcode);
        if (grocycode != null && grocycode.isProduct()) {
            product = Product.getProductFromId(products, grocycode.getObjectId());
        }

        if (product == null) {
            ProductBarcode productBarcode = ProductBarcode.getFromBarcode(barcodes, barcode);
            if (productBarcode != null) {
                product = Product.getProductFromId(products, productBarcode.getProductIdInt());
            }
        }
        return product;
    }

    private void consumeProduct(Product product, String barcode) {
        JSONObject body = new JSONObject();
        try {
            body.put("amount", "1");
            body.put("allow_subproduct_substitution", true);
        } catch (JSONException e) {
            Log.e(TAG, "consumeProduct: JSON error", e);
        }

        dlHelper.postWithArray(
            grocyApi.consumeProduct(product.getId()),
            body,
            response -> {
                Log.d(TAG, "Consumed product ID: " + product.getId() + ", Name: " + product.getName() + ", Barcode: " + barcode);
                successMessageLive.setValue(getString(R.string.kitchen_consumed_msg, product.getName()));
                sendEvent(Event.CONSUME_SUCCESS);
                sendEvent(Event.CONTINUE_SCANNING);
            },
            error -> {
                String errorMsg = error != null && error.getLocalizedMessage() != null 
                    ? error.getLocalizedMessage() 
                    : getString(R.string.error_network);
                scanStatusLive.setValue(getString(R.string.kitchen_consume_failed, errorMsg));
                sendEvent(Event.CONTINUE_SCANNING);
            }
        );
    }

    public MutableLiveData<String> getScanStatusLive() {
        return scanStatusLive;
    }

    public MutableLiveData<String> getLastBarcodeLive() {
        return lastBarcodeLive;
    }

    public MutableLiveData<Boolean> getIsProcessingLive() {
        return isProcessingLive;
    }

    public MutableLiveData<String> getSuccessMessageLive() {
        return successMessageLive;
    }

    @Override
    protected void onCleared() {
        dlHelper.destroy();
        super.onCleared();
    }
}

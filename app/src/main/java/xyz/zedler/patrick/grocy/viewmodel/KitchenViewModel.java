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
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import com.android.volley.VolleyError;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.model.ProductDetails;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.repository.InventoryRepository;
import xyz.zedler.patrick.grocy.util.DateUtil;

public class KitchenViewModel extends BaseViewModel {

    private static final String TAG = "GROCY_KITCHEN";
    private static final String UNKNOWN_PREFIX = "Unknown Product ";
    private static final Pattern UNKNOWN_PATTERN = Pattern.compile("^Unknown Product (\\d+)$", Pattern.CASE_INSENSITIVE);

    private final InventoryRepository repository;
    private final GrocyApi grocyApi;
    private final DownloadHelper dlHelper;
    private final DateUtil dateUtil;

    private List<Product> products;
    private List<Location> locations;
    private List<QuantityUnit> quantityUnits;

    private final MutableLiveData<String> scanStatusLive = new MutableLiveData<>();
    private final MutableLiveData<String> lastBarcodeLive = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isProcessingLive = new MutableLiveData<>(false);
    private final MutableLiveData<String> successMessageLive = new MutableLiveData<>();
    private boolean isCreatingUnknown = false;

    public KitchenViewModel(@NonNull Application application) {
        super(application);
        repository = new InventoryRepository(application);
        grocyApi = new GrocyApi(application);
        dlHelper = new DownloadHelper(application, TAG, isProcessingLive::setValue, getOfflineLive());
        dateUtil = new DateUtil(application);
        loadData();
    }

    private void loadData() {
        Log.d(TAG, "DIAG: Refreshing local cache from database...");
        repository.loadFromDatabase(data -> {
            this.products = data.getProducts();
            this.locations = data.getLocations();
            this.quantityUnits = data.getQuantityUnits();
            Log.d(TAG, "DIAG: Local cache refreshed. Products: " + (products != null ? products.size() : 0));
        }, error -> {
            Log.e(TAG, "DIAG: Failed to load local data", (Throwable) error);
            onError(error, TAG);
        });
    }

    public void lookupBarcode(String barcode, @Nullable byte[] pictureData, boolean isConsume) {
        if (isBusy()) {
            Log.d(TAG, "DIAG: Lookup request for [" + barcode + "] ignored because VM is busy.");
            return;
        }

        final String opId = String.valueOf(System.currentTimeMillis());
        lastBarcodeLive.setValue(barcode);
        scanStatusLive.setValue(getString(R.string.kitchen_looking_up));
        isProcessingLive.setValue(true);

        String url = grocyApi.getStockProductByBarcode(barcode);
        Log.d(TAG, "DIAG: [Op " + opId + "] Direct Barcode Lookup START. Endpoint: " + url);

        dlHelper.get(url, opId, response -> {
            isProcessingLive.setValue(false);
            try {
                Type type = new TypeToken<ProductDetails>() {}.getType();
                ProductDetails details = dlHelper.gson.fromJson(response, type);
                if (details != null && details.getProduct() != null) {
                    Product product = details.getProduct();
                    Log.d(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: FOUND. Product: " + product.getName() + " (ID: " + product.getId() + ")");
                    if (isConsume) {
                        consumeProduct(product, barcode);
                    } else {
                        purchaseProduct(product, barcode);
                    }
                } else {
                    Log.d(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: NOT_FOUND (Success but empty data). Starting unknown creation.");
                    processUnknownBarcode(opId, barcode, pictureData);
                }
            } catch (Exception e) {
                Log.e(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: SERVER_ERROR (Parse failed). Response: " + response, e);
                reportError(opId, "Parse Error during lookup");
            }
        }, error -> {
            isProcessingLive.setValue(false);
            int status = -1;
            String errorBody = "<empty>";
            String apiErrorMessage = "";
            if (error != null && error.networkResponse != null) {
                status = error.networkResponse.statusCode;
                if (error.networkResponse.data != null) {
                    errorBody = new String(error.networkResponse.data, StandardCharsets.UTF_8);
                    try {
                        JSONObject json = new JSONObject(errorBody);
                        apiErrorMessage = json.optString("error_message", "").trim().toLowerCase(Locale.ROOT);
                    } catch (Exception ignored) {}
                }
            }

            Log.d(TAG, "DIAG: [Op " + opId + "] Lookup ERROR status: " + status);
            Log.d(TAG, "DIAG: [Op " + opId + "] Raw Body: " + errorBody);
            Log.d(TAG, "DIAG: [Op " + opId + "] Parsed Message: [" + apiErrorMessage + "]");

            boolean isNotFound = (status == 400 || status == 404);

            if (isNotFound) {
                Log.d(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: NOT_FOUND (" + status + "). Starting unknown creation flow...");
                processUnknownBarcode(opId, barcode, pictureData);
            } else if (error != null && error.networkResponse == null) {
                Log.e(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: NETWORK_ERROR (No response). Message: " + error.getMessage());
                logNetworkError(opId, "Direct Lookup", url, error);
                reportError(opId, getString(R.string.error_network) + " (Lookup)");
            } else {
                Log.e(TAG, "DIAG: [Op " + opId + "] Lookup RESULT: SERVER_ERROR (" + status + ").");
                logNetworkError(opId, "Direct Lookup", url, error);
                reportError(opId, "Server Error (" + status + ") during lookup");
            }
        });
    }

    private void consumeProduct(Product product, String barcode) {
        JSONObject body = new JSONObject();
        try {
            body.put("amount", "1");
            body.put("allow_subproduct_substitution", true);
        } catch (JSONException e) {
            Log.e(TAG, "consumeProduct: JSON error", e);
        }

        Log.d(TAG, "Consume Start: Barcode [" + barcode + "], Product [" + product.getName() + "], ID [" + product.getId() + "]");
        dlHelper.postWithArray(
            grocyApi.consumeProduct(product.getId()),
            body,
            response -> {
                Log.d(TAG, "Consume Success: ID [" + product.getId() + "]");
                successMessageLive.setValue(getString(R.string.kitchen_consumed_msg, product.getName()));
                sendEvent(Event.CONSUME_SUCCESS);
                sendEvent(Event.CONTINUE_SCANNING);
            },
            error -> {
                String errorMsg = error != null && error.getLocalizedMessage() != null 
                    ? error.getLocalizedMessage() 
                    : getString(R.string.error_network);
                Log.e(TAG, "Consume Failure: " + errorMsg);
                scanStatusLive.setValue(getString(R.string.kitchen_consume_failed, errorMsg));
                sendEvent(Event.CONTINUE_SCANNING);
            }
        );
    }

    private void purchaseProduct(Product product, String barcode) {
        JSONObject body = new JSONObject();
        try {
            body.put("amount", "1");
        } catch (JSONException e) {
            Log.e(TAG, "purchaseProduct: JSON error", e);
        }

        Log.d(TAG, "Purchase Start: Barcode [" + barcode + "], Product [" + product.getName() + "], ID [" + product.getId() + "]");
        dlHelper.postWithArray(
            grocyApi.purchaseProduct(product.getId()),
            body,
            response -> {
                Log.d(TAG, "Purchase Success: ID [" + product.getId() + "]");
                successMessageLive.setValue(getString(R.string.kitchen_purchased_msg, product.getName()));
                sendEvent(Event.PURCHASE_SUCCESS);
                sendEvent(Event.CONTINUE_SCANNING);
            },
            error -> {
                String errorMsg = error != null && error.getLocalizedMessage() != null 
                    ? error.getLocalizedMessage() 
                    : getString(R.string.error_network);
                Log.e(TAG, "Purchase Failure: " + errorMsg);
                scanStatusLive.setValue(getString(R.string.kitchen_purchase_failed, errorMsg));
                sendEvent(Event.CONTINUE_SCANNING);
            }
        );
    }

    private void processUnknownBarcode(final String opId, final String barcode, @Nullable final byte[] pictureData) {
        if (isBusy()) return;

        Log.d(TAG, "DIAG: [Op " + opId + "] --- START UNKNOWN CREATION FLOW ---");

        Location kitchen = findKitchenLocation();
        if (kitchen == null) {
            Log.e(TAG, "DIAG: [Op " + opId + "] FAILED: 'Kitchen' location not found.");
            scanStatusLive.setValue(getString(R.string.kitchen_unknown_location));
            sendEvent(Event.CONTINUE_SCANNING);
            return;
        }

        QuantityUnit piece = findPieceUnit();
        if (piece == null) {
            Log.e(TAG, "DIAG: [Op " + opId + "] FAILED: 'Piece' unit not found.");
            scanStatusLive.setValue(getString(R.string.kitchen_unknown_unit));
            sendEvent(Event.CONTINUE_SCANNING);
            return;
        }

        fetchProductsAndCreate(opId, barcode, pictureData, kitchen, piece, false);
    }

    private void fetchProductsAndCreate(final String opId, final String barcode, @Nullable final byte[] pictureData, 
                                        final Location kitchen, final QuantityUnit piece, final boolean isRetry) {
        
        isCreatingUnknown = true;
        isProcessingLive.setValue(true);
        
        String url = grocyApi.getObjects(GrocyApi.ENTITY.PRODUCTS);
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 2: GET Fresh Products from server. URL: " + url + (isRetry ? " (RETRY)" : ""));
        
        dlHelper.get(url, opId, response -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Step 2 Success.");
            Type type = new TypeToken<List<Product>>() {}.getType();
            List<Product> freshProducts = dlHelper.gson.fromJson(response, type);
            Log.d(TAG, "DIAG: [Op " + opId + "] Fetched " + (freshProducts != null ? freshProducts.size() : 0) + " products for name generation.");
            
            String nextName = generateNextName(opId, freshProducts);
            createProduct(opId, barcode, pictureData, kitchen, piece, nextName, isRetry);
            
        }, error -> {
            logNetworkError(opId, "Step 2 (Fetch Products)", url, error);
            resetState();
            scanStatusLive.setValue(getString(R.string.error_network) + " (Fetch Products)");
            sendEvent(Event.CONTINUE_SCANNING);
        });
    }

    private String generateNextName(String opId, List<Product> freshProducts) {
        int nextNum = 1;
        if (freshProducts != null) {
            for (Product p : freshProducts) {
                if (p.getName() != null) {
                    Matcher m = UNKNOWN_PATTERN.matcher(p.getName());
                    if (m.matches()) {
                        Log.d(TAG, "DIAG: [Op " + opId + "] Matching Unknown Product found: " + p.getName());
                        try {
                            String group = m.group(1);
                            if (group != null) {
                                int num = Integer.parseInt(group);
                                if (num >= nextNum) nextNum = num + 1;
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        String name = UNKNOWN_PREFIX + String.format(Locale.ROOT, "%04d", nextNum);
        Log.d(TAG, "DIAG: [Op " + opId + "] Selected next name: " + name);
        return name;
    }

    private void createProduct(final String opId, final String barcode, @Nullable final byte[] pictureData, 
                               final Location kitchen, final QuantityUnit piece, final String productName, final boolean isRetry) {
        
        String timestamp = dateUtil.getLocalizedDate(DateUtil.getDateStringToday(), DateUtil.FORMAT_MEDIUM) 
                + " " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date());

        Product newProduct = new Product(getSharedPrefs());
        newProduct.setName(productName);
        newProduct.setDescription("Created in KitchenActivity at " + timestamp);
        newProduct.setLocationId(String.valueOf(kitchen.getId()));
        newProduct.setQuIdPurchase(piece.getId());
        newProduct.setQuIdStock(piece.getId());
        newProduct.setQuIdConsume(piece.getId());
        newProduct.setQuIdPrice(piece.getId());

        String url = grocyApi.getObjects(GrocyApi.ENTITY.PRODUCTS);
        JSONObject payload = newProduct.getJsonFromProduct(getSharedPrefs(), isDebuggingEnabled(), TAG);
        
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 3: POST Create Product [" + productName + "]. URL: " + url);
        Log.d(TAG, "DIAG: [Op " + opId + "] Body: " + payload.toString());
        
        dlHelper.post(url, payload, response -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Step 3 Success. Response: " + (response != null ? response.toString() : "<empty>"));
            try {
                int productId = response.getInt("created_object_id");
                safetyCheckBarcode(opId, productId, barcode, productName, pictureData);
            } catch (JSONException e) {
                Log.e(TAG, "DIAG: [Op " + opId + "] Step 3 FAILED: JSON Error", e);
                resetState();
                scanStatusLive.setValue(getString(R.string.error_undefined) + " (Create Product)");
                sendEvent(Event.CONTINUE_SCANNING);
            }
        }, error -> {
            logNetworkError(opId, "Step 3 (Create Product)", url, error);
            
            boolean isDuplicate = false;
            if (error != null && error.networkResponse != null) {
                if (error.networkResponse.statusCode == 400) {
                    String body = new String(error.networkResponse.data, StandardCharsets.UTF_8);
                    if (body.contains("UNIQUE constraint failed") || body.toLowerCase().contains("name already exists")) {
                        isDuplicate = true;
                    }
                }
            }
            
            if (isDuplicate && !isRetry) {
                Log.w(TAG, "DIAG: [Op " + opId + "] Collision detected for name [" + productName + "]. Retrying once...");
                fetchProductsAndCreate(opId, barcode, pictureData, kitchen, piece, true);
            } else {
                resetState();
                scanStatusLive.setValue(getString(R.string.error_network) + " (Create Product)");
                sendEvent(Event.CONTINUE_SCANNING);
            }
        });
    }

    private void safetyCheckBarcode(final String opId, final int newProductId, final String barcode, final String productName, @Nullable final byte[] pictureData) {
        String url = grocyApi.getStockProductByBarcode(barcode);
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 3.5: Safety Barcode Lookup before assignment. URL: " + url);

        dlHelper.get(url, opId, response -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Safety Check SUCCESS. Barcode already assigned.");
            try {
                Type type = new TypeToken<ProductDetails>() {}.getType();
                ProductDetails details = dlHelper.gson.fromJson(response, type);
                if (details != null && details.getProduct() != null) {
                    Product product = details.getProduct();
                    Log.d(TAG, "DIAG: [Op " + opId + "] Stopping placeholder assignment. Using existing product: " + product.getName());
                    resetState();
                    consumeProduct(product, barcode);
                } else {
                    assignBarcode(opId, newProductId, barcode, productName, pictureData);
                }
            } catch (Exception e) {
                assignBarcode(opId, newProductId, barcode, productName, pictureData);
            }
        }, error -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Safety Check: Barcode still unassigned (404 expected). Proceeding.");
            assignBarcode(opId, newProductId, barcode, productName, pictureData);
        });
    }

    private void assignBarcode(final String opId, final int productId, final String barcode, final String productName, @Nullable final byte[] pictureData) {
        ProductBarcode productBarcode = new ProductBarcode();
        productBarcode.setProductIdInt(productId);
        productBarcode.setBarcode(barcode);

        final String url = grocyApi.getObjects(GrocyApi.ENTITY.PRODUCT_BARCODES);
        final JSONObject payload = productBarcode.getJsonFromProductBarcode(isDebuggingEnabled(), TAG);
        
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 4: POST Assign Barcode [" + barcode + "] to ID [" + productId + "]. URL: " + url);
        Log.d(TAG, "DIAG: [Op " + opId + "] Body: " + payload.toString());

        dlHelper.post(
            url,
            payload,
            response -> {
                Log.d(TAG, "DIAG: [Op " + opId + "] Step 4 Success. Response: " + (response != null ? response.toString() : "<empty>"));
                addInitialStock(opId, productId, productName, pictureData);
            },
            error -> {
                logNetworkError(opId, "Step 4 (Assign Barcode)", url, error);
                resetState();
                scanStatusLive.setValue(getString(R.string.kitchen_error_partial, productName, "barcode assignment"));
                sendEvent(Event.CONTINUE_SCANNING);
            }
        );
    }

    private void addInitialStock(final String opId, final int productId, final String productName, @Nullable final byte[] pictureData) {
        JSONObject body = new JSONObject();
        try {
            body.put("amount", "1");
        } catch (JSONException ignored) {}

        final String url = grocyApi.purchaseProduct(productId);
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 5: POST Add Stock to ID [" + productId + "]. URL: " + url);
        Log.d(TAG, "DIAG: [Op " + opId + "] Body: " + body.toString());

        dlHelper.postWithArray(url, body, response -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Step 5 Success. Response: " + (response != null ? response.toString() : "<empty>"));
            resetState();
            successMessageLive.setValue(getString(R.string.kitchen_created_msg, productName));
            sendEvent(Event.TRANSACTION_SUCCESS);
            sendEvent(Event.CONTINUE_SCANNING);
            if (pictureData != null) {
                uploadPictureAsync(opId, productId, pictureData);
            }
            loadData();
        }, error -> {
            logNetworkError(opId, "Step 5 (Add Stock)", url, error);
            resetState();
            scanStatusLive.setValue(getString(R.string.kitchen_error_partial, productName, "adding stock"));
            sendEvent(Event.CONTINUE_SCANNING);
        });
    }

    private void uploadPictureAsync(final String opId, final int productId, byte[] pictureData) {
        final String filename = productId + "_" + System.currentTimeMillis() + ".jpg";
        final String url = grocyApi.getProductPicture(filename);
        Log.d(TAG, "DIAG: [Op " + opId + "] Step 7: PUT Upload Picture [" + filename + "]. URL: " + url);

        dlHelper.putFile(url, pictureData, () -> {
            Log.d(TAG, "DIAG: [Op " + opId + "] Step 7 Success.");
            JSONObject body = new JSONObject();
            try {
                body.put("picture_file_name", filename);
            } catch (JSONException ignored) {}
            
            final String updateUrl = grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, productId);
            Log.d(TAG, "DIAG: [Op " + opId + "] Step 8: PUT Update Product Filename. URL: " + updateUrl);
            
            dlHelper.put(updateUrl, body, r -> Log.d(TAG, "DIAG: [Op " + opId + "] Step 8 Success. Picture Assigned."),
                e -> {
                    Log.e(TAG, "DIAG: [Op " + opId + "] Step 8 FAILED.");
                    if (e instanceof VolleyError) logNetworkError(opId, "Step 8", updateUrl, (VolleyError) e);
                }
            );
        }, error -> {
            Log.e(TAG, "DIAG: [Op " + opId + "] Step 7 FAILED.");
            logNetworkError(opId, "Step 7", url, error);
        });
    }

    private void logNetworkError(String opId, String step, String url, VolleyError error) {
        String msg = error != null ? error.getMessage() : "null";
        String className = error != null ? error.getClass().getName() : "null";
        int statusCode = -1;
        String body = "<empty>";
        if (error != null && error.networkResponse != null) {
            statusCode = error.networkResponse.statusCode;
            if (error.networkResponse.data != null) {
                body = new String(error.networkResponse.data, StandardCharsets.UTF_8);
            }
        }
        Log.e(TAG, "DIAG: [Op " + opId + "] " + step + " FAILED. URL: " + url);
        Log.e(TAG, "DIAG: [Op " + opId + "] Error Class: " + className + ", Message: " + msg);
        Log.e(TAG, "DIAG: [Op " + opId + "] HTTP Status: " + statusCode + ", Body: " + body);
    }

    private void reportError(String opId, String msg) {
        Log.e(TAG, "DIAG: [Op " + opId + "] Reporting UI error: " + msg);
        resetState();
        scanStatusLive.setValue(msg);
        sendEvent(Event.ERROR);
        sendEvent(Event.CONTINUE_SCANNING);
    }

    @Nullable
    private Location findKitchenLocation() {
        if (locations == null) return null;
        for (Location loc : locations) {
            if ("Kitchen".equalsIgnoreCase(loc.getName())) return loc;
        }
        return null;
    }

    @Nullable
    private QuantityUnit findPieceUnit() {
        if (quantityUnits == null) return null;
        for (QuantityUnit qu : quantityUnits) {
            if ("Piece".equalsIgnoreCase(qu.getName()) || "Piece".equalsIgnoreCase(qu.getNamePlural())) return qu;
        }
        return null;
    }

    public boolean isBusy() {
        return isCreatingUnknown || Boolean.TRUE.equals(isProcessingLive.getValue());
    }

    private void resetState() {
        isCreatingUnknown = false;
        isProcessingLive.setValue(false);
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

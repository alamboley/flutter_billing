package com.lykhonis.flutterbilling;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponse;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

public final class BillingPlugin implements MethodCallHandler {
    private final String TAG = BillingPlugin.class.getSimpleName();

    private enum BillingServiceStatus {
        IDLE, STARTING, READY
    }

    private final Activity activity;
    private final BillingClient billingClient;
    private final Map<String, Result> pendingPurchaseRequests;
    private final Deque<Request> pendingRequests;
    private BillingServiceStatus billingServiceStatus;

    private final List<String> consumeIds = new ArrayList<>();

    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "flutter_billing");
        channel.setMethodCallHandler(new BillingPlugin(registrar.activity()));
    }

    private BillingPlugin(Activity activity) {
        this.activity = activity;

        pendingPurchaseRequests = new HashMap<>();
        pendingRequests = new ArrayDeque<>();
        billingServiceStatus = BillingServiceStatus.IDLE;

        billingClient = BillingClient.newBuilder(activity)
                                     .setListener(new BillingListener())
                                     .build();

        final Application application = activity.getApplication();

        application.registerActivityLifecycleCallbacks(new LifecycleCallback() {
            @Override
            public void onActivityDestroyed(Activity activity) {
                if (activity == BillingPlugin.this.activity) {
                    application.unregisterActivityLifecycleCallbacks(this);

                    stopServiceConnection();
                }
            }
        });

        executeServiceRequest(new Request() {
            @Override
            public void execute() {
                Log.d(TAG, "Billing service is ready.");
            }

            @Override
            public void failed() {
                Log.d(TAG, "Failed to setup billing service!");
            }
        });
    }

    @Override
    public void onMethodCall(MethodCall methodCall, Result result) {
        if ("fetchPurchases".equals(methodCall.method)) {
            fetchPurchases(result);
        } else if ("purchase".equals(methodCall.method)) {
            purchase(methodCall.<String>argument("identifier"), methodCall.<Boolean>argument("consume"), result);
        } else if ("fetchProducts".equals(methodCall.method)) {
            fetchProducts(methodCall.<List<String>>argument("identifiers"), result);
        } else {
            result.notImplemented();
        }
    }

    private void fetchProducts(final List<String> identifiers, final Result result) {
        executeServiceRequest(new Request() {
            @Override
            public void execute() {
                billingClient.querySkuDetailsAsync(
                        SkuDetailsParams.newBuilder()
                                        .setSkusList(identifiers)
                                        .setType(SkuType.INAPP)
                                        .build(),
                        new SkuDetailsResponseListener() {
                            @Override
                            public void onSkuDetailsResponse(int responseCode, List<SkuDetails> skuDetailsList) {
                                if (responseCode == BillingResponse.OK) {
                                    final List<Map<String, Object>> products = new ArrayList<>();

                                    for (SkuDetails details : skuDetailsList) {
                                        final Map<String, Object> product = new HashMap<>();
                                        product.put("identifier", details.getSku());
                                        product.put("price", details.getPrice());
                                        product.put("title", details.getTitle());
                                        product.put("description", details.getDescription());
                                        product.put("currency", details.getPriceCurrencyCode());
                                        product.put("amount", details.getPriceAmountMicros() / 10_000L);
                                        products.add(product);
                                    }

                                    result.success(products);
                                } else {
                                    result.error("ERROR", "Failed to fetch products!", null);
                                }
                            }
                        });
            }

            @Override
            public void failed() {
                result.error("UNAVAILABLE", "Billing service is unavailable!", null);
            }
        });
    }

    final ConsumeResponseListener onConsumeListener = new ConsumeResponseListener() {
        @Override
        public void onConsumeResponse(@BillingResponse int responseCode, String purchaseToken) {

          Log.d(TAG, "onConsumeResponse: " + responseCode);
        }
  };

    private void purchase(final String identifier, final Boolean consume, final Result result) {
        executeServiceRequest(new Request() {
            @Override
            public void execute() {
                
                if (consume && !consumeIds.contains(identifier))
                    consumeIds.add(identifier);

                final int responseCode = billingClient.launchBillingFlow(
                        activity,
                        BillingFlowParams.newBuilder()
                                         .setSku(identifier)
                                         .setType(SkuType.INAPP)
                                         .build());

                if (responseCode == BillingResponse.OK) {
                    pendingPurchaseRequests.put(identifier, result);
                } else {
                    result.error("ERROR", "Failed to launch billing flow to purchase an item with error " + responseCode, null);
                }
            }

            @Override
            public void failed() {
                result.error("UNAVAILABLE", "Billing service is unavailable!", null);
            }
        });
    }

    private void fetchPurchases(final Result result) {
        executeServiceRequest(new Request() {
            @Override
            public void execute() {
                final Purchase.PurchasesResult purchasesResult = billingClient.queryPurchases(SkuType.INAPP);
                final int responseCode = purchasesResult.getResponseCode();

                if (responseCode == BillingResponse.OK) {
                    result.success(getIdentifiers(purchasesResult.getPurchasesList()));
                } else {
                    result.error("ERROR", "Failed to query purchases with error " + responseCode, null);
                }
            }

            @Override
            public void failed() {
                result.error("UNAVAILABLE", "Billing service is unavailable!", null);
            }
        });
    }

    private List<String> getIdentifiers(List<Purchase> purchases) {
        if (purchases == null) return Collections.emptyList();

        final List<String> identifiers = new ArrayList<>(purchases.size());

        Log.d(TAG, "fetchPurchases, trying to consume if necessary:");

        for (Purchase purchase : purchases) {
            String sku = purchase.getSku();
            identifiers.add(sku);
            if (consumeIds.contains(sku)) {
                billingClient.consumeAsync(purchase.getPurchaseToken(), onConsumeListener);
                consumeIds.remove(sku);
            }
        }

        return identifiers;
    }

    private void stopServiceConnection() {
        if (billingClient.isReady()) {
            Log.d(TAG, "Stopping billing service.");

            billingClient.endConnection();
            billingServiceStatus = BillingServiceStatus.IDLE;
        }
    }

    private void startServiceConnection() {
        if (billingServiceStatus != BillingServiceStatus.IDLE) return;
        billingServiceStatus = BillingServiceStatus.STARTING;
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@BillingResponse int billingResponseCode) {
                Log.d(TAG, "Billing service was setup with code " + billingResponseCode);

                billingServiceStatus = billingResponseCode == BillingResponse.OK ? BillingServiceStatus.READY : BillingServiceStatus.IDLE;
                Request request;

                while ((request = pendingRequests.poll()) != null) {
                    if (billingServiceStatus == BillingServiceStatus.READY) {
                        request.execute();
                    } else {
                        request.failed();
                    }
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.d(TAG, "Billing service was disconnected!");

                billingServiceStatus = BillingServiceStatus.IDLE;
            }
        });
    }

    private void executeServiceRequest(Request request) {
        if (billingServiceStatus == BillingServiceStatus.READY) {
            request.execute();
        } else {
            pendingRequests.add(request);
            startServiceConnection();
        }
    }

    final class BillingListener implements PurchasesUpdatedListener {
        @Override
        public void onPurchasesUpdated(int resultCode, List<Purchase> purchases) {
            if (resultCode == BillingResponse.OK && purchases != null) {
                final List<String> identifiers = getIdentifiers(purchases);

                for (String identifier : identifiers) {
                    final Result result = pendingPurchaseRequests.remove(identifier);
                    if (result != null) result.success(identifiers);
                }
            } else {
                for (Result result : pendingPurchaseRequests.values()) {
                    result.error("ERROR", "Failed to purchase an item with error " + resultCode, null);
                }

                pendingPurchaseRequests.clear();
            }
        }
    }

    interface Request {
        void execute();

        void failed();
    }

    static class LifecycleCallback implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}

package com.eightbit.samsprung.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.android.billingclient.api.*
import com.eightbit.material.IconifiedSnackbar
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprung
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DonationManager internal constructor(private val activity: CoverPreferences) {

    private val Number.toPx get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(),
        Resources.getSystem().displayMetrics
    )

    private lateinit var billingClient: BillingClient
    private val iapSkuDetails: ArrayList<ProductDetails> = arrayListOf()
    private val subSkuDetails: ArrayList<ProductDetails> = arrayListOf()

    private fun getIAP(amount: Int) : String {
        return String.format(Locale.ROOT, "subscription_%02d", amount)
    }

    private fun getSub(amount: Int) : String {
        return String.format(Locale.ROOT, "monthly_%02d", amount)
    }

    private val iapList: ArrayList<String> = arrayListOf()
    private val subList: ArrayList<String> = arrayListOf()

    private val consumeResponseListener = ConsumeResponseListener { _, _ ->
        IconifiedSnackbar(activity).buildTickerBar(
            activity.getString(R.string.donation_thanks)
        ).show()
    }

    private fun handlePurchaseIAP(purchase : Purchase) {
        val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken)
        billingClient.consumeAsync(consumeParams.build(), consumeResponseListener)
    }

    private var acknowledgePurchaseResponseListener = AcknowledgePurchaseResponseListener {
        IconifiedSnackbar(activity).buildTickerBar(activity.getString(R.string.donation_thanks)).show()
        SamSprung.hasSubscription = true
    }

    private fun handlePurchaseSub(purchase : Purchase) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
        billingClient.acknowledgePurchase(acknowledgePurchaseParams.build(),
            acknowledgePurchaseResponseListener)
    }

    private fun handlePurchase(purchase : Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                iapList.forEach {
                    if (purchase.products.contains(it)) handlePurchaseIAP(purchase)
                }
                subList.forEach {
                    if (purchase.products.contains(it)) handlePurchaseSub(purchase)
                }
            }
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && null != purchases) {
            purchases.forEach {
                handlePurchase(it)
            }
        }
    }

    private val subsPurchased: ArrayList<String> = arrayListOf()

    private val subsOwnedListener = PurchasesResponseListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            run breaking@{
                purchases.forEach {
                    it.products.forEach { sku ->
                        if (subsPurchased.contains(sku)) {
                            SamSprung.hasSubscription = true
                            return@breaking
                        }
                    }
                }
            }
        }
    }

    private val subHistoryListener = PurchaseHistoryResponseListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && null != purchases) {
            for (purchase in purchases)
                subsPurchased.addAll(purchase.products)
            billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build(), subsOwnedListener)
        }
    }

    private val iapHistoryListener = PurchaseHistoryResponseListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && null != purchases) {
            run breaking@{
                purchases.forEach {
                    it.products.forEach { sku ->
                        if (sku.split("_").toTypedArray()[1].toInt() >= 10) {
                            return@breaking
                        }
                    }
                }
            }
        }
    }

    private val billingConnectionMutex = Mutex()

    private val resultAlreadyConnected = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .setDebugMessage("Billing client is already connected")
        .build()

    /**
     * Returns immediately if this BillingClient is already connected, otherwise
     * initiates the connection and suspends until this client is connected.
     * If a connection is already in the process of being established, this
     * method just suspends until the billing client is ready.
     */
    private suspend fun BillingClient.connect(): BillingResult = billingConnectionMutex.withLock {
        if (isReady) {
            // fast path: avoid suspension if already connected
            resultAlreadyConnected
        } else {
            unsafeConnect()
        }
    }

    private suspend fun BillingClient.unsafeConnect() = suspendCoroutine { cont ->
        startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                cont.resume(billingResult)
            }
            override fun onBillingServiceDisconnected() {
                // no need to setup reconnection logic here, call ensureReady()
                // before each purchase to reconnect as necessary
            }
        })
    }

    fun retrieveDonationMenu() {
        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchasesUpdatedListener).enablePendingPurchases().build()

        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            iapSkuDetails.clear()
            subSkuDetails.clear()

            val clientResponseCode = billingClient.connect().responseCode
            if (clientResponseCode == BillingClient.BillingResponseCode.OK) {
                iapList.add(getIAP(1))
                iapList.add(getIAP(5))
                iapList.add(getIAP(10))
                iapList.add(getIAP(25))
                iapList.add(getIAP(50))
                iapList.add(getIAP(75))
                iapList.add(getIAP(99))
                iapList.forEach {
                    val productList = QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP).build()
                    val params = QueryProductDetailsParams.newBuilder()
                        .setProductList(listOf(productList))
                    billingClient.queryProductDetailsAsync(params.build()) { _, productDetailsList ->
                        iapSkuDetails.addAll(productDetailsList)
                        billingClient.queryPurchaseHistoryAsync(
                            QueryPurchaseHistoryParams.newBuilder().setProductType(
                                BillingClient.ProductType.INAPP
                            ).build(), iapHistoryListener
                        )
                    }
                }
                if (BuildConfig.GOOGLE_PLAY) return@launch
                subList.add(getSub(1))
                subList.add(getSub(5))
                subList.add(getSub(10))
                subList.add(getSub(25))
                subList.add(getSub(50))
                subList.add(getSub(75))
                subList.add(getSub(99))
                subList.forEach {
                    val productList = QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS).build()
                    val params = QueryProductDetailsParams.newBuilder()
                        .setProductList(listOf(productList))
                    billingClient.queryProductDetailsAsync(params.build()) { _, productDetailsList ->
                        subSkuDetails.addAll(productDetailsList)
                        billingClient.queryPurchaseHistoryAsync(
                            QueryPurchaseHistoryParams.newBuilder().setProductType(
                                BillingClient.ProductType.SUBS
                            ).build(), subHistoryListener
                        )
                    }
                }
            }
        }
    }

    private fun getDonationButton(skuDetail: ProductDetails): Button {
        val button = Button(activity.applicationContext)
        button.setBackgroundResource(R.drawable.button_rippled)
        button.elevation = 10f.toPx
        button.setTextColor(ContextCompat.getColor(activity, android.R.color.black))
        button.textSize = 8f.toPx
        button.text = activity.getString(
            R.string.iap_button, skuDetail.oneTimePurchaseOfferDetails!!.formattedPrice
        )
        button.setOnClickListener {
            val productDetailsParamsList = BillingFlowParams.ProductDetailsParams
                .newBuilder().setProductDetails(skuDetail).build()
            billingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParamsList)).build()
            )
        }
        return button
    }

    private fun getSubscriptionButton(skuDetail: ProductDetails): Button {
        val button = Button(activity.applicationContext)
        button.setBackgroundResource(R.drawable.button_rippled)
        button.elevation = 10f.toPx
        button.setTextColor(ContextCompat.getColor(activity, android.R.color.black))
        button.textSize = 8f.toPx
        button.text = activity.getString(
            R.string.sub_button,
            skuDetail.subscriptionOfferDetails!![0].pricingPhases.pricingPhaseList[0].formattedPrice
        )
        button.setOnClickListener {
            val productDetailsParamsList = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setOfferToken(skuDetail.subscriptionOfferDetails!![0]!!.offerToken)
                .setProductDetails(skuDetail).build()
            billingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParamsList)).build()
            )
        }
        return button
    }

    @SuppressLint("InflateParams")
    fun onSendDonationClicked() {
        with (activity.layoutInflater.inflate(R.layout.donation_layout, null) as LinearLayout) {
           AlertDialog.Builder(
                ContextThemeWrapper(activity, R.style.Theme_Overlay_NoActionBar)
            ).apply {
                findViewById<LinearLayout>(R.id.donation_layout).also { layout ->
                    layout.removeAllViewsInLayout()
                    iapSkuDetails.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.productId }
                    ).forEach { skuDetail ->
                        if (null != skuDetail.oneTimePurchaseOfferDetails)
                            layout.addView(getDonationButton(skuDetail))
                    }
                    findViewById<LinearLayout>(R.id.subscription_layout).run {
                        if (BuildConfig.GOOGLE_PLAY) {
                            isVisible = false
                        } else {
                            isVisible = true
                            removeAllViewsInLayout()
                            subSkuDetails.sortedWith(
                                compareBy(String.CASE_INSENSITIVE_ORDER) { it.productId }
                            ).forEach { skuDetail ->
                                if (null != skuDetail.subscriptionOfferDetails)
                                    addView(getSubscriptionButton(skuDetail))
                            }
                        }
                        this@apply.setOnCancelListener {
                            layout.removeAllViewsInLayout()
                            if (!BuildConfig.GOOGLE_PLAY) removeAllViewsInLayout()
                        }
                        this@apply.setOnDismissListener {
                            layout.removeAllViewsInLayout()
                            if (!BuildConfig.GOOGLE_PLAY) removeAllViewsInLayout()
                        }
                    }
                }
            }.setView(this).show().also { donateDialog ->
               val padding = TypedValue.applyDimension(
                   TypedValue.COMPLEX_UNIT_DIP, 4f, Resources.getSystem().displayMetrics
               ).toInt()
               val params = LinearLayout.LayoutParams(
                   LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
               )
               params.setMargins(0, padding, 0, padding)

               if (SamSprung.hasSubscription) {
                   addView(activity.layoutInflater.inflate(R.layout.button_cancel_sub, null).apply {
                       setOnClickListener {
                           activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                               "https://support.google.com/googleplay/workflow/9827184"
                           )))
                           donateDialog.cancel()
                       }
                       layoutParams = params
                   })
               }

               if (!BuildConfig.GOOGLE_PLAY) {
                   addView(activity.layoutInflater.inflate(R.layout.button_sponsor, null).apply {
                       setOnClickListener {
                           activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                               "https://github.com/sponsors/AbandonedCart"
                           )))
                           donateDialog.cancel()
                       }
                       layoutParams = params
                   })

                   addView(activity.layoutInflater.inflate(R.layout.button_paypal, null).apply {
                       setOnClickListener {
                           activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                               "https://www.paypal.com/donate/?hosted_button_id=Q2LFH2SC8RHRN"
                           )))
                           donateDialog.cancel()
                       }
                   })
               }
               donateDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
           }
        }
    }
}
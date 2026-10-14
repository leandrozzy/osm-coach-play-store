package com.leandrozzy.managercoach

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Premium (compra única pelo Google Play Billing): libera os 4 slots e tira os anúncios.
 * O estado fica guardado para abrir rápido, mas a fonte é sempre o Google Play (recarregado a cada abertura).
 */
object Premium {
    const val PRODUCT_ID = "premium_vitalicio"

    /** Slot liberado no plano grátis. */
    const val FREE_SLOT = 1

    var active by mutableStateOf(false)
        private set
    var price by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private var client: BillingClient? = null
    private var details: ProductDetails? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("premium", Context.MODE_PRIVATE)

    fun allows(slot: Int): Boolean = active || slot == FREE_SLOT

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        active = prefs(app).getBoolean("active", false)
        if (client != null) return
        val c = BillingClient.newBuilder(app)
            .setListener { result, purchases -> onPurchases(app, result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                refresh(app)
                loadDetails()
            }

            override fun onBillingServiceDisconnected() {
                client = null
            }
        })
    }

    /** Confere no Google Play se a compra existe (também serve de "restaurar compra"). */
    fun refresh(ctx: Context) {
        val c = client ?: return init(ctx)
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) onPurchases(ctx, result, list, fromQuery = true)
        }
    }

    private fun loadDetails() {
        val c = client ?: return
        val q = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
            )
        ).build()
        c.queryProductDetailsAsync(q) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = list.firstOrNull()
            price = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    fun buy(activity: Activity) {
        val c = client
        val d = details
        if (c == null || d == null) {
            message = "Loja indisponível agora. Confira a internet e se o app veio da Play Store."
            init(activity)
            return
        }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())
        ).build()
        c.launchBillingFlow(activity, params)
    }

    private fun onPurchases(ctx: Context, result: BillingResult, purchases: List<Purchase>?, fromQuery: Boolean = false) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            if (!fromQuery) message = "A compra não foi concluída (código ${result.responseCode})."
            return
        }
        val owned = purchases.orEmpty().filter { PRODUCT_ID in it.products }
        val bought = owned.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (owned.any { it.purchaseState == Purchase.PurchaseState.PENDING } && bought == null) {
            message = "Pagamento pendente: o Premium libera assim que o Google confirmar."
        }
        if (bought != null && !bought.isAcknowledged) {
            // compra não reconhecida em 3 dias é devolvida pelo Google
            client?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build()
            ) { }
        }
        // na consulta, ausência de compra desfaz o Premium (reembolso); na compra, só soma
        val now = bought != null || (!fromQuery && active)
        active = now
        prefs(ctx).edit().putBoolean("active", now).apply()
        if (bought != null && !fromQuery) {
            message = "Premium liberado. Obrigado!"
            Monitor.event("premium_comprado")
        }
    }
}

/** Consentimento de anúncios (LGPD/GDPR) pelo Google UMP e inicialização do AdMob depois dele. */
object Ads {
    var ready by mutableStateOf(false)
        private set
    private val started = AtomicBoolean(false)
    private var info: ConsentInformation? = null

    fun gather(activity: Activity) {
        if (Premium.active) return
        val ci = UserMessagingPlatform.getConsentInformation(activity)
        info = ci
        ci.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                if (ci.canRequestAds()) startAds(activity)
            }
        }, { _ -> })
        // consentimento de uma abertura anterior: já pode carregar
        if (ci.canRequestAds()) startAds(activity)
    }

    private fun startAds(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        Thread {
            MobileAds.initialize(ctx.applicationContext) { ready = true }
        }.start()
    }

    /** Mostra o botão "Privacidade dos anúncios" quando a região exige (UE/Reino Unido etc.). */
    fun privacyOptionsRequired(): Boolean =
        info?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }
}

/**
 * Monitoramento (Firebase Crashlytics + Analytics): só funciona quando o projeto tem o google-services.json e o
 * usuário não desligou em Ajustes. Nunca envia telas, nomes de jogadores ou chaves: só eventos e travamentos.
 */
object Monitor {
    private const val OPT_OUT = "monitor_off"
    @Volatile private var on = false

    fun enabled(ctx: Context): Boolean = BuildConfig.HAS_FIREBASE && Settings.get(ctx, OPT_OUT, "") != "1"

    fun init(ctx: Context) {
        if (!BuildConfig.HAS_FIREBASE) return
        on = enabled(ctx)
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = on
            com.google.firebase.analytics.FirebaseAnalytics.getInstance(ctx).setAnalyticsCollectionEnabled(on)
        } catch (e: Exception) {
            on = false
        }
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        Settings.put(ctx, OPT_OUT, if (value) "" else "1")
        init(ctx)
    }

    fun event(name: String, vararg params: Pair<String, String>) {
        if (!on) return
        try {
            val b = Bundle()
            for ((k, v) in params) b.putString(k, v.take(90))
            appContext?.let { com.google.firebase.analytics.FirebaseAnalytics.getInstance(it).logEvent(name, b) }
        } catch (e: Exception) { }
    }

    fun error(where: String, e: Throwable) {
        if (!on) return
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log(where)
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e)
        } catch (x: Exception) { }
    }

    @Volatile var appContext: Context? = null
}

/** Chaves de IA guardadas criptografadas com uma chave do Android Keystore (não saem do aparelho). */
object SecretBox {
    private const val ALIAS = "managercoach_secrets"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isBlank()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

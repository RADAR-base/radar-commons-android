package org.radarbase.android.util

import android.security.keystore.KeyGenParameterSpec
import java.security.Key
import java.security.KeyStore
import javax.crypto.KeyGenerator

object AndroidKeyStore {
    fun getOrCreateSecretKey(name: String, algorithm: String, purposes: Int, generatorBuilder: KeyGenParameterSpec.Builder.() -> Unit = {}): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)
        val key = keyStore.getKey(name, null)
        return if (key != null) {
            key
        } else {
            val keyGenerator = KeyGenerator.getInstance(algorithm, ANDROID_KEY_STORE)
            keyGenerator.init(KeyGenParameterSpec.Builder(name, purposes)
                    .apply(generatorBuilder)
                    .build())
            keyGenerator.generateKey()
        }
    }

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
}

package nl.thehyve.prmt.shimmer.ui

import android.content.Intent
import android.graphics.drawable.Animatable2.AnimationCallback
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView

fun ImageView.repeatAnimation(): AnimationCallback? {
    val avd = drawable as? AnimatedVectorDrawable
        ?: return null
    val callback = object : AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            post { avd.start() }
        }
    }
    avd.registerAnimationCallback(callback)
    avd.start()
    return callback
}

fun ImageView.cancelAnimation(callback: AnimationCallback? = null) {
    val avd = drawable as? AnimatedVectorDrawable
        ?: return
    if (callback != null) {
        avd.unregisterAnimationCallback(callback)
    }
    avd.stop()
}

inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}

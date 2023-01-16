package org.radarbase.android.util

import android.content.ComponentName
import android.os.IBinder

sealed class BindState<T>

fun <S, T: IBinder> BindState<T>.applyBinder(doApply: T.(componentName: ComponentName) -> S): S? =
    when (this) {
        is ManagedServiceConnection.Unbound<T> -> null
        is ManagedServiceConnection.BoundService<T> -> {
            binder.doApply(componentName)
        }
    }

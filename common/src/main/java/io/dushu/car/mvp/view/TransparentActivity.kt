package io.dushu.car.mvp.view

import android.app.Activity
import android.os.Bundle
import com.shilapi.xcertplay.GeelyCompatibilityMediaControls

/** OEM card launch trampoline; enabled only in the separately selected compatibility build. */
class TransparentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GeelyCompatibilityMediaControls.openFromFactory(this, intent)
        finish()
    }
}

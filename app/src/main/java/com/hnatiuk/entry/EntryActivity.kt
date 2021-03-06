package com.hnatiuk.entry

import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.hnatiuk.core.base.BaseActivity
import com.hnatiuk.features.databinding.ActivityEntryBinding
import com.hnatiuk.navigation.NavigationSampleActivity
import com.hnatiuk.overlay.OverlayActivity
import com.hnatiuk.screencapture.ScreenCaptureActivity

class EntryActivity : BaseActivity<ActivityEntryBinding>() {

    override val bindingFactory: (LayoutInflater) -> ActivityEntryBinding =
        ActivityEntryBinding::inflate

    override fun ActivityEntryBinding.initUI() {
        toScreenCapture.setOnClickListener {
            startActivity(ScreenCaptureActivity.getIntent(this@EntryActivity))
        }
        toOverlay.setOnClickListener {
            startActivity(OverlayActivity.getIntent(this@EntryActivity))
        }
        toRoomSample.isVisible = false
//        toRoomSample.setOnClickListener {
//            startActivity(RoomSampleActivity.getIntent(this@EntryActivity))
//        }
        toNavigationSample.setOnClickListener {
            startActivity(NavigationSampleActivity.getIntent(this@EntryActivity))
        }
    }
}
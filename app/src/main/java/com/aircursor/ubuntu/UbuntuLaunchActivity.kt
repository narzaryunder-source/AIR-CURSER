package com.aircursor.ubuntu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aircursor.MainActivity
import com.aircursor.cloudpc.CloudPcDesktop
import com.aircursor.cloudpc.CloudPcDesktopActivity
import com.aircursor.cloudpc.CloudPcEngine
import com.aircursor.pro.ProAccess

/**
 * The "AirCursor PC" icon on the phone's home screen (and its Quick Settings tile):
 * opens the desktop when it is installed, otherwise the AirCursor PC setup screen.
 */
class UbuntuLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = CloudPcEngine.isReady(this) && CloudPcDesktop.isInstalled(this)
        val intent = if (ready && ProAccess.allowed(this)) {
            Intent(this, CloudPcDesktopActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, if (ready) "pro" else "cloudpc")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

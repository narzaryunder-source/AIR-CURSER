package com.aircursor.ubuntu

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.aircursor.MainActivity

/**
 * The "Ubuntu PC" icon on the phone's home screen: one tap starts the Ubuntu desktop, opens
 * Termux:X11 and turns on the hand cursor. It stays (invisible) for a few seconds because
 * Android only lets a visible app start the camera and open other apps.
 */
class UbuntuLaunchActivity : ComponentActivity() {
    private var handled = false

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            go()
        } else {
            Toast.makeText(this, "Permission ke bina Ubuntu PC nahi chalega", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean("handled") ?: false
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        if (!UbuntuMode.isInstalled(this, UbuntuMode.TERMUX) || !UbuntuMode.isInstalled(this, UbuntuMode.X11)) {
            Toast.makeText(this, "Pehle Termux aur Termux:X11 install karke Ubuntu setup karo", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (UbuntuMode.hasPermission(this)) go() else askPermission.launch(UbuntuMode.PERMISSION)
    }

    private fun go() {
        val err = UbuntuMode.start(this)
        if (err != null) {
            Toast.makeText(
                this,
                "Termux ne command nahi li. Termux me ek baar chalao: bash fix.sh --keep",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        Toast.makeText(this, "Ubuntu PC start ho raha hai… (pehli baar 1-2 min)", Toast.LENGTH_LONG).show()
        // Stay visible until Termux:X11 has been opened (UbuntuMode opens it after 2.5 s).
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 4000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("handled", handled)
    }
}

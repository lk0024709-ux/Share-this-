package com.sharethis.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sharethis.app.core.network.NetworkBandManager
import com.sharethis.app.core.permissions.PermissionManager
import com.sharethis.app.databinding.ActivityMainBinding
import com.sharethis.app.ui.receive.ReceiveActivity
import com.sharethis.app.ui.send.SendActivity

/**
 * Entry screen: Send / Receive cards + live radio-band capability badge.
 * Each flow asks for exactly what it needs, right when it needs it — the
 * only thing requested up front is the (optional, API 33+) notification
 * permission so the transfer foreground service can show progress.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            .also { /* result ignored — denied just hides the progress notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val missingNotifications = PermissionManager.missingPermissions(
            this, PermissionManager.notificationPermission()
        )
        if (missingNotifications.isNotEmpty()) {
            notificationPermLauncher.launch(missingNotifications)
        }

        binding.cardSend.setOnClickListener {
            startActivity(Intent(this, SendActivity::class.java))
        }
        binding.cardReceive.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBandBadge()
    }

    private fun refreshBandBadge() {
        val band = NetworkBandManager(this)
        val capable = band.is5GHzSupported()
        binding.tvBandStatus.text = if (capable) {
            "● ${band.bandSummary()} — 5 GHz capable hardware"
        } else {
            "● ${band.bandSummary()} — 2.4 GHz fallback mode"
        }
    }
}

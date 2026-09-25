package com.techlion.healthconnectexporter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportButton: Button
    private lateinit var shareButton: Button
    private val exportWorker by lazy { ExportWorker(applicationContext) }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(exportWorker.permissions)) {
            export()
        } else {
            statusText.setText(R.string.status_permission_denied)
            exportButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        exportButton = findViewById(R.id.exportButton)
        shareButton = findViewById(R.id.shareButton)
        progressBar.visibility = View.GONE
        exportButton.setOnClickListener { checkAndRequestPermissions() }
        shareButton.setOnClickListener { shareLatestExport() }
        shareButton.isEnabled = exportWorker.hasExport()

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                statusText.setText(
                    if (exportWorker.hasExport()) R.string.status_previous_export else R.string.status_ready
                )
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                statusText.setText(R.string.status_provider_update)
                exportButton.isEnabled = false
            }
            else -> {
                statusText.setText(R.string.status_unavailable)
                exportButton.isEnabled = false
            }
        }
    }

    private fun checkAndRequestPermissions() {
        exportButton.isEnabled = false
        lifecycleScope.launch {
            if (exportWorker.hasAllPermissions()) {
                export()
            } else {
                exportButton.isEnabled = true
                permissionLauncher.launch(exportWorker.permissions)
            }
        }
    }

    private fun export() {
        statusText.setText(R.string.status_exporting)
        progressBar.visibility = View.VISIBLE
        exportButton.isEnabled = false
        shareButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = exportWorker.exportAll { name, count ->
                    runOnUiThread {
                        statusText.text = getString(R.string.status_exported_type, name, count)
                    }
                }
                statusText.text = if (result.errors.isEmpty()) {
                    getString(R.string.status_complete, result.totalRecords)
                } else {
                    getString(
                        R.string.status_complete_with_errors,
                        result.totalRecords,
                        result.errors.size
                    )
                }
                shareButton.isEnabled = true
            } catch (error: Exception) {
                statusText.text = getString(
                    R.string.status_failed,
                    error.message ?: error::class.java.simpleName
                )
            } finally {
                progressBar.visibility = View.GONE
                exportButton.isEnabled = true
            }
        }
    }

    private fun shareLatestExport() {
        shareButton.isEnabled = false
        statusText.setText(R.string.status_preparing_archive)
        lifecycleScope.launch {
            try {
                val archive = withContext(Dispatchers.IO) {
                    exportWorker.createShareArchive()
                }
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    archive
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
                statusText.setText(R.string.status_previous_export)
            } catch (error: Exception) {
                statusText.text = getString(
                    R.string.status_share_failed,
                    error.message ?: error::class.java.simpleName
                )
            } finally {
                shareButton.isEnabled = exportWorker.hasExport()
            }
        }
    }
}

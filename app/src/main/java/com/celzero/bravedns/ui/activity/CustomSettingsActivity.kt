package com.celzero.bravedns.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.databinding.ActivityCustomSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Themes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.koin.android.ext.android.inject
import java.io.OutputStreamWriter
import android.content.res.Configuration

class CustomSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityCustomSettingsBinding
    private val persistentState by inject<PersistentState>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        try {
            super.onCreate(savedInstanceState)
            b = ActivityCustomSettingsBinding.inflate(layoutInflater)
            setContentView(b.root)

            b.acsAppLockCard.setOnClickListener {
                showAppLockOptionsDialog()
            }

            b.acsExportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "rethink_state.json")
                }
                createDocumentLauncher.launch(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("CustomSettings", "Crash in onCreate", e)
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        val state = VpnController.state()
                        val status = if (state.on) "started" else "stopped"
                        val json = JSONObject()
                        json.put("status", status)

                        val universal = JSONObject()
                        universal.put("blockWhenDeviceLocked", persistentState.getBlockWhenDeviceLocked())
                        universal.put("blockAppWhenBackground", persistentState.getBlockAppWhenBackground())
                        universal.put("udpBlocked", persistentState.getUdpBlocked())
                        universal.put("blockUnknownConnections", persistentState.getBlockUnknownConnections())
                        universal.put("disallowDnsBypass", persistentState.getDisallowDnsBypass())
                        universal.put("blockNewlyInstalledApp", persistentState.getBlockNewlyInstalledApp())
                        universal.put("blockMeteredConnections", persistentState.getBlockMeteredConnections())

                        json.put("universal", universal)

                        lifecycleScope.launch {
                            val ipportParent = JSONObject()
                            val perappParent = JSONObject()

                            val ipRules = withContext(Dispatchers.IO) { customIpRepository.getIpRules() }
                            val universalIpportArray = JSONArray()
                            val perappIpportArray = JSONArray()
                            for (rule in ipRules) {
                                val ruleJson = JSONObject()
                                ruleJson.put("uid", rule.uid)
                                ruleJson.put("ipAddress", rule.ipAddress)
                                ruleJson.put("port", rule.port)
                                ruleJson.put("protocol", rule.protocol)
                                ruleJson.put("isActive", rule.isActive)
                                ruleJson.put("status", rule.status)
                                
                                if (rule.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY) {
                                    universalIpportArray.put(ruleJson)
                                } else {
                                    perappIpportArray.put(ruleJson)
                                }
                            }
                            ipportParent.put("ipport", universalIpportArray)
                            perappParent.put("ipport", perappIpportArray)

                            val domainRules = withContext(Dispatchers.IO) { customDomainRepository.getAllCustomDomains() }
                            val universalDomainArray = JSONArray()
                            val perappDomainArray = JSONArray()
                            for (rule in domainRules) {
                                val ruleJson = JSONObject()
                                ruleJson.put("domain", rule.domain)
                                ruleJson.put("uid", rule.uid)
                                ruleJson.put("ips", rule.ips)
                                ruleJson.put("status", rule.status)
                                ruleJson.put("type", rule.type)
                                
                                if (rule.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY) {
                                    universalDomainArray.put(ruleJson)
                                } else {
                                    perappDomainArray.put(ruleJson)
                                }
                            }
                            ipportParent.put("domain", universalDomainArray)
                            perappParent.put("domain", perappDomainArray)

                            json.put("ipport", ipportParent)
                            json.put("perapp", perappParent)

                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri)?.use { outputStream ->
                                    OutputStreamWriter(outputStream).use { writer ->
                                        writer.write(json.toString(2))
                                    }
                                }
                            }
                            Toast.makeText(this@CustomSettingsActivity, "State exported successfully", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Error exporting state", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private fun showAppLockOptionsDialog() {
        val prefs = getSharedPreferences("RethinkPinLock", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("ENABLE_PIN", true)
        
        val options = arrayOf(
            if (isEnabled) "Disable PIN" else "Enable PIN",
            "Change PIN"
        )
        
        AlertDialog.Builder(this)
            .setTitle("App Lock Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Toggle enable/disable
                        prefs.edit().putBoolean("ENABLE_PIN", !isEnabled).apply()
                        Toast.makeText(this, if (!isEnabled) "PIN Enabled" else "PIN Disabled", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Change PIN
                        showChangePinDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 16, 48, 8)

        val etCurrent = android.widget.EditText(this)
        etCurrent.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etCurrent.hint = "Current PIN"
        layout.addView(etCurrent)

        val etNew = android.widget.EditText(this)
        etNew.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etNew.hint = "New PIN"
        layout.addView(etNew)

        val etConfirm = android.widget.EditText(this)
        etConfirm.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etConfirm.hint = "Confirm New PIN"
        layout.addView(etConfirm)

        val changePinDialog = AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            
        changePinDialog.show()
        changePinDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val prefs = getSharedPreferences("RethinkPinLock", Context.MODE_PRIVATE)
            val savedPin = prefs.getString("PIN", "123456")
            val current = etCurrent.text.toString().trim()
            val newPin = etNew.text.toString().trim()
            val confirm = etConfirm.text.toString().trim()

            if (current != savedPin) {
                etCurrent.error = "Incorrect current PIN"
                return@setOnClickListener
            }
            if (newPin.isEmpty()) {
                etNew.error = "PIN cannot be empty"
                return@setOnClickListener
            }
            if (newPin != confirm) {
                etConfirm.error = "PINs do not match"
                return@setOnClickListener
            }

            prefs.edit().putString("PIN", newPin).apply()
            Toast.makeText(this, "PIN updated successfully", Toast.LENGTH_SHORT).show()
            changePinDialog.dismiss()
        }
    }
}

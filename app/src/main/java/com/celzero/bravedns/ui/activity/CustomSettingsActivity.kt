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
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.util.Themes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.koin.android.ext.android.inject
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import android.content.res.Configuration

import org.apache.poi.ss.usermodel.*
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

class CustomSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityCustomSettingsBinding
    private val persistentState by inject<PersistentState>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val customIpRepository by inject<CustomIpRepository>()
    private val customDomainRepository by inject<CustomDomainRepository>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()

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

            b.acsImportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                openDocumentLauncher.launch(intent)
            }

            b.acsExportExcelCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.ms-excel"
                    putExtra(Intent.EXTRA_TITLE, "rethink_logs.xls")
                }
                exportExcelLauncher.launch(intent)
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

    private val exportExcelLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    lifecycleScope.launch {
                        try {
                            Toast.makeText(this@CustomSettingsActivity, "Exporting logs to Excel...", Toast.LENGTH_SHORT).show()
                            exportLogsToExcel(uri)
                            Toast.makeText(this@CustomSettingsActivity, "Logs exported successfully", Toast.LENGTH_SHORT).show()
                        } catch (e: Throwable) {
                            android.util.Log.e("CustomSettings", "Error exporting Excel", e)
                            Toast.makeText(this@CustomSettingsActivity, "Error exporting Excel: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private suspend fun exportLogsToExcel(uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val logs = connectionTrackerRepository.getAllLogs()
        val workbook = HSSFWorkbook()
        val sheet = workbook.createSheet("Network Logs")

        val headers = arrayOf(
            "Time", "App Name", "Package", "IP Address", "Port",
            "Protocol", "Blocked?", "Blocked By", "Target IP",
            "Flag", "Message", "Download (B)", "Upload (B)"
        )

        val headerRow = sheet.createRow(0)
        for ((index, header) in headers.withIndex()) {
            headerRow.createCell(index).setCellValue(header)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for ((rowIndex, log) in logs.withIndex()) {
            val row = sheet.createRow(rowIndex + 1)
            row.createCell(0).setCellValue(dateFormat.format(Date(log.timeStamp)))
            row.createCell(1).setCellValue(log.appName ?: "")
            row.createCell(2).setCellValue(log.packageName ?: "")
            row.createCell(3).setCellValue(log.ipAddress ?: "")
            row.createCell(4).setCellValue(log.port.toString())
            row.createCell(5).setCellValue(if (log.protocol == 1) "UDP" else if (log.protocol == 2) "TCP" else "OTHER")
            row.createCell(6).setCellValue(if (log.isBlocked) "Yes" else "No")
            row.createCell(7).setCellValue(log.blockedByRule ?: "")
            row.createCell(8).setCellValue(log.dnsQuery ?: "") // dnsQuery stores the Target IP or Domain
            row.createCell(9).setCellValue(log.flag ?: "")
            row.createCell(10).setCellValue(log.message ?: "")
            row.createCell(11).setCellValue(log.downloadBytes.toString())
            row.createCell(12).setCellValue(log.uploadBytes.toString())
        }

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()
    }

    private val openDocumentLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    lifecycleScope.launch {
                        try {
                            val jsonString = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    InputStreamReader(inputStream).readText()
                                }
                            }
                            if (jsonString != null) {
                                val json = JSONObject(jsonString)
                                importStateFromJson(json)
                                Toast.makeText(this@CustomSettingsActivity, "State imported successfully", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CustomSettings", "Error importing state", e)
                            Toast.makeText(this@CustomSettingsActivity, "Error importing state: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private suspend fun importStateFromJson(json: JSONObject) = withContext(Dispatchers.IO) {
        if (json.has("status")) {
            val status = json.getString("status")
            val vpnState = VpnController.state()
            android.util.Log.i("CustomSettings", "Importing status: $status (current: ${vpnState.on})")
            if (status == "started" && !vpnState.on) {
                VpnController.start(this@CustomSettingsActivity)
            } else if (status == "stopped" && vpnState.on) {
                VpnController.stop("State imported from JSON", this@CustomSettingsActivity)
            }
        }

        if (json.has("universal")) {
            val universal = json.getJSONObject("universal")
            android.util.Log.i("CustomSettings", "Importing universal rules")
            if (universal.has("blockWhenDeviceLocked")) persistentState.setBlockWhenDeviceLocked(universal.getBoolean("blockWhenDeviceLocked"))
            if (universal.has("blockAppWhenBackground")) persistentState.setBlockAppWhenBackground(universal.getBoolean("blockAppWhenBackground"))
            if (universal.has("udpBlocked")) persistentState.setUdpBlocked(universal.getBoolean("udpBlocked"))
            if (universal.has("blockUnknownConnections")) persistentState.setBlockUnknownConnections(universal.getBoolean("blockUnknownConnections"))
            if (universal.has("disallowDnsBypass")) persistentState.setDisallowDnsBypass(universal.getBoolean("disallowDnsBypass"))
            if (universal.has("blockNewlyInstalledApp")) persistentState.setBlockNewlyInstalledApp(universal.getBoolean("blockNewlyInstalledApp"))
            if (universal.has("blockMeteredConnections")) persistentState.setBlockMeteredConnections(universal.getBoolean("blockMeteredConnections"))
        }

        if (json.has("ipport")) {
            val ipportParent = json.getJSONObject("ipport")
            if (ipportParent.has("ipport")) {
                importCustomIps(ipportParent.getJSONArray("ipport"), true)
            }
            if (ipportParent.has("domain")) {
                importCustomDomains(ipportParent.getJSONArray("domain"), true)
            }
        }

        if (json.has("perapp")) {
            val perappParent = json.getJSONObject("perapp")
            if (perappParent.has("ipport")) {
                importCustomIps(perappParent.getJSONArray("ipport"), false)
            }
            if (perappParent.has("domain")) {
                importCustomDomains(perappParent.getJSONArray("domain"), false)
            }
        }
    }

    private suspend fun importCustomIps(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customIpRepository.getIpRules().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        android.util.Log.i("CustomSettings", "Importing ${array.length()} CustomIp rules (Universal: $isUniversal)")
        
        for (i in 0 until array.length()) {
            val ruleJson = array.getJSONObject(i)
            val ipAddress = ruleJson.getString("ipAddress")
            val port = ruleJson.getInt("port")
            val uid = ruleJson.getInt("uid")
            val protocol = ruleJson.getString("protocol")
            val isActive = ruleJson.getBoolean("isActive")
            val status = ruleJson.getInt("status")
            
            val existing = currentRules.find { it.ipAddress == ipAddress && it.port == port && it.uid == uid && it.protocol == protocol }
            if (existing == null) {
                val newRule = com.celzero.bravedns.database.CustomIp()
                newRule.uid = uid
                newRule.ipAddress = ipAddress
                newRule.port = port
                newRule.protocol = protocol
                newRule.isActive = isActive
                newRule.status = status
                customIpRepository.insert(newRule)
                android.util.Log.i("CustomSettings", "Inserted CustomIp: $ipAddress:$port (uid: $uid)")
            } else if (existing.isActive != isActive || existing.status != status) {
                existing.isActive = isActive
                existing.status = status
                customIpRepository.update(existing)
                android.util.Log.i("CustomSettings", "Updated CustomIp: $ipAddress:$port (uid: $uid)")
            }
        }
    }

    private suspend fun importCustomDomains(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customDomainRepository.getAllCustomDomains().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        android.util.Log.i("CustomSettings", "Importing ${array.length()} CustomDomain rules (Universal: $isUniversal)")
        
        for (i in 0 until array.length()) {
            val ruleJson = array.getJSONObject(i)
            val domain = ruleJson.getString("domain")
            val uid = ruleJson.getInt("uid")
            val status = ruleJson.getInt("status")
            val type = ruleJson.getInt("type")
            val ips = ruleJson.optString("ips", "")
            
            val existing = currentRules.find { it.domain == domain && it.uid == uid }
            if (existing == null) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.insert(newRule)
                android.util.Log.i("CustomSettings", "Inserted CustomDomain: $domain (uid: $uid)")
            } else if (existing.status != status || existing.type != type || existing.ips != ips) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.update(existing, newRule)
                android.util.Log.i("CustomSettings", "Updated CustomDomain: $domain (uid: $uid)")
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

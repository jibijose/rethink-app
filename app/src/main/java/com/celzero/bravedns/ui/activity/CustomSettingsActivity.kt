package com.celzero.bravedns.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Color
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
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

data class ScheduledJobInfo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val data: String,
    val file: String,
    val executeAt: String,
    val days: String,
    val isRecurring: Boolean,
    val job: kotlinx.coroutines.Job
)

class CustomSettingsActivity : BaseActivity() {

    companion object {
        val automationScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        val activeJobs = java.util.Collections.synchronizedList(mutableListOf<ScheduledJobInfo>())
    }

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

            val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)

            b.acsExportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    putExtra(Intent.EXTRA_TITLE, "rethink_state_${formatter.format(java.util.Date())}.json")
                }
                manualExportStateLauncher.launch(intent)
            }

            b.acsImportStateCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    prefs.getString("last_import_state_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                manualImportStateLauncher.launch(intent)
            }

            b.acsImportCommandsCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val mimeTypes = arrayOf("application/json", "text/plain", "text/javascript", "application/octet-stream")
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    prefs.getString("last_import_commands_uri", null)?.let { uriStr ->
                        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(uriStr))
                    }
                }
                importCommandsLauncher.launch(intent)
            }

            b.acsExportExcelCard.setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.ms-excel"
                    val formatter = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    putExtra(Intent.EXTRA_TITLE, "rethink_stats_${formatter.format(java.util.Date())}.xls")
                }
                manualExportStatsLauncher.launch(intent)
            }

            b.acsSetupAutomation.setOnClickListener {
                startActivity(Intent(this, AuthorizedFilesActivity::class.java))
            }

            b.acsAutomationToggleCard.setOnClickListener {
                b.acsAutomationSwitch.isChecked = !b.acsAutomationSwitch.isChecked
            }

            b.acsLogsButton.setOnClickListener {
                showLogsDialog()
            }

            b.acsJobsButton.setOnClickListener {
                showJobsDialog()
            }

            updateAutomationUi()
        } catch (e: Exception) {
            android.util.Log.e("CustomSettings", "Crash in onCreate", e)
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutomationUi()
    }

    private val manualExportStateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    lifecycleScope.launch {
                        val fileName = getFileName(uri) ?: "rethink_state.json"
                        try {
                            exportStateToJson(uri)
                            logActivity("Manual", "Export State", fileName, "SUCCESS", "State saved to $fileName")
                            Toast.makeText(this@CustomSettingsActivity, "State exported successfully", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            val err = e.message ?: "Unknown error"
                            logActivity("Manual", "Export State", fileName, "FAILED", err)
                            Toast.makeText(this@CustomSettingsActivity, "Error exporting state: $err", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private suspend fun exportStateToJson(uri: android.net.Uri) {
        val state = VpnController.state()
        val status = if (state.on) "started" else "stopped"
        val json = JSONObject()
        json.put("status", status)

        val universal = JSONObject()
        universal.put("blockWhenDeviceLocked", persistentState.getBlockWhenDeviceLocked())
        universal.put("blockAppWhenBackground", persistentState.getBlockAppWhenBackground())
        universal.put("blockUnknownConnections", persistentState.getBlockUnknownConnections())
        universal.put("udpBlocked", persistentState.getUdpBlocked())
        universal.put("disallowDnsBypass", persistentState.getDisallowDnsBypass())
        universal.put("blockNewlyInstalledApp", persistentState.getBlockNewlyInstalledApp())
        universal.put("blockMeteredConnections", persistentState.getBlockMeteredConnections())
        universal.put("blockHttpConnections", persistentState.getBlockHttpConnections())
        universal.put("universalLockdown", persistentState.getUniversalLockdown())

        json.put("universal", universal)

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
    }

    private val manualExportStatsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    lifecycleScope.launch {
                        val fileName = getFileName(uri) ?: "rethink_stats.xls"
                        try {
                            Toast.makeText(this@CustomSettingsActivity, "Exporting stats to Excel...", Toast.LENGTH_SHORT).show()
                            exportLogsToExcel(uri)
                            logActivity("Manual", "Export Stats", fileName, "SUCCESS", "Network logs exported to $fileName")
                            Toast.makeText(this@CustomSettingsActivity, "Stats exported successfully", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_UI, "Error exporting Excel: ${e.message}", e)
                            val err = e.message ?: "Unknown error"
                            logActivity("Manual", "Export Stats", fileName, "FAILED", err)
                            Toast.makeText(this@CustomSettingsActivity, "Error exporting Excel: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

    private suspend fun exportLogsToExcel(uri: android.net.Uri, fromTime: Long? = null, toTime: Long? = null) = withContext(Dispatchers.IO) {
        val actualFrom = fromTime ?: run {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        val actualTo = toTime ?: System.currentTimeMillis()
        
        val allLogs = connectionTrackerRepository.getAllLogs()
        val logs = allLogs.filter {
            if (toTime != null) {
                it.timeStamp >= actualFrom && it.timeStamp < actualTo
            } else {
                it.timeStamp in actualFrom..actualTo
            }
        }
        val workbook = HSSFWorkbook()
        val sheet = workbook.createSheet("Network Logs")

        val headers = arrayOf(
            "Time", "App Name", "Package", "IP Address", "Port",
            "Protocol", "Blocked?", "Blocked By", "Target IP",
            "Flag", "Message", "Download (B)", "Upload (B)"
        )

        val headerRow = sheet.createRow(0)
        for ((index, header) in headers.withIndex()) {
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
        }

        // Set reasonable fixed widths instead of autoSizeColumn (which crashes on Android without awt fonts)
        // Values are in units of 1/256th of a character width
        val columnWidths = intArrayOf(
            5500, // Time
            6000, // App Name
            7000, // Package
            4500, // IP Address
            2000, // Port
            2500, // Protocol
            2500, // Blocked?
            4000, // Blocked By
            6000, // Target IP
            3000, // Flag
            8000, // Message
            4000, // Download
            4000  // Upload
        )
        for (i in columnWidths.indices) {
            sheet.setColumnWidth(i, columnWidths[i])
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

        sheet.createFreezePane(0, 1) // Freeze top row
        // Add auto filter
        sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, logs.size, 0, headers.size - 1))

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()
    }

    private val manualImportStateLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val fileName = getFileName(uri) ?: "rethink_state.json"
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_import_state_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_UI, "Failed to take persistable URI permission: ${e.message}", e)
                    }

                    lifecycleScope.launch {
                        try {
                            val jsonString = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    InputStreamReader(inputStream).readText()
                                }
                            }
                            if (jsonString.isNullOrBlank()) {
                                logActivity("Manual", "Import State", fileName, "FAILED", "Selected file is empty (0 bytes)")
                                MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                    .setTitle("Import State Failed")
                                    .setMessage("Selected file is empty (0 bytes).")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                val cleanJson = if (jsonString.startsWith("\uFEFF")) jsonString.substring(1).trim() else jsonString.trim()
                                val json = JSONObject(cleanJson)
                                importStateFromJson(json)
                                logActivity("Manual", "Import State", fileName, "SUCCESS", "Firewall & DNS rules reloaded")
                                MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                    .setTitle("Import State")
                                    .setMessage("State imported successfully. Firewall & DNS rules reloaded.")
                                    .setPositiveButton("OK", null)
                                    .show()
                                Toast.makeText(this@CustomSettingsActivity, "State imported successfully", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            val err = e.message ?: "Unknown error"
                            Logger.e(LOG_TAG_UI, "Error importing state: $err", e)
                            logActivity("Manual", "Import State", fileName, "FAILED", err)
                            MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                .setTitle("Import State Failed")
                                .setMessage("Error importing state: $err")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }

    private val importCommandsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val commandsFileName = getFileName(uri) ?: "rethink_command.json"
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("last_import_commands_uri", uri.toString()).apply()
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_UI, "Failed to take persistable URI permission: ${e.message}", e)
                    }

                    lifecycleScope.launch {
                        try {
                            val jsonString = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    InputStreamReader(inputStream).readText()
                                }
                            }
                            if (jsonString.isNullOrBlank()) {
                                logActivity("Manual", "Import Commands", commandsFileName, "FAILED", "Selected command file is empty (0 bytes)")
                                MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                    .setTitle("Import Commands Failed")
                                    .setMessage("Selected command file is empty (0 bytes).")
                                    .setPositiveButton("OK", null)
                                    .show()
                                return@launch
                            }

                            val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                            val cleanJson = if (jsonString.startsWith("\uFEFF")) jsonString.substring(1).trim() else jsonString.trim()
                            val root = org.json.JSONTokener(cleanJson).nextValue()
                            val commandsArray = if (root is org.json.JSONArray) {
                                root
                            } else if (root is JSONObject) {
                                root.optJSONArray("commands") ?: org.json.JSONArray()
                            } else {
                                org.json.JSONArray()
                            }

                            if (commandsArray.length() == 0) {
                                logActivity("Manual", "Import Commands", commandsFileName, "FAILED", "No executable commands found")
                                MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                    .setTitle("Import Commands")
                                    .setMessage("No executable commands found in file. Ensure 'commands' array contains objects with '\"execute\": true'.")
                                    .setPositiveButton("OK", null)
                                    .show()
                                return@launch
                            }

                            activeJobs.forEach { it.job.cancel() }
                            activeJobs.clear()
                            logActivity("Manual", "Import Commands", commandsFileName, "SUCCESS", "Loaded ${commandsArray.length()} commands")

                            val executionResults = mutableListOf<String>()
                            var anyCommandFailed = false

                            for (i in 0 until commandsArray.length()) {
                                val cmdObj = commandsArray.optJSONObject(i) ?: continue
                                if (!cmdObj.optBoolean("execute", false)) {
                                    continue
                                }
                                
                                val command = cmdObj.optString("command").trim().lowercase()
                                val data = cmdObj.optString("data").trim().lowercase()
                                val customFile = cmdObj.optString("file", "").trim()
                                val (allowedDays, daysLabel) = parseDays(cmdObj)
                                
                                val executeAtStr = cmdObj.optString("executeAt", null)
                                var delayMillis = 0L
                                var isRecurring = false
                                if (!executeAtStr.isNullOrEmpty()) {
                                    try {
                                        val parsedTod = parseTimeOfDay(executeAtStr)
                                        if (parsedTod != null) {
                                            val (h, m) = parsedTod
                                            val now = Calendar.getInstance()
                                            val targetCal = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, h)
                                                set(Calendar.MINUTE, m)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            if (targetCal.before(now) || targetCal.timeInMillis <= now.timeInMillis) {
                                                targetCal.add(Calendar.DAY_OF_MONTH, 1)
                                            }
                                            delayMillis = targetCal.timeInMillis - now.timeInMillis
                                            isRecurring = true
                                        } else {
                                            val fullExecuteTime = parseFullDateTime(executeAtStr) ?: run {
                                                val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                                                sdf.parse(executeAtStr)?.time
                                            } ?: 0L
                                            val now = System.currentTimeMillis()
                                            if (fullExecuteTime > now) {
                                                delayMillis = fullExecuteTime - now
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Logger.e(LOG_TAG_UI, "Invalid executeAt format: $executeAtStr", e)
                                    }
                                }
                                
                                val defaultFile = when {
                                    data == "stats" -> "rethink_automated_stats.xls"
                                    command == "export" && data == "state" -> "rethink_automated_state.json"
                                    else -> "rethink_state.json"
                                }
                                val fileLabel = if (customFile.isNotEmpty()) customFile else defaultFile

                                val task: suspend () -> Boolean = task@ {
                                    var success = false
                                    val currentCal = java.util.Calendar.getInstance()
                                    val currentDay = currentCal.get(java.util.Calendar.DAY_OF_WEEK)
                                    if (allowedDays != null && !allowedDays.contains(currentDay)) {
                                        val dayName = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(currentCal.time)
                                        val skipMsg = "Today ($dayName) is not in allowed days ($daysLabel)"
                                        Logger.i(LOG_TAG_UI, "Command $command $data skipped: $skipMsg")
                                        logActivity("Automated", "$command $data".uppercase(), fileLabel, "SKIPPED", skipMsg)
                                        executionResults.add("⏭️ $command $data: Skipped (today $dayName not in $daysLabel)")
                                        return@task true
                                    }

                                    when {
                                        command == "import" && data == "state" -> {
                                            var targetUri = if (customFile.isNotEmpty()) {
                                                getAuthorizedFileUri(customFile)
                                            } else {
                                                getAuthorizedFileUri("rethink_state.json", "state.json")
                                            }

                                            var stateJsonString: String? = null
                                            if (targetUri != null) {
                                                stateJsonString = withContext(Dispatchers.IO) {
                                                    try {
                                                        contentResolver.openInputStream(targetUri)?.use { inputStream ->
                                                            InputStreamReader(inputStream).readText()
                                                        }
                                                    } catch (e: Exception) {
                                                        Logger.e(LOG_TAG_UI, "Failed to read $targetUri: ${e.message}", e)
                                                        null
                                                    }
                                                }
                                            }

                                            // Fallback to rethink_automated_state.json only if custom file was not explicitly provided
                                            if (stateJsonString.isNullOrBlank() && customFile.isEmpty()) {
                                                val fallbackUri = getAuthorizedFileUri("rethink_automated_state.json")
                                                    ?: prefs.getString("automated_export_state_uri", null)?.let { android.net.Uri.parse(it) }
                                                    ?: prefs.getString("last_import_state_uri", null)?.let { android.net.Uri.parse(it) }
                                                
                                                if (fallbackUri != null && fallbackUri != targetUri) {
                                                    val fallbackText = withContext(Dispatchers.IO) {
                                                        try {
                                                            contentResolver.openInputStream(fallbackUri)?.use { inputStream ->
                                                                InputStreamReader(inputStream).readText()
                                                            }
                                                        } catch (e: Exception) {
                                                            null
                                                        }
                                                    }
                                                    if (!fallbackText.isNullOrBlank()) {
                                                        Logger.i(LOG_TAG_UI, "rethink_state.json was empty/missing; fell back to rethink_automated_state.json")
                                                        targetUri = fallbackUri
                                                        stateJsonString = fallbackText
                                                    }
                                                }
                                            }

                                            if (targetUri == null) {
                                                val err = "File '$fileLabel' not found. Authorize '$fileLabel' in Automation Setup."
                                                Logger.e(LOG_TAG_UI, err)
                                                logActivity("Automated", "Import State", fileLabel, "FAILED", err)
                                                executionResults.add("✗ import state: Failed ($err)")
                                                success = false
                                            } else if (stateJsonString.isNullOrBlank()) {
                                                val err = "State file '$fileLabel' is empty (0 bytes). Please paste valid JSON configuration."
                                                Logger.e(LOG_TAG_UI, err)
                                                logActivity("Automated", "Import State", fileLabel, "FAILED", err)
                                                executionResults.add("✗ import state: Failed ($err)")
                                                success = false
                                            } else {
                                                try {
                                                    val cleanStateJson = if (stateJsonString.startsWith("\uFEFF")) stateJsonString.substring(1).trim() else stateJsonString.trim()
                                                    val json = JSONObject(cleanStateJson)
                                                    importStateFromJson(json)
                                                    Logger.i(LOG_TAG_UI, "Command executed: State imported successfully from $fileLabel")
                                                    logActivity("Automated", "Import State", fileLabel, "SUCCESS", "Firewall & DNS rules reloaded")
                                                    executionResults.add("✓ import state ($fileLabel): Success (reloaded firewall & DNS rules)")
                                                    success = true
                                                } catch (e: Exception) {
                                                    val err = "Error applying state: ${e.message}"
                                                    Logger.e(LOG_TAG_UI, err, e)
                                                    logActivity("Automated", "Import State", fileLabel, "FAILED", err)
                                                    executionResults.add("✗ import state ($fileLabel): Failed ($err)")
                                                    success = false
                                                }
                                            }
                                        }
                                        command == "export" && data == "state" -> {
                                            val savedUri = if (customFile.isNotEmpty()) {
                                                getAuthorizedFileUri(customFile)
                                            } else {
                                                getAuthorizedFileUri("rethink_automated_state.json", "rethink_state.json", "state.json")
                                                    ?: prefs.getString("automated_export_state_uri", null)?.let { android.net.Uri.parse(it) }
                                            }
                                            if (savedUri != null) {
                                                try {
                                                    exportStateToJson(savedUri)
                                                    Logger.i(LOG_TAG_UI, "Command executed: State exported successfully to $fileLabel")
                                                    logActivity("Automated", "Export State", fileLabel, "SUCCESS", "State saved to $fileLabel")
                                                    executionResults.add("✓ export state: Success (saved to $fileLabel)")
                                                    success = true
                                                } catch (e: Exception) {
                                                    val err = "Error exporting state: ${e.message}"
                                                    Logger.e(LOG_TAG_UI, err, e)
                                                    logActivity("Automated", "Export State", fileLabel, "FAILED", err)
                                                    executionResults.add("✗ export state: Failed ($err)")
                                                    success = false
                                                }
                                            } else {
                                                val err = "No export state file found for '$fileLabel'. Authorize '$fileLabel' in Automation Setup."
                                                Logger.e(LOG_TAG_UI, err)
                                                logActivity("Automated", "Export State", fileLabel, "FAILED", err)
                                                executionResults.add("✗ export state: Failed ($err)")
                                                success = false
                                            }
                                        }
                                        command == "export" && data == "stats" -> {
                                            val savedUri = if (customFile.isNotEmpty()) {
                                                getAuthorizedFileUri(customFile)
                                            } else {
                                                getAuthorizedFileUri("rethink_automated_stats.xls", "rethink_stats.xls", "stats.xls", "rethink_logs.xls")
                                                    ?: prefs.getString("automated_export_stats_uri", null)?.let { android.net.Uri.parse(it) }
                                            }
                                            if (savedUri != null) {
                                                try {
                                                    val runTime = System.currentTimeMillis()
                                                    val resolvedRange = resolveExportTimeRange(cmdObj, runTime)
                                                    val fromTime = resolvedRange?.first
                                                    val toTime = resolvedRange?.second

                                                    exportLogsToExcel(savedUri, fromTime, toTime)
                                                    val sdfLog = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                                                    val rangeStr = if (fromTime != null && toTime != null) {
                                                        " (${sdfLog.format(Date(fromTime))} -> ${sdfLog.format(Date(toTime))})"
                                                    } else {
                                                        ""
                                                    }
                                                    Logger.i(LOG_TAG_UI, "Command executed: Stats exported successfully to $fileLabel$rangeStr")
                                                    logActivity("Automated", "Export Stats", fileLabel, "SUCCESS", "Network logs exported to $fileLabel$rangeStr")
                                                    executionResults.add("✓ export stats: Success (saved to $fileLabel$rangeStr)")
                                                    success = true
                                                } catch (e: Exception) {
                                                    val err = "Error exporting stats: ${e.message}"
                                                    Logger.e(LOG_TAG_UI, err, e)
                                                    logActivity("Automated", "Export Stats", fileLabel, "FAILED", err)
                                                    executionResults.add("✗ export stats: Failed ($err)")
                                                    success = false
                                                }
                                            } else {
                                                val err = "No export stats file found for '$fileLabel'. Authorize '$fileLabel' in Automation Setup."
                                                Logger.e(LOG_TAG_UI, err)
                                                logActivity("Automated", "Export Stats", fileLabel, "FAILED", err)
                                                executionResults.add("✗ export stats: Failed ($err)")
                                                success = false
                                            }
                                        }
                                        else -> {
                                            Logger.w(LOG_TAG_UI, "Unknown command ignored: $command $data")
                                            executionResults.add("⚠️ Ignored unknown command: $command $data")
                                            success = true
                                        }
                                    }
                                    success
                                }

                                val daysSuffix = if (daysLabel != "ALL") " [Days: $daysLabel]" else ""
                                val fileSuffix = if (customFile.isNotEmpty()) " [File: $customFile]" else ""

                                if (isRecurring) {
                                    val job = automationScope.launch {
                                        var nextDelay = delayMillis
                                        while (isActive) {
                                            kotlinx.coroutines.delay(nextDelay)
                                            task()
                                            val parsedTod = parseTimeOfDay(executeAtStr)
                                            if (parsedTod != null) {
                                                val (h, m) = parsedTod
                                                val now = Calendar.getInstance()
                                                val targetCal = Calendar.getInstance().apply {
                                                    set(Calendar.HOUR_OF_DAY, h)
                                                    set(Calendar.MINUTE, m)
                                                    set(Calendar.SECOND, 0)
                                                    set(Calendar.MILLISECOND, 0)
                                                }
                                                if (targetCal.before(now) || targetCal.timeInMillis <= now.timeInMillis) {
                                                    targetCal.add(Calendar.DAY_OF_MONTH, 1)
                                                }
                                                nextDelay = targetCal.timeInMillis - now.timeInMillis
                                            } else {
                                                nextDelay = 24 * 60 * 60 * 1000L
                                            }
                                        }
                                    }
                                    val jobInfo = ScheduledJobInfo(
                                        command = command,
                                        data = data,
                                        file = fileLabel,
                                        executeAt = executeAtStr ?: "",
                                        days = daysLabel,
                                        isRecurring = true,
                                        job = job
                                    )
                                    activeJobs.add(jobInfo)
                                    executionResults.add("⏳ $command $data$fileSuffix: Scheduled recurring at $executeAtStr$daysSuffix")
                                } else if (delayMillis > 0) {
                                    lateinit var jobInfo: ScheduledJobInfo
                                    val job = automationScope.launch {
                                        kotlinx.coroutines.delay(delayMillis)
                                        task()
                                        activeJobs.remove(jobInfo)
                                    }
                                    jobInfo = ScheduledJobInfo(
                                        command = command,
                                        data = data,
                                        file = fileLabel,
                                        executeAt = executeAtStr ?: "",
                                        days = daysLabel,
                                        isRecurring = false,
                                        job = job
                                    )
                                    activeJobs.add(jobInfo)
                                    executionResults.add("⏳ $command $data$fileSuffix: Scheduled (delay ${delayMillis / 1000}s)$daysSuffix")
                                } else {
                                    val success = task()
                                    if (!success) {
                                        anyCommandFailed = true
                                        break
                                    }
                                }
                            }

                            if (executionResults.isNotEmpty()) {
                                val title = if (anyCommandFailed) "Command Sequence Incomplete" else "Commands Executed Successfully"
                                val message = executionResults.joinToString("\n\n")

                                MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                    .setTitle(title)
                                    .setMessage(message)
                                    .setPositiveButton("OK", null)
                                    .show()

                                if (!anyCommandFailed) {
                                    Toast.makeText(this@CustomSettingsActivity, "All commands executed successfully", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            val err = e.message ?: "Unknown error"
                            Logger.e(LOG_TAG_UI, "Error importing commands: $err", e)
                            logActivity("Manual", "Import Commands", commandsFileName, "FAILED", err)
                            MaterialAlertDialogBuilder(this@CustomSettingsActivity, R.style.App_Dialog_NoDim)
                                .setTitle("Error Importing Commands")
                                .setMessage(err)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }

    private suspend fun importStateFromJson(json: JSONObject) = withContext(Dispatchers.IO) {
        if (json.has("status")) {
            val status = json.getString("status")
            val vpnState = VpnController.state()
            Logger.i(LOG_TAG_UI, "Importing status: $status (current: ${vpnState.on})")
            withContext(Dispatchers.Main) {
                if (status == "started" && !vpnState.on) {
                    VpnController.start(this@CustomSettingsActivity)
                } else if (status == "stopped" && vpnState.on) {
                    VpnController.stop("State imported from JSON", this@CustomSettingsActivity)
                }
            }
        }

        if (json.has("universal")) {
            val universal = json.getJSONObject("universal")
            Logger.i(LOG_TAG_UI, "Importing universal rules")
            if (universal.has("blockWhenDeviceLocked")) persistentState.setBlockWhenDeviceLocked(universal.getBoolean("blockWhenDeviceLocked"))
            if (universal.has("blockAppWhenBackground")) persistentState.setBlockAppWhenBackground(universal.getBoolean("blockAppWhenBackground"))
            if (universal.has("blockUnknownConnections")) persistentState.setBlockUnknownConnections(universal.getBoolean("blockUnknownConnections"))
            if (universal.has("udpBlocked")) persistentState.setUdpBlocked(universal.getBoolean("udpBlocked"))
            if (universal.has("disallowDnsBypass")) persistentState.setDisallowDnsBypass(universal.getBoolean("disallowDnsBypass"))
            if (universal.has("blockNewlyInstalledApp")) persistentState.setBlockNewlyInstalledApp(universal.getBoolean("blockNewlyInstalledApp"))
            if (universal.has("blockMeteredConnections")) persistentState.setBlockMeteredConnections(universal.getBoolean("blockMeteredConnections"))
            if (universal.has("blockHttpConnections")) persistentState.setBlockHttpConnections(universal.getBoolean("blockHttpConnections"))
            if (universal.has("universalLockdown")) persistentState.setUniversalLockdown(universal.getBoolean("universalLockdown"))
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

        try {
            val domCount = DomainRulesManager.load()
            val ipCount = IpRulesManager.load()
            Logger.i(LOG_TAG_UI, "Reloaded rules into engine: $domCount domains, $ipCount IPs")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error reloading rules into engine: ${e.message}", e)
        }
    }

    private suspend fun importCustomIps(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customIpRepository.getIpRules().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        Logger.i(LOG_TAG_UI, "Importing ${array.length()} CustomIp rules (Universal: $isUniversal)")
        
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
                Logger.i(LOG_TAG_UI, "Inserted CustomIp: $ipAddress:$port (uid: $uid)")
            } else if (existing.isActive != isActive || existing.status != status) {
                existing.isActive = isActive
                existing.status = status
                customIpRepository.update(existing)
                Logger.i(LOG_TAG_UI, "Updated CustomIp: $ipAddress:$port (uid: $uid)")
            }
        }
    }

    private suspend fun importCustomDomains(array: JSONArray, isUniversal: Boolean) {
        val currentRules = customDomainRepository.getAllCustomDomains().filter { 
            if (isUniversal) it.uid == com.celzero.bravedns.util.Constants.UID_EVERYBODY else it.uid != com.celzero.bravedns.util.Constants.UID_EVERYBODY 
        }
        Logger.i(LOG_TAG_UI, "Importing ${array.length()} CustomDomain rules (Universal: $isUniversal)")
        
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
                Logger.i(LOG_TAG_UI, "Inserted CustomDomain: $domain (uid: $uid)")
            } else if (existing.status != status || existing.type != type || existing.ips != ips) {
                val newRule = com.celzero.bravedns.database.CustomDomain(null)
                newRule.domain = domain
                newRule.uid = uid
                newRule.status = status
                newRule.type = type
                newRule.ips = ips
                customDomainRepository.update(existing, newRule)
                Logger.i(LOG_TAG_UI, "Updated CustomDomain: $domain (uid: $uid)")
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
    private val setupAutoStateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("automated_export_state_uri", uri.toString()).apply()
                        Toast.makeText(this, "Automated state export location saved!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission", e)
                        Toast.makeText(this, "Failed to save location", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val setupAutoStatsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("automated_export_stats_uri", uri.toString()).apply()
                        Toast.makeText(this, "Automated stats export location saved!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("CustomSettings", "Failed to take persistable URI permission", e)
                        Toast.makeText(this, "Failed to save location", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private fun parseDays(cmdObj: JSONObject): Pair<Set<Int>?, String> {
        if (!cmdObj.has("days")) {
            return Pair(null, "ALL")
        }

        val rawDays = mutableListOf<String>()
        val daysVal = cmdObj.get("days")
        if (daysVal is JSONArray) {
            for (i in 0 until daysVal.length()) {
                val item = daysVal.optString(i, "").trim()
                if (item.isNotEmpty()) rawDays.add(item)
            }
        } else {
            val str = daysVal.toString().trim()
            if (str.isEmpty() || str.equals("ALL", ignoreCase = true)) {
                return Pair(null, "ALL")
            }
            str.split(",").forEach {
                val trimmed = it.trim()
                if (trimmed.isNotEmpty()) rawDays.add(trimmed)
            }
        }

        if (rawDays.isEmpty() || rawDays.any { it.equals("ALL", ignoreCase = true) }) {
            return Pair(null, "ALL")
        }

        val calendarDays = mutableSetOf<Int>()
        for (dayStr in rawDays) {
            when (dayStr.uppercase()) {
                "SUN", "SUNDAY" -> calendarDays.add(java.util.Calendar.SUNDAY)
                "MON", "MONDAY" -> calendarDays.add(java.util.Calendar.MONDAY)
                "TUE", "TUES", "TUESDAY" -> calendarDays.add(java.util.Calendar.TUESDAY)
                "WED", "WEDNESDAY" -> calendarDays.add(java.util.Calendar.WEDNESDAY)
                "THU", "THUR", "THURS", "THURSDAY" -> calendarDays.add(java.util.Calendar.THURSDAY)
                "FRI", "FRIDAY" -> calendarDays.add(java.util.Calendar.FRIDAY)
                "SAT", "SATURDAY" -> calendarDays.add(java.util.Calendar.SATURDAY)
                else -> Logger.w(LOG_TAG_UI, "Unknown day in 'days': $dayStr")
            }
        }

        return if (calendarDays.isEmpty() || calendarDays.size == 7) {
            Pair(null, "ALL")
        } else {
            Pair(calendarDays, rawDays.joinToString(","))
        }
    }

    private fun parsePeriodMillis(periodStr: String): Long? {
        val trimmed = periodStr.trim()
        val regex = Regex("""(\d+)\s*([dhmsDHMS])""")
        val matches = regex.findAll(trimmed).toList()
        if (matches.isEmpty()) return null
        var totalMillis = 0L
        for (match in matches) {
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = match.groupValues[2].lowercase(Locale.US)
            val millis = when (unit) {
                "d" -> amount * 24 * 60 * 60 * 1000L
                "h" -> amount * 60 * 60 * 1000L
                "m" -> amount * 60 * 1000L
                "s" -> amount * 1000L
                else -> 0L
            }
            totalMillis += millis
        }
        return if (totalMillis > 0) totalMillis else null
    }

    private fun parseTimeOfDay(raw: String): Pair<Int, Int>? {
        val trimmed = raw.trim()
        val amPmRegex = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(AM|PM|am|pm)$""", RegexOption.IGNORE_CASE)
        val matchAmPm = amPmRegex.find(trimmed)
        if (matchAmPm != null) {
            val hStr = matchAmPm.groupValues[1]
            val mStr = matchAmPm.groupValues[2]
            val amPm = matchAmPm.groupValues[3].uppercase(Locale.US)
            var hour = hStr.toIntOrNull() ?: return null
            val minute = if (mStr.isNotEmpty()) mStr.toIntOrNull() ?: 0 else 0
            if (hour !in 1..12 || minute !in 0..59) return null
            if (amPm == "PM" && hour < 12) hour += 12
            if (amPm == "AM" && hour == 12) hour = 0
            return Pair(hour, minute)
        }

        val h24Regex = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")
        val match24 = h24Regex.find(trimmed)
        if (match24 != null) {
            val hour = match24.groupValues[1].toIntOrNull() ?: return null
            val minute = match24.groupValues[2].toIntOrNull() ?: return null
            if (hour in 0..23 && minute in 0..59) {
                return Pair(hour, minute)
            }
        }
        return null
    }

    private fun parseFullDateTime(raw: String): Long? {
        val patterns = arrayOf(
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd hh:mm a",
            "yyyy-MM-dd hh:mm a",
            "yyyy/MM/dd hh:mma",
            "yyyy-MM-dd hh:mma"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(raw.trim())
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun resolveExportTimeRange(cmdObj: JSONObject, runTime: Long): Pair<Long, Long>? {
        if (cmdObj.has("period")) {
            val periodStr = cmdObj.getString("period")
            val millis = parsePeriodMillis(periodStr)
            if (millis != null) {
                val toTime = runTime
                val fromTime = toTime - millis
                return Pair(fromTime, toTime)
            } else {
                Logger.w(LOG_TAG_UI, "Invalid period format: $periodStr")
            }
        }

        val fromRaw = cmdObj.optString("from", "").trim()
        val toRaw = cmdObj.optString("to", "").trim()

        if (fromRaw.isEmpty() && toRaw.isEmpty()) {
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return Pair(startOfDay, runTime)
        }

        val fullFrom = if (fromRaw.isNotEmpty()) parseFullDateTime(fromRaw) else null
        val fullTo = if (toRaw.isNotEmpty()) parseFullDateTime(toRaw) else null

        if (fullFrom != null || fullTo != null) {
            val from = fullFrom ?: Calendar.getInstance().apply {
                timeInMillis = fullTo ?: runTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val to = fullTo ?: runTime
            return Pair(from, to)
        }

        val fromTod = if (fromRaw.isNotEmpty()) parseTimeOfDay(fromRaw) else null
        val toTod = if (toRaw.isNotEmpty()) parseTimeOfDay(toRaw) else null

        if (fromTod != null && toTod != null) {
            val (h1, m1) = fromTod
            val (h2, m2) = toTod

            // Full-day case (e.g., 00:00 to 23:59)
            if (h1 == 0 && m1 == 0 && h2 == 23 && m2 >= 50) {
                val startCal = Calendar.getInstance().apply {
                    timeInMillis = runTime
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = Calendar.getInstance().apply {
                    timeInMillis = startCal.timeInMillis
                    add(Calendar.DAY_OF_MONTH, 1)
                }
                return Pair(startCal.timeInMillis, endCal.timeInMillis)
            }

            val minutes1 = h1 * 60 + m1
            val minutes2 = h2 * 60 + m2

            val (earlierH, earlierM) = if (minutes1 <= minutes2) Pair(h1, m1) else Pair(h2, m2)
            val (laterH, laterM) = if (minutes1 <= minutes2) Pair(h2, m2) else Pair(h1, m1)

            // End time is earlier time (e.g. 10AM) on the most recent occurrence at or before runTime (e.g. 20/Oct 10AM)
            val endCal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, earlierH)
                set(Calendar.MINUTE, earlierM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (endCal.timeInMillis > runTime) {
                endCal.add(Calendar.DAY_OF_MONTH, -1)
            }

            // Start time is later time (e.g. 3PM) on the day before endCal (e.g. 19/Oct 3PM)
            val startCal = Calendar.getInstance().apply {
                timeInMillis = endCal.timeInMillis
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.HOUR_OF_DAY, laterH)
                set(Calendar.MINUTE, laterM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return Pair(startCal.timeInMillis, endCal.timeInMillis)
        }

        if (fromTod != null) {
            val (h, m) = fromTod
            val cal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis > runTime) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            return Pair(cal.timeInMillis, runTime)
        }

        if (toTod != null) {
            val (h, m) = toTod
            val toCal = Calendar.getInstance().apply {
                timeInMillis = runTime
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (toCal.timeInMillis > runTime) {
                toCal.add(Calendar.DAY_OF_MONTH, -1)
            }
            val fromCal = Calendar.getInstance().apply {
                timeInMillis = toCal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return Pair(fromCal.timeInMillis, toCal.timeInMillis)
        }

        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = runTime
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Pair(startOfDay, runTime)
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result
    }

    @Synchronized
    private fun logActivity(type: String, action: String, fileName: String, status: String, details: String = "") {
        try {
            val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
            val logsJson = prefs.getString("activity_logs_json", "[]") ?: "[]"
            val array = JSONArray(logsJson)
            val obj = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("type", type)
                put("action", action)
                put("fileName", fileName)
                put("status", status)
                put("details", details)
            }
            val newArray = JSONArray()
            newArray.put(obj)
            val limit = minOf(array.length(), 99)
            for (i in 0 until limit) {
                newArray.put(array.getJSONObject(i))
            }
            prefs.edit().putString("activity_logs_json", newArray.toString()).apply()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error writing activity log: ${e.message}", e)
        }
    }

    private fun showJobsDialog() {
        val jobs = activeJobs.filter { it.job.isActive }
        if (jobs.isEmpty()) {
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle("Scheduled Jobs")
                .setMessage("No pending or recurring jobs are currently scheduled.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val context = this
        val isDark = isDarkThemeOn()
        val cardBg = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#F5F5F5")
        val titleColor = if (isDark) Color.parseColor("#EEEEEE") else Color.parseColor("#212121")
        val subtitleColor = if (isDark) Color.parseColor("#B0B0B0") else Color.parseColor("#616161")

        val scrollView = android.widget.ScrollView(context)
        val rootLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 16, 36, 16)
        }
        scrollView.addView(rootLayout)

        var dialog: androidx.appcompat.app.AlertDialog? = null

        for (jobInfo in jobs) {
            val itemCard = com.google.android.material.card.MaterialCardView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                radius = 16f
                cardElevation = 2f
                setCardBackgroundColor(cardBg)
            }

            val row = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24, 20, 16, 20)
            }

            val infoLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val tvTitle = androidx.appcompat.widget.AppCompatTextView(context).apply {
                text = "${jobInfo.command.uppercase()} ${jobInfo.data.uppercase()} [${jobInfo.file}]"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(titleColor)
            }

            val tvSubtitle = androidx.appcompat.widget.AppCompatTextView(context).apply {
                val typeStr = if (jobInfo.isRecurring) "Recurring" else "Scheduled"
                text = "⏰ ${jobInfo.executeAt} • Days: ${jobInfo.days} ($typeStr)"
                textSize = 13f
                setTextColor(subtitleColor)
                setPadding(0, 4, 0, 0)
            }

            infoLayout.addView(tvTitle)
            infoLayout.addView(tvSubtitle)

            val btnDelete = android.widget.TextView(context).apply {
                text = "✕"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#E53935"))
                setPadding(24, 16, 24, 16)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    jobInfo.job.cancel()
                    activeJobs.remove(jobInfo)
                    Toast.makeText(context, "Cancelled ${jobInfo.command} ${jobInfo.data}", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    showJobsDialog()
                }
            }

            row.addView(infoLayout)
            row.addView(btnDelete)
            itemCard.addView(row)
            rootLayout.addView(itemCard)
        }

        dialog = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle("Scheduled Jobs (${jobs.size})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Cancel All") { _, _ ->
                activeJobs.forEach { it.job.cancel() }
                activeJobs.clear()
                Toast.makeText(context, "All scheduled jobs cancelled", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLogsDialog() {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val logsJson = prefs.getString("activity_logs_json", "[]") ?: "[]"
        val logsArray = try {
            JSONArray(logsJson)
        } catch (e: Exception) {
            JSONArray()
        }

        if (logsArray.length() == 0) {
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle("Import & Export Logs")
                .setMessage("No import/export activity recorded yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val context = this
        val isDark = isDarkThemeOn()
        val scrollView = android.widget.ScrollView(context)
        val rootLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 16, 36, 16)
        }
        scrollView.addView(rootLayout)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (i in 0 until logsArray.length()) {
            val logObj = logsArray.optJSONObject(i) ?: continue
            val ts = logObj.optLong("timestamp", 0L)
            val timeStr = if (ts > 0L) sdf.format(Date(ts)) else "Unknown time"
            val type = logObj.optString("type", "Manual")
            val action = logObj.optString("action", "")
            val fileName = logObj.optString("fileName", "")
            val status = logObj.optString("status", "")
            val details = logObj.optString("details", "")

            val cardBg = when (status) {
                "SUCCESS" -> if (isDark) Color.parseColor("#1B3320") else Color.parseColor("#F1F8E9")
                "FAILED" -> if (isDark) Color.parseColor("#3E1F1F") else Color.parseColor("#FFEBEE")
                else -> if (isDark) Color.parseColor("#3E3219") else Color.parseColor("#FFF8E1")
            }
            val statusColor = when (status) {
                "SUCCESS" -> if (isDark) Color.parseColor("#81C784") else Color.parseColor("#2E7D32")
                "FAILED" -> if (isDark) Color.parseColor("#E57373") else Color.parseColor("#C62828")
                else -> if (isDark) Color.parseColor("#FFD54F") else Color.parseColor("#F57F17")
            }
            val fileColor = if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#212121")
            val timeColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
            val detailsColor = if (isDark) Color.parseColor("#CCCCCC") else Color.parseColor("#424242")

            val card = com.google.android.material.card.MaterialCardView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                radius = 16f
                cardElevation = 2f
                setCardBackgroundColor(cardBg)
            }

            val content = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
            }

            val statusIcon = when (status) {
                "SUCCESS" -> "✓"
                "FAILED" -> "✗"
                else -> "⏭️"
            }

            val tvHeader = androidx.appcompat.widget.AppCompatTextView(context).apply {
                text = "$statusIcon [$type] $action • $status"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(statusColor)
            }

            val tvTime = androidx.appcompat.widget.AppCompatTextView(context).apply {
                text = "🕒 $timeStr"
                textSize = 12f
                setTextColor(timeColor)
                setPadding(0, 2, 0, 2)
            }

            val tvFile = androidx.appcompat.widget.AppCompatTextView(context).apply {
                text = "📄 File: $fileName"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(fileColor)
                setPadding(0, 4, 0, 2)
            }

            content.addView(tvHeader)
            content.addView(tvTime)
            content.addView(tvFile)

            if (details.isNotEmpty()) {
                val tvDetails = androidx.appcompat.widget.AppCompatTextView(context).apply {
                    text = "💬 $details"
                    textSize = 12f
                    setTextColor(detailsColor)
                    setPadding(0, 2, 0, 0)
                }
                content.addView(tvDetails)
            }

            card.addView(content)
            rootLayout.addView(card)
        }

        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle("Import & Export Logs (${logsArray.length()})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Logs") { _, _ ->
                prefs.edit().remove("activity_logs_json").apply()
                Toast.makeText(context, "Activity logs cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun getAuthorizedFileUri(vararg candidateNames: String): android.net.Uri? {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("authorized_files_map", "{}") ?: "{}"
        try {
            val jsonObject = JSONObject(jsonString)
            // 1. Exact match on candidate names
            for (name in candidateNames) {
                val trimmed = name.trim()
                if (jsonObject.has(trimmed)) {
                    val uriStr = jsonObject.getString(trimmed)
                    if (!uriStr.isNullOrEmpty()) return android.net.Uri.parse(uriStr)
                }
            }
            // 2. Case-insensitive match on candidate names
            val keys = jsonObject.keys()
            val keyList = mutableListOf<String>()
            while (keys.hasNext()) {
                keyList.add(keys.next())
            }
            for (candidate in candidateNames) {
                val trimmed = candidate.trim()
                val matchedKey = keyList.find { it.trim().equals(trimmed, ignoreCase = true) }
                if (matchedKey != null) {
                    val uriStr = jsonObject.getString(matchedKey)
                    if (!uriStr.isNullOrEmpty()) return android.net.Uri.parse(uriStr)
                }
            }
            // 3. Substring match fallback (e.g. key contains "automated_state" or "stats.xls")
            for (candidate in candidateNames) {
                val trimmed = candidate.trim()
                val matchedKey = keyList.find { it.contains(trimmed, ignoreCase = true) }
                if (matchedKey != null) {
                    val uriStr = jsonObject.getString(matchedKey)
                    if (!uriStr.isNullOrEmpty()) return android.net.Uri.parse(uriStr)
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "Error reading authorized_files_map: ${e.message}", e)
        }
        return null
    }

    private fun getAuthorizedFilesCount(): Int {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("authorized_files_map", "{}") ?: "{}"
        return try {
            JSONObject(jsonString).length()
        } catch (e: Exception) {
            0
        }
    }

    private fun updateAutomationUi() {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("automation_service_enabled", false)
        val count = getAuthorizedFilesCount()

        // Detach listener while setting state programmatically to avoid false triggers
        b.acsAutomationSwitch.setOnCheckedChangeListener(null)
        b.acsAutomationSwitch.isChecked = isEnabled && count > 0

        if (count == 0) {
            b.acsAutomationStatus.text = "⚠️ 0 out of 4 files authorized (Setup required)"
            b.acsAutomationStatus.setTextColor(Color.parseColor("#C62828"))
            if (isEnabled) {
                prefs.edit().putBoolean("automation_service_enabled", false).apply()
                b.acsAutomationSwitch.isChecked = false
            }
        } else if (count < 4) {
            b.acsAutomationStatus.text = "⚠️ $count out of 4 files authorized"
            b.acsAutomationStatus.setTextColor(Color.parseColor("#E65100"))
        } else {
            b.acsAutomationStatus.text = "✓ $count files authorized"
            b.acsAutomationStatus.setTextColor(Color.parseColor("#2E7D32"))
        }

        b.acsAutomationSwitch.setOnCheckedChangeListener { _, isChecked ->
            val fileCount = getAuthorizedFilesCount()
            if (isChecked) {
                if (fileCount == 0) {
                    showAutomationPermissionsDialog()
                } else {
                    prefs.edit().putBoolean("automation_service_enabled", true).apply()
                    Toast.makeText(this, "Automation service enabled", Toast.LENGTH_SHORT).show()
                    checkFirstTimeNotice(prefs)
                }
            } else {
                prefs.edit().putBoolean("automation_service_enabled", false).apply()
                Toast.makeText(this, "Automation service disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkFirstTimeNotice(prefs: android.content.SharedPreferences) {
        val isFirstTime = !prefs.getBoolean("automation_first_time_notice_shown", false)
        if (isFirstTime) {
            prefs.edit().putBoolean("automation_first_time_notice_shown", true).apply()
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle("Automation Service Active")
                .setMessage("Background polling (every 5 minutes) is now active.\n\nEnsure the required files (rethink_command.json, rethink_automated_state.json, rethink_automated_stats.xls, rethink_state.json) are created in your folder/drive and authorized in Automation Setup.")
                .setPositiveButton("Got It", null)
                .setNeutralButton("View Authorized Files") { _, _ ->
                    startActivity(Intent(this, AuthorizedFilesActivity::class.java))
                }
                .show()
        }
    }

    private fun showAutomationPermissionsDialog() {
        val message = """
To run background automation, Rethink requires persistent Storage Access Framework (SAF) permissions.

⚠️ Important: Please create these files in your local folder or Google Drive first before adding authorization:

1. rethink_command.json (Read Permission)
   Reads your automation tasks and schedules.

2. rethink_automated_state.json (Write Permission)
   Stores exported app & firewall state.

3. rethink_automated_stats.xls (Write Permission)
   Exports network logs to Excel.

4. rethink_state.json or custom state files (Read Permission)
   Imports firewall & DNS rules (e.g. rethink_state_open.json, rethink_state_locked.json).

Once the files are created in your folder or Google Drive, tap "Configure Files" to select and authorize each file.
        """.trimIndent()

        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle("File Permissions Required")
            .setMessage(message)
            .setPositiveButton("Configure Files") { _, _ ->
                b.acsAutomationSwitch.isChecked = false
                startActivity(Intent(this, AuthorizedFilesActivity::class.java))
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                b.acsAutomationSwitch.isChecked = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                b.acsAutomationSwitch.isChecked = false
            }
            .setCancelable(false)
            .show()
    }
}

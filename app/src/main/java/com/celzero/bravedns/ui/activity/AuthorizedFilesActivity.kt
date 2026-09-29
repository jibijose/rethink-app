package com.celzero.bravedns.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.json.JSONObject

class AuthorizedFilesActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AuthorizedFilesAdapter
    private var authorizedFiles = mutableMapOf<String, String>()

    private val addFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    // Take persistable permission so we can read it later in background
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    // Get file name
                    val fileName = getFileName(uri) ?: "unknown_file_${System.currentTimeMillis()}"

                    // Save mapping
                    authorizedFiles[fileName] = uri.toString()
                    saveAuthorizedFiles()
                    
                    adapter.updateData(authorizedFiles)
                    Toast.makeText(this@AuthorizedFilesActivity, "Authorized: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(this@AuthorizedFilesActivity, "Failed to authorize file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_authorized_files)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.files_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadAuthorizedFiles()
        
        adapter = AuthorizedFilesAdapter(authorizedFiles) { fileName ->
            removeFile(fileName)
        }
        recyclerView.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.btn_add_file).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                val mimeTypes = arrayOf("application/json", "application/vnd.ms-excel")
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
            addFileLauncher.launch(intent)
        }
    }

    private fun loadAuthorizedFiles() {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("authorized_files_map", "{}")
        try {
            val jsonObject = JSONObject(jsonString!!)
            authorizedFiles.clear()
            jsonObject.keys().forEach { key ->
                authorizedFiles[key] = jsonObject.getString(key)
            }
        } catch (e: Exception) {
            authorizedFiles.clear()
        }
    }

    private fun saveAuthorizedFiles() {
        val prefs = getSharedPreferences("RethinkPrefs", Context.MODE_PRIVATE)
        val jsonObject = JSONObject()
        authorizedFiles.forEach { (key, value) ->
            jsonObject.put(key, value)
        }
        prefs.edit().putString("authorized_files_map", jsonObject.toString()).apply()
    }

    private fun removeFile(fileName: String) {
        val uriString = authorizedFiles.remove(fileName)
        if (uriString != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(uriString), 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Ignore if permission was already lost
            }
        }
        saveAuthorizedFiles()
        adapter.updateData(authorizedFiles)
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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
}

class AuthorizedFilesAdapter(
    private var data: Map<String, String>,
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<AuthorizedFilesAdapter.ViewHolder>() {

    private var keys = data.keys.toList()

    fun updateData(newData: Map<String, String>) {
        data = newData.toMap()
        keys = data.keys.toList()
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tv_file_name)
        val tvFileUri: TextView = view.findViewById(R.id.tv_file_uri)
        val btnRemove: ImageView = view.findViewById(R.id.btn_remove_file)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_authorized_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = keys[position]
        holder.tvFileName.text = key
        holder.tvFileUri.text = data[key]
        holder.btnRemove.setOnClickListener {
            onRemoveClick(key)
        }
    }

    override fun getItemCount() = keys.size
}

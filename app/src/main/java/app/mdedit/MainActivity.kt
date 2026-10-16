package app.mdedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) vm.addTree(uri)
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.openExternal(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MdEditTheme {
                MdEditApp(
                    vm = vm,
                    pickFolder = { folderPicker.launch(null) },
                    pickFile = { filePicker.launch(arrayOf("*/*")) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) vm.openExternal(data)
    }
}

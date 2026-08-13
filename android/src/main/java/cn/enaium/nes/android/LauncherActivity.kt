package cn.enaium.nes.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.init
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import java.io.File

/**
 * First activity: a Compose UI that uses FileKit to pick a NES ROM. The picked
 * ROM is copied into the app's internal storage and the native emulator
 * activity is launched with the resulting path.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FileKit needs the ActivityResultRegistry to present the picker.
        FileKit.init(this)

        setContent {
            MaterialTheme {
                var message by remember { mutableStateOf("Pick a NES ROM (.nes) to start.") }
                val scope = rememberCoroutineScope()

                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                message = "Opening file picker..."
                                val file = try {
                                    FileKit.openFilePicker(type = FileKitType.File("nes"))
                                } catch (e: Throwable) {
                                    null
                                }
                                if (file == null) {
                                    message = "Pick cancelled."
                                    return@launch
                                }
                                try {
                                    val bytes = file.readBytes()
                                    val dest = File(filesDir, "rom.nes")
                                    dest.writeBytes(bytes)
                                    message = "Starting emulator..."
                                    startActivity(
                                        Intent(this@LauncherActivity, EmulatorActivity::class.java)
                                            .putExtra("rom_path", dest.absolutePath),
                                    )
                                } catch (e: Throwable) {
                                    message = "Failed to load ROM: ${e.message}"
                                }
                            }
                        },
                    ) {
                        Text("Choose NES ROM")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(message)
                }
            }
        }
    }
}

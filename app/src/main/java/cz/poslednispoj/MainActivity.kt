package cz.poslednispoj

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra("ps_select")?.let { stop ->
            val legs = intent.getStringExtra("ps_legs")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            vm.debugSelect(intent.getStringExtra("ps_dest"), stop, legs)
        }
        setContent {
            val holder = rememberMapHolder(vm)
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                if (res.values.any { it }) holder.enableLocation()
            }
            LaunchedEffect(Unit) {
                if (hasLocationPermission()) holder.enableLocation()
                else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            App(vm, holder, onLocate = {
                if (!hasLocationPermission()) {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    holder.enableLocation()
                    if (!holder.zoomToMe()) Toast.makeText(this, "Poloha zatím není známá, zkus to za chvíli.", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

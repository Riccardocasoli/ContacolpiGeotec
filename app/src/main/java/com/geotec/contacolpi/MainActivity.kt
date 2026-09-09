package com.geotec.contacolpi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.*

data class IntervalData(
    val depthMeter: Double,
    var blowCount: Int
)

data class LocationUTM32N(
    val eastX: Double,
    val northY: Double,
    val altitudeZ: Double,
    val accuracyMeters: Float
)

data class TestSession(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String,
    val operatorName: String,
    val testType: String,
    val date: String,
    val location: LocationUTM32N?,
    val intervals: List<IntervalData>
)

// Funzione matematica per la conversione da WGS84 (Lat, Lon) a EPSG:32632 (UTM 32N)
fun wgs84ToEPSG32632(lat: Double, lon: Double): Pair<Double, Double> {
    val a = 6378137.0 // Semiasse maggiore WGS84
    val f = 1 / 298.257223563
    val k0 = 0.9996
    val e2 = f * (2 - f)
    val e2prime = e2 / (1 - e2)

    val latRad = Math.toRadians(lat)
    val lonRad = Math.toRadians(lon)

    val lon0 = Math.toRadians(9.0) // Meridiano centrale per fuso 32 (9° E)

    val N = a / sqrt(1 - e2 * sin(latRad) * sin(latRad))
    val T = tan(latRad) * tan(latRad)
    val C = e2prime * cos(latRad) * cos(latRad)
    val A = (lonRad - lon0) * cos(latRad)

    val M = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latRad
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latRad)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latRad)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * latRad))

    val easting = k0 * N * (A + (1 - T + C) * A * A * A / 6
            + (5 - 18 * T + T * T + 72 * C - 58 * e2prime) * A * A * A * A * A / 120) + 500000.0

    val northing = k0 * (M + N * tan(latRad) * (A * A / 2
            + (5 - T + 9 * C + 4 * C * C) * A * A * A * A / 24
            + (61 - 58 * T + T * T + 600 * C - 330 * e2prime) * A * A * A * A * A * A / 720))

    return Pair(easting, northing)
}

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = audioGranted
        hasLocationPermission = locationGranted

        if (!audioGranted || !locationGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ContacolpiApp(
                        hasAudioPermission = hasAudioPermission,
                        hasLocationPermission = hasLocationPermission
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContacolpiApp(hasAudioPermission: Boolean, hasLocationPermission: Boolean) {
    val context = LocalContext.current
    var isTestActive by remember { mutableStateOf(false) }

    var projectName by remember { mutableStateOf("Cantiere Alpha") }
    var operatorName by remember { mutableStateOf("Ing. Rossi") }
    var selectedTestType by remember { mutableStateOf("DPSH") }
    var stepSizeCm by remember { mutableStateOf("20") }

    var currentLocationUTM by remember { mutableStateOf<LocationUTM32N?>(null) }
    var isLocating by remember { mutableStateOf(false) }

    val completedTests = remember { mutableStateListOf<TestSession>() }
    var showMenu by remember { mutableStateOf(false) }

    if (!isTestActive) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Contacolpi Geotec - Home") },
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Info Sistema") },
                                onClick = { showMenu = false }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Statistiche Cantiere",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Prove per '$projectName': ${completedTests.count { it.projectName == projectName }}")
                        Text("Totale prove registrate: ${completedTests.size}")
                    }
                )

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Nome Cantiere / Progetto") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = operatorName,
                    onValueChange = { operatorName = it },
                    label = { Text("Operatore") },
                    modifier = Modifier.fillMaxWidth()
                )

                val testTypes = listOf("DPSH", "DPM", "DPL", "DL30", "SPT", "Personalizzato")
                var expandedType by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTestType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tipo Prova Penetrometrica") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        testTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    selectedTestType = type
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = stepSizeCm,
                    onValueChange = { stepSizeCm = it },
                    label = { Text("Avanzamento per intervallo (cm)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        isLocating = true
                        if (hasLocationPermission) {
                            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                            try {
                                fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                                    if (loc != null) {
                                        val (x, y) = wgs84ToEPSG32632(loc.latitude, loc.longitude)
                                        currentLocationUTM = LocationUTM32N(
                                            eastX = x,
                                            northY = y,
                                            altitudeZ = loc.altitude,
                                            accuracyMeters = loc.accuracy
                                        )
                                    }
                                    isLocating = false
                                    isTestActive = true
                                }.addOnFailureListener {
                                    isLocating = false
                                    isTestActive = true
                                }
                            } catch (e: SecurityException) {
                                isLocating = false
                                isTestActive = true
                            }
                        } else {
                            isLocating = false
                            isTestActive = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    enabled = hasAudioPermission && !isLocating
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rilevamento GPS in corso...")
                    } else {
                        Text("AVVIA NUOVA PROVA", fontSize = 18.sp)
                    }
                }
            }
        }
    } else {
        CountingScreen(
            projectName = projectName,
            operatorName = operatorName,
            testType = selectedTestType,
            stepCm = stepSizeCm.toDoubleOrNull() ?: 20.0,
            hasAudioPermission = hasAudioPermission,
            locationUTM = currentLocationUTM,
            onFinishTest = { session ->
                if (session != null) {
                    completedTests.add(session)
                }
                isTestActive = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingScreen(
    projectName: String,
    operatorName: String,
    testType: String,
    stepCm: Double,
    hasAudioPermission: Boolean,
    locationUTM: LocationUTM32N?,
    onFinishTest: (TestSession?) -> Unit
) {
    val context = LocalContext.current
    val stepMeter = stepCm / 100.0

    val intervals = remember { mutableStateListOf(IntervalData(stepMeter, 0)) }
    var currentIntervalIndex by remember { mutableStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var sensitivityThreshold by remember { mutableStateOf(3000f) }

    val listState = rememberLazyListState()

    LaunchedEffect(intervals.size, currentIntervalIndex) {
        if (intervals.isNotEmpty()) {
            listState.animateScrollToItem(intervals.size - 1)
        }
    }

    DisposableEffect(isRecording) {
        var audioRecord: AudioRecord? = null
        var isThreadRunning = false

        if (isRecording && hasAudioPermission) {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                isThreadRunning = true

                thread {
                    val buffer = ShortArray(bufferSize)
                    var lastPeakTime = 0L

                    while (isThreadRunning) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var maxAmp = 0
                            for (i in 0 until read) {
                                val absVal = abs(buffer[i].toInt())
                                if (absVal > maxAmp) maxAmp = absVal
                            }

                            val now = System.currentTimeMillis()
                            if (maxAmp > sensitivityThreshold && (now - lastPeakTime) > 300) {
                                lastPeakTime = now
                                if (currentIntervalIndex < intervals.size) {
                                    intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                                        blowCount = intervals[currentIntervalIndex].blowCount + 1
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        onDispose {
            isThreadRunning = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var showCancelDialog by remember { mutableStateOf(false) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Interrompere la prova?") },
            text = { Text("Tornando alla schermata iniziale, la prova corrente verrà annullata.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onFinishTest(null)
                }) {
                    Text("Annulla ed Esci")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continua Prova")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$projectName ($testType)") },
                navigationIcon = {
                    IconButton(onClick = { showCancelDialog = true }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Esci")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val session = TestSession(
                            projectName = projectName,
                            operatorName = operatorName,
                            testType = testType,
                            date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                            location = locationUTM,
                            intervals = intervals.toList()
                        )
                        exportAndShareCSV(context, session)
                    }) {
                        Text("CSV", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Operatore: $operatorName", style = MaterialTheme.typography.bodySmall)
                Text("Tratto attuale: #${currentIntervalIndex + 1}", style = MaterialTheme.typography.bodySmall)
            }

            // Indicatore Coordinate GPS EPSG:32632
            if (locationUTM != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "GPS (EPSG:32632): X=%.2f m | Y=%.2f m | Z=%.1f m (prec. ±%.1fm)",
                            locationUTM.eastX, locationUTM.northY, locationUTM.altitudeZ, locationUTM.accuracyMeters
                        ),
                        modifier = Modifier.padding(6.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2E7D32)
                    )
                }
            } else {
                Text(
                    text = "GPS non disponibile o non acquisito",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(6.dp)
            ) {
                itemsIndexed(intervals) { idx, item ->
                    val isCurrent = idx == currentIntervalIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1f m", item.depthMeter),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${item.blowCount} colpi",
                                fontSize = 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val nextDepth = (intervals.size + 1) * stepMeter
                    intervals.add(IntervalData(nextDepth, 0))
                    currentIntervalIndex = intervals.size - 1
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PROSSIMO TRATTO (+${stepCm.toInt()} cm)")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = {
                    if (intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                    }
                }) {
                    Text("-1")
                }

                OutlinedButton(onClick = {
                    if (currentIntervalIndex > 0 && intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                        intervals[currentIntervalIndex - 1] = intervals[currentIntervalIndex - 1].copy(
                            blowCount = intervals[currentIntervalIndex - 1].blowCount + 1
                        )
                    }
                }) {
                    Text("< Sposta prec.")
                }

                OutlinedButton(onClick = {
                    if (currentIntervalIndex < intervals.size - 1 && intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                        intervals[currentIntervalIndex + 1] = intervals[currentIntervalIndex + 1].copy(
                            blowCount = intervals[currentIntervalIndex + 1].blowCount + 1
                        )
                    }
                }) {
                    Text("Sposta succ. >")
                }

                OutlinedButton(onClick = {
                    intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                        blowCount = intervals[currentIntervalIndex].blowCount + 1
                    )
                }) {
                    Text("+1")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { isRecording = !isRecording },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    )
                ) {
                    Text(if (isRecording) "PAUSA ASCOLTO" else "AVVIA ASCOLTO")
                }

                Text(
                    text = if (isRecording) "Microfono attivo" else "In pausa",
                    fontSize = 12.sp,
                    color = if (isRecording) Color(0xFF388E3C) else Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = {
                    val session = TestSession(
                        projectName = projectName,
                        operatorName = operatorName,
                        testType = testType,
                        date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                        location = locationUTM,
                        intervals = intervals.toList()
                    )
                    onFinishTest(session)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("FINE PROVA")
            }
        }
    }
}

fun exportAndShareCSV(context: Context, session: TestSession) {
    try {
        val fileName = "Contacolpi_${session.projectName.replace(" ", "_")}_${System.currentTimeMillis()}.csv"
        val file = File(context.cacheDir, fileName)

        val builder = StringBuilder()
        builder.append("Cantiere;${session.projectName}\n")
        builder.append("Operatore;${session.operatorName}\n")
        builder.append("Tipo Prova;${session.testType}\n")
        builder.append("Data;${session.date}\n")
        builder.append("Sistema Riferimento;EPSG:32632 (UTM Zona 32N)\n")

        if (session.location != null) {
            builder.append(String.format(Locale.US, "Coord X (Est);%.2f\n", session.location.eastX))
            builder.append(String.format(Locale.US, "Coord Y (Nord);%.2f\n", session.location.northY))
            builder.append(String.format(Locale.US, "Quota Z (m s.l.m.);%.1f\n", session.location.altitudeZ))
            builder.append(String.format(Locale.US, "Accuratezza GPS (m);%.1f\n", session.location.accuracyMeters))
        } else {
            builder.append("Coord X (Est);N/D\n")
            builder.append("Coord Y (Nord);N/D\n")
            builder.append("Quota Z (m s.l.m.);N/D\n")
        }

        builder.append("\nProfondita (m);Colpi\n")

        for (interval in session.intervals) {
            builder.append(String.format(Locale.US, "%.1f;%d\n", interval.depthMeter, interval.blowCount))
        }

        file.writeText(builder.toString())

        val uri = FileProvider.getUriForFile(
            context,
            "com.geotec.contacolpi.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Esporta CSV con:"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Errore durante l'esportazione CSV", Toast.LENGTH_SHORT).show()
    }
}package com.geotec.contacolpi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

data class IntervalData(
    val depthMeter: Double,
    var blowCount: Int
)

data class TestSession(
    val id: String = UUID.randomUUID().toString(),
    val projectName: String,
    val operatorName: String,
    val testType: String,
    val date: String,
    val intervals: List<IntervalData>
)

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            hasAudioPermission = true
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ContacolpiApp(hasAudioPermission = hasAudioPermission)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContacolpiApp(hasAudioPermission: Boolean) {
    var isTestActive by remember { mutableStateOf(false) }

    // Parametri cantiere e prova
    var projectName by remember { mutableStateOf("Cantiere Alpha") }
    var operatorName by remember { mutableStateOf("Ing. Rossi") }
    var selectedTestType by remember { mutableStateOf("DPSH") }
    var stepSizeCm by remember { mutableStateOf("20") } // cm per intervallo

    // Storico prove locali
    val completedTests = remember { mutableStateListOf<TestSession>() }

    var showMenu by remember { mutableStateOf(false) }

    if (!isTestActive) {
        // SCHERMATA INIZIALE / SETUP
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Contacolpi Geotec - Home") },
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Esporta Tutte le Prove (CSV)") },
                                onClick = {
                                    showMenu = false
                                    // Gestito in sottoschermata o per singola prova
                                }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Riquadro Info Cantiere / Statistiche
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Statistiche Cantiere",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Prove completate per '$projectName': ${completedTests.count { it.projectName == projectName }}")
                        Text("Totale complessivo prove registrate: ${completedTests.size}")
                    }
                )

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Nome Cantiere / Progetto") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = operatorName,
                    onValueChange = { operatorName = it },
                    label = { Text("Operatore") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Selezione Tipo Prova
                val testTypes = listOf("DPSH", "DPM", "DPL", "DL30", "SPT", "Personalizzato")
                var expandedType by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expandedType,
                    onExpandedChange = { expandedType = !expandedType }
                ) {
                    OutlinedTextField(
                        value = selectedTestType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tipo Prova Penetrometrica") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        testTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    selectedTestType = type
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = stepSizeCm,
                    onValueChange = { stepSizeCm = it },
                    label = { Text("Avanzamento per intervallo (cm)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { isTestActive = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    enabled = hasAudioPermission
                ) {
                    Text("AVVIA NUOVA PROVA", fontSize = 18.sp)
                }
            }
        }
    } else {
        // SCHERMATA DI CONTEGGIO ATTIVA
        CountingScreen(
            projectName = projectName,
            operatorName = operatorName,
            testType = selectedTestType,
            stepCm = stepSizeCm.toDoubleOrNull() ?: 20.0,
            hasAudioPermission = hasAudioPermission,
            onFinishTest = { session ->
                if (session != null) {
                    completedTests.add(session)
                }
                isTestActive = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountingScreen(
    projectName: String,
    operatorName: String,
    testType: String,
    stepCm: Double,
    hasAudioPermission: Boolean,
    onFinishTest: (TestSession?) -> Unit
) {
    val context = LocalContext.current
    val stepMeter = stepCm / 100.0

    val intervals = remember { mutableStateListOf(IntervalData(stepMeter, 0)) }
    var currentIntervalIndex by remember { mutableStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var sensitivityThreshold by remember { mutableStateOf(3000f) }

    val listState = rememberLazyListState()

    // Scorrimento automatico verso l'ultimo elemento della lista
    LaunchedEffect(intervals.size, currentIntervalIndex) {
        if (intervals.isNotEmpty()) {
            listState.animateScrollToItem(intervals.size - 1)
        }
    }

    // Algoritmo di rilevamento audio
    DisposableEffect(isRecording) {
        var audioRecord: AudioRecord? = null
        var isThreadRunning = false

        if (isRecording && hasAudioPermission) {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                audioRecord.startRecording()
                isThreadRunning = true

                thread {
                    val buffer = ShortArray(bufferSize)
                    var lastPeakTime = 0L

                    while (isThreadRunning) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            var maxAmp = 0
                            for (i in 0 until read) {
                                val absVal = abs(buffer[i].toInt())
                                if (absVal > maxAmp) maxAmp = absVal
                            }

                            val now = System.currentTimeMillis()
                            if (maxAmp > sensitivityThreshold && (now - lastPeakTime) > 300) {
                                lastPeakTime = now
                                if (currentIntervalIndex < intervals.size) {
                                    intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                                        blowCount = intervals[currentIntervalIndex].blowCount + 1
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        onDispose {
            isThreadRunning = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var showCancelDialog by remember { mutableStateOf(false) }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Interrompere la prova?") },
            text = { Text("Tornando alla schermata iniziale, la prova corrente verrà annullata.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onFinishTest(null)
                }) {
                    Text("Annulla ed Esci")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continua Prova")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$projectName ($testType)") },
                navigationIcon = {
                    IconButton(onClick = { showCancelDialog = true }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Esci")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val session = TestSession(
                            projectName = projectName,
                            operatorName = operatorName,
                            testType = testType,
                            date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                            intervals = intervals.toList()
                        )
                        exportAndShareCSV(context, session)
                    }) {
                        Text("CSV", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            // Scheda Riepilogo Rapido
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Operatore: $operatorName", style = MaterialTheme.typography.bodySmall)
                Text("Tratto attuale: #${currentIntervalIndex + 1}", style = MaterialTheme.typography.bodySmall)
            }

            // Lista Graficizzata/Compatta dei tratti
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(6.dp)
            ) {
                itemsIndexed(intervals) { idx, item ->
                    val isCurrent = idx == currentIntervalIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1f m", item.depthMeter),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${item.blowCount} colpi",
                                fontSize = 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pulsante Prossimo Tratto / Avanzamento
            Button(
                onClick = {
                    val nextDepth = (intervals.size + 1) * stepMeter
                    intervals.add(IntervalData(nextDepth, 0))
                    currentIntervalIndex = intervals.size - 1
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PROSSIMO TRATTO (+${stepCm.toInt()} cm)")
            }

            // Tasti di Correzione
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Tasto - (Riduce la serie corrente)
                OutlinedButton(onClick = {
                    if (intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                    }
                }) {
                    Text("-1 (Attuale)")
                }

                // Tasto < (Sposta colpo al tratto precedente)
                OutlinedButton(onClick = {
                    if (currentIntervalIndex > 0 && intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                        intervals[currentIntervalIndex - 1] = intervals[currentIntervalIndex - 1].copy(
                            blowCount = intervals[currentIntervalIndex - 1].blowCount + 1
                        )
                    }
                }) {
                    Text("< Sposta prec.")
                }

                // Tasto > (Sposta colpo al tratto successivo)
                OutlinedButton(onClick = {
                    if (currentIntervalIndex < intervals.size - 1 && intervals[currentIntervalIndex].blowCount > 0) {
                        intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                            blowCount = intervals[currentIntervalIndex].blowCount - 1
                        )
                        intervals[currentIntervalIndex + 1] = intervals[currentIntervalIndex + 1].copy(
                            blowCount = intervals[currentIntervalIndex + 1].blowCount + 1
                        )
                    }
                }) {
                    Text("Sposta succ. >")
                }

                // Tasto + (Aumenta la serie corrente)
                OutlinedButton(onClick = {
                    intervals[currentIntervalIndex] = intervals[currentIntervalIndex].copy(
                        blowCount = intervals[currentIntervalIndex].blowCount + 1
                    )
                }) {
                    Text("+1 (Attuale)")
                }
            }

            // Controllo Ascolto
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { isRecording = !isRecording },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    )
                ) {
                    Text(if (isRecording) "PAUSA ASCOLTO" else "AVVIA ASCOLTO")
                }

                Text(
                    text = if (isRecording) "Microfono attivo" else "In pausa",
                    fontSize = 12.sp,
                    color = if (isRecording) Color(0xFF388E3C) else Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Pulsante Termina Prova
            Button(
                onClick = {
                    val session = TestSession(
                        projectName = projectName,
                        operatorName = operatorName,
                        testType = testType,
                        date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                        intervals = intervals.toList()
                    )
                    onFinishTest(session)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("FINE PROVA")
            }
        }
    }
}

fun exportAndShareCSV(context: Context, session: TestSession) {
    try {
        val fileName = "Contacolpi_${session.projectName.replace(" ", "_")}_${System.currentTimeMillis()}.csv"
        val file = File(context.cacheDir, fileName)

        val builder = StringBuilder()
        builder.append("Cantiere;${session.projectName}\n")
        builder.append("Operatore;${session.operatorName}\n")
        builder.append("Tipo Prova;${session.testType}\n")
        builder.append("Data;${session.date}\n\n")
        builder.append("Profondita (m);Colpi\n")

        for (interval in session.intervals) {
            builder.append(String.format(Locale.US, "%.1f;%d\n", interval.depthMeter, interval.blowCount))
        }

        file.writeText(builder.toString())

        val uri = FileProvider.getUriForFile(
            context,
            "com.geotec.contacolpi.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Esporta CSV con:"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Errore durante l'esportazione CSV", Toast.LENGTH_SHORT).show()
    }
}package com.geotec.contacolpi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.sqrt

data class SerieColpi(val profondita: Int, val colpi: Int)

class MainActivity : ComponentActivity() {

    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    // Stato dell'App
    private val cantiere = mutableStateOf("Cantiere Principale")
    private val prova = mutableStateOf("P1")
    private val operatore = mutableStateOf("Operatore 1")
    private val passoCm = mutableStateOf(10)
    private val sensibilitaSoglia = mutableStateOf(3000f)
    
    private val colpiLive = mutableStateOf(0)
    private val profonditaAttuale = mutableStateOf(10)
    private val listaSerie = mutableStateListOf<SerieColpi>()
    private val inInizioConfig = mutableStateOf(true)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) avviaAscoltoMicrofono()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (inInizioConfig.value) {
                        SchermataImpostazioni(
                            cantiere = cantiere.value, onCantiereChange = { cantiere.value = it },
                            prova = prova.value, onProvaChange = { prova.value = it },
                            operatore = operatore.value, onOperatoreChange = { operatore.value = it },
                            passoCm = passoCm.value, onPassoChange = { passoCm.value = it },
                            sensibilita = sensibilitaSoglia.value, onSensibilitaChange = { sensibilitaSoglia.value = it },
                            onAvvia = {
                                inInizioConfig.value = false
                                profonditaAttuale.value = passoCm.value
                                controllaPermessiEAvvia()
                            }
                        )
                    } else {
                        SchermataOperativa(
                            cantiere = cantiere.value,
                            prova = prova.value,
                            operatore = operatore.value,
                            colpiLive = colpiLive.value,
                            profondita = profonditaAttuale.value,
                            passo = passoCm.value,
                            listaSerie = listaSerie,
                            onPiuColpo = { colpiLive.value++ },
                            onMenoColpo = { if (colpiLive.value > 0) colpiLive.value-- },
                            onSpostaIndietro = {
                                if (colpiLive.value > 0 && listaSerie.isNotEmpty()) {
                                    colpiLive.value--
                                    val ultima = listaSerie.removeAt(listaSerie.size - 1)
                                    listaSerie.add(SerieColpi(ultima.profondita, ultima.colpi + 1))
                                }
                            },
                            onSpostaAvanti = {
                                if (listaSerie.isNotEmpty() && listaSerie.last().colpi > 0) {
                                    val ultima = listaSerie.removeAt(listaSerie.size - 1)
                                    listaSerie.add(SerieColpi(ultima.profondita, ultima.colpi - 1))
                                    colpiLive.value++
                                }
                            },
                            onAvanzamento = {
                                listaSerie.add(SerieColpi(profonditaAttuale.value, colpiLive.value))
                                profonditaAttuale.value += passoCm.value
                                colpiLive.value = 0
                            },
                            onEsporta = { esportaCSV(this) }
                        )
                    }
                }
            }
        }
    }

    private fun controllaPermessiEAvvia() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            avviaAscoltoMicrofono()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @SuppressLint("MissingPermission")
    private fun avviaAscoltoMicrofono() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isRecording = true
        audioRecord?.startRecording()

        thread {
            val buffer = ShortArray(bufferSize)
            var ultimoColpoTempo = 0L

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / read)
                    val ora = System.currentTimeMillis()

                    // Rilevamento impatto con debounce di 220ms
                    if (rms > sensibilitaSoglia.value && (ora - ultimoColpoTempo) > 220) {
                        ultimoColpoTempo = ora
                        colpiLive.value++
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
    }

    private fun esportaCSV(context: Context) {
        val dataOra = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val nomeFile = "Contacolpi_${cantiere.value}_${prova.value}.csv"
        val file = File(context.cacheDir, nomeFile)

        val sb = StringBuilder()
        sb.append("Cantiere;${cantiere.value}\n")
        sb.append("Prova;${prova.value}\n")
        sb.append("Operatore;${operatore.value}\n")
        sb.append("Data e Ora;${dataOra}\n")
        sb.append("Passo (cm);${passoCm.value}\n\n")
        sb.append("Profondita (cm);Colpi (N)\n")

        for (s in listaSerie) {
            sb.append("${s.profondita};${s.colpi}\n")
        }

        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Condividi File CSV"))
    }
}

// --- INTERFACCIA GRAFICA (COMPOSE) ---

@Composable
fun SchermataImpostazioni(
    cantiere: String, onCantiereChange: (String) -> Unit,
    prova: String, onProvaChange: (String) -> Unit,
    operatore: String, onOperatoreChange: (String) -> Unit,
    passoCm: Int, onPassoChange: (Int) -> Unit,
    sensibilita: Float, onSensibilitaChange: (Float) -> Unit,
    onAvvia: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CONTACOLPI GEOTEC", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(value = cantiere, onValueChange = onCantiereChange, label = { Text("Nome Cantiere / Località") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = prova, onValueChange = onProvaChange, label = { Text("Codice Prova (es. P1)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = operatore, onValueChange = onOperatoreChange, label = { Text("Nome Operatore") }, modifier = Modifier.fillMaxWidth())

        Text("Passo di Avanzamento (cm):", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30).forEach { p ->
                FilterChip(
                    selected = (passoCm == p),
                    onClick = { onPassoChange(p) },
                    label = { Text("$p cm") }
                )
            }
        }

        Text("Sensibilità Microfono (Soglia):", fontWeight = FontWeight.SemiBold)
        Slider(
            value = sensibilita,
            onValueChange = onSensibilitaChange,
            valueRange = 1000f..10000f
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onAvvia,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("INIZIA PROVA", fontSize = 18.sp)
        }
    }
}

@Composable
fun SchermataOperativa(
    cantiere: String, prova: String, operatore: String,
    colpiLive: Int, profondita: Int, passo: Int,
    listaSerie: List<SerieColpi>,
    onPiuColpo: () -> Unit, onMenoColpo: () -> Unit,
    onSpostaIndietro: () -> Unit, onSpostaAvanti: () -> Unit,
    onAvanzamento: () -> Unit, onEsporta: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$cantiere | Prova: $prova ($operatore)", fontSize = 14.sp, color = Color.Gray)
        
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("PROFONDITÀ: $profondita cm", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("$colpiLive", fontSize = 64.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Text("COLPI RILEVATI", fontSize = 12.sp, color = Color.Gray)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Button(onClick = onMenoColpo) { Text("-") }
                    Button(onClick = onPiuColpo) { Text("+") }
                    Button(onClick = onSpostaIndietro) { Text("<") }
                    Button(onClick = onSpostaAvanti) { Text(">") }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onAvanzamento,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text("AVANZAMENTO +$passo cm", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TABELLA E GRAFICO
        Text("Riepilogo Avanzamento", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
        
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(listaSerie) { serie ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${serie.profondita} cm", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold)
                    Text("${serie.colpi}", modifier = Modifier.width(40.dp))
                    
                    // Barra del Grafico orizzontale
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .width((serie.colpi * 6).dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Button(
            onClick = onEsporta,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ESPORTA CSV / CONDIVIDI")
        }
    }
}

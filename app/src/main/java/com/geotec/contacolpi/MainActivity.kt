package com.geotec.contacolpi

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

package org.phonemesh.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.*
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.MessageCodec
import org.phonemesh.core.PackInfo
import org.phonemesh.core.PackKind
import org.phonemesh.core.PackRejected
import org.phonemesh.core.PackStore
import org.phonemesh.core.Readiness
import org.phonemesh.core.readiness
import org.phonemesh.ble.BleMeshTransport
import org.phonemesh.ble.DeliveryState
import org.phonemesh.speech.AudioPcm
import org.phonemesh.speech.LocalSpeech
import org.phonemesh.speech.SharedEspeakData
import org.phonemesh.speech.SpeechPack
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), BleMeshTransport.Listener {
    private val work=Executors.newSingleThreadExecutor()
    private val microphone by lazy { MicrophoneCapture(this) }
    private lateinit var bridge:BleMeshTransport
    private val radioDevices=linkedMapOf<String,String>()
    private var radioAdapter:ArrayAdapter<String>?=null
    private var radioDialog:AlertDialog?=null
    private lateinit var store:PackStore
    private lateinit var packDownloads:PackDownloads
    private lateinit var packList:LinearLayout
    private val downloadPoll=Handler(Looper.getMainLooper())
    private val pollTick=Runnable { pollDownloads() }
    private lateinit var status:TextView
    private lateinit var operationStatus:TextView
    private lateinit var diagnosticStatus:TextView
    private lateinit var reconnect:Button
    private var pendingReconnect=false
    private lateinit var voiceStatus:TextView
    private lateinit var draft:EditText
    private lateinit var destination:EditText
    private lateinit var channel:EditText
    private lateinit var packsSpinner:Spinner
    private lateinit var modelSpinner:Spinner
    private var modelChoices=emptyList<PackInfo>()
    private lateinit var record:Button
    private lateinit var send:Button
    private lateinit var preview:Button
    private lateinit var inbox:LinearLayout
    private lateinit var outbox:LinearLayout
    private val deliveryRows=linkedMapOf<Long,TextView>()
    private var packs=emptyList<PackInfo>()
    private var languageChoices=emptyList<Pair<String,String>>()
    private var currentLanguage="fr"
    private var selected:Readiness=Readiness.MissingEngine
    private var engine:LocalSpeech?=null // confined to work executor
    private var engineRoot:String?=null // "engineRoot|voiceRoot" of the loaded engine
    private var player:MediaPlayer?=null
    private var playingFile:File?=null
    private var recording=false
    private var connected=false
    private var busy=false
    private var foreground=false
    private var captureEpoch=0
    private var playbackEpoch=0

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store=PackStore(File(filesDir,"packs"),GeneratedPackCatalog.entries,BuildConfig.ALLOW_UNKNOWN_PACKS)
        packDownloads=PackDownloads(this,store)
        bridge=BleMeshTransport(this, this)
        val scroll=ScrollView(this)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard=insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            }
            insets
        }
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            val padding=(16*resources.displayMetrics.density).toInt()
            setPadding(padding,padding,padding,padding)
            setBackgroundColor(Color.rgb(246,248,248))
        }
        scroll.addView(layout)
        fun label(text:String,size:Float=16f):TextView = TextView(this).apply {
            this.text=text; textSize=size; setPadding(0,10,0,10); layout.addView(this)
        }
        label("Phonemesh",30f)
        label("Voix locale → texte radio → voix locale. Aucun audio sur la radio.",14f)
        status=label("Radio : déconnectée").apply { contentDescription="Radio connection status" }
        operationStatus=label("Prêt. Aucun envoi automatique.",14f).apply { contentDescription="Operation status" }
        diagnosticStatus=label("Aucun diagnostic radio",12f).apply { visibility=View.GONE }
        val diagnostics=Button(this).apply { text="Afficher / masquer le diagnostic radio" }
        layout.addView(diagnostics)
        diagnostics.setOnClickListener {
            diagnosticStatus.visibility=if(diagnosticStatus.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        }
        val connect=Button(this).apply { text="Choisir une radio Bluetooth"; contentDescription="Connect Meshtastic" }
        layout.addView(connect)
        connect.setOnClickListener { chooseRadio() }
        reconnect=Button(this).apply { text="Reconnecter la dernière radio"; contentDescription="Reconnect last radio" }
        layout.addView(reconnect)
        reconnect.setOnClickListener { reconnectLastRadio() }
        val disconnect=Button(this).apply { text="Déconnecter la radio"; contentDescription="Disconnect radio" }
        layout.addView(disconnect)
        disconnect.setOnClickListener { bridge.disconnect() }
        label("Déconnectez la radio de l'application Meshtastic avant de la connecter ici.",13f)
        voiceStatus=label("Voix hors ligne : importer un pack de modèles").apply { contentDescription="Offline voice status" }
        val import=Button(this).apply { text="Importer un pack de modèles ZIP"; contentDescription="Import offline models" }
        layout.addView(import)
        import.setOnClickListener {
            if(busy || recording) { note("Attendez la fin de l'opération."); return@setOnClickListener }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="application/zip"
                putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/zip","application/octet-stream","application/x-zip-compressed"))
                // The Downloads provider can omit ZIPs copied over USB; use the filesystem provider.
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload"))
            },101)
        }
        packList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; contentDescription="Language packs" }
        layout.addView(packList)
        packsSpinner=Spinner(this).apply { contentDescription="Message language" }
        layout.addView(packsSpinner)
        label("Modèle vocal — Tiny : léger ; Base : plus volumineux",13f)
        modelSpinner=Spinner(this).apply { contentDescription="Offline model choice" }
        layout.addView(modelSpinner)
        modelSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                selectEngine(modelChoices.getOrNull(position))
            }
        }
        packsSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                currentLanguage=languageChoices.getOrNull(position)?.first ?: "fr"
                getPreferences(MODE_PRIVATE).edit().putString("language",currentLanguage).apply()
                refreshModelChoices()
            }
        }
        refreshPacks()
        record=Button(this).apply { text="● Enregistrer (30 s maximum)"; contentDescription="Record speech" }
        layout.addView(record)
        record.setOnClickListener {
            if(recording) { microphone.stop(); record.isEnabled=false }
            else if(!busy) {
                if(selected !is Readiness.Ready) note("Importez d'abord un pack de modèles.")
                else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),102)
                else startRecording()
            }
        }
        draft=EditText(this).apply {
            hint="Transcription — modifiable avant l'envoi"
            minLines=3; maxLines=8; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            contentDescription="Message draft"; setText(savedInstanceState?.getString("draft") ?: "")
        }
        layout.addView(draft)
        preview=Button(this).apply { text="▶ Écouter le brouillon"; contentDescription="Preview speech" }
        layout.addView(preview)
        preview.setOnClickListener {
            (selected as? Readiness.Ready)?.let { speak(draft.text.toString(),it) } ?: note("Importez les modèles de cette langue.")
        }
        val stopPlayback=Button(this).apply { text="■ Arrêter la lecture"; contentDescription="Stop playback" }
        layout.addView(stopPlayback)
        stopPlayback.setOnClickListener { playbackEpoch++; stopPlayback() }
        destination=EditText(this).apply {
            hint="Destinataire : !12345678 (ou ^all)"; setSingleLine(); contentDescription="Radio recipient"
            setText(savedInstanceState?.getString("destination") ?: getPreferences(MODE_PRIVATE).getString("destination",""))
        }
        layout.addView(destination)
        val peers=Button(this).apply { text="Choisir un nœud Meshtastic" }
        layout.addView(peers)
        peers.setOnClickListener {
            task {
                val found=bridge.peers()
                ui { AlertDialog.Builder(this).setTitle("Destinataire")
                    .setItems(found.map { "${it.name} (${it.id})" }.toTypedArray()) { _,i -> destination.setText(found[i].id) }
                    .setNegativeButton("Annuler",null).show() }
            }
        }
        channel=EditText(this).apply {
            hint="Canal (0–7)"; inputType=InputType.TYPE_CLASS_NUMBER
            setText(savedInstanceState?.getString("channel") ?: "0"); contentDescription="Channel index"
        }
        layout.addView(channel)
        send=Button(this).apply { text="Confirmer et envoyer le texte"; contentDescription="Review and send" }
        layout.addView(send)
        send.setOnClickListener { confirmSend() }
        label("Suivi des envois — accusé radio ≠ message lu",18f)
        outbox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(outbox)
        label("Réception — lecture uniquement sur demande",20f)
        inbox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(inbox)
        label("Un envoi soumis n'est pas une livraison confirmée. Configurez le canal et la région dans Meshtastic.",13f)
        setContentView(scroll)
        scroll.requestApplyInsets()
        updateControls()
        if(savedInstanceState==null) openFromIntent(intent)
    }
    private fun refreshPacks() {
        packs=store.list()
        val defaults=linkedMapOf("fr" to "Français", "en-US" to "English (US)", "en-GB" to "English (UK)",
            "es" to "Español", "hi" to "हिन्दी", "it" to "Italiano", "ja" to "日本語", "pt-BR" to "Português (Brasil)", "zh" to "中文")
        val voices=packs.filter { it.kind==PackKind.VOICE }
        voices.forEach { val tag=checkNotNull(it.languageTag); if(tag !in defaults) defaults[tag]=it.label }
        languageChoices=defaults.map { it.key to it.value }
        packsSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            languageChoices.map { (tag,name) -> "$name [$tag]"+if(voices.any { it.languageTag==tag }) " • modèles installés" else " • texte" })
        currentLanguage=getPreferences(MODE_PRIVATE).getString("language","fr") ?: "fr"
        val index=languageChoices.indexOfFirst { it.first==currentLanguage }.coerceAtLeast(0)
        currentLanguage=languageChoices[index].first
        packsSpinner.setSelection(index)
        refreshModelChoices()
        renderPackList()
    }
    /** Rebuilds the pack list from the installed packs and the downloads in progress. */
    private fun renderPackList() {
        val pending=runCatching { packDownloads.pending() }.getOrDefault(emptyMap())
        val rows=PackRows.build(GeneratedPackCatalog.entries,packs,
            pending.keys.associateWith { runCatching { packDownloads.progress(it) }.getOrNull() })
        packList.removeAllViews()
        for(row in rows) {
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply { text=row.name; textSize=15f },
                LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            line.addView(when(val action=row.action) {
                PackRow.Action.Download -> Button(this).apply {
                    val entry=checkNotNull(row.entry)
                    text="Télécharger (${String.format(Locale.ROOT,"%.0f Mo",entry.zipBytes/1e6)})"; isAllCaps=false
                    contentDescription="Download ${row.id}"
                    setOnClickListener { download(row) }
                }
                is PackRow.Action.Progress -> TextView(this).apply { text=action.text; textSize=15f }
                PackRow.Action.Remove -> Button(this).apply {
                    text="Retirer"; isAllCaps=false; contentDescription="Remove ${row.id}"
                    setOnClickListener { remove(row) }
                }
                PackRow.Action.None -> TextView(this).apply { text="Installé"; textSize=15f }
            })
            packList.addView(line)
        }
    }
    private fun download(row:PackRow) {
        try {
            val decision=packDownloads.start(checkNotNull(row.entry))
            note(PackMessages.forDecision(decision) ?: "Téléchargement : ${row.name}")
        } catch(error:Exception) { note(PackMessages.forFailure(error)) }
        pollDownloads()
    }
    private fun remove(row:PackRow) {
        if(busy || recording) { note("Attendez la fin de l'opération."); return }
        task {
            // Nothing may keep using the files about to be deleted.
            releaseSpeech()
            val message=try { if(store.remove(row.id)) "Pack retiré : ${row.name}" else "Échec. Réessayez." }
                catch(error:Exception) { PackMessages.forFailure(error) }
            ui { refreshPacks(); note(message) }
        }
    }
    /** Shows download progress and installs finished downloads; repeats every second while any is pending. */
    private fun pollDownloads() {
        downloadPoll.removeCallbacks(pollTick)
        if(isDestroyed || !foreground) return
        renderPackList()
        if(runCatching { packDownloads.pending() }.getOrDefault(emptyMap()).isEmpty()) return
        if(!busy && !recording && runCatching { packDownloads.anyFinished() }.getOrDefault(true)) collectDownloads()
        downloadPoll.postDelayed(pollTick,1000)
    }
    private fun collectDownloads() = task {
        val messages=try {
            val results=packDownloads.collectFinished()
            if(results.any { it.isSuccess }) releaseSpeechIfGone()
            results.map { result -> result.fold({ "Pack installé : ${nameOf(it)}" },{ PackMessages.forFailure(it) }) }
        } catch(error:Exception) { listOf(PackMessages.forFailure(error)) }
        ui { refreshPacks(); if(messages.isNotEmpty()) note(messages.joinToString("\n")) }
    }
    private fun nameOf(pack:PackInfo) =
        GeneratedPackCatalog.entries.firstOrNull { it.id==pack.id }?.let { PackDownloadText.name(it) } ?: pack.label
    /** Installs a ZIP the user picked or opened from another app. */
    private fun importPack(uri:Uri) = task {
        try {
            val input=contentResolver.openInputStream(uri) ?: error("Fichier inaccessible")
            val installed=input.use { store.install(it) }
            releaseSpeechIfGone()
            val preferences=getPreferences(MODE_PRIVATE).edit()
            if(installed.kind==PackKind.ENGINE) preferences.putString("engine",installed.id)
            else preferences.putString("language",installed.languageTag)
            preferences.apply()
            ui { refreshPacks(); note("Pack importé : ${nameOf(installed)}") }
        } catch(error:Exception) { ui { note(PackMessages.forFailure(error)) } }
    }
    /** A ZIP opened or shared from another app (file manager, browser downloads, messaging). */
    private fun openFromIntent(intent:Intent?) {
        if(intent==null || (intent.action!=Intent.ACTION_VIEW && intent.action!=Intent.ACTION_SEND)) return
        if(busy || recording) { note("Attendez la fin de l'opération."); return }
        val uri=if(intent.action==Intent.ACTION_SEND) {
            if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else intent.data
        // Only content the sender shares with us; a file path could point into this app's own storage.
        if(uri==null || uri.scheme!="content") { note(PackMessages.forFailure(PackRejected(PackRejected.Reason.UNSAFE,"not content"))); return }
        importPack(uri)
    }
    public override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }
    private fun engineName(engine:PackInfo) = if(engine.recognizerModel=="whisper-base") "Whisper Base" else "Whisper Tiny"
    private fun refreshModelChoices() {
        modelChoices=packs.filter { it.kind==PackKind.ENGINE }
        val preferred=getPreferences(MODE_PRIVATE).getString("engine",null)
        val index=modelChoices.indexOfFirst { it.id==preferred }.coerceAtLeast(0)
        modelSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            if(modelChoices.isEmpty()) listOf("Aucun modèle installé") else modelChoices.map { engineName(it) })
        modelSpinner.setSelection(index)
        selectEngine(modelChoices.getOrNull(index))
    }
    private fun selectEngine(choice:PackInfo?) {
        choice?.let { getPreferences(MODE_PRIVATE).edit().putString("engine",it.id).apply() }
        selected=store.readiness(choice?.id,currentLanguage)
        voiceStatus.text=when(val state=selected) {
            is Readiness.Ready -> "${state.voice.label} • ${engineName(state.engine)} + Piper • hors ligne"
            Readiness.MissingEngine -> "Téléchargez le moteur vocal"
            is Readiness.MissingVoice -> "$currentLanguage : texte disponible ; ajoutez la voix"
            is Readiness.UnsupportedLanguage -> "Ce moteur ne gère pas cette langue"
        }
        if(::record.isInitialized && ::send.isInitialized) updateControls()
    }
    /** Engine and voice for speaking a received message in [languageTag], if both are installed. */
    private fun readyFor(languageTag:String) =
        store.readiness(getPreferences(MODE_PRIVATE).getString("engine",null),languageTag) as? Readiness.Ready
    private fun connectRadio(address:String,name:String) {
        getPreferences(MODE_PRIVATE).edit().putString("radioAddress",address).putString("radioName",name).apply()
        note("Connexion demandée pour $name. Attendez l'état « connectée » ; diagnostic disponible en cas d'échec.")
        bridge.stopScan()
        bridge.connect(address)
    }
    private fun reconnectLastRadio() {
        if(busy || recording) { note("Attendez la fin de l'opération."); return }
        val address=getPreferences(MODE_PRIVATE).getString("radioAddress",null)
        if(address==null) { note("Choisissez d'abord une radio Bluetooth."); return }
        try {
            pendingReconnect=true
            if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),103); return
            }
            val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            require(adapter!=null) { "Bluetooth indisponible sur cet appareil" }
            if(!adapter.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),104); return }
            pendingReconnect=false
            connectRadio(address,getPreferences(MODE_PRIVATE).getString("radioName","Meshtastic") ?: "Meshtastic")
        } catch(error:Exception) { pendingReconnect=false; note(error.message ?: "Reconnexion impossible") }
    }
    private fun chooseRadio() {
        if(busy || recording) { note("Attendez la fin de l'opération."); return }
        pendingReconnect=false
        try {
            val permissions=if(Build.VERSION.SDK_INT>=31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if(permissions.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) {
                requestPermissions(permissions,103); return
            }
            val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            require(adapter!=null) { "Bluetooth indisponible sur cet appareil" }
            if(!adapter.isEnabled) {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),104); return
            }
            radioDevices.clear()
            val list=ArrayAdapter<String>(this,android.R.layout.simple_list_item_1)
            radioAdapter=list
            radioDialog=AlertDialog.Builder(this).setTitle("Radios Meshtastic à proximité")
                .setAdapter(list) { _,position ->
                    val address=radioDevices.keys.elementAtOrNull(position) ?: return@setAdapter
                    connectRadio(address,radioDevices[address] ?: address)
                }.setNegativeButton("Annuler",null).create()
            radioDialog?.setOnDismissListener { bridge.stopScan(); radioAdapter=null }
            radioDialog?.show()
            bridge.scan()
        } catch(error:Exception) { radioDialog?.dismiss(); note(error.message ?: "Bluetooth indisponible") }
    }
    override fun onDevice(address:String,name:String) {
        if(radioAdapter==null || (address !in radioDevices && radioDevices.size>=50)) return
        radioDevices[address]=name
        radioAdapter?.clear()
        radioAdapter?.addAll(radioDevices.map { "${it.value} (${it.key})" })
        radioAdapter?.notifyDataSetChanged()
    }
    /** Closes the cached recognizer and voice (one LocalSpeech). Work executor only. */
    private fun releaseSpeech() { runCatching { engine?.close() }; engine=null; engineRoot=null }
    /** Drops the cached speech if an install replaced the packs it was loaded from. Work executor only. */
    private fun releaseSpeechIfGone() {
        if(engineRoot?.split('|')?.any { !File(it).isDirectory }==true) releaseSpeech()
    }
    private fun speech(pack:Readiness.Ready):LocalSpeech {
        val key="${pack.engine.root.absolutePath}|${pack.voice.root.absolutePath}"
        if(engineRoot!=key) {
            engine?.close(); engine=null; engineRoot=null
            // Every voice shares one espeak-ng copy that pack removal never deletes (see SharedEspeakData).
            val pronunciation=SharedEspeakData(File(filesDir,"espeak-ng-data"))
            engine=LocalSpeech(SpeechPack(pack.engine.root,pack.voice.root,
                checkNotNull(pack.voice.languageTag),checkNotNull(pack.voice.whisperLanguage),
                espeakDataDir=pronunciation.prepare(pack.voice.root)))
            pronunciation.markLoaded()
            engineRoot=key
        }
        return checkNotNull(engine)
    }
    private fun startRecording() {
        val pack=selected as? Readiness.Ready ?: return
        playbackEpoch++; stopPlayback()
        val epoch=++captureEpoch
        recording=true; record.text="■ Annuler"; note("Préparation du microphone…")
        updateControls()
        try { microphone.start(onStarted = { ui {
            if(epoch==captureEpoch) { record.text="■ Arrêter — parlez maintenant"; note("Microphone actif — parlez maintenant") }
        } }) { samples,error -> ui {
            if(epoch!=captureEpoch) return@ui
            recording=false; record.text="● Enregistrer (30 s maximum)"; record.isEnabled=true
            updateControls()
            if(error!=null) { note(error); return@ui }
            task {
                val text=speech(pack).transcribe(checkNotNull(samples))
                ui { draft.setText(text); note("Transcription locale terminée. Vérifiez avant l'envoi.") }
            }
        } } } catch(error:Exception) {
            recording=false; record.text="● Enregistrer (30 s maximum)"
            updateControls(); note(error.message ?: "Microphone indisponible")
        }
    }
    private fun speak(text:String,pack:Readiness.Ready) {
        if(busy || recording) return
        val epoch=++playbackEpoch
        stopPlayback()
        task {
            MessageCodec.validateText(text)
            require(text.toByteArray().size<=200) { "Texte trop long — 200 octets maximum" }
            val audio=speech(pack).synthesize(text)
            val file=File.createTempFile("speech-",".wav",cacheDir)
            WavOutput.write(file,audio.samples,audio.sampleRate)
            ui {
                if(epoch!=playbackEpoch || !foreground) { file.delete(); return@ui }
                try {
                    val next=MediaPlayer()
                    player=next; playingFile=file
                    next.setDataSource(file.absolutePath)
                    next.setOnCompletionListener { stopPlayback() }
                    next.setOnErrorListener { _,_,_ -> stopPlayback(); note("Lecture audio impossible"); true }
                    next.prepare(); next.start()
                    note("Lecture locale — aucun audio transmis")
                } catch(error:Exception) { stopPlayback(); note(error.message ?: "Lecture impossible") }
            }
        }
    }
    private fun stopPlayback() {
        runCatching { player?.stop() }; player?.release(); player=null
        playingFile?.delete(); playingFile=null
    }
    private fun confirmSend() {
        try {
            check(connected) { "Connectez une radio Meshtastic d'abord" }
            val recipient=destination.text.toString().trim()
            require(recipient=="^all" || Regex("![0-9a-fA-F]{8}").matches(recipient)) { "Identifiant destinataire invalide" }
            require(recipient.lowercase()!="!ffffffff") { "Pour diffuser, choisissez explicitement ^all" }
            val index=channel.text.toString().toInt()
            require(index in 0..7) { "Canal invalide" }
            val payload=MessageCodec.encode(draft.text.toString(),currentLanguage)
            AlertDialog.Builder(this).setTitle("Envoyer ce texte ?")
                .setMessage("À $recipient • canal $index • ${payload.toByteArray().size}/200 octets\n\n$payload")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Envoyer") { _,_ ->
                    getPreferences(MODE_PRIVATE).edit().putString("destination",recipient).apply()
                    task {
                        val id=bridge.send(payload,recipient,index)
                        ui { note("Envoi #$id : consultez le suivi ci-dessous. Aucun renvoi automatique.") }
                    }
                }.show()
        } catch(error:Exception) { note(error.message ?: "Message invalide") }
    }
    override fun onState(connected:Boolean,detail:String) {
        val wasConnected=this.connected
        this.connected=connected
        val name=getPreferences(MODE_PRIVATE).getString("radioName","Meshtastic") ?: "Meshtastic"
        status.text=if(connected) "Radio : connectée — $name" else "Radio : déconnectée — choisissez ou reconnectez une radio"
        diagnosticStatus.text=detail
        if(connected && !wasConnected) note("Radio prête. Chaque envoi demande votre confirmation.")
        else if(!connected && wasConnected) note("Connexion interrompue. Reconnectez la radio ; aucun renvoi automatique.")
        if(::send.isInitialized) updateControls()
    }
    override fun onDelivery(packetId:Long,state:DeliveryState,detail:String) {
        if(!::outbox.isInitialized) return
        val row=deliveryRows.getOrPut(packetId) {
            TextView(this).apply {
                textSize=15f; setPadding(0,12,0,12); contentDescription="Delivery $packetId"
                outbox.addView(this,0)
            }
        }
        val description=when(state) {
            DeliveryState.SUBMITTING -> "Transmission à la radio en cours"
            DeliveryState.QUEUED -> "En file sur la radio — livraison non confirmée"
            DeliveryState.ACKNOWLEDGED -> "Accusé de réception de la radio destinataire — lecture non confirmée"
            DeliveryState.FAILED -> "Échec — $detail"
            DeliveryState.UNCONFIRMED -> "Livraison non confirmée — $detail"
        }
        row.text="#$packetId • $description"
        while(deliveryRows.size>20) {
            val oldest=deliveryRows.keys.first()
            outbox.removeView(deliveryRows.remove(oldest))
        }
    }
    override fun onText(sender:String,text:String,channel:Int) {
        if(!::inbox.isInitialized || channel!=this.channel.text.toString().toIntOrNull()) return
        try {
            val message=MessageCodec.inspectIncoming(text)
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,16,0,16) }
            row.addView(TextView(this).apply { this.text="$sender • ${message.language ?: "langue non indiquée"}\n${message.text}"; textSize=17f })
            row.addView(Button(this).apply {
                this.text=if(message.language==null) "▶ Choisir la langue et écouter" else "▶ Écouter"
                setOnClickListener {
                    if(message.language==null) {
                        val voices=packs.filter { it.kind==PackKind.VOICE }
                        if(voices.isEmpty()) note("Importez un pack vocal pour écouter.")
                        else AlertDialog.Builder(this@MainActivity).setTitle("Langue de ce message")
                            .setItems(voices.map { it.label }.toTypedArray()) { _,i ->
                                val pack=readyFor(checkNotNull(voices[i].languageTag))
                                if(pack==null) note("Téléchargez le moteur vocal") else speak(message.text,pack)
                            }
                            .setNegativeButton("Annuler",null).show()
                    } else {
                        val pack=readyFor(checkNotNull(message.language))
                        if(pack==null) note("Importez un pack ${message.language} pour écouter.")
                        else speak(message.text,pack)
                    }
                }
            })
            inbox.addView(row,0)
            while(inbox.childCount>20) inbox.removeViewAt(inbox.childCount-1)
        } catch(error:Exception) { note("Message non pris en charge : ${error.message}") }
    }
    private fun task(action:()->Unit) {
        if(busy || recording) { note("Une opération est déjà en cours."); return }
        busy=true; updateControls()
        work.execute {
            try { action() }
            catch(error:Exception) {
                releaseSpeech()
                ui { note(error.message ?: "Opération impossible") }
            }
            catch(error:LinkageError) {
                releaseSpeech()
                ui { note("Le moteur vocal natif est indisponible sur cet appareil.") }
            }
            finally { ui { busy=false; updateControls() } }
        }
    }
    private fun ui(action:()->Unit) { runOnUiThread { if(!isDestroyed) action() } }
    private fun note(text:String) { operationStatus.text=text }
    private fun updateControls() {
        record.isEnabled=!busy
        preview.isEnabled=!busy && !recording && selected is Readiness.Ready
        send.isEnabled=!busy && !recording && connected
        packsSpinner.isEnabled=!busy && !recording
        modelSpinner.isEnabled=!busy && !recording && modelChoices.isNotEmpty()
        reconnect.isEnabled=!busy && !recording && !connected
    }
    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==104 && resultCode==RESULT_OK && foreground) {
            if(pendingReconnect) reconnectLastRadio() else chooseRadio()
        }
        if(requestCode==101 && resultCode==RESULT_OK) importPack(data?.data ?: return)
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==102 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED && foreground && !busy) startRecording()
        else if(code==102) note("Permission microphone refusée — le texte reste utilisable.")
        if(code==103 && results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED } && foreground) {
            if(pendingReconnect) reconnectLastRadio() else chooseRadio()
        }
        else if(code==103) note("Permission Bluetooth refusée — les fonctions vocales locales restent disponibles.")
    }
    override fun onStart() { super.onStart(); foreground=true }
    override fun onResume() { super.onResume(); pollDownloads() }
    override fun onStop() {
        downloadPoll.removeCallbacks(pollTick)
        foreground=false; captureEpoch++; playbackEpoch++
        radioDialog?.dismiss(); bridge.stopScan()
        microphone.stop(); recording=false
        if(::record.isInitialized) { record.text="● Enregistrer (30 s maximum)"; updateControls() }
        stopPlayback(); super.onStop()
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("draft",draft.text.toString()); state.putString("destination",destination.text.toString())
        state.putString("channel",channel.text.toString()); super.onSaveInstanceState(state)
    }
    override fun onDestroy() {
        downloadPoll.removeCallbacks(pollTick)
        microphone.close(); bridge.close()
        work.execute { engine?.close(); engine=null }; work.shutdown()
        super.onDestroy()
    }
    // Test hook
    internal fun refreshPacksForTest() = runOnUiThread { refreshPacks() }
    // Test hook (posted to the main thread: task() updates the controls)
    internal fun loadSpeechForTest() = runOnUiThread { (selected as? Readiness.Ready)?.let { ready -> task { speech(ready) } } }
    // Test hook
    internal var recordingForTest:Boolean
        get() = recording
        set(value) { recording = value }
    // Test hook: returns once everything queued on the work executor has run.
    internal fun awaitWorkForTest() { work.submit {}.get(120,TimeUnit.SECONDS) }
    // Test hook
    internal fun speechLoadedForTest():Boolean = work.submit(Callable { engine!=null }).get(120,TimeUnit.SECONDS)
    // Test hook: synthesizes to samples through the cached speech; no playback.
    internal fun synthesizeForTest(languageTag:String,text:String):AudioPcm = work.submit(Callable {
        speech(checkNotNull(readyFor(languageTag)) { "No packs for $languageTag" }).synthesize(text)
    }).get(120,TimeUnit.SECONDS)
    // Test hook: transcribes samples (e.g. from synthesizeForTest); no microphone.
    internal fun transcribeForTest(languageTag:String,audio:AudioPcm):String = work.submit(Callable {
        speech(checkNotNull(readyFor(languageTag)) { "No packs for $languageTag" }).transcribe(audio.samples,audio.sampleRate)
    }).get(120,TimeUnit.SECONDS)
}

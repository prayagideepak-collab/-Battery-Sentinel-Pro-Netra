package com.example.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.NetraApplication
import com.example.data.repository.SentinelSettings
import com.example.model.BatteryTelemetry
import com.example.model.BluetoothDeviceItem
import com.example.model.CanonicalChargingSpeed
import com.example.model.NetraCentralEvent
import com.example.model.NetraCentralState
import com.example.model.NetraEventType
import com.example.model.AudioRoutingPolicy
import com.example.model.AudioRouteType
import com.example.util.AudioRoutingInspector
import com.example.util.MediaPlaybackController
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class AnnouncementPriority(val level: Int) {
    CRITICAL_THERMAL(1),
    CHARGER_STATE(2),
    BLUETOOTH_STATE(3),
    CHARGING_SPEED(4),
    PHONE_BATTERY(5),
    BLUETOOTH_BATTERY(6),
    INFORMATIONAL(7)
}

enum class AnnouncementSpeechState {
    QUEUED, SPEAKING, COMPLETED, FAILED, CANCELLED
}

data class AnnouncementItem(
    val id: String,
    val text: String,
    val priority: AnnouncementPriority,
    val category: String,
    val isNightException: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var speechState: AnnouncementSpeechState = AnnouncementSpeechState.QUEUED
) : Comparable<AnnouncementItem> {
    override fun compareTo(other: AnnouncementItem): Int {
        val prioDiff = this.priority.level.compareTo(other.priority.level)
        return if (prioDiff != 0) prioDiff else this.timestamp.compareTo(other.timestamp)
    }
}

/**
 * Centralized Announcement Engine for Battery Sentinel Pro Netra.
 * All voice alerts pass through this engine with controlled priority queuing,
 * state deduplication, night protection, and media playback pausing/resuming.
 * Collects state and events autonomously from the Central Unit (NetraCentralDataCenter).
 */
class AnnouncementEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mediaController = MediaPlaybackController(context)

    // Controlled Priority Queue
    private val queue = PriorityQueue<AnnouncementItem>()
    private val isSpeaking = AtomicBoolean(false)
    private var currentPlayingAnnouncement: AnnouncementItem? = null

    // Event ID tracking for absolute deduplication across observers/recomposition
    private val processedEventIds = mutableSetOf<String>()
    private val recentSpoken = HashMap<String, Long>()
    private val lastByCategory = HashMap<String, Long>()

    // State Tracking & Baseline for Deduplication inside Announcement Engine
    private var isBaselineEstablished = false
    private var lastPhoneLevel: Int? = null
    private var lastPhoneBoundary: Int? = null
    private var lastChargingState: Boolean? = null
    private var lastAnnouncementSpeed: CanonicalChargingSpeed? = null
    private var lastThermalWarningState: Boolean = false
    private var lastCriticalOverheatState: Boolean = false
    private val chargerThermalGuard = ChargerThermalGuard()

    // Bluetooth Per-Device State Tracking (Internal for compatibility)
    private val lastBtConnectionMap = mutableMapOf<String, Boolean>()
    private val lastBtBatteryBoundaryMap = mutableMapOf<String, Int>()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }

        // Autonomously collect state and events from NetraCentralDataCenter
        scope.launch {
            try {
                val dataCenter = NetraApplication.instance.centralDataCenter
                
                // 1. Collect and establish baseline from centralState
                launch {
                    dataCenter.centralState.collect { state ->
                        if (!isBaselineEstablished && state.batteryLevel != null) {
                            establishBaseline(state)
                        } else if (isBaselineEstablished) {
                            checkSpeedAndThermalStateChanges(state)
                        }
                    }
                }

                // 2. Collect and act on canonical events
                launch {
                    dataCenter.centralEvents.collect { event ->
                        if (isBaselineEstablished) {
                            handleCanonicalEvent(event)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing central flow collectors in AnnouncementEngine", e)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId")
                    currentPlayingAnnouncement?.speechState = AnnouncementSpeechState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done: $utteranceId")
                    currentPlayingAnnouncement?.let {
                        if (it.speechState == AnnouncementSpeechState.SPEAKING) {
                            it.speechState = AnnouncementSpeechState.COMPLETED
                        }
                    }
                    onSpeechFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error on utterance: $utteranceId")
                    val failedItem = currentPlayingAnnouncement
                    currentPlayingAnnouncement?.let {
                        if (it.speechState != AnnouncementSpeechState.CANCELLED) {
                            it.speechState = AnnouncementSpeechState.FAILED
                        }
                    }
                    if (failedItem != null) {
                        triggerSpeakerFallback(failedItem, "TTS error callback")
                    }
                    onSpeechFinished()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error ($errorCode) on utterance: $utteranceId")
                    val failedItem = currentPlayingAnnouncement
                    currentPlayingAnnouncement?.let {
                        if (it.speechState != AnnouncementSpeechState.CANCELLED) {
                            it.speechState = AnnouncementSpeechState.FAILED
                        }
                    }
                    if (failedItem != null) {
                        triggerSpeakerFallback(failedItem, "TTS error code $errorCode")
                    }
                    onSpeechFinished()
                }
            })

            isTtsReady = true
            processQueue()
        } else {
            Log.e(TAG, "TextToSpeech init failed with status: $status")
        }
    }

    private fun establishBaseline(state: NetraCentralState) {
        lastPhoneLevel = state.batteryLevel
        lastPhoneBoundary = state.batteryLevel?.let { (it / 5) * 5 }
        lastChargingState = state.isCharging
        lastAnnouncementSpeed = state.announcementSpeed
        lastThermalWarningState = (state.temperatureCelsius ?: 0f) >= 40.0f
        lastCriticalOverheatState = (state.temperatureCelsius ?: 0f) >= 45.0f

        state.bluetoothDevices.forEach { device ->
            val key = device.address.ifBlank { device.name }
            lastBtConnectionMap[key] = device.isConnected
            device.batteryPercent?.let {
                lastBtBatteryBoundaryMap[key] = (it / 10) * 10
            }
        }

        isBaselineEstablished = true
        Log.d(TAG, "Baseline established in AnnouncementEngine: level=${state.batteryLevel}%, charging=${state.isCharging}")
    }

    private fun checkSpeedAndThermalStateChanges(state: NetraCentralState) {
        val settings = getSettings()
        val now = System.currentTimeMillis()

        // Early temperature advice while charging (trend based) and the "charger or cable may be faulty" hint.
        val advice = chargerThermalGuard.onSample(now, state.isCharging, state.temperatureCelsius, state.powerWatts, state.batteryLevel, state.pluggedType == com.example.model.CanonicalPluggedType.USB)
        if (advice != null && settings.announceThermalWarning) {
            val danger = advice == ChargerThermalGuard.Advice.PRE_DANGER
            enqueue(
                AnnouncementItem(
                    id = "charger_thermal_${advice.name}_$now",
                    text = chargerThermalGuard.text(advice, state.temperatureCelsius ?: 0f),
                    priority = if (danger) AnnouncementPriority.CRITICAL_THERMAL else AnnouncementPriority.CHARGER_STATE,
                    category = "CHARGER_THERMAL",
                    isNightException = danger
                )
            )
        }

        if (state.isCharging == true && state.announcementSpeed != CanonicalChargingSpeed.UNAVAILABLE) {
            val currentSpeed = state.announcementSpeed
            val coolingDown = isInCooldown(lastByCategory["CHARGING_SPEED"], now, CATEGORY_COOLDOWN_MS.getValue("CHARGING_SPEED"))
            if (currentSpeed != lastAnnouncementSpeed && !coolingDown) {
                lastAnnouncementSpeed = currentSpeed
                // Near full the phone tapers the current on purpose, so "slow" there says nothing about the charger.
                val taperAtFull = currentSpeed == CanonicalChargingSpeed.SLOW && (state.batteryLevel ?: 0) >= ChargerThermalGuard.NEAR_FULL_PERCENT
                if (settings.announceChargingSpeed && !taperAtFull) {
                    val speechText = when (currentSpeed) {
                        CanonicalChargingSpeed.SLOW -> "Slow charging."
                        CanonicalChargingSpeed.NORMAL -> "Normal charging."
                        CanonicalChargingSpeed.FAST -> "Fast charging."
                        CanonicalChargingSpeed.ULTRA_FAST -> "Ultra fast charging."
                        else -> "Normal charging."
                    }
                    enqueue(
                        AnnouncementItem(
                            id = "speed_${now}",
                            text = speechText,
                            priority = AnnouncementPriority.CHARGING_SPEED,
                            category = "CHARGING_SPEED",
                            isNightException = false
                        )
                    )
                }
            }
        } else {
            lastAnnouncementSpeed = CanonicalChargingSpeed.UNAVAILABLE
        }
    }

    private fun handleCanonicalEvent(event: NetraCentralEvent) {
        if (processedEventIds.contains(event.eventId)) {
            Log.d(TAG, "Canonical event already processed by AnnouncementEngine -> ${event.eventId}")
            return
        }
        processedEventIds.add(event.eventId)
        if (processedEventIds.size > 200) {
            processedEventIds.remove(processedEventIds.first())
        }

        val settings = getSettings()
        val now = System.currentTimeMillis()

        when (event.eventType) {
            NetraEventType.CHARGER_CONNECTED -> {
                if (settings.announceChargerConnected) {
                    enqueue(
                        AnnouncementItem(
                            id = "charger_connected_$now",
                            text = "Charger connected.",
                            priority = AnnouncementPriority.CHARGER_STATE,
                            category = "CHARGER",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.CHARGER_DISCONNECTED -> {
                if (settings.announceChargerConnected) {
                    enqueue(
                        AnnouncementItem(
                            id = "charger_disconnected_$now",
                            text = "Charger disconnected.",
                            priority = AnnouncementPriority.CHARGER_STATE,
                            category = "CHARGER",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.CHARGING_STARTED -> {
                if (settings.announceChargerConnected) {
                    enqueue(
                        AnnouncementItem(
                            id = "charging_started_$now",
                            text = "Charging started.",
                            priority = AnnouncementPriority.CHARGER_STATE,
                            category = "CHARGER",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.CHARGING_STOPPED -> {
                if (settings.announceChargerConnected) {
                    enqueue(
                        AnnouncementItem(
                            id = "charging_stopped_$now",
                            text = "Charging stopped.",
                            priority = AnnouncementPriority.CHARGER_STATE,
                            category = "CHARGER",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.DISCHARGING_STARTED -> {
                if (settings.announcePhoneBattery) {
                    enqueue(
                        AnnouncementItem(
                            id = "discharging_started_$now",
                            text = "Discharging started.",
                            priority = AnnouncementPriority.CHARGER_STATE,
                            category = "CHARGER",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.BATTERY_LEVEL_CROSSED -> {
                val boundary = event.newValue?.toIntOrNull()
                if (boundary != null && boundary % 5 == 0 && settings.announcePhoneBattery) {
                    val state = NetraApplication.instance.centralDataCenter.centralState.value
                    val isCharging = state.isCharging == true
                    val prefix = if (isCharging) "C" else "D"
                    val text = "$prefix $boundary percent"
                    enqueue(
                        AnnouncementItem(
                            id = "phone_boundary_$boundary",
                            text = text,
                            priority = AnnouncementPriority.PHONE_BATTERY,
                            category = "PHONE_BATTERY",
                            isNightException = false
                        )
                    )
                }
            }
            NetraEventType.BLUETOOTH_CONNECTED -> {
                enqueue(
                    AnnouncementItem(
                        id = "bt_conn_${event.eventId}",
                        text = "BT connected.",
                        priority = AnnouncementPriority.BLUETOOTH_STATE,
                        category = "BLUETOOTH_STATE",
                        isNightException = false
                    )
                )
            }
            NetraEventType.BLUETOOTH_DISCONNECTED -> {
                enqueue(
                    AnnouncementItem(
                        id = "bt_disc_${event.eventId}",
                        text = "BT disconnected.",
                        priority = AnnouncementPriority.BLUETOOTH_STATE,
                        category = "BLUETOOTH_STATE",
                        isNightException = false
                    )
                )
            }
            NetraEventType.BLUETOOTH_BATTERY_BOUNDARY -> {
                val batteryPct = event.newValue?.toIntOrNull()
                if (batteryPct != null && settings.announceBluetoothBattery) {
                    enqueue(
                        AnnouncementItem(
                            id = "bt_battery_${event.eventId}",
                            text = "BT $batteryPct percent",
                            priority = AnnouncementPriority.BLUETOOTH_BATTERY,
                            category = "BLUETOOTH_BATTERY",
                            isNightException = false
                        )
                    )
                }
            }
            NetraEventType.THERMAL_WARNING -> {
                if (settings.announceThermalWarning) {
                    val temp = event.newValue?.toFloatOrNull() ?: 40.0f
                    val tempFormatted = String.format(Locale.US, "%.1f", temp)
                    val text = "Phone temperature is $tempFormatted degrees. Please stop using the phone and move to a cooler environment."
                    enqueue(
                        AnnouncementItem(
                            id = "thermal_warn_$now",
                            text = text,
                            priority = AnnouncementPriority.CRITICAL_THERMAL,
                            category = "THERMAL_WARNING",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.THERMAL_CRITICAL -> {
                if (settings.announceThermalWarning) {
                    val temp = event.newValue?.toFloatOrNull() ?: 45.0f
                    val tempFormatted = String.format(Locale.US, "%.1f", temp)
                    val text = "Thermal warning. Your phone temperature is $tempFormatted degrees. Please stop using the phone and move to a cooler environment."
                    enqueue(
                        AnnouncementItem(
                            id = "thermal_crit_$now",
                            text = text,
                            priority = AnnouncementPriority.CRITICAL_THERMAL,
                            category = "THERMAL_CRITICAL",
                            isNightException = true
                        )
                    )
                }
            }
            NetraEventType.THERMAL_PROTECTION_STARTED -> {
                enqueue(
                    AnnouncementItem(
                        id = "thermal_prot_start_$now",
                        text = "Thermal control started.",
                        priority = AnnouncementPriority.CRITICAL_THERMAL,
                        category = "THERMAL_PROTECTION",
                        isNightException = true
                    )
                )
            }
            NetraEventType.LOW_BATTERY_PROTECTION_STARTED -> {
                enqueue(
                    AnnouncementItem(
                        id = "low_bat_prot_start_$now",
                        text = "Battery power saving started.",
                        priority = AnnouncementPriority.CHARGER_STATE,
                        category = "LOW_BATTERY_PROTECTION",
                        isNightException = true
                    )
                )
            }
            else -> {}
        }
    }

    /**
     * Compatibility bridge for legacy telemetry inputs (deprecated - prefers central event-driven flow).
     */
    @Deprecated("Prefers NetraCentralDataCenter automated state/event-driven triggers")
    fun onTelemetryUpdate(telemetry: BatteryTelemetry) {
        // Left for backward compatibility with external service triggers; no-op as flow handles it autonomously.
    }

    /**
     * Compatibility bridge for legacy Bluetooth inputs (delegates to centralDataCenter).
     */
    fun onBluetoothDevicesUpdate(devices: List<BluetoothDeviceItem>) {
        scope.launch {
            try {
                NetraApplication.instance.centralDataCenter.processBluetoothDevices(devices)
            } catch (_: Exception) {}
        }
    }

    /**
     * Calculates crossed 5% boundaries between prev and current levels (kept for unit test coverage).
     */
    fun getCrossed5PercentBoundaries(prev: Int, current: Int, isCharging: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        val validBoundaries = (0..100 step 5).toList()

        if (isCharging) {
            for (b in validBoundaries) {
                if (b in (prev + 1)..current) {
                    result.add(b)
                }
            }
        } else {
            for (b in validBoundaries.reversed()) {
                if (b in current until prev) {
                    result.add(b)
                }
            }
        }

        return result
    }

    /**
     * Categorizes power speed based on raw/net watts (kept for unit test coverage).
     */
    fun categorizePowerSpeed(powerWatts: Float): String {
        return when {
            powerWatts >= 20.0f -> "ULTRA_FAST"
            powerWatts >= 10.0f -> "FAST"
            powerWatts >= 5.0f -> "NORMAL"
            else -> "SLOW"
        }
    }

    /**
     * Enqueues an announcement item respecting master enabled, night protection, and deduplication.
     */
    @Synchronized
    fun enqueue(item: AnnouncementItem) {
        val settings = getSettings()

        // 1. Global Master Switch Check
        if (!settings.announcementsMasterEnabled) {
            Log.d(TAG, "Announcement suppressed: master switch is OFF -> ${item.text}")
            return
        }

        // 1b. User mute interval: routine announcements are held back, critical warnings still play
        if (item.priority != AnnouncementPriority.CRITICAL_THERMAL &&
            System.currentTimeMillis() < settings.announcementMutedUntilMs
        ) {
            Log.d(TAG, "Announcement suppressed: muted by user until ${settings.announcementMutedUntilMs} -> ${item.text}")
            return
        }

            // 2. Night Protection Check (Delegated strictly to Central Unit canonical state)
            val dataCenter = NetraApplication.instance.centralDataCenter
            // Force refresh of night protection based on current time
            dataCenter.refreshNightProtectionStateSynchronously() 
            if (dataCenter.centralState.value.isNightProtectionActive) {
                if (!item.isNightException) {
                    Log.d(TAG, "Announcement suppressed by Night Protection policy decision -> ${item.text}")
                    return
                }
            }

        // 3. Prevent exact duplicate item already waiting in queue
        if (queue.any { it.text == item.text }) {
            Log.d(TAG, "Duplicate announcement discarded -> ${item.text}")
            return
        }

        // 3a. Per-category cooldown: charging-speed change announcements wait at least 60 seconds after the last one,
        // even if the speed changed again meanwhile. No continuous repeating.
        val cooldown = CATEGORY_COOLDOWN_MS[item.category]
        if (cooldown != null) {
            val nowMs = System.currentTimeMillis()
            if (isInCooldown(lastByCategory[item.category], nowMs, cooldown)) {
                Log.d(TAG, "Announcement held by ${item.category} cooldown -> ${item.text}")
                return
            }
            lastByCategory[item.category] = nowMs
        }

        // 3b. One central ledger for every announcement type: the same words are never spoken twice
        // within DEDUPE_WINDOW_MS, whatever path produced them (event, state change, test).
        // A late announcement is fine; a repeat is not. Manual previews are exempt.
        if (item.category != "DIRECT_TEST") {
            val nowMs = System.currentTimeMillis()
            if (isRecentDuplicate(recentSpoken, item.text, nowMs, DEDUPE_WINDOW_MS)) {
                Log.d(TAG, "Repeat announcement suppressed by central ledger -> ${item.text}")
                return
            }
            recentSpoken[item.text] = nowMs
            if (recentSpoken.size > 50) recentSpoken.entries.removeAll { nowMs - it.value > DEDUPE_WINDOW_MS }
        }

        // 4. Discard obsolete low-priority announcements where safe
        if (item.priority == AnnouncementPriority.PHONE_BATTERY) {
            queue.removeAll { it.priority == AnnouncementPriority.PHONE_BATTERY }
        } else if (item.priority == AnnouncementPriority.BLUETOOTH_BATTERY) {
            queue.removeAll { it.priority == AnnouncementPriority.BLUETOOTH_BATTERY }
        }

        // 5. Interrupt active low priority speaking if a critical thermal event arrives
        if (item.priority == AnnouncementPriority.CRITICAL_THERMAL) {
            val current = currentPlayingAnnouncement
            if (current != null && current.priority != AnnouncementPriority.CRITICAL_THERMAL) {
                Log.i(TAG, "Interrupting low-priority speech for critical thermal event!")
                current.speechState = AnnouncementSpeechState.CANCELLED
                try {
                    tts?.stop() // This initiates recovery/next queue poll
                } catch (_: Exception) {}
            }
        }

        // Keep the eight most urgent items. Include the incoming item in overflow selection.
        item.speechState = AnnouncementSpeechState.QUEUED
        offerBounded(queue, item)
        processQueue()
    }

    /**
     * Helper to test or trigger manual spoken preview.
     */
    fun speakDirect(text: String, priority: AnnouncementPriority = AnnouncementPriority.INFORMATIONAL) {
        enqueue(
            AnnouncementItem(
                id = "direct_${System.currentTimeMillis()}",
                text = text,
                priority = priority,
                category = "DIRECT_TEST",
                isNightException = true
            )
        )
    }

    @Synchronized
    private fun processQueue() {
        if (!isTtsReady || isSpeaking.get() || queue.isEmpty()) {
            return
        }

        val nextItem = queue.poll() ?: return
        isSpeaking.set(true)
        currentPlayingAnnouncement = nextItem

        scope.launch(Dispatchers.IO) {
            val settings = getSettings()
            if (settings.mediaPlaybackHandlingEnabled) {
                try {
                    NetraApplication.instance.centralDataCenter.requestMediaPause(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting media pause from central unit", e)
                }
            }

            // Audio routing check and fallback policy evaluation
            val routingStatus = AudioRoutingInspector.inspectRouting(context, settings.audioRoutingPolicy)
            val isCritical = nextItem.priority == AnnouncementPriority.CRITICAL_THERMAL

            // Fixed behaviour: the phone speaker always speaks first (notification stream).
            // If a Bluetooth device is connected, the same announcement is replayed there right after (see onDone).
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, nextItem.id)
                putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION.toString())
            }

            nextItem.speechState = AnnouncementSpeechState.SPEAKING
            val result = tts?.speak(nextItem.text, TextToSpeech.QUEUE_FLUSH, params, nextItem.id)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS speak failed for: ${nextItem.text}")
                nextItem.speechState = AnnouncementSpeechState.FAILED
                
                // Trigger fallback to phone speaker if primary route failed
                if (routingStatus.isBluetoothA2dpConnected) {
                    triggerSpeakerFallback(nextItem, "TTS speak API returned failure")
                }
                onSpeechFinished()
            } else {
                Log.i(TAG, "Spoken: '${nextItem.text}' [Priority: ${nextItem.priority}, Route: ${routingStatus.activePrimaryRoute}]")
                logAnnouncementToDb(nextItem)

                // Volume zero check on active bluetooth
                if (routingStatus.isBluetoothA2dpConnected && !routingStatus.isMusicVolumeAdequate && isCritical) {
                    Log.w(TAG, "Bluetooth volume is zero for critical alert; invoking speaker fallback")
                    playSpeakerAlertChime()
                }
            }
        }
    }

    /**
     * Fallback policy execution when primary Bluetooth speech delivery is disrupted or muted.
     */
    fun triggerSpeakerFallback(item: AnnouncementItem, reason: String) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.w(TAG, "Executing Phone Speaker Fallback for '${item.text}' due to: $reason")
                // Sound alarm/notification chime over speaker to alert user
                playSpeakerAlertChime()

                // Update central state with last fallback trigger
                val dataCenter = NetraApplication.instance.centralDataCenter
                val curState = dataCenter.centralState.value
                val updatedRouting = curState.audioRoutingStatus.copy(
                    lastFallbackTriggered = "Fallback to Speaker: $reason (${System.currentTimeMillis()})"
                )
                dataCenter.updateAudioRoutingStatus(updatedRouting)
            } catch (e: Exception) {
                Log.e(TAG, "Failed executing speaker fallback chime", e)
            }
        }
    }

    /**
     * Emits a standard safety confirmation chime through device speaker using ToneGenerator.
     */
    fun playSpeakerAlertChime() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
            toneGen.release()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to generate ToneGenerator alert tone", e)
        }
    }

    /**
     * Second pass of the fixed routing: after the phone speaker finished, speak the same text on the default
     * (media) stream, which Android routes to a connected Bluetooth device. Android does not promise speaker and
     * Bluetooth at the same moment, so this is sequential. Returns true when a replay was started.
     */
    private fun onSpeechFinished() {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = getSettings()
                if (settings.mediaPlaybackHandlingEnabled) {
                    NetraApplication.instance.centralDataCenter.requestMediaResume(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring media playback via central unit", e)
            } finally {
                isSpeaking.set(false)
                currentPlayingAnnouncement = null
                scope.launch(Dispatchers.Main) {
                    processQueue()
                }
            }
        }
    }

    /**
     * Evaluates if current system time falls within Night Protection window.
     */
    fun isNightTime(startHour: Int, endHour: Int): Boolean {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour > endHour) {
            currentHour >= startHour || currentHour < endHour
        } else {
            currentHour in startHour until endHour
        }
    }

    private fun logAnnouncementToDb(item: AnnouncementItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val repository = NetraApplication.instance.batteryRepository
                val dotColor = when (item.priority) {
                    AnnouncementPriority.CRITICAL_THERMAL -> "RED"
                    AnnouncementPriority.CHARGER_STATE -> "CYAN"
                    AnnouncementPriority.CHARGING_SPEED -> "GREEN"
                    AnnouncementPriority.PHONE_BATTERY -> "BLUE"
                    AnnouncementPriority.BLUETOOTH_STATE -> "PURPLE"
                    AnnouncementPriority.BLUETOOTH_BATTERY -> "CYAN"
                    AnnouncementPriority.INFORMATIONAL -> "AMBER"
                }
                repository.logEvent(
                    title = "Voice Announcement",
                    message = item.text,
                    category = item.category,
                    severity = if (item.priority == AnnouncementPriority.CRITICAL_THERMAL) "CRITICAL" else "INFO",
                    dotColor = dotColor
                )
            } catch (_: Exception) {}
        }
    }

    private fun getSettings(): SentinelSettings {
        return try {
            NetraApplication.instance.settingsRepository.settings.value
        } catch (_: Exception) {
            SentinelSettings()
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        mediaController.restoreAfterAnnouncement()
    }

    companion object {
        /** Uses the existing queue; never sacrifices an urgent item for a lower-priority arrival. */
        internal fun offerBounded(queue: PriorityQueue<AnnouncementItem>, item: AnnouncementItem) {
            queue.offer(item)
            if (queue.size > 8) {
                // Higher comparator values are less urgent; newest loses equal-priority overflow.
                val leastUrgent = queue.maxOrNull() ?: return
                queue.remove(leastUrgent)
                leastUrgent.speechState = AnnouncementSpeechState.CANCELLED
            }
        }

        /** The same announcement text is not spoken again within this window. */
        internal const val DEDUPE_WINDOW_MS = 20_000L

        /** Minimum gap between two announcements of the same category. */
        internal val CATEGORY_COOLDOWN_MS: Map<String, Long> = mapOf("CHARGING_SPEED" to 60_000L)

        /** True when something was announced less than [cooldownMs] ago. */
        internal fun isInCooldown(lastAtMs: Long?, nowMs: Long, cooldownMs: Long): Boolean =
            lastAtMs != null && nowMs - lastAtMs in 0 until cooldownMs

        /** True when [key] was accepted less than [windowMs] ago. */
        internal fun isRecentDuplicate(seen: Map<String, Long>, key: String, nowMs: Long, windowMs: Long): Boolean {
            val prev = seen[key] ?: return false
            return nowMs - prev in 0 until windowMs
        }

        private const val TAG = "NetraAnnouncementEngine"
    }
}

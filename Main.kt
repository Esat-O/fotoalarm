package com.example.fotoalarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject



val DAY_ORDER = listOf(
    Calendar.MONDAY to "Pzt", Calendar.TUESDAY to "Sal", Calendar.WEDNESDAY to "Çar",
    Calendar.THURSDAY to "Per", Calendar.FRIDAY to "Cum", Calendar.SATURDAY to "Cmt",
    Calendar.SUNDAY to "Paz"
)

data class Alarm(
    val id: Int,
    var hour: Int,
    var minute: Int,
    var label: String = "",
    var days: MutableSet<Int> = mutableSetOf(), // boşsa tek seferlik
    var enabled: Boolean = true,
    var hasPhoto: Boolean = false
) {
    fun photoFile(ctx: Context) = File(ctx.filesDir, "alarm_$id.jpg")

    fun daysText(): String = when {
        days.isEmpty() -> "Bir kez"
        days.size == 7 -> "Her gün"
        else -> DAY_ORDER.filter { it.first in days }.joinToString(" ") { it.second }
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute); put("label", label)
        put("days", JSONArray(days.toList())); put("enabled", enabled); put("hasPhoto", hasPhoto)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val d = mutableSetOf<Int>()
            val arr = o.optJSONArray("days")
            if (arr != null) for (i in 0 until arr.length()) d.add(arr.getInt(i))
            return Alarm(
                o.getInt("id"), o.getInt("hour"), o.getInt("minute"),
                o.optString("label", ""), d, o.optBoolean("enabled", true), o.optBoolean("hasPhoto", false)
            )
        }
    }
}

object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY = "list"

    fun all(ctx: Context): MutableList<Alarm> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(s)
        val out = mutableListOf<Alarm>()
        for (i in 0 until arr.length()) out.add(Alarm.fromJson(arr.getJSONObject(i)))
        return out.sortedWith(compareBy({ it.hour }, { it.minute })).toMutableList()
    }

    private fun write(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: Int): Alarm? = all(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, a: Alarm) {
        val l = all(ctx)
        val i = l.indexOfFirst { it.id == a.id }
        if (i >= 0) l[i] = a else l.add(a)
        write(ctx, l)
    }

    fun delete(ctx: Context, id: Int) = write(ctx, all(ctx).filter { it.id != id })

    fun nextId(ctx: Context): Int = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1
}



object Scheduler {
    private const val SNOOZE_BASE = 100000

    fun nextTrigger(a: Alarm): Long {
        val now = System.currentTimeMillis()
        for (offset in 0..7) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, a.hour)
                set(Calendar.MINUTE, a.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now) continue
            if (a.days.isEmpty() || a.days.contains(c.get(Calendar.DAY_OF_WEEK))) return c.timeInMillis
        }
        return -1
    }

    private fun firePi(ctx: Context, requestCode: Int, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("id", id).putExtra("snooze", snooze)
        return PendingIntent.getBroadcast(
            ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAt(ctx: Context, time: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), pi)
    }

    fun schedule(ctx: Context, a: Alarm) {
        if (!a.enabled) { cancel(ctx, a.id); return }
        val t = nextTrigger(a)
        if (t > 0) setAt(ctx, t, firePi(ctx, a.id, a.id, false))
    }

    fun snooze(ctx: Context, id: Int, minutes: Int) {
        val t = System.currentTimeMillis() + minutes * 60_000L
        setAt(ctx, t, firePi(ctx, SNOOZE_BASE + id, id, true))
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(firePi(ctx, id, id, false))
        am.cancel(firePi(ctx, SNOOZE_BASE + id, id, true))
    }
}



class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val snooze = intent.getBooleanExtra("snooze", false)
        val a = AlarmStore.get(ctx, id) ?: return
        if (!snooze) {
            if (a.days.isEmpty()) {
                a.enabled = false
                AlarmStore.upsert(ctx, a)
            } else {
                Scheduler.schedule(ctx, a) // bir sonraki tekrarı kur
            }
        }
        ctx.startForegroundService(Intent(ctx, RingService::class.java).putExtra("id", id))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmStore.all(ctx).filter { it.enabled }.forEach { Scheduler.schedule(ctx, it) }
    }
}



class RingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", -1) ?: -1
        val a = AlarmStore.get(this, id)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("alarm", "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        val ring = Intent(this, RingActivity::class.java)
            .putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, ring, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = Notification.Builder(this, "alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm")
            .setContentText(a?.label?.ifBlank { null } ?: "Alarm çalıyor")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }

        startSound()
        startVibration()

        // "Diğer uygulamaların üzerinde göster" izni verildiyse ekranı doğrudan aç
        if (Settings.canDrawOverlays(this)) startActivity(ring)

        return START_NOT_STICKY
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startVibration() {
        vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0))
    }

    override fun onDestroy() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        vibrator?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}



object Photos {
    private fun sample(w: Int, h: Int, max: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= max) s *= 2
        return s
    }

    fun load(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide)
        o.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, o)
    }

    fun fromUri(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide)
        o.inJustDecodeBounds = false
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun save(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}



fun Activity.dp(x: Int) = (x * resources.displayMetrics.density).toInt()

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var overlayBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(28), dp(16), dp(12))

        val title = TextView(this)
        title.text = "Foto Alarm"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        overlayBtn = Button(this)
        overlayBtn.text = "Alarm ekranı için izin ver (önerilir)"
        overlayBtn.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        root.addView(overlayBtn)

        val add = Button(this)
        add.text = "+ Yeni alarm"
        add.setOnClickListener { startActivity(Intent(this, EditActivity::class.java)) }
        root.addView(add)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        overlayBtn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        list.removeAllViews()
        val alarms = AlarmStore.all(this)
        if (alarms.isEmpty()) {
            val hint = TextView(this)
            hint.text = "Henüz alarm yok. \"+ Yeni alarm\" ile başla."
            hint.setPadding(dp(8), dp(24), dp(8), 0)
            list.addView(hint)
        }
        alarms.forEach { list.addView(row(it)) }
    }

    private fun row(a: Alarm): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(12), dp(8), dp(12))
        row.setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", a.id))
        }

        if (a.hasPhoto) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(Photos.load(a.photoFile(this), 160))
            val lp = LinearLayout.LayoutParams(dp(56), dp(56))
            lp.rightMargin = dp(12)
            row.addView(iv, lp)
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val time = TextView(this)
        time.text = "%02d:%02d".format(a.hour, a.minute)
        time.textSize = 32f
        val sub = TextView(this)
        sub.text = (if (a.label.isNotBlank()) a.label + " · " else "") + a.daysText()
        col.addView(time)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = Switch(this)
        sw.isChecked = a.enabled
        sw.setOnCheckedChangeListener { _, on ->
            a.enabled = on
            AlarmStore.upsert(this, a)
            Scheduler.schedule(this, a)
        }
        row.addView(sw)
        return row
    }
}



class EditActivity : Activity() {
    private var alarm: Alarm? = null
    private lateinit var picker: TimePicker
    private lateinit var labelEt: EditText
    private lateinit var preview: ImageView
    private val dayBoxes = mutableMapOf<Int, CheckBox>()
    private var pendingBitmap: Bitmap? = null
    private var removePhoto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra("id", -1)
        alarm = if (id >= 0) AlarmStore.get(this, id) else null
        val a = alarm

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(16))

        picker = TimePicker(this)
        picker.setIs24HourView(true)
        val now = Calendar.getInstance()
        picker.hour = a?.hour ?: now.get(Calendar.HOUR_OF_DAY)
        picker.minute = a?.minute ?: now.get(Calendar.MINUTE)
        root.addView(picker)

        labelEt = EditText(this)
        labelEt.hint = "Alarm adı (isteğe bağlı)"
        labelEt.setText(a?.label ?: "")
        root.addView(labelEt)

        val daysRow = LinearLayout(this)
        daysRow.orientation = LinearLayout.HORIZONTAL
        DAY_ORDER.forEach { (day, name) ->
            val cb = CheckBox(this)
            cb.text = name
            cb.textSize = 11f
            cb.isChecked = a?.days?.contains(day) ?: false
            dayBoxes[day] = cb
            daysRow.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(daysRow)

        val hint = TextView(this)
        hint.text = "Hiçbir gün seçilmezse alarm bir kez çalar."
        hint.textSize = 12f
        root.addView(hint)

        preview = ImageView(this)
        preview.scaleType = ImageView.ScaleType.CENTER_CROP
        preview.setBackgroundColor(Color.parseColor("#DDDDDD"))
        if (a != null && a.hasPhoto) preview.setImageBitmap(Photos.load(a.photoFile(this), 1000))
        val plp = LinearLayout.LayoutParams(-1, dp(220))
        plp.topMargin = dp(12)
        root.addView(preview, plp)

        val photoRow = LinearLayout(this)
        photoRow.orientation = LinearLayout.HORIZONTAL
        val pick = Button(this)
        pick.text = "Fotoğraf seç"
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            startActivityForResult(i, 10)
        }
        val rem = Button(this)
        rem.text = "Fotoğrafı kaldır"
        rem.setOnClickListener {
            pendingBitmap = null
            removePhoto = true
            preview.setImageDrawable(null)
        }
        photoRow.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
        photoRow.addView(rem, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(photoRow)

        val save = Button(this)
        save.text = "Kaydet"
        save.setOnClickListener { save() }
        root.addView(save)

        if (a != null) {
            val del = Button(this)
            del.text = "Alarmı sil"
            del.setOnClickListener {
                Scheduler.cancel(this, a.id)
                a.photoFile(this).delete()
                AlarmStore.delete(this, a.id)
                finish()
            }
            root.addView(del)
        }

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == RESULT_OK && data?.data != null) {
            val bmp = Photos.fromUri(this, data.data!!)
            if (bmp == null) {
                Toast.makeText(this, "Fotoğraf okunamadı", Toast.LENGTH_SHORT).show()
                return
            }
            pendingBitmap = bmp
            removePhoto = false
            preview.setImageBitmap(bmp)
        }
    }

    private fun save() {
        picker.clearFocus()
        val id = alarm?.id ?: AlarmStore.nextId(this)
        val days = dayBoxes.filter { it.value.isChecked }.keys.toMutableSet()
        val a = Alarm(
            id, picker.hour, picker.minute, labelEt.text.toString().trim(),
            days, true, alarm?.hasPhoto ?: false
        )
        val bmp = pendingBitmap
        if (bmp != null) {
            Photos.save(bmp, a.photoFile(this))
            a.hasPhoto = true
        } else if (removePhoto) {
            a.photoFile(this).delete()
            a.hasPhoto = false
        }
        AlarmStore.upsert(this, a)
        Scheduler.schedule(this, a)

        val mins = (Scheduler.nextTrigger(a) - System.currentTimeMillis()) / 60000
        Toast.makeText(this, "Alarm ${mins / 60} sa ${mins % 60} dk sonra çalacak", Toast.LENGTH_LONG).show()
        finish()
    }
}



class RingActivity : Activity() {
    private var alarmId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        alarmId = intent.getIntExtra("id", -1)
        val a = AlarmStore.get(this, alarmId)

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)

        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        if (a != null && a.hasPhoto) iv.setImageBitmap(Photos.load(a.photoFile(this), 1600))
        frame.addView(iv, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16), dp(56), dp(16), dp(32))

        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 72f
        clock.setTextColor(Color.WHITE)
        clock.setShadowLayer(12f, 0f, 0f, Color.BLACK)
        col.addView(clock)

        val label = TextView(this)
        label.text = a?.label ?: ""
        label.textSize = 22f
        label.setTextColor(Color.WHITE)
        label.setShadowLayer(12f, 0f, 0f, Color.BLACK)
        col.addView(label)

        col.addView(Space(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val snooze = Button(this)
        snooze.text = "Ertele (10 dk)"
        snooze.setOnClickListener {
            Scheduler.snooze(this, alarmId, 10)
            stopService(Intent(this, RingService::class.java))
            finish()
        }
        val stop = Button(this)
        stop.text = "Kapat"
        stop.setOnClickListener {
            stopService(Intent(this, RingService::class.java))
            finish()
        }
        val lp = LinearLayout.LayoutParams(0, dp(64), 1f)
        lp.setMargins(dp(6), 0, dp(6), 0)
        btns.addView(snooze, lp)
        btns.addView(stop, LinearLayout.LayoutParams(lp))
        col.addView(btns, LinearLayout.LayoutParams(-1, -2))

        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* alarmı yalnızca butonlar kapatsın */ }
}


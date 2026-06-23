package kr.co.donghyun.flamelauncher.data.setting

import android.content.Context
import com.google.gson.Gson
import kr.co.donghyun.flamelauncher.data.jvm.JvmSettings
import java.io.File

data class Setting(
    val neverShowCautionAgain : Boolean = false
)

object SettingManager {
    private const val FILE_NAME = "setting.json"
    private val gson = Gson()

    fun load(context: Context): Setting {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return Setting()
            val settings = gson.fromJson(file.readText(), Setting::class.java)
                ?: Setting()

            settings
        } catch (_: Exception) {
            Setting()
        }
    }

    fun save(context: Context, settings: Setting) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(settings))
        } catch (_: Exception) {}
    }
}
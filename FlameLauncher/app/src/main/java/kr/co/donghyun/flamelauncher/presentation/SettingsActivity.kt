package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.SettingsScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme

class SettingsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        setContent {
            FlameLauncherTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}
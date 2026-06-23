package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.RendererSettingsScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.PingLauncherTheme

class RendererSettingsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, RendererSettingsActivity::class.java))
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        setContent {
            PingLauncherTheme {
                RendererSettingsScreen(onBack = { finish() })
            }
        }
    }
}
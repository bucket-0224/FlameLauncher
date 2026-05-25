package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme

class LoginActivity : BaseActivity() {

    companion object {
        const val RESULT_ERROR = "login_error"
    }

    override fun onCreated() {
        setContent {
            PingLauncherTheme {
                var isLoading by remember { mutableStateOf(false) }
                var statusMessage by remember { mutableStateOf("") }
                val Pink = Color(0xFFE91E8C)
                val TextSub = Color(0xFFBB86A0)

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0008))) {
                    if (isLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = Pink)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(statusMessage, color = TextSub, fontSize = 13.sp)
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.startsWith(MicrosoftAuthManager.REDIRECT_URI)) {
                                                val code = request.url?.getQueryParameter("code")
                                                if (code != null) {
                                                    isLoading = true
                                                    statusMessage = "로그인 중..."
                                                    lifecycleScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val session = MicrosoftAuthManager.loginWithCode(code)
                                                            MicrosoftAuthManager.saveSession(this@LoginActivity, session)
                                                            withContext(Dispatchers.Main) {
                                                                val intent = Intent()
                                                                setResult(RESULT_OK, intent)
                                                                finish()
                                                            }
                                                        } catch (e: Exception) {
                                                            withContext(Dispatchers.Main) {
                                                                isLoading = false
                                                                val intent = Intent().putExtra(RESULT_ERROR, e.message)
                                                                setResult(RESULT_CANCELED, intent)
                                                                finish()
                                                            }
                                                        }
                                                    }
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    loadUrl(MicrosoftAuthManager.getAuthUrl())
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
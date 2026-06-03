package kr.co.donghyun.pinglauncher.presentation.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object FolderOpener {
    fun openFolder(context: Context, folder: File) {
        if (!folder.exists()) folder.mkdirs()
        try {
            // 1순위: 파일 매니저로 폴더 직접 열기
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                folder
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "폴더 열기"))
        } catch (e: Exception) {
            Log.w("FolderOpener", "ACTION_VIEW 실패, SAF로 폴백", e)
            // 2순위: SAF로 폴백
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e("FolderOpener", "SAF도 실패", e2)
            }
        }
    }
}
package com.atuy.pdftoimg

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ImageFormat(val extension: String, val mimeType: String, val compressFormat: Bitmap.CompressFormat) {
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
    WEBP("webp", "image/webp", Bitmap.CompressFormat.WEBP_LOSSLESS);

    companion object {
        fun fromExtension(ext: String?): ImageFormat {
            return entries.find { it.extension == ext } ?: PNG
        }
    }
}

class PdfConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("pdf_converter_prefs", Application.MODE_PRIVATE)
    private val KEY_SELECTED_FORMAT = "key_selected_format"

    // UI状態
    private val _uiState = MutableStateFlow(PdfConverterUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // 設定の読み込み
        val savedExt = prefs.getString(KEY_SELECTED_FORMAT, ImageFormat.PNG.extension)
        _uiState.value = _uiState.value.copy(selectedFormat = ImageFormat.fromExtension(savedExt))
    }

    fun updateSelectedFormat(format: ImageFormat) {
        _uiState.value = _uiState.value.copy(selectedFormat = format)
        // 設定の保存
        prefs.edit().putString(KEY_SELECTED_FORMAT, format.extension).apply()
    }

    fun setTargetUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(targetUri = uri, statusMessage = if(uri != null) "変換する準備ができました" else "ファイルが選択されていません")
    }

    fun convertPdf() {
        val uri = uiState.value.targetUri ?: return
        val format = uiState.value.selectedFormat

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConverting = true, progress = 0f, statusMessage = "変換中...")

            try {
                convertPdfToImages(uri, format)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConverting = false,
                    statusMessage = "エラー: ${e.localizedMessage}"
                )
            }
        }
    }

    private suspend fun convertPdfToImages(pdfUri: Uri, format: ImageFormat) {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            var fileDescriptor: ParcelFileDescriptor? = null
            var pdfRenderer: PdfRenderer? = null

            try {
                fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                if (fileDescriptor == null) {
                    throw Exception("ファイルを開けませんでした")
                }

                // ここでSecurityExceptionが発生する可能性がある
                pdfRenderer = PdfRenderer(fileDescriptor)

                val pageCount = pdfRenderer.pageCount

                for (i in 0 until pageCount) {
                    if (!_uiState.value.isConverting) break // キャンセル処理用

                    val page = pdfRenderer.openPage(i)

                    // メモリ対策: 解像度制限
                    // 長辺が2048pxを超えないようにスケールを調整
                    val scale = calculateScale(page.width, page.height, 2048)
                    val width = (page.width * scale).toInt()
                    val height = (page.height * scale).toInt()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    saveBitmapToGallery(context, bitmap, "page_${i + 1}", format)

                    page.close()
                    bitmap.recycle()

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(progress = (i + 1).toFloat() / pageCount)
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isConverting = false,
                        statusMessage = "完了: ${pageCount}枚保存しました",
                        isComplete = true
                    )
                }

            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isConverting = false,
                        statusMessage = "パスワード付きPDFは対応していません"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isConverting = false,
                        statusMessage = "エラーが発生しました: ${e.message}"
                    )
                }
            } finally {
                pdfRenderer?.close()
                fileDescriptor?.close()
            }
        }
    }

    private fun calculateScale(width: Int, height: Int, maxDimension: Int): Float {
        val maxSide = maxOf(width, height)
        return if (maxSide > maxDimension) {
            maxDimension.toFloat() / maxSide
        } else {
            2.0f // 元画像のサイズが小さければ2倍にする（高解像度化）
        }
    }

    private fun saveBitmapToGallery(
        context: Application,
        bitmap: Bitmap,
        baseFileName: String,
        format: ImageFormat
    ) {
        val filename = "PDF_${System.currentTimeMillis()}_$baseFileName.${format.extension}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "PdfConverter")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it).use { outputStream ->
                if (outputStream != null) {
                    bitmap.compress(format.compressFormat, 100, outputStream)
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }
    }
}

data class PdfConverterUiState(
    val targetUri: Uri? = null,
    val selectedFormat: ImageFormat = ImageFormat.PNG,
    val isConverting: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "ファイルを選択してください",
    val isComplete: Boolean = false
)
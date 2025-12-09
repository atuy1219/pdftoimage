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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    private val _uiState = MutableStateFlow(PdfConverterUiState())
    val uiState = _uiState.asStateFlow()

    init {
        val savedExt = prefs.getString(KEY_SELECTED_FORMAT, ImageFormat.PNG.extension)
        _uiState.value = _uiState.value.copy(selectedFormat = ImageFormat.fromExtension(savedExt))
    }

    fun updateSelectedFormat(format: ImageFormat) {
        _uiState.value = _uiState.value.copy(selectedFormat = format)
        prefs.edit().putString(KEY_SELECTED_FORMAT, format.extension).apply()
    }

    fun setTargetUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(
            targetUri = uri,
            statusMessage = if(uri != null) "変換する準備ができました" else "ファイルが選択されていません",
            isComplete = false,
            isSaveComplete = false,
            generatedImageUris = emptyList(),
            generatedImageFiles = emptyList()
        )
    }

    fun convertPdf() {
        val uri = uiState.value.targetUri ?: return
        val format = uiState.value.selectedFormat

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConverting = true,
                progress = 0f,
                statusMessage = "変換中...",
                generatedImageUris = emptyList(),
                generatedImageFiles = emptyList()
            )

            try {
                convertPdfToTempFiles(uri, format)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConverting = false,
                    statusMessage = "エラー: ${e.localizedMessage}"
                )
            }
        }
    }

    fun saveImagesToGallery() {
        val files = uiState.value.generatedImageFiles
        val format = uiState.value.selectedFormat
        
        if (files.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSaving = true,
                statusMessage = "保存中..."
            )

            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    var successCount = 0
                    
                    files.forEach { file ->
                        if (saveFileToGallery(context, file, format)) {
                            successCount++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            isSaveComplete = true,
                            statusMessage = "${successCount}枚の画像を保存しました"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "保存エラー: ${e.localizedMessage}"
                )
            }
        }
    }

    private suspend fun convertPdfToTempFiles(pdfUri: Uri, format: ImageFormat) {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            var fileDescriptor: ParcelFileDescriptor? = null
            var pdfRenderer: PdfRenderer? = null
            val generatedUris = mutableListOf<Uri>()
            val generatedFiles = mutableListOf<File>()

            val cacheDir = File(context.cacheDir, "pdf_images")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            cacheDir.mkdirs()

            try {
                fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                if (fileDescriptor == null) {
                    throw Exception("ファイルを開けませんでした")
                }

                pdfRenderer = PdfRenderer(fileDescriptor)
                val pageCount = pdfRenderer.pageCount

                for (i in 0 until pageCount) {
                    if (!_uiState.value.isConverting) break

                    val page = pdfRenderer.openPage(i)

                    val scale = calculateScale(page.width, page.height, 2048)
                    val width = (page.width * scale).toInt()
                    val height = (page.height * scale).toInt()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val fileName = "temp_page_${i + 1}.${format.extension}"
                    val file = File(cacheDir, fileName)
                    
                    FileOutputStream(file).use { out ->
                        bitmap.compress(format.compressFormat, 100, out)
                    }

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )

                    generatedFiles.add(file)
                    generatedUris.add(uri)

                    page.close()
                    bitmap.recycle()

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(progress = (i + 1).toFloat() / pageCount)
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isConverting = false,
                        statusMessage = "変換完了: ${pageCount}枚",
                        isComplete = true,
                        generatedImageUris = generatedUris,
                        generatedImageFiles = generatedFiles
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
            2.0f
        }
    }

    private fun saveFileToGallery(
        context: Application,
        sourceFile: File,
        format: ImageFormat
    ): Boolean {
        val filename = "PDF_${System.currentTimeMillis()}_${sourceFile.name}"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "PdfConverter")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        return uri?.let { targetUri ->
            try {
                resolver.openOutputStream(targetUri).use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream!!)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(targetUri, contentValues, null, null)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        } ?: false
    }
}

data class PdfConverterUiState(
    val targetUri: Uri? = null,
    val selectedFormat: ImageFormat = ImageFormat.PNG,
    val isConverting: Boolean = false,
    val isSaving: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "ファイルを選択してください",
    val isComplete: Boolean = false,
    val isSaveComplete: Boolean = false,
    val generatedImageUris: List<Uri> = emptyList(),
    val generatedImageFiles: List<File> = emptyList()
)
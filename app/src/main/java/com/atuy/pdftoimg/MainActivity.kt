package com.atuy.pdftoimg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.atuy.pdftoimg.ui.theme.PdftoimgTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PdfConverterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Intent.ACTION_SEND == intent.action && intent.type == "application/pdf") {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            viewModel.setTargetUri(uri)
        }

        setContent {
            PdftoimgTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        ConverterDialogContent(
                            viewModel = viewModel,
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConverterDialogContent(
    viewModel: PdfConverterViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.isSaveComplete) {
        if (uiState.isSaveComplete) {
            Toast.makeText(context, uiState.statusMessage, Toast.LENGTH_LONG).show()
            onClose()
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PDF変換",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.targetUri != null) {
                Text(
                    text = "選択中: PDFファイル",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "ファイルが共有されていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "保存形式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Start)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val displayFormats = ImageFormat.values()

                displayFormats.forEach { format ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RadioButton(
                            selected = (format == uiState.selectedFormat),
                            onClick = {
                                if (!uiState.isConverting && !uiState.isSaving) {
                                    viewModel.updateSelectedFormat(format)
                                }
                            }
                        )
                        Text(
                            text = format.extension.uppercase(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isConverting || uiState.isSaving) {
                LinearProgressIndicator(
                    progress = { 
                        if(uiState.isSaving) 0f // 保存中は不確定プログレス（または別途計算）にする
                        else uiState.progress 
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (uiState.isConverting) {
                    Text(
                        text = "${(uiState.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (uiState.statusMessage.isNotEmpty() && !uiState.isConverting && !uiState.isSaving) {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.statusMessage.contains("エラー") || uiState.statusMessage.contains("対応していません"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (uiState.isComplete) {
                    // 変換完了後の表示（共有、保存）
                    OutlinedButton(
                        onClick = { shareImages(context, uiState.generatedImageUris) },
                        enabled = !uiState.isSaving
                    ) {
                        Text("共有")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { viewModel.saveImagesToGallery() },
                        enabled = !uiState.isSaving
                    ) {
                        Text("保存")
                    }
                } else {
                    // 変換前の表示（閉じる、変換）
                    TextButton(onClick = onClose) {
                        Text("閉じる")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { viewModel.convertPdf() },
                        enabled = uiState.targetUri != null && !uiState.isConverting
                    ) {
                        Text("変換")
                    }
                }
            }
        }
    }
}

fun shareImages(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return

    val shareIntent = Intent().apply {
        if (uris.size == 1) {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        type = "image/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "画像を共有"))
}
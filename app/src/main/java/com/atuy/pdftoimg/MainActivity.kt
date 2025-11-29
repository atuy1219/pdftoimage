package com.atuy.pdftoimg

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

        // 共有インテントからURIを取得
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
                // ダイアログ風のUIを提供
                // Activity自体が透明テーマなので、ここで背景を半透明にしたり、Cardを配置したりする
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent // 背景はテーマで制御済みだが念のため
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                        // 画面外タップで閉じる処理を入れたい場合はここにclickableを追加し、Cardのクリックイベントを消費させる
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

    // 完了時にToastを出して閉じる（オプション）
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            Toast.makeText(context, uiState.statusMessage, Toast.LENGTH_LONG).show()
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

            // フォーマット選択
            Text(
                text = "保存形式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Start)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // よく使うフォーマットのみ表示、またはすべて表示
                val displayFormats = ImageFormat.values()


                displayFormats.forEach { format ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RadioButton(
                            selected = (format == uiState.selectedFormat),
                            onClick = {
                                if (!uiState.isConverting) {
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

            // ステータス表示
            if (uiState.isConverting) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(uiState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // エラーメッセージ等の表示（赤字などで強調）
            if (uiState.statusMessage.isNotEmpty() && !uiState.isConverting) {
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
                TextButton(onClick = onClose) {
                    Text("閉じる")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { viewModel.convertPdf() },
                    enabled = uiState.targetUri != null && !uiState.isConverting
                ) {
                    Text(if (uiState.isComplete) "再変換" else "保存")
                }
            }
        }
    }
}
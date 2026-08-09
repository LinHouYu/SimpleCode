package com.linhouyu.simplecode

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

data class CodeItem(
    val id: String,
    val title: String,
    val code: String,
    val isQrcode: Boolean = false
)

val LightPrimary = Color(0xFF43A047)
val LightBackground = Color(0xFFF2F7F4)
val LightSurface = Color(0xFFFFFFFF)

val DarkPrimary = Color(0xFFC084FC)
val DarkBackground = Color(0xFF1A1625)
val DarkSurface = Color(0xFF2D2844)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }
            val colors = if (isDarkTheme) {
                darkColorScheme(primary = DarkPrimary, background = DarkBackground, surface = DarkSurface)
            } else {
                lightColorScheme(primary = LightPrimary, background = LightBackground, surface = LightSurface)
            }

            val view = LocalView.current
            val context = LocalContext.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (context as Activity).window
                    window.statusBarColor = colors.background.toArgb()
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
                }
            }

            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SimpleCodeApp(toggleTheme = { isDarkTheme = !isDarkTheme })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimpleCodeApp(toggleTheme: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("SimpleCodeDB", Context.MODE_PRIVATE)
    val gson = remember { Gson() }

    var items by remember {
        val json = prefs.getString("items", "[]")
        val type = object : TypeToken<List<CodeItem>>() {}.type
        mutableStateOf(gson.fromJson<List<CodeItem>>(json, type))
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var fullScreenIndex by remember { mutableStateOf<Int?>(null) }
    var itemToDelete by remember { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    val saveItems = { newItems: List<CodeItem> ->
        items = newItems
        prefs.edit { putString("items", gson.toJson(newItems)) }
    }

    // CSV 导出
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    val csvContent = buildString {
                        append("\uFEFF名称,代码,默认显示类型\n")
                        items.forEach { item ->
                            val typeStr = if (item.isQrcode) "qrcode" else "barcode"
                            append("\"${item.title.replace("\"", "\"\"")}\",\"${item.code.replace("\"", "\"\"")}\",${typeStr}\n")
                        }
                    }
                    os.write(csvContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "导出成功！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                    val lines = reader.readLines()
                    var added = 0
                    val newItems = mutableListOf<CodeItem>()

                    for (i in 1 until lines.size) {
                        val line = lines[i]
                        if (line.isBlank()) continue
                        val row = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                        if (row.size >= 2) {
                            val t = row[0].removePrefix("\"").removeSuffix("\"").replace("\"\"", "\"").trim()
                            val c = row[1].removePrefix("\"").removeSuffix("\"").replace("\"\"", "\"").trim()
                            val type = if (row.size > 2) row[2].removePrefix("\"").removeSuffix("\"").trim() else "barcode"
                            if (t.isNotEmpty() && c.isNotEmpty()) {
                                newItems.add(CodeItem((System.currentTimeMillis() + added).toString(), t, c, type == "qrcode"))
                                added++
                            }
                        }
                    }
                    if (added > 0) {
                        saveItems(items + newItems)
                        Toast.makeText(context, "成功导入 $added 个数据！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "未找到有效数据", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快扫库 Pro", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = toggleTheme) { Icon(Icons.Filled.Brightness4, contentDescription = "切换主题") }
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.padding(end = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 8.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = {
                        if (items.isEmpty()) Toast.makeText(context, "空库无法导出", Toast.LENGTH_SHORT).show()
                        else exportLauncher.launch("快扫管家备份.csv")
                    }) { Text("导出 CSV") }

                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/octet-stream"))
                    }) { Text("导入 CSV") }
                }
            }
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { item ->
                val index = items.indexOf(item)
                val isDragging = draggingIndex == index

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .zIndex(if (isDragging) 100f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                scaleX = 1.05f
                                scaleY = 1.05f
                                rotationZ = 3f
                                shadowElevation = 40f
                                alpha = 0.95f
                            }
                        }
                        .offset {
                            if (isDragging) IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                            else IntOffset.Zero
                        }
                        .pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = items.indexOf(item)
                                    dragOffset = Offset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount
                                    val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress

                                    val visibleItems = gridState.layoutInfo.visibleItemsInfo
                                    val currentInfo = visibleItems.firstOrNull { it.index == currentIndex }
                                    if (currentInfo != null) {
                                        val center = Offset(
                                            currentInfo.offset.x + currentInfo.size.width / 2f + dragOffset.x,
                                            currentInfo.offset.y + currentInfo.size.height / 2f + dragOffset.y
                                        )
                                        val target = visibleItems.firstOrNull {
                                            it.index != currentIndex &&
                                                    center.x >= it.offset.x && center.x <= it.offset.x + it.size.width &&
                                                    center.y >= it.offset.y && center.y <= it.offset.y + it.size.height
                                        }
                                        if (target != null) {
                                            val targetIndex = target.index

                                            val dx = target.offset.x - currentInfo.offset.x
                                            val dy = target.offset.y - currentInfo.offset.y
                                            dragOffset -= Offset(dx.toFloat(), dy.toFloat())

                                            val list = items.toMutableList()
                                            val temp = list[currentIndex]
                                            list[currentIndex] = list[targetIndex]
                                            list[targetIndex] = temp
                                            items = list
                                            draggingIndex = targetIndex
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingIndex = null
                                    dragOffset = Offset.Zero
                                    saveItems(items)
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    dragOffset = Offset.Zero
                                }
                            )
                        }
                        .pointerInput(item.id) {
                            detectTapGestures(
                                onTap = {
                                    if (draggingIndex == null) fullScreenIndex = items.indexOf(item)
                                }
                            )
                        }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(item.code, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))

                        Box(
                            modifier = Modifier.fillMaxWidth().height(80.dp).background(Color.White, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            val bitmap = remember(item.code) { generateBarcodeBitmap(item.code, BarcodeFormat.CODE_128, 400, 150) }
                            bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit) }
                        }
                    }
                }
            }
        }
        if (showAddDialog) {
            var inputTitle by remember { mutableStateOf("") }
            var inputCode by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("添加新卡", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = inputTitle, onValueChange = { inputTitle = it }, label = { Text("卡片名称") })
                        OutlinedTextField(value = inputCode, onValueChange = { inputCode = it }, label = { Text("条码编号/内容") })
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (inputTitle.isNotBlank() && inputCode.isNotBlank()) {
                            saveItems(items + CodeItem(System.currentTimeMillis().toString(), inputTitle, inputCode))
                            showAddDialog = false
                        }
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
            )
        }

        if (itemToDelete != null) {
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text("确认删除", fontWeight = FontWeight.Bold) },
                text = { Text("确定要删除这张卡片吗？该操作无法撤销。") },
                confirmButton = {
                    Button(
                        onClick = {
                            val updated = items.toMutableList()
                            updated.removeAt(itemToDelete!!)
                            saveItems(updated)
                            itemToDelete = null
                            // 如果删光了，退出全屏
                            if (updated.isEmpty()) fullScreenIndex = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) { Text("取消") }
                }
            )
        }

        if (fullScreenIndex != null) {
            val pagerState = rememberPagerState(initialPage = fullScreenIndex!!) { items.size }

            Dialog(onDismissRequest = { fullScreenIndex = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { fullScreenIndex = null }) { Text("✕", fontSize = 24.sp) }
                            Text("${pagerState.currentPage + 1} / ${items.size}", color = Color.Gray, fontWeight = FontWeight.Bold)
                        }

                        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                            val currentItem = items[page]
                            Column(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(currentItem.title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text(currentItem.code, fontSize = 16.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 20.dp))

                                val bitmap = remember(currentItem.code, currentItem.isQrcode) {
                                    if (currentItem.isQrcode) generateBarcodeBitmap(currentItem.code, BarcodeFormat.QR_CODE, 600, 600)
                                    else generateBarcodeBitmap(currentItem.code, BarcodeFormat.CODE_128, 600, 200)
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .background(Color.White, RoundedCornerShape(24.dp))
                                        .padding(24.dp)
                                        .pointerInput(currentItem.isQrcode) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    bitmap?.let { b -> saveBitmapToGallery(context, b, currentItem.title) }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    bitmap?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }

                                Text("💡 长按上方图片可直接保存", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Button(
                                        onClick = {
                                            val updated = items.toMutableList()
                                            updated[page] = currentItem.copy(isQrcode = !currentItem.isQrcode)
                                            saveItems(updated)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)
                                    ) { Text("切换为 ${if (currentItem.isQrcode) "条形码" else "二维码"} ") }

                                    Button(
                                        onClick = { itemToDelete = page },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x20EF4444), contentColor = Color(0xFFEF4444))
                                    ) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap, title: String) {
    try {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SimpleCode_${title}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SimpleCode")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            Toast.makeText(context, "已保存到相册！", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
    }
}

fun generateBarcodeBitmap(content: String, format: BarcodeFormat, width: Int, height: Int): Bitmap? {
    return try {
        val bitMatrix = MultiFormatWriter().encode(content, format, width, height)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        bitmap
    } catch (_: Exception) {
        null
    }
}
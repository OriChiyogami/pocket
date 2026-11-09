package io.pocket.host.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pocket.host.R
import io.pocket.host.core.InstalledApp
import io.pocket.host.core.iconPathOf
import io.pocket.host.ui.theme.PocketDeep
import io.pocket.host.ui.theme.PocketPurple
import io.pocket.host.ui.theme.PocketStroke
import io.pocket.host.ui.theme.PocketSub
import io.pocket.host.ui.theme.PocketSurface
import io.pocket.host.ui.theme.PocketText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF141414)
private val Paper = Color(0xFFF3F5F8)
private val CardWhite = Color(0xFFFFFFFF)
private val Muted = Color(0xFF8C93A3)
private val HairLine = Color(0xFFE7EAF0)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<InstalledApp>,
    folders: List<String>,
    onOpen: (InstalledApp, IntRect?) -> Unit,
    onLongPress: (InstalledApp) -> Unit,
    onOpenFolder: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
    onDraw: () -> Unit,
    onTransfer: () -> Unit,
    onClipboard: () -> Unit,
    onFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var listMode by remember { mutableStateOf(false) }
    var expandedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favorites by remember { mutableStateOf(readFavorites(context)) }
    var pickerOpen by remember { mutableStateOf(false) }

    val looseApps = apps.filter { it.folder.isNullOrEmpty() }
    val visibleApps = if (query.isBlank()) looseApps else apps.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    val visibleFolders = if (query.isBlank()) folders else folders.filter {
        it.contains(query, ignoreCase = true)
    }
    val favoriteApps = apps.filter { favorites.contains(it.id) }

    fun toggleFavorite(id: String) {
        favorites = if (favorites.contains(id)) favorites - id else favorites + id
        writeFavorites(context, favorites)
    }

    Box(modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize()) {
            Header(onAi = onAi, onDraw = onDraw)
            Spacer(Modifier.height(14.dp))
            SearchRow(
                query = query,
                onQuery = { query = it },
                onAdd = onAdd
            )
            Spacer(Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 128.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                if (query.isBlank()) {
                    item(span = { GridItemSpan(3) }) { HeroCard(onTransfer) }
                    item(span = { GridItemSpan(3) }) {
                        FavoritesSection(
                            apps = favoriteApps,
                            onOpen = { onOpen(it, null) },
                            onLongPress = onLongPress,
                            onManage = { pickerOpen = true }
                        )
                    }
                }

                item(span = { GridItemSpan(3) }) {
                    AppsHeader(
                        title = if (query.isBlank()) "小应用" else "搜索结果",
                        count = visibleApps.size + visibleFolders.size,
                        listMode = listMode,
                        onToggle = { listMode = it }
                    )
                }

                visibleFolders.forEach { folder ->
                    val folderApps = apps.filter { it.folder == folder }
                    val expanded = expandedFolders.contains(folder)
                    item(
                        key = "folder:$folder",
                        span = { GridItemSpan(if (expanded) 3 else 1) }
                    ) {
                        FolderCard(
                            name = folder,
                            apps = folderApps,
                            expanded = expanded,
                            onClick = {
                                expandedFolders = if (expanded) {
                                    expandedFolders - folder
                                } else {
                                    expandedFolders + folder
                                }
                            },
                            onLongClick = { onOpenFolder(folder) }
                        )
                    }
                    if (expanded) {
                        item(span = { GridItemSpan(3) }, key = "folder-apps:$folder") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                folderApps.forEach { app ->
                                    AppRow(
                                        app = app,
                                        favorite = favorites.contains(app.id),
                                        onClick = { onOpen(app, null) },
                                        onToggleFavorite = { toggleFavorite(app.id) },
                                        onLongClick = { onLongPress(app) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (listMode) {
                    visibleApps.forEach { app ->
                        item(span = { GridItemSpan(3) }, key = app.id) {
                            AppRow(
                                app = app,
                                favorite = favorites.contains(app.id),
                                onClick = { onOpen(app, null) },
                                onToggleFavorite = { toggleFavorite(app.id) },
                                onLongClick = { onLongPress(app) }
                            )
                        }
                    }
                } else {
                    items(visibleApps, key = { it.id }) { app ->
                        AppCard(
                            app = app,
                            favorite = favorites.contains(app.id),
                            onClick = { bounds -> onOpen(app, bounds) },
                            onLongClick = { onLongPress(app) },
                            onToggleFavorite = { toggleFavorite(app.id) }
                        )
                    }
                }

                if (visibleApps.isEmpty() && visibleFolders.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        EmptyState(
                            title = if (query.isBlank()) "还没有装任何小玩意" else "没有找到「$query」",
                            subtitle = if (query.isBlank()) "点右上角 + 导入 .pkt，或粘贴一段 HTML" else "换个关键词试试",
                            onAdd = onAdd
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        FavoritePickerDialog(
            apps = apps,
            favorites = favorites,
            onToggle = { toggleFavorite(it) },
            onDismiss = { pickerOpen = false }
        )
    }
}

@Composable
private fun Header(onAi: () -> Unit, onDraw: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 18.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "晚上好 · 同一 Wi-Fi",
                fontSize = 10.sp,
                color = Muted,
                letterSpacing = 1.1.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.pocket_wordmark),
                    contentDescription = "pocket",
                    modifier = Modifier.width(48.dp).height(15.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "口袋",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    color = PocketSub
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderChip("AI", onAi)
            HeaderChip("画", onDraw)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CardWhite)
                    .border(1.dp, HairLine, CircleShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.pocket_mark),
                    contentDescription = "口袋",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(CardWhite)
            .border(1.dp, HairLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(CardWhite)
                .border(1.dp, HairLine, RoundedCornerShape(15.dp))
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = PocketText),
                cursorBrush = SolidColor(PocketText),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索应用或文件夹", fontSize = 13.sp, color = Muted)
                    }
                    inner()
                }
            )
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEFEFEC))
                        .clickable { onQuery("") },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", fontSize = 15.sp, color = Muted, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Ink)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun HeroCard(onTransfer: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Ink)
            .clickable(onClick = onTransfer)
    ) {
        // 玻璃质感的光球：用径向渐变直接铺在卡片上，避免 blur 图层被卡片边缘裁出一条竖线
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF5B8DEF).copy(alpha = .5f), Color(0x005B8DEF)),
                    center = Offset(size.width * 0.86f, size.height * 0.06f),
                    radius = size.width * 0.46f
                ),
                radius = size.width * 0.46f,
                center = Offset(size.width * 0.86f, size.height * 0.06f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF52D6E8).copy(alpha = .38f), Color(0x0052D6E8)),
                    center = Offset(size.width * 0.78f, size.height * 0.72f),
                    radius = size.width * 0.4f
                ),
                radius = size.width * 0.4f,
                center = Offset(size.width * 0.78f, size.height * 0.72f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7C8CF8).copy(alpha = .3f), Color(0x007C8CF8)),
                    center = Offset(size.width * 0.12f, size.height * 1.02f),
                    radius = size.width * 0.34f
                ),
                radius = size.width * 0.34f,
                center = Offset(size.width * 0.12f, size.height * 1.02f)
            )
        }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "POCKET LINK",
                    fontSize = 9.sp,
                    letterSpacing = 2.2.sp,
                    color = Color.White.copy(alpha = .55f),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = .1f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("同一 Wi-Fi", fontSize = 9.sp, color = Color.White.copy(alpha = .8f))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "把文件和剪贴板，\n放进同一个口袋。",
                fontSize = 22.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "手机 ↔ 电脑 · 自动发现",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = .55f)
                )
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White)
                        .clickable(onClick = onTransfer)
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "打开互传",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Ink, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PocketText)
        Spacer(Modifier.weight(1f))
        Text(trailing, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    dark: Boolean,
    onClick: () -> Unit
) {
    val bg = if (dark) Ink else CardWhite
    val fg = if (dark) Color.White else Ink
    val sub = if (dark) Color.White.copy(alpha = .55f) else Muted
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(142.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .border(1.dp, if (dark) Color.Transparent else HairLine, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(15.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (dark) Color.White.copy(alpha = .1f) else Ink),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 9.5.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Icon(
            Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = sub,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun QuickRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Ink),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = PocketText)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 9.5.sp, color = Muted)
        }
        Icon(
            Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = Color(0xFF9A9A96),
            modifier = Modifier.size(15.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    name: String,
    apps: List<InstalledApp>,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.height(112.dp) else Modifier.aspectRatio(0.86f))
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(50.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        apps.getOrNull(0)?.let { MiniIcon(it, 23.dp) }
                        Spacer(Modifier.size(4.dp))
                        apps.getOrNull(2)?.let { MiniIcon(it, 23.dp) }
                    }
                    Spacer(Modifier.size(4.dp))
                    Column {
                        apps.getOrNull(1)?.let { MiniIcon(it, 23.dp) }
                        Spacer(Modifier.size(4.dp))
                        apps.getOrNull(3)?.let { MiniIcon(it, 23.dp) }
                    }
                }
                if (apps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ink),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("空", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF0F0EC)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = Muted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = name,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = PocketText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${apps.size} 个 · ${if (expanded) "收起" else "展开"}",
            fontSize = 9.sp,
            color = Muted,
            maxLines = 1
        )
    }
}

@Composable
private fun MiniIcon(app: InstalledApp, size: androidx.compose.ui.unit.Dp) {
    AppIcon(app = app, accent = Ink, size = size)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app: InstalledApp,
    favorite: Boolean,
    onClick: (IntRect?) -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var bounds by remember { mutableStateOf<IntRect?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                bounds = IntRect(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.right.toInt(),
                    rect.bottom.toInt()
                )
            }
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = { onClick(bounds) }, onLongClick = onLongClick)
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(app = app, accent = Ink, size = 42.dp)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (favorite) "取消常用" else "设为常用",
                    tint = if (favorite) Ink else Color(0xFFC9C9C3),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = app.name,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = PocketText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "v${app.version}",
            fontSize = 9.sp,
            color = Muted,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app = app, accent = Ink, size = 40.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PocketText)
            Spacer(Modifier.height(2.dp))
            Text("v${app.version}", fontSize = 9.5.sp, color = Muted)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (favorite) "取消常用" else "设为常用",
                tint = if (favorite) Ink else Color(0xFFC9C9C3),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.ArrowForward,
            contentDescription = null,
            tint = Color(0xFFB0B0AA),
            modifier = Modifier.size(14.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesSection(
    apps: List<InstalledApp>,
    onOpen: (InstalledApp) -> Unit,
    onLongPress: (InstalledApp) -> Unit,
    onManage: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("常用应用", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PocketText)
            Spacer(Modifier.weight(1f))
            Text(
                "管理",
                fontSize = 10.sp,
                color = Muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            apps.forEach { app ->
                FavoriteTile(
                    app = app,
                    onClick = { onOpen(app) },
                    onLongClick = { onLongPress(app) }
                )
            }
            AddFavoriteTile(onClick = onManage)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(
    app: InstalledApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .height(108.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(11.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(app = app, accent = Ink, size = 38.dp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            app.name,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "打开",
            fontSize = 8.5.sp,
            color = Muted
        )
    }
}

@Composable
private fun AddFavoriteTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .height(108.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(11.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFFF0F0EC)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加常用", tint = Ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("添加", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AppsHeader(
    title: String,
    count: Int,
    listMode: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PocketText)
        Spacer(Modifier.weight(1f))
        Text("$count 个", fontSize = 10.sp, color = Muted)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFEAEAE6))
                .padding(2.dp)
        ) {
            ModePill("卡片", !listMode) { onToggle(false) }
            ModePill("列表", listMode) { onToggle(true) }
        }
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Ink else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = if (active) Color.White else Muted,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun FavoritePickerDialog(
    apps: List<InstalledApp>,
    favorites: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(26.dp),
        title = { Text("常用应用", fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (apps.isEmpty()) {
                    Text("还没有安装应用", fontSize = 12.sp, color = Muted)
                }
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggle(app.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app = app, accent = Ink, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("v${app.version}", fontSize = 9.sp, color = Muted)
                        }
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (favorites.contains(app.id)) Ink else Color(0xFFC9C9C3),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Ink, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private const val HOME_PREFS = "pocket_home"
private val DEFAULT_FAVORITES = setOf(
    "com.pocket.transfer",
    "com.pocket.clipboard",
    "com.pocket.files"
)

private fun readFavorites(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
    return prefs.getStringSet("favorites", null) ?: DEFAULT_FAVORITES
}

private fun writeFavorites(context: Context, ids: Set<String>) {
    context.getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet("favorites", ids)
        .apply()
}

@Composable
fun AppIcon(
    app: InstalledApp,
    accent: Color,
    size: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current
    val path = remember(app.id, app.iconFile) { iconPathOf(context, app) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    val style = remember(app.id, app.name) { glyphStyleFor(app) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.3f))
            .background(Brush.linearGradient(listOf(style.start, style.end))),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            // 应用自带图标时直接显示（不再压成黑白）
            Image(
                bitmap = image,
                contentDescription = app.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // 没有图标就用图形化 MG 图标，不再放一个大字
            GlyphTile(style = style, size = size)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(24.dp))
            .clickable(onClick = onAdd)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Ink),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PocketText)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 10.5.sp,
            color = Muted,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun PocketBottomBar(
    onHome: () -> Unit,
    onFiles: () -> Unit,
    onAdd: () -> Unit,
    onClipboard: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.height(82.dp)) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(18.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .background(CardWhite)
                .border(1.dp, HairLine, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("首页", Icons.Filled.Home, true, onHome, Modifier.weight(1f))
            NavItem("文件", Icons.Filled.List, false, onFiles, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
            NavItem("剪贴板", Icons.Filled.Edit, false, onClipboard, Modifier.weight(1f))
            NavItem("设置", Icons.Filled.Settings, false, onSettings, Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(52.dp)
                .shadow(14.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Ink)
                .border(4.dp, CardWhite, CircleShape)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) Ink else Color(0xFF9A9A96)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 9.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

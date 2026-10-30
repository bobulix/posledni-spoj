@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package cz.poslednispoj

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.nestedScroll

private val Ink = Color(0xFF1C2620)
private val Ink2 = Color(0xFF56625A)
private val Paper = Color(0xFFEEF1EA)
private val Hair = Color(0xFFD5DBD0)
private val Accent = Color(0xFFC8321F)
private val Condensed = FontFamily.SansSerif

private val AppColors = lightColorScheme(
    primary = Ink, onPrimary = Color.White, surface = Color.White, onSurface = Ink,
    secondaryContainer = Paper, onSecondaryContainer = Ink, outline = Hair,
)

@Composable
fun App(vm: AppViewModel, holder: MapHolder, onLocate: () -> Unit) {
    MaterialTheme(colorScheme = AppColors) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
            val screenH = maxHeight
            MapHost(holder, Modifier.fillMaxSize())
            if (BuildConfig.MAPY_API_KEY.isNotBlank()) {
                MapyAttribution(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 8.dp, bottom = 6.dp))
            }

            TopPanel(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))

            val sheetOpen = vm.selected >= 0
            if (!sheetOpen || vm.sheetMini) {
                SideControls(
                    vm, holder, onLocate,
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                        .padding(end = 12.dp, bottom = if (sheetOpen) 100.dp else 24.dp)
                )
            }
            // detail se plynule vysune zespodu
            AnimatedVisibility(
                visible = sheetOpen,
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                DetailSheet(vm, screenH, Modifier.navigationBarsPadding().padding(12.dp))
            }

            val loading = vm.loading
            val error = vm.error
            if (loading != null || error != null) {
                Surface(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp, color = Color.White
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: loading ?: "", color = if (error != null) Accent else Ink2, fontSize = 15.sp)
                        if (error != null) {
                            Spacer(Modifier.size(12.dp))
                            Button(onClick = { vm.retry() }) { Text("Zkusit znovu") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- horní panel ----------------

@Composable
private fun TopPanel(vm: AppViewModel, modifier: Modifier) {
    val collapsed = vm.panelCollapsed
    val density = LocalDensity.current
    var pull by remember { mutableStateOf(0f) }
    // tažení dolů rozbalí, tažení nahoru sbalí (jako u spodního detailu)
    val dragMod = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { pull = 0f },
            onDragEnd = {
                val d = with(density) { pull.toDp() }
                if (d > 24.dp && vm.panelCollapsed) vm.togglePanel()
                else if (d < (-24).dp && !vm.panelCollapsed) vm.togglePanel()
                pull = 0f
            },
        ) { change, dy -> change.consume(); pull += dy }
    }
    Surface(
        modifier.widthIn(max = 560.dp).fillMaxWidth().then(dragMod),
        shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, color = Color.White
    ) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))) {
            if (collapsed) {
                // sbalený panel: jeden řádek „Praha · do 19:00 · dnes“, klepnutím nebo tažením dolů se rozbalí
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Rozbalit panel") { vm.togglePanel() }
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrailMark()
                    Spacer(Modifier.width(10.dp))
                    val dest = if (vm.dest == PRAHA) "Praha" else vm.dest.removeSuffix(TOWN_SUFFIX)
                    Text(dest, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text("  ·  do ${hm(vm.arriveBy)}  ·  ${vm.shortDay()}", fontSize = 15.sp, color = Ink2, maxLines = 1, softWrap = false)
                }
            } else {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrailMark()
                        Spacer(Modifier.width(10.dp))
                        Text("Poslední spoj", fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Ink)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DestinationField(vm, Modifier.weight(1.7f), showLabel = true)
                        TimeField(vm, Modifier.weight(1f), showLabel = true)
                    }
                    DayField(vm)
                    ModeChips(vm)
                    Legend(vm)
                    if (vm.status.isNotEmpty()) Text(vm.status, fontSize = 13.sp, color = Ink2)
                    Text("verze ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Ink2)
                }
            }
            // úchyt dole: klepnutím přepne, tažením se panel vytáhne / zasune
            Box(
                Modifier.fillMaxWidth().clickable(onClickLabel = if (collapsed) "Rozbalit panel" else "Sbalit panel") { vm.togglePanel() }
                    .padding(top = 6.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Hair)) }
        }
    }
}

@Composable
private fun TrailMark() {
    Column(Modifier.size(26.dp, 18.dp).clip(RoundedCornerShape(2.dp)).border(1.dp, Hair, RoundedCornerShape(2.dp))) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White))
        Box(Modifier.weight(1f).fillMaxWidth().background(Accent))
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White))
    }
}

@Composable
private fun RoundButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Paper).clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // šipky a křížek kreslíme čarou, aby byly dobře vidět
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height; val sw = 2.6.dp.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val path = androidx.compose.ui.graphics.Path()
            when (label) {
                "▴" -> { path.moveTo(w * 0.15f, h * 0.68f); path.lineTo(w * 0.5f, h * 0.3f); path.lineTo(w * 0.85f, h * 0.68f) }
                "▾" -> { path.moveTo(w * 0.15f, h * 0.32f); path.lineTo(w * 0.5f, h * 0.7f); path.lineTo(w * 0.85f, h * 0.32f) }
                else -> { path.moveTo(w * 0.2f, h * 0.2f); path.lineTo(w * 0.8f, h * 0.8f); path.moveTo(w * 0.8f, h * 0.2f); path.lineTo(w * 0.2f, h * 0.8f) }
            }
            drawPath(path, Ink, style = stroke)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink2, letterSpacing = 0.8.sp)
}

@Composable
private fun FieldBox(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Paper)
            .border(1.dp, Hair, RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun DestinationField(vm: AppViewModel, modifier: Modifier, showLabel: Boolean) {
    var value by remember(vm.dest) { mutableStateOf(TextFieldValue(vm.dest)) }
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var fieldBottom by remember { mutableStateOf(0) }
    val focus = LocalFocusManager.current
    // otevření detailu zastávky ukončí rozepsaný cíl (vrátí se platný cíl)
    androidx.compose.runtime.LaunchedEffect(vm.selected) { if (vm.selected >= 0) focus.clearFocus() }
    val scope = rememberCoroutineScope()
    Column(modifier) {
        if (showLabel) FieldLabel("Chci se vrátit do")
        Box {
            // stejný vzhled jako políčka času a dne
            BasicTextField(
                value = value,
                onValueChange = { value = it; open = it.text.trim().isNotEmpty() },
                singleLine = true,
                textStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    open = false; focus.clearFocus()
                    vm.suggest(value.text).firstOrNull()?.let { vm.setDestination(it) }
                }),
                modifier = Modifier.fillMaxWidth()
                    .onGloballyPositioned { c -> fieldBottom = (c.positionInRoot().y + c.size.height).toInt() }
                    .onFocusChanged { st ->
                        if (st.isFocused && !focused) {
                            vm.onDestinationFocus()
                            // klepnutí na zadaný název ho celý označí, takže lze rovnou psát nový
                            scope.launch {
                                kotlinx.coroutines.delay(60)
                                value = value.copy(selection = TextRange(0, value.text.length))
                            }
                        }
                        if (!st.isFocused) { open = false; value = TextFieldValue(vm.dest) }
                        focused = st.isFocused
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Paper)
                            .border(1.dp, if (focused) Ink else Hair, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                    ) { inner() }
                },
            )
            val q = value.text
            val suggestions = remember(q) { if (open) vm.suggest(q) else emptyList() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val view = androidx.compose.ui.platform.LocalView.current
            val imeBottom = WindowInsets.ime.getBottom(density)
            val maxMenu = with(density) {
                val free = view.rootView.height - imeBottom - fieldBottom - 16.dp.roundToPx()
                free.coerceAtLeast(56.dp.roundToPx()).toDp()
            }
            DropdownMenu(
                expanded = open && suggestions.isNotEmpty(),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false),
                modifier = Modifier.heightIn(max = maxMenu),
            ) {
                suggestions.forEach { s ->
                    DropdownMenuItem(text = { Text(s) }, onClick = {
                        open = false; vm.setDestination(s); value = TextFieldValue(s); focus.clearFocus()
                    })
                }
            }
        }
    }
}

@Composable
private fun TimeField(vm: AppViewModel, modifier: Modifier, showLabel: Boolean) {
    val ctx = LocalContext.current
    Column(modifier) {
        if (showLabel) FieldLabel("Nejpozději v")
        FieldBox(hm(vm.arriveBy)) {
            TimePickerDialog(ctx, { _, h, m -> vm.setArrival(h * 60 + m) }, vm.arriveBy / 60, vm.arriveBy % 60, true).show()
        }
    }
}

@Composable
private fun DayField(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Column {
        FieldLabel("Den")
        Box {
            FieldBox(vm.dayLabel(vm.dayKey)) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.meta?.days?.forEach { (d, _) ->
                    DropdownMenuItem(text = { Text(vm.dayLabel(d)) }, onClick = { open = false; vm.setDay(d) })
                }
            }
        }
    }
}

@Composable
private fun ModeChips(vm: AppViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Modes.TRAIN to "Vlak", Modes.BUS to "Autobus", Modes.TRAM to "Tramvaj", Modes.METRO to "Metro").forEach { (bit, label) ->
            FilterChip(
                selected = (vm.modeMask and bit) != 0,
                onClick = { vm.toggleMode(bit) },
                label = { Text((if ((vm.modeMask and bit) != 0) "✓ " else "") + label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Ink, selectedLabelColor = Color.White,
                    containerColor = Color.White, labelColor = Ink2,
                ),
            )
        }
    }
}

/** Legenda jako souvislý barevný pruh: vlevo „musím odjet brzo“, vpravo čas příjezdu. */
@Composable
private fun Legend(vm: AppViewModel) {
    val bins = BINS.indices.reversed().toList()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Poslední odjezd", fontSize = 12.sp, color = Ink2)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            bins.forEach { i -> Box(Modifier.weight(1f).fillMaxHeight().background(Color(BINS[i].bg))) }
        }
        Row(Modifier.fillMaxWidth()) {
            bins.forEach { i ->
                // pod pravým okrajem každého úseku čas, kdy úsek končí
                val end = vm.arriveBy - (if (i == 0) 0 else BINS[i - 1].hours) * 60
                Text(hm(maxOf(end, 0)), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink2,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------- boční ovládání ----------------

@Composable
private fun SideControls(vm: AppViewModel, holder: MapHolder, onLocate: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(10.dp), shadowElevation = 4.dp, color = Color.White) {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VerticalSlider(vm.opacity, { vm.updateOpacity(it) }, Modifier.size(32.dp, 130.dp))
                Text("${(vm.opacity * 100).toInt()} %", fontSize = 10.sp, color = Ink2, fontWeight = FontWeight.SemiBold)
            }
        }
        SquareButton("◎", "Moje poloha") { onLocate() }
        SquareButton("+", "Přiblížit") { holder.zoomBy(1.0) }
        SquareButton("−", "Oddálit") { holder.zoomBy(-1.0) }
    }
}

@Composable
private fun SquareButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(Color.White).clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 22.sp, color = Ink) }
}

/** Svislý posuvník průhlednosti (nahoře = plně viditelné). */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures { c, _ -> change(1f - (c.position.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> change(1f - (o.y / size.height).coerceIn(0f, 1f)) }
            }
    ) {
        val cx = size.width / 2
        val pad = 8.dp.toPx()
        val top = pad; val bottom = size.height - pad
        val y = bottom - (bottom - top) * value
        drawRoundRect(Hair, Offset(cx - 2.dp.toPx(), top), Size(4.dp.toPx(), bottom - top), CornerRadius(2.dp.toPx()))
        drawRoundRect(Ink, Offset(cx - 2.dp.toPx(), y), Size(4.dp.toPx(), bottom - y), CornerRadius(2.dp.toPx()))
        drawCircle(Ink, 8.dp.toPx(), Offset(cx, y))
    }
}

// ---------------- detail zastávky ----------------

@Composable
private fun DetailSheet(vm: AppViewModel, screenH: Dp, modifier: Modifier) {
    val m = vm.meta ?: return
    val p = vm.profile ?: return
    val trips = vm.trips ?: return
    val day = vm.day ?: return
    @Suppress("UNUSED_VARIABLE") val tick = vm.renderTick // přepočítá se při změně výběru
    val i = vm.selected
    if (i !in 0 until m.size) return
    val prof = p.prof[i]
    val k = vm.selK
    val entry = prof.getOrNull(k)
    val dep = entry?.dep ?: -1
    val missed = vm.nowMin >= 0 && dep in 0 until vm.nowMin
    val timeColor = if (dep < 0 || missed) Ink2 else Color(vm.binOf(dep).bg)
    val ctx = LocalContext.current

    // tři polohy: jen hlavička, polovina obrazovky, skoro celá obrazovka (nad sbaleným panelem)
    val miniH = 80.dp
    val halfH = (screenH * 0.5f).coerceAtLeast(miniH + 120.dp)
    val fullH = (screenH - 150.dp).coerceAtLeast(halfH)
    val levels = listOf(miniH, halfH, fullH)
    val level = if (vm.sheetMini) 0 else if (vm.sheetFull) 2 else 1
    // výška panelu: při tažení sleduje prst, po puštění plynule dojede do nejbližší polohy (bez skoku zpět)
    val anim = remember { Animatable(levels[level], Dp.VectorConverter) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(level, levels) {
        if (!dragging) anim.animateTo(levels[level], spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    }
    val h = anim.value
    val density = LocalDensity.current
    var startH by remember { mutableStateOf(miniH) }
    /** Po puštění prstu dojede do nejbližší polohy; stačí táhnout kus, rychlé švihnutí počítá směr. */
    fun settle(velocityDp: Float) {
        val cur = anim.value
        val target = when {
            cur > startH + 40.dp || velocityDp < -800f -> levels.firstOrNull { it > startH + 1.dp } ?: levels.last()
            cur < startH - 40.dp || velocityDp > 800f -> levels.lastOrNull { it < startH - 1.dp } ?: levels.first()
            else -> levels.minByOrNull { kotlin.math.abs((it - cur).value) }!!
        }
        vm.setSheetLevel(levels.indexOf(target))
        scope.launch {
            anim.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            dragging = false
        }
    }
    fun moveBy(dyPx: Float) {
        if (!dragging) { dragging = true; startH = anim.value }
        val nv = (anim.value - with(density) { dyPx.toDp() }).coerceIn(miniH, fullH)
        scope.launch { anim.snapTo(nv) }
    }
    val dragMod = Modifier.pointerInput(levels) {
        detectVerticalDragGestures(
            onDragStart = { dragging = true; startH = anim.value },
            onDragEnd = { settle(0f) },
            onDragCancel = { settle(0f) },
        ) { change, dy -> change.consume(); moveBy(dy) }
    }
    // Obsah detailu: táhne-li prst dolů a obsah je už posunutý úplně nahoru, posouvá se celé okno;
    // tažení nahoru nejdřív vytáhne okno na celou obrazovku, teprve pak posouvá obsah.
    val nested = remember(levels) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                android.util.Log.d("PSSHEET", "pre ${available.y} src=$source dragging=$dragging h=${anim.value}")
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return Offset.Zero
                // když okno už táhneme, pokračujeme oběma směry
                if (dragging || (available.y < 0 && anim.value < fullH - 1.dp)) {
                    moveBy(available.y); return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                android.util.Log.d("PSSHEET", "post c=${consumed.y} a=${available.y} src=$source")
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0) {
                    moveBy(available.y); return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!dragging) return androidx.compose.ui.unit.Velocity.Zero
                settle(with(density) { available.y.toDp().value })
                return available
            }
        }
    }
    val mini = h < miniH + 24.dp

    Surface(
        modifier.widthIn(max = 560.dp).fillMaxWidth().height(h),
        shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp, color = Color.White
    ) {
      Column {
        Column(
            dragMod.fillMaxWidth()
                .then(if (vm.sheetMini) Modifier.clickable(onClickLabel = "Rozbalit detail") { vm.expandSheet() } else Modifier)
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
        ) {
            // úchyt pro tažení
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Hair))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.names[i], fontFamily = Condensed, fontWeight = FontWeight.Bold,
                    fontSize = if (mini) 19.sp else 23.sp, color = Ink,
                    maxLines = if (mini) 1 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (mini) {
                    Text(hm(dep), fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = timeColor)
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton("×", "Zavřít") { vm.closeSheet() }
            }
        }
        if (mini) return@Column
        Column(
            Modifier.weight(1f).nestedScroll(nested).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
        ) {

            if (entry == null) {
                Text("Odsud se do cíle včas nedostaneš.", color = Ink2)
                return@Column
            }
            // čas odjezdu se šipkami na dřívější / pozdější spoj
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                NavButton("‹", "Dřívější spoj", enabled = k < prof.size - 1) { vm.select(i, k + 1) }
                Text(hm(dep), fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = timeColor,
                    modifier = Modifier.padding(horizontal = 8.dp))
                NavButton("›", "Pozdější spoj", enabled = k > 0) { vm.select(i, k - 1) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        (if (k == 0) "poslední možný odjezd odsud" else "$k. dřívější spoj") + if (missed) " · dnes už ujel" else "",
                        fontSize = 14.sp, color = Ink2
                    )
                    if (prof.size > 1) Text("dřívějších spojů: ${prof.size - 1}", fontSize = 12.sp, color = Ink2)
                    if (k > 0) Text("zpět na poslední", fontSize = 12.sp, color = Ink,
                        modifier = Modifier.clickable { vm.select(i, 0) }.padding(vertical = 2.dp))
                }
            }
            // úseky cesty
            var lastArr = -1
            vm.journey.forEachIndexed { li, leg ->
                when (leg) {
                    is Leg.WalkLeg -> LegRow(time = null) {
                        Text("Pěšky k zastávce ${m.names[leg.to]}", fontSize = 14.sp, color = Ink2)
                    }
                    is Leg.Ride -> {
                        lastArr = leg.arr
                        val ri = trips.route.getOrElse(leg.trip) { 0 }
                        val col = Color(m.routeColor[ri])
                        val fg = if (luminance(m.routeColor[ri]) > 0.55) Ink else Color.White
                        val open = vm.expanded.containsKey(li)
                        LegRow(time = leg.dep) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.toggleRoute(li) }.padding(vertical = 2.dp)
                            ) {
                                val lbl = trips.label.getOrElse(leg.trip) { "" }
                                Text(
                                    "${modeLabel(m.routeType[ri])} ${lbl.ifEmpty { m.routeName[ri] }}", color = fg, fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp, fontFamily = Condensed,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(col).padding(horizontal = 6.dp)
                                )
                                Text((if (lbl.isNotEmpty()) " ${m.routeName[ri]} ·" else "") + " směr ${trips.head.getOrElse(leg.trip) { "" }}", fontSize = 14.sp, color = Ink2,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                                Text(" ▾", fontSize = 13.sp, color = Ink2, modifier = Modifier.rotate(rot))
                            }
                            Text("${m.names[leg.from]} → ${m.names[leg.to]}  ·  příjezd ${hm(leg.arr)}", fontSize = 14.sp, color = Ink)
                            vm.alertsForRoute(ri).forEach { AlertBox(it) }
                            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                RouteList(vm, day, leg, col)
                            }
                        }
                    }
                }
            }
            val endNode = when (val l = vm.journey.lastOrNull()) { is Leg.Ride -> l.to; is Leg.WalkLeg -> l.to; null -> i }
            LegRow(time = lastArr, end = true) { Text("v cíli · ${m.names[endNode]}", fontSize = 14.sp, color = Ink) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = {
                    // ověření přesně tohoto spojení v IDOS (výluky a mimořádnosti)
                    openIdos(ctx, m.idosName[i], m.idosName[endNode], vm.dayKey, dep)
                }) { Text("Ověřit v IDOS") }
                OutlinedButton(onClick = { openInMapy(ctx, m.lat[i], m.lon[i], m.names[i]) }) { Text("Otevřít v Mapách") }
            }
            Text(
                "Zdroj: jízdní řády ${vm.sourcesOf(vm.journey).ifEmpty { m.sources.joinToString() }}, stav k ${vm.dataDate()}. " +
                    "Výluky nemusí být úplné, poslední spoj si ověř v IDOS. Mimořádnosti PID se načítají živě. " +
                    "Počítá se 2 min na přestup (4 min na nádraží) a pěší přechody do 450 m.",
                fontSize = 12.sp, color = Ink2, modifier = Modifier.padding(top = 10.dp)
            )
        }
      }
    }
}

/** Upozornění na mimořádnost; klepnutím se rozbalí podrobnosti a odkaz na pid.cz. */
@Composable
private fun AlertBox(a: Alert) {
    var open by remember(a.id) { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(
        Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF1E6))
            .clickable { open = !open }.padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text("⚠ " + a.header.ifEmpty { "Mimořádnost na lince" } + if (open) "" else "  ▾",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9A3A00))
        if (open) {
            if (a.description.isNotEmpty()) Text(a.description, fontSize = 13.sp, color = Ink, modifier = Modifier.padding(top = 3.dp))
            if (a.url.isNotEmpty()) Text("Více na pid.cz ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9A3A00),
                modifier = Modifier.padding(top = 4.dp).clickable {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) } catch (_: Exception) {}
                })
        }
    }
}

@Composable
private fun NavButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Paper).border(1.dp, Hair, CircleShape)
            .clickable(enabled = enabled, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 22.sp, color = if (enabled) Ink else Hair) }
}

@Composable
private fun LegRow(time: Int?, end: Boolean = false, content: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(if (end) Accent else Ink))
        Spacer(Modifier.width(10.dp))
        Text(if (time != null && time >= 0) hm(time) else "", fontFamily = Condensed, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, color = Ink, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun RouteList(vm: AppViewModel, day: Day, leg: Leg.Ride, color: Color) {
    val m = vm.meta ?: return
    val route = remember(leg) { Csa.tripRoute(day, leg.trip) }
    val (a, b) = remember(leg) { Csa.legIndices(route, leg) }
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Paper).padding(vertical = 6.dp)
    ) {
        route.forEachIndexed { k, s ->
            val on = k in a..b
            val edge = k == a || k == b
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(if (on) Color.White else Paper).padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Box(Modifier.width(3.dp).size(3.dp, 16.dp).background(if (edge) Accent else Color.Transparent))
                Spacer(Modifier.width(5.dp))
                Text(hm(if (k == a) s.dep else s.arr), fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = if (edge) Accent else if (on) Ink else Ink2, modifier = Modifier.width(42.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) color else Hair))
                Spacer(Modifier.width(6.dp))
                Text(m.names[s.node], fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Ink else Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun modeLabel(t: Int) = when (t) { 0 -> "Tram"; 1 -> "Metro"; 2 -> "Vlak"; 3 -> "Bus"; 4 -> "Přívoz"; 7 -> "Lanovka"; 11 -> "Trolejbus"; else -> "Linka" }

private fun luminance(argb: Int): Double {
    val r = (argb shr 16) and 255; val g = (argb shr 8) and 255; val b = argb and 255
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
}

/** Povinné označení podkladu Mapy.com: logo (odkaz na mapy.com) a copyright (odkaz na podmínky). */
@Composable
private fun MapyAttribution(modifier: Modifier) {
    val ctx = LocalContext.current
    val open = { url: String -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(ctx)
                .data(
                    if (remember { ctx.assets.list("")?.contains("mapy-logo.svg") == true }) "file:///android_asset/mapy-logo.svg"
                    else "https://api.mapy.com/img/api/logo.svg"
                )
                .decoderFactory(coil.decode.SvgDecoder.Factory())
                .build(),
            contentDescription = "Mapy.com",
            modifier = Modifier.size(width = 100.dp, height = 30.dp).clickable { open("https://mapy.com/") },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "© Seznam.cz a.s. a další", fontSize = 11.sp, color = Ink,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.8f))
                .clickable { open("https://api.mapy.com/copyright") }.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/** Otevře místo v aplikaci Mapy.com (Mapy.cz); když není nainstalovaná, otevře web Mapy.com. */
private fun openInMapy(ctx: android.content.Context, lat: Double, lon: Double, name: String) {
    val url = "https://mapy.com/fnc/v1/showmap?mapset=outdoor&center=$lon,$lat&zoom=16&marker=true"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        ctx.startActivity(Intent(intent).setPackage("cz.seznam.mapy"))
    } catch (e: android.content.ActivityNotFoundException) {
        try { ctx.startActivity(intent) } catch (_: Exception) {}
    }
}

/** Otevře IDOS s vyplněným spojením (odkud, kam, den a čas odjezdu). */
private fun openIdos(ctx: android.content.Context, from: String, to: String, dayKey: String, depMin: Int) {
    val date = if (dayKey.length == 8) "${dayKey.substring(6).toInt()}.${dayKey.substring(4, 6).toInt()}.${dayKey.substring(0, 4)}" else ""
    val time = if (depMin >= 0) "${(depMin / 60) % 24}:${(depMin % 60).toString().padStart(2, '0')}" else ""
    val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
    val url = "https://idos.cz/vlakyautobusymhdvse/spojeni/vysledky/?f=${enc(from)}&t=${enc(to)}&date=${enc(date)}&time=${enc(time)}"
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

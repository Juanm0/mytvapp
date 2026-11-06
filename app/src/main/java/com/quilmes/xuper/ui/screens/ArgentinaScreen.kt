package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ChannelLayoutStore
import com.quilmes.xuper.data.ChannelQueueStore
import com.quilmes.xuper.data.SavedChannelGroup
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.Button
import com.quilmes.xuper.ui.components.ChannelGridCard
import com.quilmes.xuper.ui.components.ChannelGridCardHeight
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperSurface
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GROUP_POPULAR = "popular"
private const val GROUP_SPORTS = "sports"

/** Un grupo de la pestaña (Canales populares / Deportes Argentina): principales y, por principal, sus secundarios. */
private data class ChannelGroupData(
    val id: String,
    val title: String,
    val principals: List<Title>,
    /** Clave = URL del stream del principal. */
    val secondaries: Map<String, List<Title>>,
)

private data class ArgentinaData(
    val popular: ChannelGroupData,
    val sports: ChannelGroupData,
    /** Todos los canales de argentina.m3u (populares primero, después alfabético). */
    val all: List<Title>,
    /** "Otros canales para ver": una categoría por group-title, juntando todas las listas de otros-canales. */
    val others: List<Category>,
)

private enum class MenuRole { PRINCIPAL, SECONDARY, OTHER }

/** Canal sobre el que se mantuvo apretado y desde dónde (para armar el menú). */
private data class MenuTarget(
    val title: Title,
    val role: MenuRole,
    val groupId: String?,
    val principal: Title? = null,
)

/**
 * Pestaña ARGENTINA: Canales populares y Deportes Argentina (principales + secundarios que se
 * personalizan manteniendo apretado un canal), grilla con TODOS los canales de argentina.m3u y,
 * abajo de todo, "Otros canales para ver" con el contenido de las listas de assets/otros-canales/.
 */
@Composable
internal fun ArgentinaScreen(sections: List<Section>, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ChannelLayoutStore(context) }
    var layoutVersion by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<ArgentinaData?>(null) }
    // Este cálculo recorre miles de entradas: va en segundo plano para no congelar la navegación.
    LaunchedEffect(sections, layoutVersion) {
        data = withContext(Dispatchers.Default) { buildArgentinaData(sections, store) }
    }

    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var pendingSecondary by remember { mutableStateOf<Title?>(null) }
    var pinnedPrincipalId by remember { mutableStateOf<String?>(null) }
    var focusTargetId by remember { mutableStateOf<String?>(null) }
    var movingGroupId by remember { mutableStateOf<String?>(null) }
    var movingPrincipals by remember { mutableStateOf<List<Title>?>(null) }
    var movingChannelId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = movingGroupId != null || pendingSecondary != null) {
        if (movingGroupId != null) {
            movingGroupId = null
            movingPrincipals = null
            movingChannelId = null
        } else pendingSecondary = null
    }

    val current = data
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.CenterStart) {
            Text("Cargando canales…", color = XuperTextSecondary, fontSize = 18.sp)
        }
        return
    }
    if (current.all.isEmpty() && current.others.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text("No hay canales argentinos disponibles", color = XuperTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "La lista argentina no devolvió canales. Revisá la conexión o la fuente M3U.",
                    color = XuperTextSecondary, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        return
    }

    fun groupOf(groupId: String): ChannelGroupData = if (groupId == GROUP_POPULAR) current.popular else current.sports

    /** Guarda el cambio sobre el grupo tal como se ve ahora (los principales por defecto pasan a ser "los del usuario"). */
    fun mutate(groupId: String, change: (SavedChannelGroup) -> SavedChannelGroup) {
        val group = groupOf(groupId)
        val base = SavedChannelGroup(
            principals = group.principals.map { it.streamUrl },
            secondaries = group.secondaries.mapValues { (_, list) -> list.map { it.streamUrl } },
        )
        store.save(groupId, change(base))
        layoutVersion += 1
    }

    /** Sube al principio de la pantalla (para que no haya que ir subiendo a mano) y enfoca el canal indicado. */
    fun jumpToTop(targetId: String?) {
        focusTargetId = targetId
        scope.launch { listState.scrollToItem(0) }
    }

    fun startReordering(groupId: String, principals: List<Title>, title: Title) {
        movingGroupId = groupId
        movingPrincipals = principals.toList()
        movingChannelId = title.id
        focusTargetId = title.id
    }

    fun moveDraft(delta: Int) {
        val list = movingPrincipals?.toMutableList() ?: return
        val index = list.indexOfFirst { it.id == movingChannelId }
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        movingPrincipals = list
        focusTargetId = item.id
    }

    fun finishReordering() {
        val groupId = movingGroupId ?: return
        val draft = movingPrincipals ?: return
        val currentGroup = groupOf(groupId)
        val selectedId = movingChannelId
        store.save(
            groupId,
            SavedChannelGroup(
                principals = draft.map { it.streamUrl },
                secondaries = currentGroup.secondaries.mapValues { (_, list) -> list.map { it.streamUrl } },
            ),
        )
        movingGroupId = null
        movingPrincipals = null
        movingChannelId = null
        layoutVersion += 1
        jumpToTop(selectedId)
    }

    fun handleReorderKey(event: KeyEvent): Boolean {
        if (movingGroupId == null || event.type != KeyEventType.KeyDown) return false
        when (event.key) {
            Key.DirectionLeft -> { moveDraft(-1); return true }
            Key.DirectionRight -> { moveDraft(1); return true }
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                // Ignora las repeticiones del OK que inició el mantener apretado.
                if (event.nativeKeyEvent.repeatCount == 0) finishReordering()
                return true
            }
            Key.Back, Key.Escape -> {
                movingGroupId = null
                movingPrincipals = null
                movingChannelId = null
                return true
            }
        }
        return false
    }

    fun defaultGroupFor(title: Title, from: String?): String =
        from ?: if (isArgentinaSport(normalizeName(title.name))) GROUP_SPORTS else GROUP_POPULAR

    fun addPrincipal(title: Title, groupId: String) {
        mutate(groupId) { it.withPrincipal(title.streamUrl) }
        jumpToTop(title.id)
    }

    fun startPickingPrincipal(title: Title) {
        pendingSecondary = title
        pinnedPrincipalId = null
        jumpToTop(current.popular.principals.firstOrNull()?.id ?: current.sports.principals.firstOrNull()?.id)
    }

    fun attachSecondary(principal: Title, groupId: String) {
        val pending = pendingSecondary ?: return
        pendingSecondary = null
        mutate(groupId) { it.withSecondary(principal.streamUrl, pending.streamUrl) }
        pinnedPrincipalId = principal.id
        jumpToTop(principal.id)
    }

    /** Toque/OK sobre un canal: en modo "elegir principal" no reproduce; si no, reproduce con [queue] como zapping. */
    fun playFrom(queue: List<Title>, title: Title) {
        if (pendingSecondary != null) {
            pendingSecondary = null
            return
        }
        ChannelQueueStore.set(queue)
        onPlay(title.id)
    }

    fun openMenu(target: MenuTarget) {
        if (pendingSecondary == null) menu = target
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Columnas de la grilla: se adaptan al ancho (TV o celular en horizontal).
        val gridGap = 14.dp
        val gridColumns = (((maxWidth - 96.dp) + gridGap) / (172.dp + gridGap)).toInt().coerceAtLeast(2)
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)) {
            item(key = "heading") {
                Text(
                    "ARGENTINA", color = XuperTextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
            }
            pendingSecondary?.let { pending ->
                item(key = "pick-banner") { PickBanner(title = pending, onCancel = { pendingSecondary = null }) }
            }
            for (sourceGroup in listOf(current.popular, current.sports)) {
                val group = if (movingGroupId == sourceGroup.id && movingPrincipals != null)
                    sourceGroup.copy(principals = movingPrincipals!!) else sourceGroup
                if (group.principals.isEmpty()) continue
                item(key = "group-${group.id}") {
                    ChannelGroupSection(
                        group = group,
                        pinnedPrincipalId = pinnedPrincipalId,
                        focusTargetId = focusTargetId,
                        onFocusTargetConsumed = { focusTargetId = null },
                        onPrincipalClick = { principal ->
                            if (pendingSecondary != null) attachSecondary(principal, group.id)
                            else playFrom(group.principals, principal)
                        },
                        onPrincipalLong = { principal -> startReordering(group.id, group.principals, principal) },
                        onReorderKey = ::handleReorderKey,
                        movingChannelId = if (movingGroupId == group.id) movingChannelId else null,
                        onSecondaryClick = { principal, secondary ->
                            val groupQueue = listOf(principal) + group.secondaries[principal.streamUrl].orEmpty()
                            playFrom(groupQueue, secondary)
                        },
                        onSecondaryLong = { principal, secondary ->
                            openMenu(MenuTarget(secondary, MenuRole.SECONDARY, group.id, principal))
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            if (current.popular.principals.isEmpty() && current.sports.principals.isEmpty()) {
                item(key = "empty-principals") {
                    Text(
                        "No hay canales principales. Mantené apretado un canal de la lista de abajo y elegí \"Añadir principal\".",
                        color = XuperTextSecondary, fontSize = 16.sp, modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 12.dp),
                    )
                }
            }
            channelGrid(
                keyPrefix = "all", heading = "Todos los canales (${current.all.size})", titles = current.all,
                columns = gridColumns, gap = gridGap, badge = "Argentina",
                onClick = { title -> playFrom(current.all, title) },
                onLongClick = { title -> openMenu(MenuTarget(title, MenuRole.OTHER, null)) },
            )
            if (current.others.isNotEmpty()) {
                item(key = "others-heading") {
                    Text(
                        "Otros canales para ver", color = XuperTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 48.dp, top = 36.dp),
                    )
                }
                for (category in current.others) {
                    channelGrid(
                        keyPrefix = category.id, heading = "${category.title} (${category.items.size})", titles = category.items,
                        columns = gridColumns, gap = gridGap, badge = "En vivo",
                        onClick = { title -> playFrom(category.items, title) },
                        onLongClick = { title -> openMenu(MenuTarget(title, MenuRole.OTHER, null)) },
                    )
                }
            }
        }
    }

    menu?.let { target ->
        val title = target.title
        val options = buildList<Pair<String, () -> Unit>> {
            when (target.role) {
                MenuRole.OTHER -> {
                    add("Añadir principal" to { addPrincipal(title, defaultGroupFor(title, target.groupId)) })
                    add("Añadir secundario" to { startPickingPrincipal(title) })
                }
                MenuRole.PRINCIPAL -> {
                    val groupId = target.groupId ?: GROUP_POPULAR
                    add("Pasar a secundario" to { startPickingPrincipal(title) })
                    add("Quitar de principales" to { mutate(groupId) { it.withoutPrincipal(title.streamUrl) } })
                    add("Restablecer este grupo" to {
                        store.reset(groupId)
                        layoutVersion += 1
                    })
                }
                MenuRole.SECONDARY -> {
                    val groupId = target.groupId ?: GROUP_POPULAR
                    add("Hacer principal" to { addPrincipal(title, groupId) })
                    add("Quitar de secundarios" to { mutate(groupId) { it.withoutSecondary(title.streamUrl) } })
                }
            }
        }
        ChannelMenuDialog(title = title.name, options = options, onDismiss = { menu = null })
    }
}

/** Fila de principales de un grupo + submenú de secundarios del principal en foco (o fijado con "Ver secundarios"). */
@Composable
private fun ChannelGroupSection(
    group: ChannelGroupData,
    pinnedPrincipalId: String?,
    focusTargetId: String?,
    onFocusTargetConsumed: () -> Unit,
    onPrincipalClick: (Title) -> Unit,
    onPrincipalLong: (Title) -> Unit,
    onReorderKey: (KeyEvent) -> Boolean,
    movingChannelId: String?,
    onSecondaryClick: (Title, Title) -> Unit,
    onSecondaryLong: (Title, Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    var groupHasFocus by remember { mutableStateOf(false) }
    var focusedPrincipalId by remember { mutableStateOf<String?>(null) }
    val rowState = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current

    // Un canal recién agregado/movido queda a la vista y, con mando, enfocado.
    LaunchedEffect(focusTargetId, group.principals) {
        val id = focusTargetId ?: return@LaunchedEffect
        val index = group.principals.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        rowState.scrollToItem(index)
        if (inputMode.inputMode == InputMode.Keyboard) {
            var tries = 0
            while (tries < 12) {
                delay(150)
                if (runCatching { requester.requestFocus() }.isSuccess) break
                tries += 1
            }
        }
        onFocusTargetConsumed()
    }

    Column(modifier = modifier
        .onFocusChanged { groupHasFocus = it.hasFocus }
        .onPreviewKeyEvent(onReorderKey)
    ) {
        if (movingChannelId != null) {
            Text(
                text = "ORDENANDO · ◀ ▶ para mover · OK para soltar · ATRÁS para cancelar",
                color = XuperAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
            )
        }
        Text(
            text = group.title, color = XuperTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(group.principals, key = { it.id }) { principal ->
                val focusModifier = if (principal.id == focusTargetId) Modifier.focusRequester(requester) else Modifier
                PosterCard(
                    title = principal,
                    onClick = { onPrincipalClick(principal) },
                    onLongClick = { onPrincipalLong(principal) },
                    modifier = focusModifier.onFocusChanged { if (it.hasFocus) focusedPrincipalId = principal.id },
                )
            }
        }
        val shownId = if (groupHasFocus && focusedPrincipalId != null) focusedPrincipalId else pinnedPrincipalId
        val shown = group.principals.firstOrNull { it.id == shownId }
        val secondaries = shown?.let { group.secondaries[it.streamUrl] }.orEmpty()
        if (shown != null && secondaries.isNotEmpty()) {
            SecondaryPanel(
                principal = shown,
                secondaries = secondaries,
                onClick = { secondary -> onSecondaryClick(shown, secondary) },
                onLong = { secondary -> onSecondaryLong(shown, secondary) },
            )
        }
    }
}

@Composable
private fun SecondaryPanel(
    principal: Title,
    secondaries: List<Title>,
    onClick: (Title) -> Unit,
    onLong: (Title) -> Unit,
) {
    var selectedId by remember(principal.id) { mutableStateOf(secondaries.firstOrNull()?.id) }
    val selected = secondaries.firstOrNull { it.id == selectedId } ?: secondaries.firstOrNull()
    Row(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(Color(0xFF151B26), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Secundarios de ${principal.name}", color = XuperTextSecondary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            secondaries.forEach { secondary ->
                Button(
                    onClick = { onClick(secondary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .onFocusChanged { if (it.hasFocus) selectedId = secondary.id },
                ) {
                    Text(
                        text = secondary.name,
                        color = XuperTextPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SELECCIONADO", color = XuperTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                PosterCard(
                    title = selected,
                    onClick = { onClick(selected) },
                    onLongClick = { onLong(selected) },
                )
            }
        }
    }
}

@Composable
private fun PickBanner(title: Title, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 48.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(XuperAccent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Elegí el canal principal al que querés añadir «${title.name}» como secundario",
            color = XuperTextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f),
        )
        Button(onClick = onCancel) { Text("Cancelar") }
    }
}

@Composable
private fun ChannelMenuDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(XuperSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title, color = XuperTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            options.forEachIndexed { index, option ->
                Button(
                    onClick = {
                        onDismiss()
                        option.second()
                    },
                    modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                ) { Text(option.first) }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}

/** Título + grilla de tarjetas (todas las filas completas, la última se rellena para no estirar tarjetas). */
private fun LazyListScope.channelGrid(
    keyPrefix: String,
    heading: String,
    titles: List<Title>,
    columns: Int,
    gap: Dp,
    badge: String,
    onClick: (Title) -> Unit,
    onLongClick: (Title) -> Unit,
) {
    if (titles.isEmpty()) return
    item(key = "$keyPrefix-title") {
        Text(
            heading, color = XuperTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 10.dp),
        )
    }
    val chunks = titles.chunked(columns)
    items(chunks.size, key = { "$keyPrefix-row-$it" }) { index ->
        val rowItems = chunks[index]
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp).padding(bottom = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            rowItems.forEach { title ->
                ChannelGridCard(
                    title = title,
                    onClick = { onClick(title) },
                    onLongClick = { onLongClick(title) },
                    modifier = Modifier.weight(1f),
                    badge = badge,
                )
            }
            repeat(columns - rowItems.size) { Box(Modifier.weight(1f).height(ChannelGridCardHeight)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Armado de datos (corre en segundo plano)
// ---------------------------------------------------------------------------------------------

private fun buildArgentinaData(sections: List<Section>, store: ChannelLayoutStore): ArgentinaData {
    val argentina = sections.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
        ?.categories.orEmpty().flatMap { it.items }.filter { it.isLive }
    // Se normaliza el nombre UNA sola vez por canal (hacerlo dentro del ordenamiento trababa la pestaña).
    val keyed = argentina.distinctBy { it.streamUrl }.map { it to normalizeName(it.name) }
    val ordered = keyed.sortedWith(compareBy<Pair<Title, String>>({ argentinaPriority(it.second) }, { it.second }))
    val all = ordered.map { it.first }
    val sportsDefault = ordered.filter { isArgentinaSport(it.second) }.map { it.first }
    val sportUrls = sportsDefault.map { it.streamUrl }.toSet()
    val channels = all.filter { it.streamUrl !in sportUrls }
    // Por defecto, populares = los de siempre (TN, La Nación+, Crónica, El Trece, Telefe, América, C5N, El Nueve, TV Pública).
    val popularDefault = ordered.filter { argentinaPriority(it.second) < 9 && !isArgentinaSport(it.second) }
        .map { it.first }
        .ifEmpty { channels.take(12) }

    val others = buildOthers(sections)
    val lookup = HashMap<String, Title>()
    for (title in all + others.flatMap { it.items }) lookup.putIfAbsent(title.streamUrl, title)

    return ArgentinaData(
        popular = resolveGroup(GROUP_POPULAR, "Canales populares", popularDefault, lookup, store),
        sports = resolveGroup(GROUP_SPORTS, "Deportes Argentina", sportsDefault, lookup, store),
        all = all,
        others = others,
    )
}

private fun resolveGroup(
    id: String,
    title: String,
    default: List<Title>,
    lookup: Map<String, Title>,
    store: ChannelLayoutStore,
): ChannelGroupData {
    val saved = store.load(id) ?: return ChannelGroupData(id, title, default, emptyMap())
    val principals = saved.principals.mapNotNull { lookup[it] }.distinctBy { it.streamUrl }
    val principalUrls = principals.map { it.streamUrl }.toSet()
    val secondaries = HashMap<String, List<Title>>()
    for ((principalUrl, urls) in saved.secondaries) {
        if (principalUrl !in principalUrls) continue
        val resolved = urls.mapNotNull { lookup[it] }.distinctBy { it.streamUrl }
        if (resolved.isNotEmpty()) secondaries[principalUrl] = resolved
    }
    return ChannelGroupData(id, title, principals, secondaries)
}

/** Canales en vivo de las listas de assets/otros-canales/, agrupados por group-title (sin repetir URLs ni Gran Hermano). */
private fun buildOthers(sections: List<Section>): List<Category> {
    val groups = LinkedHashMap<String, MutableList<Title>>()
    val seen = HashSet<String>()
    for (section in sections) {
        if (!section.liveOthers) continue
        for (category in section.categories) {
            val groupName = category.title
            val groupNorm = normalizeName(groupName)
            for (title in category.items) {
                if (!title.isLive || title.streamUrl.isBlank()) continue
                if (AdultContentSettings.isHiddenAdult(title)) continue
                // Gran Hermano ya no transmite: se descarta de todas las listas.
                if ("gran hermano" in groupNorm || "gran hermano" in normalizeName(title.name)) continue
                if (!seen.add(title.streamUrl)) continue
                groups.getOrPut(groupName) { mutableListOf() }.add(title)
            }
        }
    }
    val orderedNames = groups.keys.sortedWith(
        compareBy<String>({ !it.equals("Argentina", ignoreCase = true) }, { normalizeName(it) })
    )
    return orderedNames.map { name -> Category("other-$name", name, groups.getValue(name)) }
}

private val MARKS_REGEX = Regex("\\p{Mn}+")

internal fun normalizeName(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS_REGEX, "")

internal fun argentinaPriority(n: String): Int {
    return when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> 0
        "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
        "cronica" in n -> 2
        "el trece" in n || "eltrece" in n -> 3
        "telefe" in n -> 4
        n == "america" || n.startsWith("america ") || "america tv" in n -> 5
        "c5n" in n -> 6
        "canal 9" in n || n == "el nueve" || n.startsWith("canal nueve") -> 7
        "tv publica" in n || "television publica" in n -> 8
        else -> 9
    }
}

internal fun isArgentinaSport(n: String): Boolean {
    return listOf("espn", "fox sports", "tyc sports", "t y c sports", "directv sports", "dsports", "tnt sports", "deportv", "goltv", "sport tv").any { it in n }
}

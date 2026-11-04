package com.heytune.app.ui.screens.library

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachReversed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.heytune.app.LocalMenuState
import com.heytune.app.LocalPlayerAwareWindowInsets
import com.heytune.app.LocalPlayerConnection
import com.heytune.app.LocalSnackbarHostState
import com.heytune.app.MainActivity
import com.heytune.app.R
import com.heytune.app.constants.CONTENT_TYPE_HEADER
import com.heytune.app.constants.CONTENT_TYPE_SONG
import com.heytune.app.constants.ListThumbnailSize
import com.heytune.app.constants.SongFilter
import com.heytune.app.constants.SongFilterKey
import com.heytune.app.constants.SongSortDescendingKey
import com.heytune.app.constants.SongSortType
import com.heytune.app.constants.SongSortTypeKey
import com.heytune.app.constants.SwipeToQueueKey
import com.heytune.app.models.toMediaMetadata
import com.heytune.app.playback.queues.ListQueue
import com.heytune.app.ui.component.ChipsRow
import com.heytune.app.ui.component.EmptyPlaceholder
import com.heytune.app.ui.component.FloatingFooter
import com.heytune.app.ui.component.LazyColumnScrollbar
import com.heytune.app.ui.component.ScrollToTopManager
import com.heytune.app.ui.component.SelectHeader
import com.heytune.app.ui.component.SortHeader
import com.heytune.app.ui.component.items.SongListItem
import com.heytune.app.ui.menu.ActionDropdown
import com.heytune.app.ui.menu.DropdownItem
import com.heytune.app.ui.utils.MEDIA_PERMISSION_LEVEL
import com.heytune.app.utils.rememberEnumPreference
import com.heytune.app.utils.rememberPreference
import com.heytune.app.viewmodels.LibrarySongsViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibrarySongsScreen(
    navController: NavController,
    viewModel: LibrarySongsViewModel = hiltViewModel(),
    libraryFilterContent: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    var filter by rememberEnumPreference(SongFilterKey, SongFilter.LIBRARY)
    val (sortType, onSortTypeChange) = rememberEnumPreference(SongSortTypeKey, SongSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val songs by viewModel.allSongs.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val lazyListState = rememberLazyListState()

    // multiselect
    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }
    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    // O(1) lookup for scroll-time isSelected checks. Building the set is O(M)
    // once per selection change instead of O(M) per visible row per compose.
    // SnapshotStateList.contains() is a linear scan, so with "select all" on a
    // large library every new row composed while scrolling paid O(M).
    val selectionSet by remember {
        derivedStateOf { selection.toHashSet() }
    }

    LaunchedEffect(songs) {
        val songIds = songs?.let { HashSet<String>(it.size).also { set -> it.forEach { s -> set.add(s.id) } } }
        selection.fastForEachReversed { songId ->
            if (songIds == null || !songIds.contains(songId)) {
                selection.remove(songId)
            }
        }
    }

    val filterContent = @Composable {
        ChipsRow(
            chips = listOf(
                SongFilter.LIKED to stringResource(R.string.filter_liked),
                SongFilter.LIBRARY to stringResource(R.string.filter_library),
                SongFilter.DOWNLOADED to stringResource(R.string.filter_downloaded)
            ),
            currentValue = filter,
            onValueUpdate = {
                filter = it
            },
        )
    }

    val headerContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        SongSortType.CREATE_DATE -> if (filter == SongFilter.LIKED) R.string.sort_by_like_date else R.string.sort_by_create_date
                        SongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                        SongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                        SongSortType.NAME -> R.string.sort_by_name
                        SongSortType.ARTIST -> R.string.sort_by_artist
                        SongSortType.PLAY_COUNT -> R.string.sort_by_play_count
                    }
                }
            )

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                songs?.let { songs ->
                    Text(
                        text = pluralStringResource(R.plurals.n_song, songs.size, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(4.dp))
                    ActionDropdown(
                        actions = listOf(
                            DropdownItem(
                                title = stringResource(R.string.library_filter),
                                leadingIcon = { Icon(Icons.Rounded.FilterAlt, null) },
                                action = {},
                                secondaryDropdown =
                                    listOf(
                                        DropdownItem(
                                            title = stringResource(R.string.filter_liked),
                                            leadingIcon = null,
                                            action = { filter = SongFilter.LIKED }
                                        ),
                                        DropdownItem(
                                            title = stringResource(R.string.filter_library),
                                            leadingIcon = null,
                                            action = { filter = SongFilter.LIBRARY }
                                        ),
                                        DropdownItem(
                                            title = stringResource(R.string.filter_downloaded),
                                            leadingIcon = null,
                                            action = { filter = SongFilter.DOWNLOADED }
                                        ),
                                    )
                            ),
                            DropdownItem(
                                title = stringResource(R.string.queue_all_songs),
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                                action = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaMetadata() },
                                            startShuffled = false,
                                        )
                                    )
                                }
                            ),
                            DropdownItem(
                                title = stringResource(R.string.shuffle),
                                leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                                action = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = context.getString(R.string.queue_all_songs),
                                            items = songs.map { it.toMediaMetadata() },
                                            startShuffled = true,
                                        )
                                    )
                                }
                            ),
                        ),
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            item(
                key = "filter",
                contentType = CONTENT_TYPE_HEADER
            ) {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.background)
                ) {
                    var showStoragePerm by remember {
                        mutableStateOf(context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED)
                    }
                    if (showStoragePerm) {
                        TextButton(
                            onClick = {
                                showStoragePerm =
                                    false // allow user to hide error when clicked. This also makes the code a lot nicer too...
                                (context as MainActivity).permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = stringResource(R.string.missing_media_permission_warning),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    libraryFilterContent?.let { it() } ?: filterContent()
                }
            }

            item(
                key = "header",
                contentType = CONTENT_TYPE_HEADER
            ) {
                headerContent()
            }

            songs?.let { songs ->
                if (songs.isEmpty()) {
                    item {
                        EmptyPlaceholder(
                            icon = Icons.Rounded.MusicNote,
                            text = stringResource(R.string.library_song_empty),
                        )
                    }
                }
                val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                // Snapshot of the active id so inactive rows keep referential-stable
                // params (see isPlayingActive below) and skip recomposition.
                val activeId = mediaMetadata?.id
                itemsIndexed(
                    items = songs,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> CONTENT_TYPE_SONG }
                ) { index, song ->
                    val isActive = song.song.id == activeId
                    // Gate isPlaying behind isActive: previously every row received
                    // the raw isPlaying flag, so each play/pause toggle changed the
                    // param of ALL rows and recomposed the whole visible list
                    // (rebuilding MediaItems, re-joining subtitles, etc.).
                    val isPlayingActive = isPlaying && isActive
                    SongListItem(
                        song = song,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = isActive,
                        isPlaying = isPlayingActive,
                        inSelectMode = inSelectMode,
                        isSelected = selectionSet.contains(song.id),
                        onSelectedChange = {
                            inSelectMode = true
                            if (it) {
                                selection.add(song.id)
                            } else {
                                selection.remove(song.id)
                            }
                        },
                        swipeEnabled = swipeEnabled,

                        thumbnailSize = thumbnailSize,
                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.queue_all_songs),
                                    items = songs.map { it.toMediaMetadata() },
                                    startIndex = index
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        FloatingFooter(visible = inSelectMode && songs != null) {
            val currentSongs = songs
            if (currentSongs != null) {
                // Index once: the old selection.mapNotNull { s.find { ... } } was
                // O(selected * total) on every recomposition while in select mode.
                val songById = remember(currentSongs) { currentSongs.associateBy { it.id } }
                SelectHeader(
                    navController = navController,
                    selectedItems = selection.mapNotNull { songId ->
                        songById[songId]
                    }.map { it.toMediaMetadata() },
                    totalItemCount = currentSongs.size,
                    onSelectAll = {
                        selection.clear()
                        selection.addAll(currentSongs.map { it.id })
                    },
                    onDeselectAll = { selection.clear() },
                    menuState = menuState,
                    onDismiss = onExitSelectionMode
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}

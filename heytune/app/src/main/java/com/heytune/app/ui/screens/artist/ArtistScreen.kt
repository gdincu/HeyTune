package com.heytune.app.ui.screens.artist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.heytune.app.LocalDatabase
import com.heytune.app.LocalMenuState
import com.heytune.app.LocalPlayerAwareWindowInsets
import com.heytune.app.LocalPlayerConnection
import com.heytune.app.LocalSnackbarHostState
import com.heytune.app.R
import com.heytune.app.constants.AppBarHeight
import com.heytune.app.constants.ListThumbnailSize
import com.heytune.app.constants.SwipeToQueueKey
import com.heytune.app.constants.TopBarInsets
import com.heytune.app.models.toMediaMetadata
import com.heytune.app.playback.queues.ListQueue
import com.heytune.app.ui.component.AutoResizeText
import com.heytune.app.ui.component.FontSizeRange
import com.heytune.app.ui.component.LazyColumnScrollbar
import com.heytune.app.ui.component.NavigationTitle
import com.heytune.app.ui.component.button.IconButton
import com.heytune.app.ui.component.items.AlbumGridItem
import com.heytune.app.ui.component.items.SongListItem
import com.heytune.app.ui.menu.AlbumMenu
import com.heytune.app.ui.utils.backToMain
import com.heytune.app.ui.utils.fadingEdge
import com.heytune.app.utils.rememberPreference
import com.heytune.app.viewmodels.ArtistViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val libraryArtist by viewModel.libraryArtist.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current

    val transparentAppBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0
        }
    }

    val artistHead = @Composable {
        if (libraryArtist != null) {
            val thumbnail = libraryArtist?.artist?.thumbnailUrl
            val artistName = libraryArtist?.artist?.name

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (thumbnail != null) Modifier.aspectRatio(4f / 3) else Modifier
                        )
                ) {
                    if (thumbnail != null) {
                        AsyncImage(
                            model = thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fadingEdge(
                                    top = WindowInsets.systemBars
                                        .asPaddingValues()
                                        .calculateTopPadding() + AppBarHeight,
                                    bottom = 64.dp
                                )
                        )
                    }
                    AutoResizeText(
                        text = artistName
                            ?: "Unknown",
                        style = MaterialTheme.typography.displayLarge,
                        fontSizeRange = FontSizeRange(32.sp, 58.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 48.dp)
                            .then(
                                if (thumbnail == null) {
                                    Modifier.padding(
                                        top = WindowInsets.systemBars
                                            .asPaddingValues()
                                            .calculateTopPadding() + AppBarHeight
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .add(
                    WindowInsets(
                        top = -WindowInsets.systemBars.asPaddingValues()
                            .calculateTopPadding() - AppBarHeight
                    )
                )
                .asPaddingValues()
        ) {
            item(key = "header") {
                artistHead()
            }


            if (librarySongs.isNotEmpty()) {
                item {
                    NavigationTitle(
                        title = stringResource(R.string.songs),
                        onClick = {
                            navController.navigate("artist/${viewModel.artistId}/songs")
                        }
                    )
                }

                val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                itemsIndexed(
                    items = librarySongs,
                    key = { _, item -> item.hashCode() }
                ) { index, song ->
                    SongListItem(
                        song = song,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = song.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        inSelectMode = false,
                        isSelected = false,
                        onSelectedChange = { },
                        swipeEnabled = swipeEnabled,

                        thumbnailSize = thumbnailSize,
                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = "Library: ${libraryArtist?.artist?.name}",
                                    items = librarySongs.map { it.toMediaMetadata() },
                                    startIndex = index
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )

                }
            }

            if (libraryAlbums.isNotEmpty()) {
                item {
                    NavigationTitle(
                        title = stringResource(R.string.albums),
                        onClick = {
                            navController.navigate("artist/${viewModel.artistId}/albums")
                        }
                    )
                }

                item {
                    LazyRow {
                        items(
                            items = libraryAlbums,
                            key = { it.id }
                        ) { album ->
                            AlbumGridItem(
                                album = album,
                                isActive = album.id == mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                coroutineScope = coroutineScope,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            navController.navigate("album/${album.id}")
                                        },
                                        onLongClick = {
                                            menuState.show {
                                                AlbumMenu(
                                                    originalAlbum = album,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss
                                                )
                                            }
                                        }
                                    )
                                    .animateItem()
                            )
                        }
                    }
                }

            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        TopAppBar(
            title = { if (!transparentAppBar) Text(libraryArtist?.artist?.name.orEmpty()) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        database.transaction {
                            val artist = libraryArtist?.artist
                            if (artist != null) {
                                update(artist.toggleLike())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (libraryArtist?.artist?.bookmarkedAt != null) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        tint = if (libraryArtist?.artist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }
            },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
            colors = if (transparentAppBar) {
                TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            } else {
                TopAppBarDefaults.topAppBarColors()
            }
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}
/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.heytune.app.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.heytune.app.R
import com.heytune.app.constants.TopBarInsets
import com.heytune.app.ui.component.ColumnWithContentPadding
import com.heytune.app.ui.component.SettingsClickToReveal
import com.heytune.app.ui.component.button.IconButton
import com.heytune.app.ui.dialog.InfoLabel
import com.heytune.app.ui.screens.settings.fragments.BackupAndRestoreFrag
import com.heytune.app.ui.screens.settings.fragments.ExportGramophoneFrag
import com.heytune.app.ui.utils.backToMain
import com.heytune.app.viewmodels.BackupRestoreViewModel
import com.heytune.app.viewmodels.GramophoneExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupAndRestore(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BackupAndRestoreFrag(viewModel)
        Spacer(modifier = Modifier.height(16.dp))
        InfoLabel(stringResource(R.string.import_innertune_tooltip))
        Spacer(modifier = Modifier.height(8.dp))
        InfoLabel(stringResource(R.string.restore_lm_tooltip))
        SettingsClickToReveal(stringResource(R.string.prefs_advanced)) {
            val viewModel2: GramophoneExportViewModel = hiltViewModel()
            ExportGramophoneFrag(viewModel2)
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.backup_restore)) },
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
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

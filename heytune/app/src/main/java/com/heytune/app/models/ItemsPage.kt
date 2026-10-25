package com.heytune.app.models

import com.heytune.app.db.entities.LocalItem


data class ItemsPage(
    val items: List<LocalItem>,
    val continuation: String?,
)

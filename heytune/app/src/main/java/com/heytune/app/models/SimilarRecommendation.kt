package com.heytune.app.models

import com.heytune.app.db.entities.LocalItem


data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<LocalItem>,
)

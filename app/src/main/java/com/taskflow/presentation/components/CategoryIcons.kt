package com.taskflow.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "school" to Icons.Outlined.School,
        "work" to Icons.Outlined.WorkOutline,
        "data" to Icons.Outlined.Storage,
        "rocket" to Icons.Outlined.RocketLaunch,
        "money" to Icons.Outlined.AttachMoney,
        "fitness" to Icons.Outlined.FitnessCenter,
        "book" to Icons.AutoMirrored.Outlined.MenuBook,
        "home" to Icons.Outlined.Home,
        "code" to Icons.Outlined.Code,
        "music" to Icons.Outlined.MusicNote,
        "heart" to Icons.Outlined.FavoriteBorder,
        "travel" to Icons.Outlined.Flight,
        "shopping" to Icons.Outlined.ShoppingCart,
        "star" to Icons.Outlined.StarOutline,
        "dots" to Icons.Outlined.MoreHoriz,
    )
    fun of(key: String): ImageVector = all[key] ?: Icons.Outlined.MoreHoriz

    val palette: List<Long> = listOf(
        0xFF5B6CF0, 0xFF2F80ED, 0xFF0EA5A4, 0xFF8B5CF6, 0xFF16A34A, 0xFFE5484D,
        0xFFF59E0B, 0xFFB7791F, 0xFFEC4899, 0xFF71717A,
    )
}

fun Long.asColor(): Color = Color(this.toInt())

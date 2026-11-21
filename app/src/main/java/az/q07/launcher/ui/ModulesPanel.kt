package az.q07.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import az.q07.launcher.data.AppSettings
import az.q07.launcher.modules.LauncherModule
import az.q07.launcher.modules.ModuleRegistry
import az.q07.launcher.ui.theme.CarColors

@Composable
fun ModulesPanel(modifier: Modifier = Modifier) {
    PanelSurface(modifier) {
        if (AppSettings.middleTabs) TabsMode() else ListMode()
    }
}

/**
 * Tab rejimi: yuxarıda neon tab-bar, altda YALNIZ seçilmiş modul.
 * Digər modullar işləmir (OBD, GPS, media sorğusu yoxdur) → launcher daha yüngül və sürətli.
 */
@Composable
private fun TabsMode() {
    val modules = ModuleRegistry.visible()
    if (modules.isEmpty()) return
    val selected = modules.indexOfFirst { it.id == AppSettings.selectedTab }.coerceAtLeast(0)
    val glow = if (AppSettings.glowMode == "accent") CarColors.Accent else DefaultGlow

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        GlowingFlowNavigationBar(
            items = modules.map { CarMenuItem(title = it.shortTitle, icon = it.icon, glow =
                if (AppSettings.glowMode == "slot") it.accent else null) },
            selectedIndex = selected,
            onItemSelected = { i -> AppSettings.update { selectedTab = modules[i].id } },
            glowColor = glow,
            barHeight = 64.dp
        )
        Spacer(Modifier.height(14.dp))
        val module = modules[selected]
        Crossfade(targetState = module, animationSpec = tween(180), label = "tab") { m ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(m.icon, null, tint = m.accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(m.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
                }
                Spacer(Modifier.height(14.dp))
                m.Summary()
                Spacer(Modifier.height(14.dp))
                m.Expanded()
            }
        }
    }
}

/** Köhnə rejim: bütün kartlar alt-alta */
@Composable
private fun ListMode() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Modullar", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = CarColors.TextPrimary)
        }
        items(ModuleRegistry.visible(), key = { it.id }) { module -> ModuleCard(module) }
    }
}

@Composable
private fun ModuleCard(module: LauncherModule) {
    var expanded by rememberSaveable(module.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.PanelAlt)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(module.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(module.icon, null, tint = module.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                module.title, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                color = CarColors.TextPrimary, modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Bağla" else "Aç",
                tint = CarColors.TextSecondary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        module.Summary()
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(top = 14.dp)) { module.Expanded() }
        }
    }
}

package com.projectnuke.fusion.ui

import com.projectnuke.fusion.ui.theme.LocalFusionSemanticColors

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FusionLanguagePrefs = "fusion_chat_settings"
private const val FusionAppLanguageKey = "fusion_app_language"
private val LanguagePanelBg: Color @Composable get() = LocalFusionSemanticColors.current.panel
private val LanguageCardBg: Color @Composable get() = LocalFusionSemanticColors.current.menu
private val LanguageCardSelectedBg: Color @Composable get() = LocalFusionSemanticColors.current.bubble
private val LanguageTextPrimary: Color @Composable get() = LocalFusionSemanticColors.current.onSurface
private val LanguageTextSecondary: Color @Composable get() = LocalFusionSemanticColors.current.onSurfaceVariant
private val LanguageAccentBlue: Color @Composable get() = LocalFusionSemanticColors.current.primary

enum class FusionAppLanguage(val value: String, val label: String, val isSupported: Boolean) {
    SYSTEM("system", "시스템 설정 따름", true),
    KOREAN("ko", "한국어", false),
    ENGLISH("en", "English", false);

    companion object {
        fun fromValue(raw: String?): FusionAppLanguage {
            return entries.firstOrNull { it.value == raw } ?: SYSTEM
        }
    }
}

fun getStoredFusionAppLanguage(context: Context): FusionAppLanguage {
    val prefs = context.getSharedPreferences(FusionLanguagePrefs, Context.MODE_PRIVATE)
    return FusionAppLanguage.fromValue(prefs.getString(FusionAppLanguageKey, FusionAppLanguage.SYSTEM.value))
}

fun getEffectiveFusionAppLanguage(context: Context): FusionAppLanguage {
    val stored = getStoredFusionAppLanguage(context)
    return if (stored.isSupported) stored else FusionAppLanguage.SYSTEM
}

/** Runtime callers must use the effective language, not the legacy stored value. */
fun getFusionAppLanguage(context: Context): FusionAppLanguage =
    getEffectiveFusionAppLanguage(context)

fun setFusionAppLanguage(context: Context, language: FusionAppLanguage) {
    context.getSharedPreferences(FusionLanguagePrefs, Context.MODE_PRIVATE)
        .edit()
        .putString(FusionAppLanguageKey, language.value)
        .apply()
}

fun applyFusionAppLanguageIfSupported(context: Context, language: FusionAppLanguage): Boolean {
    if (!language.isSupported) return false
    setFusionAppLanguage(context, language)
    // Currently only SYSTEM is supported; future localization will wire
    // the Android per-app language API when string resources are ready.
    return true
}

fun getFusionAppLanguageLabel(language: FusionAppLanguage): String {
    return language.label
}

@Composable
fun FusionLanguageSettingsDialog(
    context: Context,
    currentLanguage: FusionAppLanguage,
    storedLanguage: FusionAppLanguage = currentLanguage,
    onSelect: (FusionAppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        FusionAppLanguage.SYSTEM,
        FusionAppLanguage.KOREAN,
        FusionAppLanguage.ENGLISH
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("언어 설정") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "앱에서 사용할 언어를 선택합니다.",
                    color = LanguageTextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    "일부 화면은 아직 번역 리소스가 준비되지 않아 바로 변경되지 않을 수 있습니다.",
                    color = LanguageTextSecondary,
                    fontSize = 12.sp
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options) { option ->
                        val isSupported = option.isSupported
                        val isLegacyUnsupported = storedLanguage == option && !isSupported
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = isSupported) {
                                    if (isSupported) {
                                        if (applyFusionAppLanguageIfSupported(context, option)) {
                                            onSelect(option)
                                            Toast.makeText(context, "언어 설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (option == currentLanguage && isSupported) LanguageCardSelectedBg else LanguageCardBg
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        getFusionAppLanguageLabel(option),
                                        color = if (isSupported) LanguageTextPrimary else LanguageTextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (!isSupported) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text("준비 중", color = LanguageAccentBlue, fontSize = 10.sp)
                                    }
                                }
                                if (option == currentLanguage && isSupported) {
                                    Text("현재 선택됨", color = LanguageAccentBlue, fontSize = 11.sp)
                                } else if (isLegacyUnsupported) {
                                    Text(
                                        "저장된 설정이지만 현재 지원되지 않습니다.",
                                        color = LanguageTextSecondary,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = LanguageAccentBlue)
            }
        },
        containerColor = LanguagePanelBg,
        titleContentColor = LanguageTextPrimary,
        textContentColor = LanguageTextPrimary
    )
}

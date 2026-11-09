package com.projectnuke.fusion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectnuke.fusion.data.AppDatabase
import kotlinx.coroutines.CancellationException

@Composable
internal fun ReviewCardReviewSurface(
    database: AppDatabase,
    cardId: String,
    onBack: () -> Unit,
) {
    val cardState by produceState<com.projectnuke.fusion.data.StudyReviewCardEntity?>(null, database, cardId) {
        value = try {
            database.studyReviewCardDao().get(cardId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("닫기") }
        Text("복습 카드", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        val card = cardState
        if (card == null) {
            Text("카드를 불러오는 중이거나 찾을 수 없어요.")
        } else {
            Text("앞면", style = MaterialTheme.typography.labelLarge)
            Text(card.front, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("답", style = MaterialTheme.typography.labelLarge)
            Text(card.back, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

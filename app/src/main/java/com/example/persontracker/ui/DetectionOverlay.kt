package com.example.persontracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.persontracker.domain.TrackedPerson

private val Palette = listOf(
    Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFBA68C8),
    Color(0xFFFF8A65), Color(0xFF4DB6AC), Color(0xFFFFD54F), Color(0xFF7986CB),
    Color(0xFFF06292), Color(0xFFA1887F),
)

fun personColor(id: Int): Color = Palette[(id - 1).mod(Palette.size)]

/** يرسم صندوقًا وملصقًا "Person N" لكل شخص. الإحداثيات طبيعية، لذا يجب أن يطابق حجم الـ Canvas حجم الفيديو. */
@Composable
fun DetectionOverlay(people: List<TrackedPerson>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val padX = 6.dp.toPx()
        val padY = 2.dp.toPx()
        for (p in people) {
            val color = personColor(p.id)
            val l = p.box.l * size.width
            val t = p.box.t * size.height
            val w = (p.box.r - p.box.l) * size.width
            val h = (p.box.b - p.box.t) * size.height
            drawRect(color, Offset(l, t), Size(w, h), style = Stroke(width = stroke))

            val layout = measurer.measure("Person ${p.id}", labelStyle)
            val tagW = layout.size.width + 2 * padX
            val tagH = layout.size.height + 2 * padY
            val tagTop = if (t - tagH >= 0f) t - tagH else t
            drawRect(color, Offset(l, tagTop), Size(tagW, tagH))
            drawText(layout, topLeft = Offset(l + padX, tagTop + padY))
        }
    }
}

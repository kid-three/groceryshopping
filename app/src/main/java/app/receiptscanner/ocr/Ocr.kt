package app.receiptscanner.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** On-device OCR (ML Kit, Latin script, bundled model — works offline). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Returns row-ordered text (see [OcrLines]). */
    suspend fun read(context: Context, photo: Uri): String {
        val image = InputImage.fromFilePath(context, photo) // applies EXIF rotation
        val result = recognizer.process(image).await()
        val fragments = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            TextFragment(line.text, b.left, b.top, b.right, b.bottom)
        }
        return OcrLines.assemble(fragments)
    }
}

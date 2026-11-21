package com.cethelper.android;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.StringJoiner;

final class PageOcr {
    private static final int MAX_IMAGE_EDGE = 3200;

    interface Result {
        void onResult(String body);
    }

    static void recognize(StudyApplication app, Uri image, boolean chinese, Result result) {
        Handler main = new Handler(Looper.getMainLooper());
        app.images.execute(() -> {
            TextRecognizer recognizer = null;
            try {
                Bitmap bitmap = decode(app.getContentResolver(), image);
                recognizer = TextRecognition.getClient(chinese
                        ? new ChineseTextRecognizerOptions.Builder().build()
                        : TextRecognizerOptions.DEFAULT_OPTIONS);
                TextRecognizer client = recognizer;
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(task -> {
                    client.close();
                    bitmap.recycle();
                    result.onResult(task.isSuccessful() ? paragraphs(task.getResult(), chinese) : null);
                });
            } catch (IOException | RuntimeException failure) {
                if (recognizer != null) {
                    recognizer.close();
                }
                main.post(() -> result.onResult(null));
            }
        });
    }

    private static String paragraphs(Text result, boolean chinese) {
        String lineSeparator = chinese ? "" : " ";
        StringBuilder page = new StringBuilder();
        Text.Line previous = null;
        for (Text.TextBlock block : result.getTextBlocks()) {
            StringJoiner paragraph = new StringJoiner(lineSeparator);
            Text.Line first = null;
            Text.Line last = null;
            for (Text.Line line : block.getLines()) {
                String text = ReadingArticle.trimSpace(line.getText());
                if (!text.isEmpty()) {
                    paragraph.add(text);
                    if (first == null) {
                        first = line;
                    }
                    last = line;
                }
            }
            if (first != null) {
                if (previous != null) {
                    page.append(continuesParagraph(previous, first) ? lineSeparator : "\n\n");
                }
                page.append(paragraph);
                previous = last;
            }
        }
        return page.toString();
    }

    private static boolean continuesParagraph(Text.Line previous, Text.Line next) {
        Rect above = previous.getBoundingBox();
        Rect below = next.getBoundingBox();
        if (above == null || below == null) {
            return false;
        }
        float lineHeight = (above.height() + below.height()) / 2f;
        int gap = below.top - above.bottom;
        // Tightly spaced lines starting in the same column can be in separate OCR blocks.
        return gap >= -lineHeight / 4 && gap <= lineHeight * 1.2f
                && Math.abs(above.left - below.left) <= lineHeight * 0.8f;
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        int longest = Math.max(width, height);
                        if (longest > MAX_IMAGE_EDGE) {
                            float scale = (float) MAX_IMAGE_EDGE / longest;
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        }
                    });
        }
        return decodeLegacy(resolver, uri);
    }

    private static Bitmap decodeLegacy(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = open(resolver, uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        int longest = Math.max(options.outWidth, options.outHeight);
        while (longest / options.inSampleSize > MAX_IMAGE_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream stream = open(resolver, uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) {
            throw new IOException("Unsupported image");
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = open(resolver, uri)) {
            orientation = new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // Images may be decodable without EXIF metadata.
        }
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1, 1);
            case ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1, -1);
            case ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90);
                transform.postScale(-1, 1);
            }
            case ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90);
            case ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(-90);
                transform.postScale(-1, 1);
            }
            case ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(-90);
            default -> {
                return bitmap;
            }
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                transform, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) {
            throw new IOException("Image is unavailable");
        }
        return stream;
    }
}

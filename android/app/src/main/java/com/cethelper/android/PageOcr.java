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
import android.util.Base64;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class PageOcr {
    private static final int MAX_IMAGE_EDGE = 3200;
    private static final int ORGANIZE_IMAGE_MAX_EDGE = 1600;
    private static final int ORGANIZE_IMAGE_MAX_BYTES = 1_500_000;
    private static final int ORGANIZE_JPEG_INITIAL_QUALITY = 82;
    private static final int ORGANIZE_JPEG_MIN_QUALITY = 42;

    private static final Pattern NUMERIC_PAGE_MARKER = Pattern.compile(
            "^[-—–·•|\\[\\]()\\s]*\\d{1,4}(?:\\s*/\\s*\\d{1,4})?[-—–·•|\\[\\]().\\s]*$");
    private static final Pattern ENGLISH_PAGE_MARKER = Pattern.compile(
            "^(?:page|pg\\.?|p\\.?)\\s*\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_PAGE_MARKER = Pattern.compile(
            "^第\\s*\\d{1,4}\\s*页(?:\\s*[/／共]\\s*\\d{1,4}\\s*页?)?$");

    interface Result {
        void onResult(String body);
    }

    record OcrLine(int left, int top, int right, int bottom, String text) {
        int height() {
            return Math.max(0, bottom - top);
        }

        boolean hasBounds() {
            return right > left && bottom > top;
        }
    }

    record OcrBlock(int left, int top, int right, int bottom, List<OcrLine> lines) {
        static OcrBlock of(int left, int top, int right, int bottom, List<String> texts) {
            List<String> clean = new ArrayList<>();
            if (texts != null) {
                for (String raw : texts) {
                    if (raw != null) {
                        String trimmed = ReadingArticle.trimSpace(raw);
                        if (!trimmed.isEmpty()) {
                            clean.add(trimmed);
                        }
                    }
                }
            }
            List<OcrLine> ocrLines = new ArrayList<>(clean.size());
            int count = clean.size();
            if (count > 0) {
                int totalHeight = Math.max(0, bottom - top);
                for (int i = 0; i < count; i++) {
                    int lineTop = top + (totalHeight * i) / count;
                    int lineBottom = top + (totalHeight * (i + 1)) / count;
                    ocrLines.add(new OcrLine(left, lineTop, right, lineBottom, clean.get(i)));
                }
            }
            return new OcrBlock(left, top, right, bottom, ocrLines);
        }

        int width() {
            return Math.max(0, right - left);
        }

        int height() {
            return Math.max(0, bottom - top);
        }

        boolean hasBounds() {
            return right > left && bottom > top;
        }
    }

    private record OrderedBlock(OcrBlock block, int columnId) {}

    static void recognize(StudyApplication app, Uri image, boolean chinese, Result result) {
        Handler main = new Handler(Looper.getMainLooper());
        app.images.execute(() -> {
            TextRecognizer recognizer = null;
            try {
                Bitmap bitmap = decode(app.getContentResolver(), image, MAX_IMAGE_EDGE);
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

    static Bitmap decodePreview(ContentResolver resolver, Uri uri, int maxEdge) throws IOException {
        return decode(resolver, uri, maxEdge);
    }

    static String encodeJpegBase64(ContentResolver resolver, Uri uri) throws IOException {
        if (resolver == null || uri == null) {
            throw new IOException("Image is unavailable");
        }
        Bitmap bitmap = decode(resolver, uri, ORGANIZE_IMAGE_MAX_EDGE);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int quality = ORGANIZE_JPEG_INITIAL_QUALITY;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
            while (output.size() > ORGANIZE_IMAGE_MAX_BYTES && quality > ORGANIZE_JPEG_MIN_QUALITY) {
                quality = Math.max(ORGANIZE_JPEG_MIN_QUALITY, quality - 12);
                output.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    private static String paragraphs(Text result, boolean chinese) {
        if (result == null) {
            return "";
        }
        List<OcrBlock> blocks = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect blockRect = block.getBoundingBox();
            List<OcrLine> lines = new ArrayList<>();
            int minLeft = Integer.MAX_VALUE;
            int minTop = Integer.MAX_VALUE;
            int maxRight = Integer.MIN_VALUE;
            int maxBottom = Integer.MIN_VALUE;
            for (Text.Line line : block.getLines()) {
                String text = ReadingArticle.trimSpace(line.getText());
                if (text.isEmpty()) {
                    continue;
                }
                Rect lineRect = line.getBoundingBox();
                int l = lineRect != null ? lineRect.left : (blockRect != null ? blockRect.left : 0);
                int t = lineRect != null ? lineRect.top : (blockRect != null ? blockRect.top : 0);
                int r = lineRect != null ? lineRect.right : (blockRect != null ? blockRect.right : 0);
                int b = lineRect != null ? lineRect.bottom : (blockRect != null ? blockRect.bottom : 0);
                lines.add(new OcrLine(l, t, r, b, text));
                if (r > l && b > t) {
                    minLeft = Math.min(minLeft, l);
                    minTop = Math.min(minTop, t);
                    maxRight = Math.max(maxRight, r);
                    maxBottom = Math.max(maxBottom, b);
                }
            }
            if (!lines.isEmpty()) {
                int left = blockRect != null ? blockRect.left : (minLeft != Integer.MAX_VALUE ? minLeft : 0);
                int top = blockRect != null ? blockRect.top : (minTop != Integer.MAX_VALUE ? minTop : 0);
                int right = blockRect != null ? blockRect.right : (maxRight != Integer.MIN_VALUE ? maxRight : 0);
                int bottom = blockRect != null ? blockRect.bottom : (maxBottom != Integer.MIN_VALUE ? maxBottom : 0);
                blocks.add(new OcrBlock(left, top, right, bottom, lines));
            }
        }
        return formatBlocks(blocks, chinese);
    }

    static String formatBlocks(List<OcrBlock> rawBlocks, boolean chinese) {
        if (rawBlocks == null || rawBlocks.isEmpty()) {
            return "";
        }
        List<OcrBlock> cleaned = sanitizeBlocks(rawBlocks);
        if (cleaned.isEmpty()) {
            return "";
        }

        boolean hasBounds = false;
        for (OcrBlock block : cleaned) {
            if (block.hasBounds()) {
                hasBounds = true;
                break;
            }
        }
        if (!hasBounds) {
            List<OrderedBlock> plain = new ArrayList<>(cleaned.size());
            for (OcrBlock block : cleaned) {
                plain.add(new OrderedBlock(block, 1));
            }
            return joinOrderedBlocks(plain, chinese, 0f);
        }

        float medianLineHeight = computeMedianLineHeight(cleaned);
        List<OcrBlock> filtered = filterEdgeNoise(cleaned, medianLineHeight);
        if (filtered.isEmpty()) {
            filtered = cleaned;
        }
        List<OrderedBlock> ordered = orderBlocksByReadingFlow(filtered, medianLineHeight);
        return joinOrderedBlocks(ordered, chinese, medianLineHeight);
    }

    private static List<OcrBlock> sanitizeBlocks(List<OcrBlock> rawBlocks) {
        List<OcrBlock> result = new ArrayList<>(rawBlocks.size());
        for (OcrBlock block : rawBlocks) {
            if (block == null || block.lines() == null || block.lines().isEmpty()) {
                continue;
            }
            List<OcrLine> lines = new ArrayList<>(block.lines().size());
            for (OcrLine line : block.lines()) {
                if (line == null || line.text() == null) {
                    continue;
                }
                String trimmed = ReadingArticle.trimSpace(line.text());
                if (!trimmed.isEmpty()) {
                    lines.add(new OcrLine(line.left(), line.top(), line.right(), line.bottom(), trimmed));
                }
            }
            if (!lines.isEmpty()) {
                result.add(new OcrBlock(block.left(), block.top(), block.right(), block.bottom(), lines));
            }
        }
        return result;
    }

    private static float computeMedianLineHeight(List<OcrBlock> blocks) {
        List<Integer> heights = new ArrayList<>();
        for (OcrBlock block : blocks) {
            for (OcrLine line : block.lines()) {
                if (line.hasBounds() && line.height() > 0) {
                    heights.add(line.height());
                }
            }
            if (heights.isEmpty() && block.hasBounds() && !block.lines().isEmpty()) {
                heights.add(Math.max(1, block.height() / block.lines().size()));
            }
        }
        if (heights.isEmpty()) {
            return 24f;
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    private static List<OcrBlock> filterEdgeNoise(List<OcrBlock> blocks, float medianLineHeight) {
        if (blocks.size() < 2) {
            return blocks;
        }
        int pageTop = Integer.MAX_VALUE;
        int pageBottom = Integer.MIN_VALUE;
        for (OcrBlock block : blocks) {
            if (block.hasBounds()) {
                pageTop = Math.min(pageTop, block.top());
                pageBottom = Math.max(pageBottom, block.bottom());
            }
        }
        int pageHeight = pageBottom - pageTop;
        if (pageHeight <= 0) {
            return blocks;
        }

        List<OcrBlock> kept = new ArrayList<>(blocks.size());
        for (OcrBlock block : blocks) {
            if (isEdgePageNoise(block, pageTop, pageBottom, pageHeight, medianLineHeight)) {
                continue;
            }
            kept.add(block);
        }
        return kept.isEmpty() ? blocks : kept;
    }

    private static boolean isEdgePageNoise(OcrBlock block, int pageTop, int pageBottom,
                                           int pageHeight, float medianLineHeight) {
        if (!block.hasBounds() || block.lines().size() > 1) {
            return false;
        }
        String text = block.lines().get(0).text();
        if (text.length() > 24 || !isPageMarkerText(text)) {
            return false;
        }
        float edgeBand = Math.max(pageHeight * 0.12f, medianLineHeight * 2.2f);
        boolean atTopEdge = block.top() <= pageTop + pageHeight * 0.08f
                && block.bottom() <= pageTop + edgeBand
                && block.bottom() < pageBottom - pageHeight * 0.15f;
        boolean atBottomEdge = block.bottom() >= pageBottom - pageHeight * 0.08f
                && block.top() >= pageBottom - edgeBand
                && block.top() > pageTop + pageHeight * 0.15f;
        return atTopEdge || atBottomEdge;
    }

    static boolean isPageMarkerText(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = ReadingArticle.trimSpace(text);
        if (trimmed.isEmpty()) {
            return false;
        }
        return NUMERIC_PAGE_MARKER.matcher(trimmed).matches()
                || ENGLISH_PAGE_MARKER.matcher(trimmed).matches()
                || CHINESE_PAGE_MARKER.matcher(trimmed).matches();
    }

    private static List<OrderedBlock> orderBlocksByReadingFlow(List<OcrBlock> blocks, float medianLineHeight) {
        if (blocks.size() <= 1) {
            List<OrderedBlock> single = new ArrayList<>(blocks.size());
            for (OcrBlock block : blocks) {
                single.add(new OrderedBlock(block, 1));
            }
            return single;
        }

        int pageLeft = Integer.MAX_VALUE;
        int pageRight = Integer.MIN_VALUE;
        for (OcrBlock block : blocks) {
            if (block.hasBounds()) {
                pageLeft = Math.min(pageLeft, block.left());
                pageRight = Math.max(pageRight, block.right());
            }
        }
        int pageWidth = pageRight - pageLeft;
        if (pageWidth <= 0) {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight), 1);
        }

        List<OcrBlock> candidates = new ArrayList<>();
        List<OcrBlock> spanning = new ArrayList<>();
        float spanningThreshold = pageWidth * 0.62f;
        for (OcrBlock block : blocks) {
            if (block.hasBounds() && block.width() > spanningThreshold) {
                spanning.add(block);
            } else {
                candidates.add(block);
            }
        }

        Float gutterX = detectColumnGutter(candidates, pageLeft, pageRight, pageWidth, medianLineHeight);
        if (gutterX == null) {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight), 1);
        }

        List<OcrBlock> leftCol = new ArrayList<>();
        List<OcrBlock> rightCol = new ArrayList<>();
        for (OcrBlock block : candidates) {
            float centerX = (block.left() + block.right()) / 2f;
            if (centerX < gutterX) {
                leftCol.add(block);
            } else {
                rightCol.add(block);
            }
        }
        if (leftCol.isEmpty() || rightCol.isEmpty()) {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight), 1);
        }

        int leftTop = minTop(leftCol);
        int leftBottom = maxBottom(leftCol);
        int rightTop = minTop(rightCol);
        int rightBottom = maxBottom(rightCol);
        int verticalOverlap = Math.min(leftBottom, rightBottom) - Math.max(leftTop, rightTop);
        if (verticalOverlap <= 0) {
            return wrapColumn(sortSingleColumn(blocks, medianLineHeight), 1);
        }

        int columnTop = Math.min(leftTop, rightTop);
        int columnBottom = Math.max(leftBottom, rightBottom);
        List<OcrBlock> topSpanning = new ArrayList<>();
        List<OcrBlock> bottomSpanning = new ArrayList<>();
        for (OcrBlock span : spanning) {
            if (span.bottom() <= columnTop + medianLineHeight * 0.5f) {
                topSpanning.add(span);
            } else if (span.top() >= columnBottom - medianLineHeight * 0.5f) {
                bottomSpanning.add(span);
            } else {
                // Spanning block intersects the middle of the column band: treat as single-column.
                return wrapColumn(sortSingleColumn(blocks, medianLineHeight), 1);
            }
        }

        List<OrderedBlock> ordered = new ArrayList<>(blocks.size());
        ordered.addAll(wrapColumn(sortSingleColumn(topSpanning, medianLineHeight), 0));
        ordered.addAll(wrapColumn(sortSingleColumn(leftCol, medianLineHeight), 1));
        ordered.addAll(wrapColumn(sortSingleColumn(rightCol, medianLineHeight), 2));
        ordered.addAll(wrapColumn(sortSingleColumn(bottomSpanning, medianLineHeight), 3));
        return ordered;
    }

    private static Float detectColumnGutter(List<OcrBlock> candidates, int pageLeft, int pageRight,
                                            int pageWidth, float medianLineHeight) {
        if (candidates.size() < 2) {
            return null;
        }
        List<int[]> intervals = new ArrayList<>(candidates.size());
        for (OcrBlock block : candidates) {
            if (block.hasBounds()) {
                intervals.add(new int[]{block.left(), block.right()});
            }
        }
        if (intervals.size() < 2) {
            return null;
        }
        intervals.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]));

        float centralMin = pageLeft + pageWidth * 0.25f;
        float centralMax = pageLeft + pageWidth * 0.75f;
        float minGapWidth = Math.max(medianLineHeight * 0.5f, pageWidth * 0.02f);

        int mergedRight = intervals.get(0)[1];
        float bestGapWidth = -1f;
        Float bestGutter = null;

        for (int i = 1; i < intervals.size(); i++) {
            int nextLeft = intervals.get(i)[0];
            int nextRight = intervals.get(i)[1];
            if (nextLeft > mergedRight) {
                float gapWidth = nextLeft - mergedRight;
                float mid = (mergedRight + nextLeft) / 2f;
                if (gapWidth >= minGapWidth && mid >= centralMin && mid <= centralMax
                        && gapWidth > bestGapWidth) {
                    bestGapWidth = gapWidth;
                    bestGutter = mid;
                }
            }
            if (nextRight > mergedRight) {
                mergedRight = nextRight;
            }
        }
        return bestGutter;
    }

    private static List<OcrBlock> sortSingleColumn(List<OcrBlock> blocks, float medianLineHeight) {
        if (blocks.size() <= 1) {
            return new ArrayList<>(blocks);
        }
        List<OcrBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(OcrBlock::top).thenComparingInt(OcrBlock::left));

        List<OcrBlock> result = new ArrayList<>(sorted.size());
        List<OcrBlock> row = new ArrayList<>();
        int rowTop = sorted.get(0).top();
        int rowBottom = sorted.get(0).bottom();
        row.add(sorted.get(0));

        float rowTolerance = Math.max(8f, medianLineHeight * 0.5f);
        for (int i = 1; i < sorted.size(); i++) {
            OcrBlock curr = sorted.get(i);
            float currCenterY = (curr.top() + curr.bottom()) / 2f;
            boolean sameRow = curr.top() <= rowTop + rowTolerance
                    || (currCenterY >= rowTop && currCenterY <= rowBottom);
            if (sameRow) {
                row.add(curr);
                rowBottom = Math.max(rowBottom, curr.bottom());
            } else {
                row.sort(Comparator.comparingInt(OcrBlock::left).thenComparingInt(OcrBlock::top));
                result.addAll(row);
                row.clear();
                row.add(curr);
                rowTop = curr.top();
                rowBottom = curr.bottom();
            }
        }
        row.sort(Comparator.comparingInt(OcrBlock::left).thenComparingInt(OcrBlock::top));
        result.addAll(row);
        return result;
    }

    private static List<OrderedBlock> wrapColumn(List<OcrBlock> blocks, int columnId) {
        List<OrderedBlock> wrapped = new ArrayList<>(blocks.size());
        for (OcrBlock block : blocks) {
            wrapped.add(new OrderedBlock(block, columnId));
        }
        return wrapped;
    }

    private static int minTop(List<OcrBlock> blocks) {
        int min = Integer.MAX_VALUE;
        for (OcrBlock block : blocks) {
            min = Math.min(min, block.top());
        }
        return min;
    }

    private static int maxBottom(List<OcrBlock> blocks) {
        int max = Integer.MIN_VALUE;
        for (OcrBlock block : blocks) {
            max = Math.max(max, block.bottom());
        }
        return max;
    }

    private static String joinOrderedBlocks(List<OrderedBlock> orderedBlocks, boolean chinese,
                                            float medianLineHeight) {
        StringBuilder page = new StringBuilder();
        OcrLine previousLine = null;
        int previousColumnId = -1;
        int previousBlockIndex = -1;

        for (int blockIndex = 0; blockIndex < orderedBlocks.size(); blockIndex++) {
            OrderedBlock ordered = orderedBlocks.get(blockIndex);
            int columnId = ordered.columnId();
            for (OcrLine line : ordered.block().lines()) {
                String text = line.text();
                if (text.isEmpty()) {
                    continue;
                }
                if (previousLine == null) {
                    page.append(text);
                } else {
                    boolean sameColumn = columnId == previousColumnId;
                    boolean sameBlock = blockIndex == previousBlockIndex;
                    if (shouldJoinIntoSameParagraph(previousLine, line, sameColumn, sameBlock,
                            chinese, medianLineHeight)) {
                        appendJoinedLine(page, text, chinese);
                    } else {
                        page.append("\n\n").append(text);
                    }
                }
                previousLine = line;
                previousColumnId = columnId;
                previousBlockIndex = blockIndex;
            }
        }
        return page.toString();
    }

    private static boolean shouldJoinIntoSameParagraph(OcrLine previous, OcrLine next,
                                                       boolean sameColumn, boolean sameBlock,
                                                       boolean chinese, float medianLineHeight) {
        if (!sameColumn) {
            return false;
        }
        if (!previous.hasBounds() || !next.hasBounds()) {
            return sameBlock || !endsWithTerminalPunctuation(previous.text(), chinese);
        }

        float lineHeight = (previous.height() + next.height()) / 2f;
        if (lineHeight <= 0f) {
            lineHeight = medianLineHeight > 0f ? medianLineHeight : 24f;
        }
        int gap = next.top() - previous.bottom();
        int indentDelta = next.left() - previous.left();
        boolean terminalEnd = endsWithTerminalPunctuation(previous.text(), chinese);

        if (sameBlock) {
            if (gap > lineHeight * 1.15f) {
                return false;
            }
            if (terminalEnd && (gap > lineHeight * 0.8f || indentDelta >= lineHeight * 0.9f)) {
                return false;
            }
            return true;
        }

        if (terminalEnd) {
            return false;
        }
        boolean normalVerticalSpacing = gap >= -lineHeight * 0.35f && gap <= lineHeight * 0.85f;
        boolean indentedParagraphStart = indentDelta >= lineHeight * 0.9f;
        return normalVerticalSpacing && !indentedParagraphStart;
    }

    private static boolean endsWithTerminalPunctuation(String text, boolean chinese) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == '?' || last == '!' || last == ':'
                || last == '"' || last == '\u201D' || last == '\u2019') {
            return true;
        }
        return chinese && (last == '。' || last == '？' || last == '！' || last == '：');
    }

    private static void appendJoinedLine(StringBuilder target, String next, boolean chinese) {
        if (target.length() == 0) {
            target.append(next);
            return;
        }
        if (!chinese && shouldMergeHyphenatedWord(target, next)) {
            target.deleteCharAt(target.length() - 1);
            target.append(next);
            return;
        }
        if (chinese) {
            char prevChar = target.charAt(target.length() - 1);
            char nextChar = next.charAt(0);
            if (isAsciiWordChar(prevChar) && isAsciiWordChar(nextChar)) {
                target.append(' ');
            }
            target.append(next);
        } else {
            target.append(' ').append(next);
        }
    }

    private static boolean shouldMergeHyphenatedWord(StringBuilder target, String next) {
        int len = target.length();
        if (len < 2 || next.isEmpty()) {
            return false;
        }
        char last = target.charAt(len - 1);
        if (last != '-' && last != '\u00AD' && last != '\u2010') {
            return false;
        }
        char beforeHyphen = target.charAt(len - 2);
        char nextFirst = next.charAt(0);
        return ((beforeHyphen >= 'A' && beforeHyphen <= 'Z') || (beforeHyphen >= 'a' && beforeHyphen <= 'z'))
                && (nextFirst >= 'a' && nextFirst <= 'z');
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri, int maxEdge) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        int longest = Math.max(width, height);
                        if (longest > maxEdge) {
                            float scale = (float) maxEdge / longest;
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        }
                    });
        }
        return decodeLegacy(resolver, uri, maxEdge);
    }

    private static Bitmap decodeLegacy(ContentResolver resolver, Uri uri, int maxEdge) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = open(resolver, uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        int longest = Math.max(options.outWidth, options.outHeight);
        while (longest / options.inSampleSize > maxEdge) {
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

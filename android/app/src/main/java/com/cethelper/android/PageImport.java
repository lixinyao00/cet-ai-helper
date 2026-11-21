package com.cethelper.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

final class PageImport {
    private static final int CAPTURE_REQUEST = 21;
    private static final int PICK_REQUEST = 22;
    private static final int CAMERA_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final Pattern ENGLISH_LETTER = Pattern.compile("[A-Za-z]");

    enum Target { READING, TRANSLATION }

    private enum Stage { EXTERNAL, RECOGNIZING, REVIEW, SAVING, ERROR }

    private final MainActivity host;
    private final StudyApplication app;
    private Stage stage;
    private Target target = Target.READING;
    private ArticleOwner owner;
    private int request;
    private String articleId;
    private String cameraFile;
    private Uri image;
    private String title = "";
    private String body = "";
    private String message;
    private EditText titleField;
    private EditText bodyField;

    PageImport(MainActivity host, StudyApplication app, Bundle saved) {
        this.host = host;
        this.app = app;
        if (saved != null) {
            stage = Stage.valueOf(saved.getString("stage"));
            target = Target.valueOf(saved.getString("target", Target.READING.name()));
            owner = new ArticleOwner(saved.getString("server"), saved.getLong("student"));
            request = saved.getInt("request");
            articleId = saved.getString("article");
            cameraFile = saved.getString("cameraFile");
            String savedImage = saved.getString("image");
            image = savedImage == null ? null : Uri.parse(savedImage);
            title = saved.getString("title", "");
            body = saved.getString("body", "");
            message = saved.getString("message");
        }
    }

    Bundle saveState() {
        if (stage == null) {
            return null;
        }
        Bundle saved = new Bundle();
        saved.putString("stage", stage.name());
        saved.putString("target", target.name());
        saved.putString("server", owner.serverUrl());
        saved.putLong("student", owner.studentId());
        saved.putInt("request", request);
        saved.putString("article", articleId);
        saved.putString("cameraFile", cameraFile);
        saved.putString("image", image == null ? null : image.toString());
        saved.putString("title", stage == Stage.REVIEW && titleField != null ? titleField.getText().toString() : title);
        saved.putString("body", stage == Stage.REVIEW && bodyField != null ? bodyField.getText().toString() : body);
        saved.putString("message", message);
        return saved;
    }

    boolean resume(ArticleOwner activeOwner) {
        if (stage == null) {
            return false;
        }
        if (!owner.equals(activeOwner)) {
            reset();
            return false;
        }
        switch (stage) {
            case EXTERNAL -> showStatus("等待照片或相册选择…", false);
            case RECOGNIZING -> recognize();
            case REVIEW -> showEditor();
            case SAVING -> save(showEditor());
            case ERROR -> showStatus(message, true);
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    void launch(boolean camera, Target destination) {
        reset();
        target = destination;
        owner = host.articleOwner();
        articleId = target == Target.READING ? UUID.randomUUID().toString() : null;
        request = camera ? CAPTURE_REQUEST : PICK_REQUEST;
        stage = Stage.EXTERNAL;
        showStatus(camera ? "等待系统相机…" : "等待相册选择…", false);
        try {
            if (camera) {
                File directory = captureDirectory();
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Capture cache unavailable");
                }
                File file = File.createTempFile("page-", ".jpg", directory);
                cameraFile = file.getName();
                image = FileProvider.getUriForFile(host, host.getPackageName() + ".capture", file);
                Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, image)
                        .addFlags(CAMERA_GRANT);
                capture.setClipData(ClipData.newRawUri("Page photo", image));
                host.startActivityForResult(capture, request);
            } else {
                openPicker();
            }
        } catch (ActivityNotFoundException | IOException | SecurityException failure) {
            fail(camera ? "无法打开系统相机，请重拍或改从相册选择。" : "无法打开图片选择器，请重选或改用相机。");
        }
    }

    @SuppressWarnings("deprecation")
    private void openPicker() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                host.startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES)
                        .setType("image/*"), PICK_REQUEST);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Use the document picker on devices without a photo picker.
            }
        }
        host.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_REQUEST);
    }

    void onActivityResult(int code, int result, Intent data) {
        if (stage != Stage.EXTERNAL || request != code) {
            return;
        }
        ArticleOwner activeOwner = host.articleOwner();
        if (result != Activity.RESULT_OK) {
            if (activeOwner != null) {
                cancel();
            } else {
                reset();
            }
            return;
        }
        if (request == PICK_REQUEST) {
            image = data == null ? null : data.getData();
        }
        stage = Stage.RECOGNIZING;
        if (activeOwner != null) {
            resume(activeOwner);
        }
    }

    void reset() {
        clearImage();
        stage = null;
        owner = null;
        title = "";
        body = "";
        message = null;
        titleField = null;
        bodyField = null;
    }

    private File captureDirectory() {
        return new File(host.getCacheDir(), "page-captures");
    }

    private void clearImage() {
        if (cameraFile != null) {
            if (image != null) {
                host.revokeUriPermission(image, CAMERA_GRANT);
            }
            new File(captureDirectory(), cameraFile).delete();
            cameraFile = null;
        }
        image = null;
    }

    private void cancel() {
        reset();
        if (target == Target.TRANSLATION) {
            host.showTranslationSource();
        } else {
            host.showHome();
        }
    }

    private void recognize() {
        if (image == null) {
            fail("没有收到图片，请重拍或重选。");
            return;
        }
        boolean chinese = target == Target.TRANSLATION;
        View progress = showStatus(chinese ? "正在本机识别中文文字…" : "正在本机识别英文文字…", false);
        PageOcr.recognize(app, image, chinese, result -> {
            if (!host.isCurrent(progress)) {
                return;
            }
            clearImage();
            if (result == null) {
                fail("无法读取或识别这张图片，请重拍或重选。");
            } else if (chinese ? result.codePoints().noneMatch(
                    code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)
                    : !ENGLISH_LETTER.matcher(result).find()) {
                fail(chinese ? "未识别到中文文字，请重拍或重选。" : "未识别到英文文字，请重拍或重选。");
            } else if (result.length() > 50000) {
                fail("这一页的识别结果过长，请缩小拍摄范围或重选。");
            } else {
                body = result;
                stage = Stage.REVIEW;
                showEditor();
            }
        });
    }

    private void fail(String reason) {
        clearImage();
        stage = Stage.ERROR;
        message = reason;
        showStatus(reason, true);
    }

    private View showStatus(String status, boolean failed) {
        View page = host.show(R.layout.screen_import_status, this::cancel);
        page.<TextView>findViewById(R.id.import_message).setText(status);
        page.findViewById(R.id.import_progress).setVisibility(failed ? View.GONE : View.VISIBLE);
        page.findViewById(R.id.import_cancel).setOnClickListener(v -> cancel());
        View capture = page.findViewById(R.id.import_recapture);
        View pick = page.findViewById(R.id.import_reselect);
        capture.setVisibility(failed ? View.VISIBLE : View.GONE);
        pick.setVisibility(failed ? View.VISIBLE : View.GONE);
        capture.setOnClickListener(v -> launch(true, target));
        pick.setOnClickListener(v -> launch(false, target));
        return page;
    }

    private View showEditor() {
        View editor = host.show(R.layout.screen_import_review, this::cancel);
        titleField = editor.findViewById(R.id.import_title);
        bodyField = editor.findViewById(R.id.import_body);
        titleField.setText(title);
        bodyField.setText(body);
        if (target == Target.TRANSLATION) {
            titleField.setVisibility(View.GONE);
            bodyField.setHint(R.string.translation_source);
            editor.<TextView>findViewById(R.id.import_review_hint).setText(R.string.translation_ocr_review);
            editor.<TextView>findViewById(R.id.import_confirm).setText(R.string.translation_start);
        }
        editor.findViewById(R.id.import_cancel).setOnClickListener(v -> cancel());
        editor.findViewById(R.id.import_confirm).setOnClickListener(v -> confirm(editor));
        return editor;
    }

    private void confirm(View editor) {
        String correctedTitle = ReadingArticle.trimSpace(titleField.getText().toString());
        String correctedBody = ReadingArticle.trimSpace(bodyField.getText().toString()
                .replace("\r\n", "\n").replace('\r', '\n'));
        if (target == Target.TRANSLATION) {
            if (TranslationScreen.checkSource(bodyField, correctedBody)) {
                host.replaceTranslation(editor, correctedBody, () -> {
                    host.getSystemService(InputMethodManager.class)
                            .hideSoftInputFromWindow(editor.getWindowToken(), 0);
                    reset();
                    host.startTranslation(correctedBody);
                });
            }
            return;
        }
        if (correctedTitle.isEmpty()) {
            titleField.setError("请输入文章标题。");
            return;
        }
        if (correctedBody.isEmpty()) {
            bodyField.setError("请输入文章正文。");
            return;
        }
        title = correctedTitle;
        body = correctedBody;
        host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(
                editor.getWindowToken(), 0);
        save(editor);
    }

    private void save(View editor) {
        stage = Stage.SAVING;
        host.setBackAction(() -> {});
        setEditorEnabled(editor, false);
        TextView status = editor.findViewById(R.id.import_review_status);
        status.setText("正在保存…");
        status.setVisibility(View.VISIBLE);
        ImportedArticle article = new ImportedArticle(owner.serverUrl(), owner.studentId(),
                articleId, title, body, System.currentTimeMillis());
        app.storage.execute(() -> {
            try {
                app.articles().insert(article);
                host.runOnUiThread(() -> {
                    if (host.isCurrent(editor)) {
                        reset();
                        host.showReader(article.readingArticle(), null);
                    }
                });
            } catch (RuntimeException storageError) {
                host.runOnUiThread(() -> {
                    if (host.isCurrent(editor)) {
                        stage = Stage.REVIEW;
                        host.setBackAction(this::cancel);
                        setEditorEnabled(editor, true);
                        status.setText("保存失败，请重试。");
                    }
                });
            }
        });
    }

    private void setEditorEnabled(View editor, boolean enabled) {
        titleField.setEnabled(enabled);
        bodyField.setEnabled(enabled);
        editor.findViewById(R.id.import_confirm).setEnabled(enabled);
        editor.findViewById(R.id.import_cancel).setEnabled(enabled);
    }
}

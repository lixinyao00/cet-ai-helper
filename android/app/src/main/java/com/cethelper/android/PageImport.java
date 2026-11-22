package com.cethelper.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;

final class PageImport {
    private static final int CAPTURE_REQUEST = 21;
    private static final int PICK_REQUEST = 22;
    private static final int PREVIEW_MAX_EDGE = 1280;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_BODY_LENGTH = 50000;
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
    private final ArrayList<String> extraCameraFiles = new ArrayList<>();
    private Uri image;
    private boolean appending;
    private boolean multiPage;
    private boolean imageCollapsed;
    private boolean organizing;
    private int organizeGeneration;
    private String reviewNotice;
    private String title = "";
    private String body = "";
    private String rawBody = "";
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
            ArrayList<String> extras = saved.getStringArrayList("extraCameraFiles");
            if (extras != null) {
                extraCameraFiles.addAll(extras);
            }
            String savedImage = saved.getString("image");
            image = savedImage == null ? null : Uri.parse(savedImage);
            appending = saved.getBoolean("appending", false);
            multiPage = saved.getBoolean("multiPage", false);
            imageCollapsed = saved.getBoolean("imageCollapsed", false);
            reviewNotice = saved.getString("reviewNotice");
            title = saved.getString("title", "");
            body = saved.getString("body", "");
            rawBody = saved.getString("rawBody", "");
            message = saved.getString("message");
        }
    }

    boolean isActive() {
        return stage != null;
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
        saved.putStringArrayList("extraCameraFiles", new ArrayList<>(extraCameraFiles));
        saved.putString("image", image == null ? null : image.toString());
        saved.putBoolean("appending", appending);
        saved.putBoolean("multiPage", multiPage);
        saved.putBoolean("imageCollapsed", imageCollapsed);
        saved.putString("reviewNotice", reviewNotice);
        saved.putString("title", stage == Stage.REVIEW && titleField != null ? titleField.getText().toString() : title);
        saved.putString("body", stage == Stage.REVIEW && bodyField != null ? bodyField.getText().toString() : body);
        saved.putString("rawBody", rawBody);
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
        appending = false;
        startCaptureOrPicker(camera);
    }

    @SuppressWarnings("deprecation")
    private void launchAppend(boolean camera) {
        organizeGeneration++;
        organizing = false;
        if (titleField != null) {
            title = titleField.getText().toString();
        }
        if (bodyField != null) {
            body = bodyField.getText().toString();
        }
        if (rawBody.isEmpty() && !body.isEmpty()) {
            rawBody = body;
        }
        if (cameraFile != null) {
            extraCameraFiles.add(cameraFile);
            cameraFile = null;
        }
        reviewNotice = null;
        appending = true;
        startCaptureOrPicker(camera);
    }

    @SuppressWarnings("deprecation")
    private void startCaptureOrPicker(boolean camera) {
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
            String reason = camera ? "无法打开系统相机，请重拍或改从相册选择。"
                    : "无法打开图片选择器，请重选或改用相机。";
            if (appending) {
                appending = false;
                reviewNotice = reason;
                stage = Stage.REVIEW;
                showEditor();
            } else {
                fail(reason);
            }
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
            if (appending) {
                appending = false;
                stage = Stage.REVIEW;
                if (activeOwner != null) {
                    showEditor();
                }
            } else if (activeOwner != null) {
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
        organizeGeneration++;
        organizing = false;
        clearImage();
        stage = null;
        owner = null;
        appending = false;
        multiPage = false;
        imageCollapsed = false;
        reviewNotice = null;
        title = "";
        body = "";
        rawBody = "";
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
        for (String extra : extraCameraFiles) {
            new File(captureDirectory(), extra).delete();
        }
        extraCameraFiles.clear();
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

    private SessionStore.Session activeSession() {
        SessionStore.Session account = host.session();
        if (account == null || account.serverUrl() == null || account.serverUrl().isEmpty()
                || account.token() == null || account.token().isEmpty()) {
            return null;
        }
        return account;
    }

    private void recognize() {
        int generation = ++organizeGeneration;
        organizing = false;
        if (image == null) {
            handleRecognizeError("没有收到图片，请重拍或重选。");
            return;
        }
        boolean chinese = target == Target.TRANSLATION;
        View progress = showStatus(chinese ? "正在本机识别中文文字…" : "正在本机识别英文文字…", false);
        PageOcr.recognize(app, image, chinese, result -> {
            if (!host.isCurrent(progress) || generation != organizeGeneration) {
                return;
            }
            if (result == null) {
                handleRecognizeError("无法读取或识别这张图片，请重拍或重选。");
            } else if (chinese ? result.codePoints().noneMatch(
                    code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)
                    : !ENGLISH_LETTER.matcher(result).find()) {
                handleRecognizeError(chinese ? "未识别到中文文字，请重拍或重选。" : "未识别到英文文字，请重拍或重选。");
            } else if ((appending ? body.length() + 2 : 0) + result.length() > MAX_BODY_LENGTH) {
                handleRecognizeError("识别结果总长度超过限制，请缩小拍摄范围或分篇保存。");
            } else if (appending) {
                body = body.isEmpty() ? result : body + "\n\n" + result;
                rawBody = rawBody.isEmpty() ? body : rawBody + "\n\n" + result;
                multiPage = true;
                reviewNotice = "已追加新的一页识别内容。";
                appending = false;
                // Retain the temporary image until the user saves or cancels the import flow.
                stage = Stage.REVIEW;
                showEditor();
            } else {
                rawBody = result;
                body = result;
                reviewNotice = null;
                SessionStore.Session account = activeSession();
                if (account == null) {
                    stage = Stage.REVIEW;
                    showEditor();
                    return;
                }
                organizeAfterRecognize(progress, account, generation);
            }
        });
    }

    private void organizeAfterRecognize(View progress, SessionStore.Session account, int generation) {
        organizing = true;
        TextView messageView = progress.findViewById(R.id.import_message);
        if (messageView != null) {
            messageView.setText(R.string.import_organizing);
        }
        Uri imageUri = image;
        String capturedRaw = rawBody;
        String targetName = target == Target.TRANSLATION ? "translation" : "reading";

        app.practiceRequests.execute(() -> {
            String imageBase64 = null;
            String imageMimeType = null;
            if (imageUri != null) {
                try {
                    imageBase64 = PageOcr.encodeJpegBase64(app.getContentResolver(), imageUri);
                    imageMimeType = "image/jpeg";
                } catch (IOException | RuntimeException ignored) {
                    // Proceed with text-only organization when image cannot be re-read.
                }
            }
            try {
                ApiClient.OrganizedArticle organized = new ApiClient(account.serverUrl())
                        .organizeArticle(account.token(), capturedRaw, imageBase64, imageMimeType, targetName);
                host.runOnUiThread(() -> {
                    if (!host.isCurrent(progress) || generation != organizeGeneration) {
                        return;
                    }
                    organizing = false;
                    if (organized.body().length() <= MAX_BODY_LENGTH) {
                        body = organized.body();
                        if (target == Target.READING && ReadingArticle.trimSpace(title).isEmpty()
                                && !organized.title().isEmpty()) {
                            title = truncateTitle(organized.title());
                        }
                        reviewNotice = !organized.summary().isEmpty()
                                ? organized.summary()
                                : host.getString(R.string.import_organize_done);
                    } else {
                        body = rawBody;
                        reviewNotice = host.getString(R.string.import_organize_fallback);
                    }
                    stage = Stage.REVIEW;
                    showEditor();
                });
            } catch (IOException | JSONException | ApiClient.Failure | RuntimeException failure) {
                host.runOnUiThread(() -> {
                    if (!host.isCurrent(progress) || generation != organizeGeneration) {
                        return;
                    }
                    organizing = false;
                    body = rawBody;
                    reviewNotice = host.getString(R.string.import_organize_fallback);
                    stage = Stage.REVIEW;
                    showEditor();
                });
            }
        });
    }

    private void handleRecognizeError(String reason) {
        if (appending) {
            appending = false;
            reviewNotice = reason + " 已保留原有内容。";
            stage = Stage.REVIEW;
            showEditor();
        } else {
            fail(reason);
        }
    }

    private void fail(String reason) {
        clearImage();
        stage = Stage.ERROR;
        message = reason;
        showStatus(reason, true);
    }

    private View showStatus(String status, boolean failed) {
        View page = host.showImportScreen(R.layout.screen_import_status, this::cancel);
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
        View editor = host.showImportScreen(R.layout.screen_import_review, this::cancel);
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
        TextView status = editor.findViewById(R.id.import_review_status);
        if (reviewNotice != null && !reviewNotice.isEmpty()) {
            status.setText(reviewNotice);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        bindImagePreview(editor);
        bindOrganizeActions(editor);
        bindAppendActions(editor);
        editor.findViewById(R.id.import_cancel).setOnClickListener(v -> cancel());
        editor.findViewById(R.id.import_confirm).setOnClickListener(v -> confirm(editor));
        return editor;
    }

    private void bindOrganizeActions(View editor) {
        View organizeButton = UiViews.findOptionalView(editor, "import_organize");
        View restoreButton = UiViews.findOptionalView(editor, "import_restore_raw");
        updateRestoreVisibility(restoreButton, bodyField == null ? body : bodyField.getText().toString());
        if (bodyField != null && restoreButton != null) {
            bodyField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    updateRestoreVisibility(restoreButton, s == null ? "" : s.toString());
                }
            });
        }
        if (organizeButton != null) {
            organizeButton.setOnClickListener(v -> runManualOrganize(editor, restoreButton));
        }
        if (restoreButton != null) {
            restoreButton.setOnClickListener(v -> {
                if (organizing || rawBody.isEmpty() || bodyField == null) {
                    return;
                }
                organizeGeneration++;
                body = rawBody;
                bodyField.setText(rawBody);
                bodyField.setSelection(rawBody.length());
                reviewNotice = host.getString(R.string.import_restored_raw);
                showReviewStatus(editor, reviewNotice);
                updateRestoreVisibility(restoreButton, rawBody);
            });
        }
    }

    private void runManualOrganize(View editor, View restoreButton) {
        if (organizing) {
            return;
        }
        String currentText = bodyField == null ? body : bodyField.getText().toString();
        String trimmedCurrent = ReadingArticle.trimSpace(currentText);
        Uri imageUri = multiPage ? null : image;
        if (trimmedCurrent.isEmpty() && imageUri == null) {
            reviewNotice = "请先输入或识别正文后再整理。";
            showReviewStatus(editor, reviewNotice);
            return;
        }
        SessionStore.Session account = activeSession();
        if (account == null) {
            reviewNotice = host.getString(R.string.import_organize_fallback);
            showReviewStatus(editor, reviewNotice);
            return;
        }
        if (rawBody.isEmpty() && !trimmedCurrent.isEmpty()) {
            rawBody = currentText;
        }
        String textToSend = !trimmedCurrent.isEmpty() ? currentText : rawBody;
        String targetName = target == Target.TRANSLATION ? "translation" : "reading";

        organizing = true;
        int generation = ++organizeGeneration;
        setEditorEnabled(editor, false);
        View cancelButton = editor.findViewById(R.id.import_cancel);
        if (cancelButton != null) {
            cancelButton.setEnabled(true);
        }
        showReviewStatus(editor, host.getString(R.string.import_organizing_manual));

        app.practiceRequests.execute(() -> {
            String imageBase64 = null;
            String imageMimeType = null;
            if (imageUri != null) {
                try {
                    imageBase64 = PageOcr.encodeJpegBase64(app.getContentResolver(), imageUri);
                    imageMimeType = "image/jpeg";
                } catch (IOException | RuntimeException ignored) {
                    // Fall back to text-only organization when image cannot be read.
                }
            }
            try {
                ApiClient.OrganizedArticle organized = new ApiClient(account.serverUrl())
                        .organizeArticle(account.token(), textToSend, imageBase64, imageMimeType, targetName);
                host.runOnUiThread(() -> {
                    if (!host.isCurrent(editor) || generation != organizeGeneration) {
                        return;
                    }
                    organizing = false;
                    setEditorEnabled(editor, true);
                    if (organized.body().length() <= MAX_BODY_LENGTH) {
                        body = organized.body();
                        if (bodyField != null) {
                            bodyField.setText(body);
                        }
                        if (target == Target.READING && titleField != null
                                && ReadingArticle.trimSpace(titleField.getText().toString()).isEmpty()
                                && !organized.title().isEmpty()) {
                            title = truncateTitle(organized.title());
                            titleField.setText(title);
                        }
                        reviewNotice = !organized.summary().isEmpty()
                                ? organized.summary()
                                : host.getString(R.string.import_organize_done);
                    } else {
                        reviewNotice = "整理结果超出长度限制，已保留当前正文。";
                    }
                    showReviewStatus(editor, reviewNotice);
                    updateRestoreVisibility(restoreButton,
                            bodyField == null ? body : bodyField.getText().toString());
                });
            } catch (IOException | JSONException | ApiClient.Failure | RuntimeException failure) {
                host.runOnUiThread(() -> {
                    if (!host.isCurrent(editor) || generation != organizeGeneration) {
                        return;
                    }
                    organizing = false;
                    setEditorEnabled(editor, true);
                    reviewNotice = host.getString(R.string.import_organize_fallback);
                    showReviewStatus(editor, reviewNotice);
                    updateRestoreVisibility(restoreButton,
                            bodyField == null ? body : bodyField.getText().toString());
                });
            }
        });
    }

    private void updateRestoreVisibility(View restoreButton, String currentBody) {
        if (restoreButton == null) {
            return;
        }
        boolean visible = !rawBody.isEmpty() && !rawBody.equals(currentBody == null ? "" : currentBody);
        restoreButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showReviewStatus(View editor, String text) {
        TextView status = editor.findViewById(R.id.import_review_status);
        if (status == null) {
            return;
        }
        if (text != null && !text.isEmpty()) {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
    }

    private static String truncateTitle(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = ReadingArticle.trimSpace(value);
        return trimmed.length() <= MAX_TITLE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_TITLE_LENGTH);
    }

    private void bindImagePreview(View editor) {
        View container = UiViews.findOptionalView(editor, "import_review_image_container");
        ImageView preview = UiViews.findOptionalView(editor, "import_review_image", ImageView.class);
        View toggle = UiViews.findOptionalView(editor, "import_review_toggle_image");
        if (image == null || (container == null && preview == null)) {
            if (container != null) {
                container.setVisibility(View.GONE);
            }
            if (preview != null) {
                preview.setVisibility(View.GONE);
            }
            if (toggle != null) {
                toggle.setVisibility(View.GONE);
            }
            return;
        }
        if (container != null) {
            container.setVisibility(View.VISIBLE);
        }
        if (toggle != null) {
            toggle.setVisibility(View.VISIBLE);
            Runnable updateToggle = () -> {
                if (preview != null) {
                    preview.setVisibility(imageCollapsed ? View.GONE : View.VISIBLE);
                } else if (container != null) {
                    container.setVisibility(imageCollapsed ? View.GONE : View.VISIBLE);
                }
                if (toggle instanceof TextView toggleText) {
                    toggleText.setText(imageCollapsed ? "展开原图对照" : "收起原图对照");
                }
            };
            updateToggle.run();
            toggle.setOnClickListener(v -> {
                imageCollapsed = !imageCollapsed;
                updateToggle.run();
            });
        } else if (preview != null) {
            preview.setVisibility(View.VISIBLE);
        }
        if (preview != null) {
            Uri previewUri = image;
            app.images.execute(() -> {
                try {
                    Bitmap bitmap = PageOcr.decodePreview(app.getContentResolver(), previewUri, PREVIEW_MAX_EDGE);
                    host.runOnUiThread(() -> {
                        if (host.isCurrent(editor)) {
                            preview.setImageBitmap(bitmap);
                        } else if (bitmap != null) {
                            bitmap.recycle();
                        }
                    });
                } catch (IOException | RuntimeException ignored) {
                    host.runOnUiThread(() -> {
                        if (host.isCurrent(editor)) {
                            if (container != null) {
                                container.setVisibility(View.GONE);
                            }
                            if (toggle != null) {
                                toggle.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            });
        }
    }

    private void bindAppendActions(View editor) {
        View appendCamera = UiViews.findOptionalView(editor, "import_review_append_camera");
        View appendGallery = UiViews.findOptionalView(editor, "import_review_append_gallery");
        if (appendCamera != null) {
            appendCamera.setOnClickListener(v -> launchAppend(true));
        }
        if (appendGallery != null) {
            appendGallery.setOnClickListener(v -> launchAppend(false));
        }
    }

    private void confirm(View editor) {
        if (organizing) {
            return;
        }
        organizeGeneration++;
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
        View organizeButton = UiViews.findOptionalView(editor, "import_organize");
        if (organizeButton != null) {
            organizeButton.setEnabled(enabled);
        }
        View restoreButton = UiViews.findOptionalView(editor, "import_restore_raw");
        if (restoreButton != null) {
            restoreButton.setEnabled(enabled);
        }
        View appendCamera = UiViews.findOptionalView(editor, "import_review_append_camera");
        if (appendCamera != null) {
            appendCamera.setEnabled(enabled);
        }
        View appendGallery = UiViews.findOptionalView(editor, "import_review_append_gallery");
        if (appendGallery != null) {
            appendGallery.setEnabled(enabled);
        }
    }
}

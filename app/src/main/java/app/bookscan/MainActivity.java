package app.bookscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SCAN = 101;
    private static final int SAVE = 102;
    private static final String SCAN_MODE = "scanMode";
    private static final int INK = 0xff111c20;
    private static final int SURFACE = 0xff203137;
    private static final int MINT = 0xffadf3d1;
    private static final int PAPER = 0xfff2f7f4;
    private static final int MUTED = 0xffb6c9c7;

    private final ArrayList<String> pages = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView count;
    private Button scanButton;
    private Button modeButton;
    private Button updateButton;
    private Button saveButton;
    private boolean busy;
    private boolean awaitingResult;
    private String replacingPage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        awaitingResult = state != null && state.getBoolean("awaitingResult");
        replacingPage = state == null ? null : state.getString("replacingPage");
        restorePages();
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);

        LinearLayout root = column();
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(INK);
        setContentView(root);

        TextView title = label("BookScan", 34, PAPER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        updateButton = button("Update", SURFACE, MINT);
        header.addView(updateButton, new LinearLayout.LayoutParams(dp(86), dp(48)));
        updateButton.setContentDescription("BookScan update စစ်ရန်");
        updateButton.setOnClickListener(view -> checkForUpdate());
        root.addView(header);
        TextView subtitle = label("စာအုပ်စာမျက်နှာများကို PDF အဖြစ် သိမ်းပါ", 14, MUTED);
        root.addView(subtitle);
        subtitle.setPadding(0, dp(2), 0, dp(16));

        scanButton = button("＋  စာမျက်နှာ ရိုက်မယ်", MINT, INK);
        root.addView(scanButton, new LinearLayout.LayoutParams(-1, dp(54)));
        scanButton.setOnClickListener(view -> startScan(null));

        modeButton = button("", SURFACE, MINT);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, dp(48));
        modeParams.topMargin = dp(8);
        root.addView(modeButton, modeParams);
        modeButton.setOnClickListener(view -> chooseScanMode(null, false));
        updateModeLabel();

        count = label("", 16, PAPER);
        count.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        count.setPadding(0, dp(16), 0, dp(8));
        root.addView(count);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = column();
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        saveButton = button("PDF သိမ်းမယ်", MINT, INK);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(54));
        saveParams.topMargin = dp(10);
        root.addView(saveButton, saveParams);
        saveButton.setOnClickListener(view -> choosePdfLocation());

        renderPages();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("awaitingResult", awaitingResult);
        out.putString("replacingPage", replacingPage);
        super.onSaveInstanceState(out);
    }

    private void startScan(String replacement) {
        if (busy || awaitingResult) return;
        int mode = getPreferences(MODE_PRIVATE).getInt(SCAN_MODE, 0);
        if (mode == 0) {
            chooseScanMode(replacement, true);
        } else {
            scan(mode, replacement);
        }
    }

    private void updateModeLabel() {
        int mode = getPreferences(MODE_PRIVATE).getInt(SCAN_MODE, 0);
        if (mode == GmsDocumentScannerOptions.SCANNER_MODE_BASE) {
            modeButton.setText("မူရင်းအရောင်  ▾");
        } else if (mode == GmsDocumentScannerOptions.SCANNER_MODE_FULL) {
            modeButton.setText("အလိုအလျောက် သန့်စင်  ▾");
        } else {
            modeButton.setText("ရိုက်ကူးမှုပုံစံ ရွေးမယ်  ▾");
        }
    }

    private void checkForUpdate() {
        setBusy(true, "Update စစ်နေသည်…");
        worker.execute(() -> {
            try {
                BookScanUpdates.Release release = BookScanUpdates.findNewer(BuildConfig.VERSION_CODE);
                runOnUiThread(() -> {
                    setBusy(false, null);
                    if (isFinishing() || isDestroyed()) return;
                    if (release == null) {
                        new AlertDialog.Builder(this)
                                .setMessage("BookScan " + BuildConfig.VERSION_NAME + " အတွက် update အသစ် မရှိသေးပါ။")
                                .setPositiveButton("ပိတ်မယ်", null).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("BookScan " + release.tag + " ရပါပြီ")
                            .setMessage("APK ကို ဒေါင်းလုဒ်လုပ်ပြီး ဖွင့်ပါ။ Android မှာ Update နှိပ်ပါ။")
                            .setPositiveButton("ဒေါင်းလုဒ်", (dialog, which) -> {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW, release.apk));
                                } catch (RuntimeException error) {
                                    showError("ဒေါင်းလုဒ် လင့်ခ် ဖွင့်၍မရပါ။", error);
                                }
                            })
                            .setNegativeButton("နောက်မှ", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showError("Update စစ်၍မရပါ။ အင်တာနက်ကို စစ်ပြီး ပြန်စမ်းပါ။", error));
            }
        });
    }

    private void selectMode(int mode, String replacement, boolean openCamera) {
        getPreferences(MODE_PRIVATE).edit().putInt(SCAN_MODE, mode).apply();
        updateModeLabel();
        if (openCamera) scan(mode, replacement);
    }

    private void chooseScanMode(String replacement, boolean openCamera) {
        if (busy || awaitingResult) return;
        new AlertDialog.Builder(this)
                .setTitle(replacement == null ? "ရိုက်ကူးမှု ပုံစံ" : "ဤစာမျက်နှာကို ပြန်ရိုက်မယ်")
                .setMessage("စာချုပ်၊ တံဆိပ်နဲ့ ရောင်စုံဓာတ်ပုံအတွက် မူရင်းအရောင်ကို သုံးပါ။ မသိမ်းမီ Crop & rotate မှာ စာရွက်ထောင့် ၄ ခုကို ညှိနိုင်ပါတယ်။")
                .setPositiveButton("မူရင်းအရောင်", (dialog, which) ->
                        selectMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE, replacement, openCamera))
                .setNeutralButton("အလိုအလျောက် သန့်စင်", (dialog, which) ->
                        selectMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL, replacement, openCamera))
                .setNegativeButton("ပိတ်မယ်", null)
                .show();
    }

    private void scan(int mode, String replacement) {
        replacingPage = replacement;
        setBusy(true, "ကင်မရာ ဖွင့်နေသည်…");
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(mode)
                .build();
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener(sender -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        awaitingResult = true;
                        startIntentSenderForResult(sender, SCAN, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | RuntimeException error) {
                        awaitingResult = false;
                        showError("ကင်မရာ ဖွင့်၍မရပါ။ ထပ်စမ်းကြည့်ပါ။", error);
                    }
                })
                .addOnFailureListener(error -> showError(
                        "Scanner ဖွင့်၍မရပါ။ Google Play Services နှင့် အင်တာနက်ကို စစ်ဆေးပြီး ထပ်စမ်းပါ။",
                        error));
    }

    private void choosePdfLocation() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "BookScan-" + new SimpleDateFormat(
                        "yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".pdf");
        try {
            awaitingResult = true;
            startActivityForResult(intent, SAVE);
            setBusy(false, null);
        } catch (RuntimeException error) {
            awaitingResult = false;
            showError("PDF သိမ်းရန် ဖိုင်ရွေးချယ်မှု ဖွင့်၍မရပါ။", error);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCAN && request != SAVE) return;
        awaitingResult = false;
        String replacement = replacingPage;
        replacingPage = null;
        if (result != RESULT_OK || data == null) {
            setBusy(false, null);
            return;
        }
        if (request == SCAN) {
            GmsDocumentScanningResult scan = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (scan == null || scan.getPages() == null || scan.getPages().isEmpty()) {
                setBusy(false, null);
                return;
            }
            importPages(scan.getPages(), replacement);
        } else if (data.getData() != null) {
            exportPdf(data.getData());
        } else {
            setBusy(false, null);
        }
    }

    private void importPages(List<GmsDocumentScanningResult.Page> scanned, String replacement) {
        setBusy(true, "စာမျက်နှာများ သိမ်းနေသည်…");
        worker.execute(() -> {
            ArrayList<String> imported = new ArrayList<>();
            try {
                for (GmsDocumentScanningResult.Page page : scanned) {
                    String name = UUID.randomUUID() + ".jpg";
                    imported.add(name);
                    try (InputStream in = getContentResolver().openInputStream(page.getImageUri());
                         OutputStream out = new FileOutputStream(pageFile(name))) {
                        if (in == null) throw new IOException("Missing scan image");
                        byte[] buffer = new byte[65536];
                        int length;
                        while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                    }
                }
                runOnUiThread(() -> {
                    ArrayList<String> updated = new ArrayList<>(pages);
                    int replaceAt = replacement == null ? -1 : updated.indexOf(replacement);
                    if (replacement == null) {
                        updated.addAll(imported);
                    } else if (replaceAt >= 0 && imported.size() == 1) {
                        updated.set(replaceAt, imported.get(0));
                    } else {
                        for (String name : imported) pageFile(name).delete();
                        showError("စာမျက်နှာ အစားထိုး၍မရပါ။", new IOException("Unexpected replacement scan"));
                        return;
                    }
                    boolean saved = storePages(updated);
                    if (saved) {
                        if (replacement != null) pageFile(replacement).delete();
                    } else {
                        for (String name : imported) pageFile(name).delete();
                    }
                    setBusy(false, null);
                    if (saved && replacement == null && !isFinishing() && !isDestroyed()) {
                        startScan(null);
                    }
                });
            } catch (IOException | RuntimeException error) {
                for (String name : imported) pageFile(name).delete();
                runOnUiThread(() -> showError("စာမျက်နှာများ သိမ်း၍မရပါ။", error));
            }
        });
    }

    private void exportPdf(Uri destination) {
        ArrayList<String> ordered = new ArrayList<>(pages);
        setBusy(true, "PDF ထုတ်နေသည်…");
        worker.execute(() -> {
            File temporary = null;
            try {
                temporary = File.createTempFile("bookscan-", ".pdf", getCacheDir());
                writePdf(ordered, temporary);
                try (InputStream in = new FileInputStream(temporary);
                     OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("Missing PDF destination");
                    byte[] buffer = new byte[65536];
                    int length;
                    while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                }
                runOnUiThread(() -> {
                    setBusy(false, null);
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this).setMessage("PDF သိမ်းပြီးပါပြီ။")
                            .setPositiveButton("PDF ဖွင့်မယ်", (dialog, which) -> openPdf(destination))
                            .setNegativeButton("ပိတ်မယ်", null).show();
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showError("PDF သိမ်း၍မရပါ။", error));
            } finally {
                if (temporary != null) temporary.delete();
            }
        });
    }

    private void writePdf(List<String> ordered, File destination) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            for (int index = 0; index < ordered.size(); index++) {
                Bitmap bitmap = loadBitmap(ordered.get(index), 2400);
                if (bitmap == null) throw new IOException("Missing scan image");
                boolean landscape = bitmap.getWidth() > bitmap.getHeight();
                int width = landscape ? 842 : 595;
                int height = landscape ? 595 : 842;
                PdfDocument.Page page = document.startPage(
                        new PdfDocument.PageInfo.Builder(width, height, index + 1).create());
                float scale = Math.min((width - 24f) / bitmap.getWidth(),
                        (height - 24f) / bitmap.getHeight());
                float w = bitmap.getWidth() * scale;
                float h = bitmap.getHeight() * scale;
                try {
                    page.getCanvas().drawColor(Color.WHITE);
                    page.getCanvas().drawBitmap(bitmap, null, new RectF(
                            (width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint);
                } finally {
                    document.finishPage(page);
                    bitmap.recycle();
                }
            }
            try (OutputStream out = new FileOutputStream(destination)) {
                document.writeTo(out);
            }
        } finally {
            document.close();
        }
    }

    private void openPdf(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "PDF ကို Files app မှာ ရှာနိုင်ပါတယ်။", Toast.LENGTH_LONG).show();
        }
    }

    private void renderPages() {
        list.removeAllViews();
        if (pages.isEmpty()) {
            LinearLayout empty = column();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(30), dp(24), dp(30));
            empty.setBackground(panel(SURFACE, 20));
            TextView headline = label("ပထမဆုံး စာမျက်နှာကို ရိုက်ပါ", 18, PAPER);
            headline.setGravity(Gravity.CENTER);
            empty.addView(headline);
            TextView detail = label("စာရွက်ထောင့် ၄ ခုကို ဘောင်ထဲ ထည့်ရိုက်ပါ။ Next နှိပ်တိုင်း စာမျက်နှာသိမ်းပြီး ကင်မရာ ပြန်ဖွင့်ပါမယ်။ ရိုက်ပြီးရင် ကင်မရာကို ပိတ်ပါ။", 14, MUTED);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(8), 0, 0);
            empty.addView(detail);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        }
        for (int index = 0; index < pages.size(); index++) addPageRow(index);
        setBusy(busy, null);
    }

    private void addPageRow(int index) {
        String name = pages.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackground(panel(SURFACE, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(86));
        params.bottomMargin = dp(8);
        list.addView(row, params);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnail.setImageBitmap(loadBitmap(name, 192));
        thumbnail.setContentDescription("စာမျက်နှာ " + (index + 1) + " အကြိုကြည့်ရန်");
        thumbnail.setOnClickListener(view -> preview(name, index));
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(52), -1));

        TextView number = label("စာမျက်နှာ " + (index + 1), 14, PAPER);
        number.setPadding(dp(10), 0, 0, 0);
        number.setOnClickListener(view -> preview(name, index));
        row.addView(number, new LinearLayout.LayoutParams(0, -2, 1));
        addAction(row, "↑", "အပေါ်ရွှေ့ရန်", index > 0, () -> movePage(index, index - 1));
        addAction(row, "↓", "အောက်ရွှေ့ရန်", index + 1 < pages.size(), () -> movePage(index, index + 1));
        addAction(row, "×", "ဖျက်ရန်", true, () -> new AlertDialog.Builder(this)
                .setMessage("စာမျက်နှာ " + (index + 1) + " ကို ဖျက်မလား။")
                .setNegativeButton("မဖျက်ပါ", null)
                .setPositiveButton("ဖျက်မယ်", (dialog, which) -> deletePage(index)).show());
    }

    private void addAction(LinearLayout row, String text, String description, boolean enabled, Runnable action) {
        Button button = button(text, SURFACE, MINT);
        button.setTextSize(20);
        button.setContentDescription(description);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
        button.setOnClickListener(view -> {
            if (!busy && !awaitingResult) action.run();
        });
        row.addView(button, new LinearLayout.LayoutParams(dp(44), dp(48)));
    }

    private void preview(String name, int index) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(getResources().getDisplayMetrics().heightPixels * 2 / 3);
        image.setImageBitmap(loadBitmap(name, 1400));
        new AlertDialog.Builder(this).setTitle("စာမျက်နှာ " + (index + 1))
                .setView(image)
                .setPositiveButton("ပြန်ရိုက်မယ်", (dialog, which) -> startScan(name))
                .setNeutralButton("အနားဖြတ်မယ်", (dialog, which) -> editPageEdges(name))
                .setNegativeButton("ပိတ်မယ်", null).show();
    }

    private void editPageEdges(String name) {
        if (busy || awaitingResult) return;
        Bitmap preview = loadBitmap(name, 1400);
        if (preview == null) {
            showError("စာမျက်နှာ ဖွင့်၍မရပါ။", new IOException("Missing scan"));
            return;
        }
        PageCropView crop = new PageCropView(this, preview);
        LinearLayout content = column();
        TextView help = label("ထောင့် ၄ ခုကို စက္ကူအဖြူဘက် အနည်းငယ်အတွင်း ဆွဲညှိပါ။", 14, PAPER);
        help.setPadding(dp(16), 0, dp(16), dp(8));
        content.addView(help);
        int height = getResources().getDisplayMetrics().heightPixels / 2;
        content.addView(crop, new LinearLayout.LayoutParams(-1, height));
        new AlertDialog.Builder(this).setTitle("စာရွက်အနား ဖြတ်မယ်")
                .setView(content)
                .setNegativeButton("မဖြတ်ပါ", null)
                .setPositiveButton("ဖြတ်ပြီး သိမ်းမယ်", (dialog, which) -> cropPage(name, crop.selection()))
                .show();
    }

    private void cropPage(String name, float[] selection) {
        setBusy(true, "စာရွက်အနား ဖြတ်နေသည်…");
        worker.execute(() -> {
            String croppedName = UUID.randomUUID() + ".jpg";
            File croppedFile = pageFile(croppedName);
            Bitmap source = null;
            Bitmap cropped = null;
            try {
                source = loadBitmap(name, 4200);
                if (source == null) throw new IOException("Missing scan");
                cropped = PageCropView.crop(source, selection);
                try (OutputStream output = new FileOutputStream(croppedFile)) {
                    if (!cropped.compress(Bitmap.CompressFormat.JPEG, 97, output)) {
                        throw new IOException("Could not encode cropped scan");
                    }
                }
                runOnUiThread(() -> {
                    ArrayList<String> updated = new ArrayList<>(pages);
                    int index = updated.indexOf(name);
                    if (index >= 0) {
                        updated.set(index, croppedName);
                        if (storePages(updated)) {
                            pageFile(name).delete();
                            setBusy(false, null);
                            return;
                        }
                    }
                    croppedFile.delete();
                    setBusy(false, null);
                });
            } catch (IOException | RuntimeException error) {
                croppedFile.delete();
                runOnUiThread(() -> showError("စာရွက်အနား ဖြတ်၍မရပါ။", error));
            } finally {
                if (source != null) source.recycle();
                if (cropped != null) cropped.recycle();
            }
        });
    }

    private void movePage(int from, int to) {
        ArrayList<String> updated = new ArrayList<>(pages);
        Collections.swap(updated, from, to);
        storePages(updated);
    }

    private void deletePage(int index) {
        ArrayList<String> updated = new ArrayList<>(pages);
        String name = updated.remove(index);
        if (storePages(updated)) pageFile(name).delete();
    }

    private File pageFile(String name) {
        return new File(getFilesDir(), name);
    }

    private Bitmap loadBitmap(String name, int limit) {
        String path = pageFile(name).getAbsolutePath();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > limit) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(path, options);
    }

    private void restorePages() {
        try {
            JSONArray saved = new JSONArray(getPreferences(MODE_PRIVATE).getString("pages", "[]"));
            for (int index = 0; index < saved.length(); index++) {
                String name = saved.getString(index);
                if (pageFile(name).isFile()) pages.add(name);
            }
        } catch (org.json.JSONException error) {
            android.util.Log.e("BookScan", "Unreadable page list", error);
        }
    }

    private boolean storePages(List<String> updated) {
        if (!getPreferences(MODE_PRIVATE).edit().putString("pages", new JSONArray(updated).toString()).commit()) {
            Toast.makeText(this, "စာမျက်နှာစာရင်း သိမ်း၍မရပါ။", Toast.LENGTH_LONG).show();
            return false;
        }
        pages.clear();
        pages.addAll(updated);
        renderPages();
        return true;
    }

    private void setBusy(boolean working, String message) {
        busy = working;
        scanButton.setEnabled(!busy && !awaitingResult);
        modeButton.setEnabled(!busy && !awaitingResult);
        updateButton.setEnabled(!busy && !awaitingResult);
        saveButton.setEnabled(!busy && !awaitingResult && !pages.isEmpty());
        scanButton.setAlpha(scanButton.isEnabled() ? 1f : 0.45f);
        saveButton.setAlpha(saveButton.isEnabled() ? 1f : 0.45f);
        count.setText(message != null ? message : "စာမျက်နှာများ  (" + pages.size() + ")");
    }

    private void showError(String message, Exception error) {
        android.util.Log.e("BookScan", message, error);
        if (isFinishing() || isDestroyed()) return;
        setBusy(false, null);
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("ပိတ်မယ်", null).show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, int background, int foreground) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextColor(foreground);
        view.setTextSize(15);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x40000000), panel(background, 14), null));
        return view;
    }

    private GradientDrawable panel(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}

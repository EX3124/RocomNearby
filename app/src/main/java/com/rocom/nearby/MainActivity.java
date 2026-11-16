package com.rocom.nearby;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.rocom.nearby.capture.CaptureRuntime;
import com.rocom.nearby.capture.PcapCaptureService;
import com.rocom.nearby.capture.PcapControl;
import com.rocom.nearby.overlay.NearbyOverlay;

import java.util.regex.Pattern;

/**
 * Standalone nearby screen. Its first milestone is the capture source: request
 * PCAPdroid's control consent, receive the target app's pcap stream and make the
 * editable target package an explicit part of the UI.
 */
public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "nearby";
    private static final String KEY_TARGET = "target_package";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private enum Phase {
        IDLE,
        PREPARING,
        REQUESTING,
        CAPTURING,
        STOPPING,
        ERROR
    }

    private View root;
    private View emptyCard;
    private View actionRow;
    private ViewGroup contentContainer;
    private View targetRow;
    private TextView targetPrefix;
    private TextView cardTitle;
    private TextView sceneSubtitle;
    private View floatButton;
    private TextView floatButtonLabel;
    private ImageView compassIcon;
    private TextInputLayout targetInputLayout;
    private TextInputEditText targetInput;
    private Button actionButton;
    private Button stopButton;

    private SharedPreferences preferences;
    private String defaultTarget;
    private String lastValidTarget;
    private Phase phase = Phase.IDLE;
    private CaptureRuntime.Snapshot snapshot = CaptureRuntime.current();
    private boolean authorized;
    private boolean controlRequested;
    private int localPort;
    /**
     * Scene resource id reported by the nearby tracker. 0 means "not known yet";
     * the subtitle stays blank until a real id arrives.
     */
    private int sceneId;

    private ActivityResultLauncher<Intent> captureStartLauncher;
    private ActivityResultLauncher<Intent> captureStopLauncher;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final NearbyOverlay.Callback overlayCallback = this::refreshFloatButton;

    private final CaptureRuntime.Listener captureListener = new CaptureRuntime.Listener() {
        @Override
        public void onCaptureSnapshot(CaptureRuntime.Snapshot newSnapshot) {
            applySnapshot(newSnapshot);
        }

        @Override
        public void onServerReady(int port) {
            if (phase != Phase.PREPARING) {
                return;
            }
            mainHandler.removeCallbacks(serviceStartTimeout);
            localPort = port;
            phase = Phase.REQUESTING;
            render();
            launchPcapdroidControlRequest();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.main);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        applyWindowInsets();
        setupActivityResults();
        setupTargetEditor();
        setupActions();
        updateSceneSubtitle();

        defaultTarget = getString(R.string.target_default);
        lastValidTarget = preferences.getString(KEY_TARGET, defaultTarget);
        if (!isValidPackage(lastValidTarget)) {
            lastValidTarget = defaultTarget;
        }
        targetInput.setText(lastValidTarget);
        targetInput.clearFocus();
        resetTargetInputPadding();

        snapshot = CaptureRuntime.current();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CaptureRuntime.setListener(captureListener);
        NearbyOverlay.setCallback(overlayCallback);
        applySnapshot(CaptureRuntime.current());
        requestControlIfServerAlreadyWaiting();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFloatButton();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(serviceStartTimeout);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        CaptureRuntime.clearListener(captureListener);
        NearbyOverlay.clearCallback(overlayCallback);
        if (targetInput.hasFocus()) {
            commitTarget(false);
            targetInput.clearFocus();
        }
        hideKeyboard();
        super.onStop();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        emptyCard = findViewById(R.id.empty_card);
        actionRow = findViewById(R.id.action_row);
        contentContainer = findViewById(R.id.content_container);
        targetRow = findViewById(R.id.target_row);
        targetPrefix = findViewById(R.id.target_prefix);
        cardTitle = findViewById(R.id.card_title);
        sceneSubtitle = findViewById(R.id.scene_subtitle);
        floatButton = findViewById(R.id.float_button);
        floatButtonLabel = findViewById(R.id.float_button_label);
        compassIcon = findViewById(R.id.compass_icon);
        targetInputLayout = findViewById(R.id.target_input_layout);
        targetInput = findViewById(R.id.target_input);
        actionButton = findViewById(R.id.action_button);
        stopButton = findViewById(R.id.stop_button);
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right,
                    Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean dark = nightMode == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    private void setupActivityResults() {
        captureStartLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        authorized = true;
                        if (phase == Phase.REQUESTING || phase == Phase.PREPARING) {
                            phase = Phase.REQUESTING;
                        }
                        render();
                    } else {
                        authorized = false;
                        phase = Phase.ERROR;
                        render();
                        stopLocalReceiver();
                    }
                });

        captureStopLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    authorized = false;
                    if (phase == Phase.STOPPING) {
                        render();
                    }
                });
    }

    private void setupTargetEditor() {
        View.OnClickListener focusTarget = view -> focusTargetInput();
        targetRow.setOnClickListener(focusTarget);
        targetPrefix.setOnClickListener(focusTarget);

        targetInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                commitTarget(false);
            }
        });

        targetInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (commitTarget(true)) {
                    hideKeyboard();
                    targetInput.clearFocus();
                    render();
                }
                return true;
            }
            return false;
        });
    }

    private void setupActions() {
        actionButton.setOnClickListener(view -> {
            if (phase == Phase.CAPTURING || phase == Phase.REQUESTING
                    || phase == Phase.STOPPING) {
                stopCaptureFlow();
            } else if (phase == Phase.IDLE || phase == Phase.ERROR) {
                startCaptureFlow();
            }
        });

        floatButton.setOnClickListener(view -> toggleFloatWindow());

        stopButton.setOnClickListener(view -> {
            if (isCaptureActive()) {
                stopCaptureFlow();
            }
        });

        contentContainer.setOnHierarchyChangeListener(
                new ViewGroup.OnHierarchyChangeListener() {
                    @Override
                    public void onChildViewAdded(View parent, View child) {
                        updateEmptyCardVisibility();
                    }

                    @Override
                    public void onChildViewRemoved(View parent, View child) {
                        updateEmptyCardVisibility();
                    }
                });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (targetInput.hasFocus()) {
                    targetInput.clearFocus();
                    hideKeyboard();
                    return;
                }
                if (isCaptureActive()) {
                    moveTaskToBack(true);
                } else {
                    finish();
                }
            }
        });
    }

    /**
     * Feeds the scene id once the nearby tracker can parse it out of the pcap.
     * The header shows the original app's {@code 场景 <id>} form, and keeps its
     * {@code 当前场景} fallback while the id is still unknown.
     */
    public void setSceneId(int newSceneId) {
        int normalized = Math.max(0, newSceneId);
        if (sceneId == normalized) {
            return;
        }
        sceneId = normalized;
        updateSceneSubtitle();
    }

    private void updateSceneSubtitle() {
        if (sceneSubtitle == null) {
            return;
        }
        sceneSubtitle.setText(sceneId > 0
                ? getString(R.string.sub_title, sceneId)
                : "");
    }

    private void toggleFloatWindow() {
        if (NearbyOverlay.isShowing()) {
            NearbyOverlay.dismiss();
            refreshFloatButton();
            return;
        }
        if (!canDrawOverlays()) {
            showOverlayPermissionDialog();
            return;
        }
        NearbyOverlay.show(this);
        refreshFloatButton();
    }

    private void refreshFloatButton() {
        if (floatButtonLabel == null) {
            return;
        }
        int label = NearbyOverlay.isShowing()
                ? R.string.float_window_close
                : R.string.float_window;
        floatButtonLabel.setText(label);
        floatButton.setContentDescription(getString(label));
    }

    private boolean canDrawOverlays() {
        try {
            return PermissionUtils.checkPermission(this);
        } catch (Throwable e) {
            return Settings.canDrawOverlays(this);
        }
    }

    private void showOverlayPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_permission_title)
                .setMessage(R.string.dialog_permission_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_permission, (dialog, which) -> {
                    // EasyFloat checks and requests the overlay permission itself.
                    NearbyOverlay.show(MainActivity.this);
                    refreshFloatButton();
                })
                .show();
    }

    private void startCaptureFlow() {
        if (phase == Phase.PREPARING || phase == Phase.REQUESTING
                || phase == Phase.CAPTURING || phase == Phase.STOPPING) {
            return;
        }
        if (!commitTarget(true)) {
            return;
        }
        beginStartCapture();
    }

    private void beginStartCapture() {
        if (phase == Phase.PREPARING || phase == Phase.REQUESTING
                || phase == Phase.CAPTURING || phase == Phase.STOPPING) {
            return;
        }
        hideKeyboard();
        authorized = false;
        controlRequested = false;
        localPort = 0;
        phase = Phase.PREPARING;
        render();

        try {
            Intent serviceIntent = new Intent(this, PcapCaptureService.class)
                    .setAction(PcapCaptureService.ACTION_START);
            startService(serviceIntent);
            mainHandler.removeCallbacks(serviceStartTimeout);
            mainHandler.postDelayed(serviceStartTimeout, 4000);
        } catch (Throwable e) {
            phase = Phase.ERROR;
            render();
        }
    }

    private final Runnable serviceStartTimeout = () -> {
        if (phase == Phase.PREPARING && !controlRequested) {
            phase = Phase.ERROR;
            render();
            stopLocalReceiver();
        }
    };

    private void launchPcapdroidControlRequest() {
        if (controlRequested) {
            return;
        }
        controlRequested = true;
        try {
            captureStartLauncher.launch(
                    PcapControl.buildStartIntent(lastValidTarget, localPort));
        } catch (Throwable e) {
            controlRequested = false;
            phase = Phase.ERROR;
            render();
            stopLocalReceiver();
            showMissingPcapdroidDialog();
        }
    }

    private void stopCaptureFlow() {
        if (phase == Phase.STOPPING) {
            return;
        }
        phase = Phase.STOPPING;
        render();

        if (authorized) {
            try {
                captureStopLauncher.launch(PcapControl.buildStopIntent());
            } catch (Throwable ignored) {
                // The local receiver still needs to be closed.
            }
            authorized = false;
        }
        mainHandler.postDelayed(this::stopLocalReceiver, 700);
    }

    private void stopLocalReceiver() {
        try {
            Intent stop = new Intent(this, PcapCaptureService.class)
                    .setAction(PcapCaptureService.ACTION_STOP);
            startService(stop);
        } catch (Throwable ignored) {
            // Already stopped.
        }
    }

    private void requestControlIfServerAlreadyWaiting() {
        if (phase == Phase.PREPARING && !controlRequested
                && snapshot.state == CaptureRuntime.State.LISTENING
                && snapshot.port > 0) {
            localPort = snapshot.port;
            phase = Phase.REQUESTING;
            render();
            launchPcapdroidControlRequest();
        }
    }

    private void applySnapshot(CaptureRuntime.Snapshot newSnapshot) {
        if (newSnapshot == null) {
            return;
        }
        snapshot = newSnapshot;
        switch (newSnapshot.state) {
            case CAPTURING:
                phase = Phase.CAPTURING;
                authorized = true;
                break;
            case LISTENING:
                if (newSnapshot.bytes > 0) {
                    // The receiver already has data; we are just between
                    // reconnects, not waiting for the initial consent.
                    phase = Phase.CAPTURING;
                    authorized = true;
                } else if (phase == Phase.IDLE) {
                    // The activity was recreated while the capture service was
                    // already listening. Let onStart resume the control request.
                    phase = Phase.PREPARING;
                    localPort = newSnapshot.port;
                }
                break;
            case ERROR:
                phase = Phase.ERROR;
                authorized = false;
                break;
            case IDLE:
                if (phase != Phase.ERROR) {
                    phase = Phase.IDLE;
                }
                authorized = false;
                break;
            case STARTING:
                if (phase == Phase.IDLE) {
                    phase = Phase.PREPARING;
                }
                break;
            default:
                break;
        }
        render();
    }

    private void render() {
        boolean captureActive = isCaptureActive();
        boolean canEditTarget = phase == Phase.IDLE || phase == Phase.ERROR;
        targetInput.setEnabled(canEditTarget);
        targetRow.setClickable(canEditTarget);
        targetRow.setAlpha(canEditTarget ? 1f : 0.7f);
        compassIcon.setAlpha(captureActive ? 0.8f : 0.5f);

        String title;
        String button;
        boolean buttonEnabled = true;

        switch (phase) {
            case PREPARING:
                title = getString(R.string.title_requesting);
                button = getString(R.string.action_stop);
                buttonEnabled = false;
                break;
            case REQUESTING:
                title = getString(authorized
                        ? R.string.title_capturing
                        : R.string.title_requesting);
                button = getString(R.string.action_stop);
                break;
            case CAPTURING:
                title = getString(R.string.title_capturing);
                button = getString(R.string.action_stop);
                break;
            case STOPPING:
                title = getString(R.string.title_stopping);
                button = getString(R.string.action_stop);
                buttonEnabled = false;
                break;
            case ERROR:
                title = getString(R.string.title_error);
                button = getString(R.string.action_retry);
                break;
            case IDLE:
            default:
                title = getString(R.string.title_default);
                button = getString(R.string.action_start);
                break;
        }

        cardTitle.setText(title);
        actionButton.setText(button);
        actionButton.setEnabled(buttonEnabled);
        actionButton.setAlpha(buttonEnabled ? 1f : 0.65f);
        updateEmptyCardVisibility();
    }

    private void updateEmptyCardVisibility() {
        if (emptyCard == null || contentContainer == null) {
            return;
        }
        boolean hasContent = contentContainer.getChildCount() > 0;
        emptyCard.setVisibility(hasContent ? View.GONE : View.VISIBLE);
        if (actionRow != null) {
            actionRow.setVisibility(hasContent ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private boolean isCaptureActive() {
        return phase == Phase.PREPARING || phase == Phase.REQUESTING
                || phase == Phase.CAPTURING || phase == Phase.STOPPING;
    }

    private void focusTargetInput() {
        if (!targetInput.isEnabled()) {
            return;
        }
        targetInput.requestFocus();
        targetInput.setSelection(targetInput.getText() != null
                ? targetInput.getText().length() : 0);
        resetTargetInputPadding();
        targetInput.post(() -> {
            resetTargetInputPadding();
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null && targetInput.hasFocus()) {
                manager.showSoftInput(targetInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /**
     * TextInputLayout still applies the filled-box side padding once, even when
     * boxBackgroundMode is none. The target line must stay compact, so clear the
     * padding which Material injected into the edit text.
     */
    private void resetTargetInputPadding() {
        if (targetInput != null) {
            targetInput.setPadding(0, 0, 0, 0);
        }
        if (targetInputLayout != null) {
            targetInputLayout.setPadding(0, 0, 0, 0);
        }
    }

    private boolean commitTarget(boolean showError) {
        String value = targetInput.getText() != null
                ? targetInput.getText().toString().trim() : "";
        if (TextUtils.isEmpty(value)) {
            // An empty target silently falls back to the default package.
            value = defaultTarget;
            targetInput.setText(value);
            targetInput.setSelection(value.length());
        }
        if (!isValidPackage(value)) {
            if (showError) {
                focusTargetInput();
            } else {
                targetInput.setText(lastValidTarget);
                targetInput.setSelection(lastValidTarget.length());
            }
            return false;
        }
        lastValidTarget = value;
        if (!value.equals(targetInput.getText().toString())) {
            targetInput.setText(value);
        }
        preferences.edit().putString(KEY_TARGET, value).apply();
        return true;
    }

    private static boolean isValidPackage(String value) {
        return !TextUtils.isEmpty(value)
                && value.length() <= 150
                && PACKAGE_PATTERN.matcher(value).matches();
    }

    private void showMissingPcapdroidDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_missing_title)
                .setMessage(getString(R.string.dialog_missing_message,
                        getString(R.string.app_name)))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_install,
                        (dialog, which) -> openPcapdroidStore())
                .show();
    }

    private void openPcapdroidStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.emanuelef.remote_capture"))
                    .setPackage("com.android.vending"));
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/emanuele-f/PCAPdroid/releases")));
            } catch (Throwable ignored) {
                // Neither a market nor a browser can handle the intent.
            }
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

}

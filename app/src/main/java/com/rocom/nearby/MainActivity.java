package com.rocom.nearby;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.lzf.easyfloat.interfaces.OnPermissionResult;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.rocom.nearby.capture.CaptureNotification;
import com.rocom.nearby.capture.NearbyAlertNotification;
import com.rocom.nearby.capture.CaptureRuntime;
import com.rocom.nearby.capture.CaptureTarget;
import com.rocom.nearby.capture.PcapCaptureService;
import com.rocom.nearby.capture.PcapControl;
import com.rocom.nearby.entity.NearbyEntity;
import com.rocom.nearby.entity.NearbyListView;
import com.rocom.nearby.map.MapCardView;
import com.rocom.nearby.map.MapCatalog;
import com.rocom.nearby.map.MapPaint;
import com.rocom.nearby.map.NearbyRuntime;
import com.rocom.nearby.overlay.NearbyOverlay;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = CaptureTarget.PREFS_NAME;
    private static final int REQUEST_POST_NOTIFICATIONS = 0x4e01;
    private static final String KEY_TARGET = CaptureTarget.KEY_TARGET;
    private static final String STATE_MAP_VISIBLE = "map_visible";
    private static final String STATE_MAP_FOLLOWING = "map_following";
    private static final String STATE_MAP_CENTER_X = "map_center_x";
    private static final String STATE_MAP_CENTER_Y = "map_center_y";
    private static final String STATE_MAP_SPAN = "map_span";
    public static final String EXTRA_OPEN_MAP = "com.rocom.nearby.extra.OPEN_MAP";
    public static final String EXTRA_TRACK_ENTITY = "com.rocom.nearby.extra.TRACK_ENTITY";
    
    private enum Phase {
        IDLE,
        PREPARING,
        REQUESTING,
        CAPTURING,
        STOPPING,
        ERROR
    }

    private View emptyCard;
    private View actionRow;
    private NestedScrollView scrollView;
    private View contentContainer;
    private View targetRow;
    private TextView cardTitle;
    private TextView sceneSubtitle;
    private View overlayButton;
    private ImageView compassIcon;
    private TextInputLayout targetInputLayout;
    private TextInputEditText targetInput;
    private Button actionButton;
    private Button stopButton;
    private Button mapButton;
    private MapCardView mapCard;
    private NearbyListView nearbyList;
    private final NearbyListView.OnEntityClickListener trackEntityListener =
            this::onEntityCardTapped;
    private SharedPreferences preferences;
    private String defaultTarget;
    private String lastValidTarget;
    private CaptureTarget captureTarget;
    private Phase phase = Phase.IDLE;
    private CaptureRuntime.Snapshot snapshot = CaptureRuntime.current();
    private NearbyRuntime.Snapshot nearby = NearbyRuntime.current();
    private boolean authorized;
    private boolean controlRequested;
    private int localPort;
    private int sceneId;
    private boolean mapVisible;
    private boolean mapFollowing = true;
    private double mapCenterX = Double.NaN;
    private double mapCenterY = Double.NaN;
    private double mapSpan = Double.NaN;
    private boolean mapViewportRestored;
    private boolean pendingMapExpand;
    private String pendingTrackEntityKey;
    private ActivityResultLauncher<Intent> captureStartLauncher;
    private ActivityResultLauncher<Intent> captureStopLauncher;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final NearbyRuntime.Listener nearbyListener = this::applyNearbySnapshot;

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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        CaptureNotification.createChannel(this);
        NearbyAlertNotification.createChannel(this);
        if (savedInstanceState != null) {
            mapVisible = savedInstanceState.getBoolean(STATE_MAP_VISIBLE, false);
            mapFollowing = savedInstanceState.getBoolean(STATE_MAP_FOLLOWING, true);
            mapCenterX = savedInstanceState.getDouble(STATE_MAP_CENTER_X, Double.NaN);
            mapCenterY = savedInstanceState.getDouble(STATE_MAP_CENTER_Y, Double.NaN);
            mapSpan = savedInstanceState.getDouble(STATE_MAP_SPAN, Double.NaN);
        }
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        setupActivityResults();
        setupTargetEditor();
        setupActions();
        requestNotificationPermissionIfNeeded();
        updateSceneSubtitle();
        defaultTarget = getString(R.string.target_default);
        lastValidTarget = preferences.getString(KEY_TARGET, defaultTarget);
        if (!CaptureTarget.isValid(lastValidTarget)) {
            lastValidTarget = defaultTarget;
        }
        captureTarget = CaptureTarget.parse(lastValidTarget);
        targetInput.setText(lastValidTarget);
        targetInput.clearFocus();
        resetTargetInputPadding();
        readTrackRequest(getIntent());
        snapshot = CaptureRuntime.current();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CaptureRuntime.setListener(captureListener);
        NearbyRuntime.setListener(nearbyListener);
        applySnapshot(CaptureRuntime.current());
        applyNearbySnapshot(NearbyRuntime.current());
        requestControlIfServerAlreadyWaiting();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean requested = readTrackRequest(intent);
        if (requested) {
            applyNearbySnapshot(NearbyRuntime.current());
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(serviceStartTimeout);
        NearbyRuntime.clearListener(nearbyListener);
        NearbyAlertNotification.releaseAll(this);
        if (mapCard != null) {
            mapCard.release();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_MAP_VISIBLE, mapVisible);
        boolean following = mapCard != null ? mapCard.isFollowing() : mapFollowing;
        outState.putBoolean(STATE_MAP_FOLLOWING, following);
        if (mapCard != null && mapCard.isBound()) {
            outState.putDouble(STATE_MAP_CENTER_X, mapCard.getCenterX());
            outState.putDouble(STATE_MAP_CENTER_Y, mapCard.getCenterY());
            outState.putDouble(STATE_MAP_SPAN, mapCard.getSpanMetres());
        } else {
            outState.putDouble(STATE_MAP_CENTER_X, mapCenterX);
            outState.putDouble(STATE_MAP_CENTER_Y, mapCenterY);
            outState.putDouble(STATE_MAP_SPAN, mapSpan);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        CaptureRuntime.clearListener(captureListener);
        if (targetInput.hasFocus()) {
            commitTarget(false);
            targetInput.clearFocus();
        }
        hideKeyboard();
        super.onStop();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }

    private void bindViews() {
        emptyCard = findViewById(R.id.empty_card);
        actionRow = findViewById(R.id.action_row);
        scrollView = findViewById(R.id.scroll_view);
        contentContainer = findViewById(R.id.content_container);
        targetRow = findViewById(R.id.target_row);
        cardTitle = findViewById(R.id.card_title);
        sceneSubtitle = findViewById(R.id.scene_subtitle);
        overlayButton = findViewById(R.id.overlay_button);
        compassIcon = findViewById(R.id.compass_icon);
        targetInputLayout = findViewById(R.id.target_input_layout);
        targetInput = findViewById(R.id.target_input);
        actionButton = findViewById(R.id.action_button);
        stopButton = findViewById(R.id.stop_button);
        mapButton = findViewById(R.id.map_button);
        mapCard = findViewById(R.id.map_card);
        mapCard.setOnFollowChangedListener(() -> mapFollowing = mapCard.isFollowing());
        nearbyList = findViewById(R.id.nearby_list);
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
                        phase = Phase.IDLE;
                        render();
                    }
                });
    }

    private void setupTargetEditor() {
        View.OnClickListener focusTarget = view -> focusTargetInput();
        targetRow.setOnClickListener(focusTarget);
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
            if (phase == Phase.PREPARING || phase == Phase.CAPTURING
                    || phase == Phase.REQUESTING || phase == Phase.STOPPING) {
                stopCaptureFlow();
            } else if (phase == Phase.IDLE || phase == Phase.ERROR) {
                startCaptureFlow();
            }
        });
        overlayButton.setOnClickListener(view -> toggleOverlayWindow());
        mapButton.setOnClickListener(view -> toggleMapSection());
        stopButton.setOnClickListener(view -> {
            if (isCaptureActive()) {
                stopCaptureFlow();
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

    private void applyNearbySnapshot(NearbyRuntime.Snapshot newSnapshot) {
        nearby = newSnapshot;
        sceneId = Math.max(0, newSnapshot.sceneId);
        if (sceneId == 0) {
            mapVisible = false;
        }
        updateMapContent();
        updateNearbyList();
        updateEmptyCardVisibility();
        updateSceneSubtitle();
        updateActionRow();
    }

    private void toggleMapSection() {
        if (!hasLivePosition()) {
            return;
        }
        mapVisible = !mapVisible;
        updateMapContent();
        updateSceneSubtitle();
        updateActionRow();
        if (mapVisible && mapCard != null && scrollView != null
                && mapCard.getParent() == contentContainer) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, mapScrollTarget()));
        }
    }

    private int mapScrollTarget() {
        if (mapCard == null || contentContainer == null) {
            return 0;
        }
        int target = mapCard.getTop() - contentContainer.getPaddingTop();
        return Math.max(0, target);
    }

    private void clampScrollToContent() {
        if (scrollView == null || contentContainer == null) {
            return;
        }
        if (scrollView.getScrollY() <= 0) {
            return;
        }
        scrollView.post(() -> {
            if (scrollView == null || contentContainer == null) {
                return;
            }
            int viewport = scrollView.getHeight() - scrollView.getPaddingTop()
                    - scrollView.getPaddingBottom();
            int max = Math.max(0, contentContainer.getHeight() - viewport);
            if (scrollView.getScrollY() > max) {
                scrollView.scrollTo(0, max);
            }
        });
    }

    private void updateSceneSubtitle() {
        if (sceneSubtitle == null) {
            return;
        }
        if (sceneId <= 0) {
            sceneSubtitle.setText("");
            return;
        }
        MapCatalog.Scene scene = MapCatalog.find(sceneId);
        sceneSubtitle.setText(scene != null
                ? scene.name
                : getString(R.string.sub_title, sceneId));
    }

    private void toggleOverlayWindow() {
        if (NearbyOverlay.isShowing()) {
            NearbyOverlay.dismiss();
            return;
        }
        if (!canDrawOverlays()) {
            showOverlayPermissionDialog();
            return;
        }
        NearbyOverlay.show(this);
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
                .setPositiveButton(R.string.dialog_permission,
                        (dialog, which) -> PermissionUtils.requestPermission(this, new OnPermissionResult() {
                            @Override
                            public void permissionResult(boolean b) {

                            }
                        }))
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
        mapVisible = false;
        NearbyRuntime.clear();
        pendingMapExpand = false;
        pendingTrackEntityKey = null;
        boolean remote = isRemoteTarget();
        authorized = false;
        controlRequested = remote;
        localPort = 0;
        phase = Phase.PREPARING;
        render();
        try {
            Intent serviceIntent = new Intent(this, PcapCaptureService.class)
                    .setAction(PcapCaptureService.ACTION_START);
            ContextCompat.startForegroundService(this, serviceIntent);
            mainHandler.removeCallbacks(serviceStartTimeout);
            if (!remote) {
                mainHandler.postDelayed(serviceStartTimeout, 4000);
            }
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
        if (isRemoteTarget()) {
            controlRequested = true;
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
        mapVisible = false;
        NearbyRuntime.clear();
        pendingMapExpand = false;
        pendingTrackEntityKey = null;
        phase = Phase.STOPPING;
        render();
        if (isRemoteTarget()) {
            authorized = false;
            stopLocalReceiver();
        } else {
            if (authorized) {
                try {
                    captureStopLauncher.launch(PcapControl.buildStopIntent());
                } catch (Throwable ignored) {
                }
                authorized = false;
            }
            mainHandler.postDelayed(this::stopLocalReceiver, 700);
        }
        mainHandler.removeCallbacks(stopFallback);
        mainHandler.postDelayed(stopFallback, 1600);
    }

    private final Runnable stopFallback = () -> {
        if (phase == Phase.STOPPING) {
            phase = Phase.IDLE;
            render();
        }
    };

    private void stopLocalReceiver() {
        try {
            Intent stop = new Intent(this, PcapCaptureService.class)
                    .setAction(PcapCaptureService.ACTION_STOP);
            startService(stop);
        } catch (Throwable ignored) {
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
                    phase = Phase.CAPTURING;
                    authorized = true;
                } else if (phase == Phase.IDLE) {
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
                title = getString(isRemoteTarget() ? R.string.title_connecting
                        : R.string.title_requesting);
                button = getString(R.string.action_stop);
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
        updateMapContent();
        updateEmptyCardVisibility();
        updateSceneSubtitle();
        updateActionRow();
    }

    private void updateMapContent() {
        if (mapCard == null) {
            return;
        }
        int targetSceneId = nearby.hasPosition && nearby.sceneId > 0
                ? nearby.sceneId
                : sceneId;
        MapCatalog.Scene targetScene = targetSceneId > 0
                ? MapCatalog.find(targetSceneId)
                : null;
        boolean canShow = targetScene != null && hasLivePosition();
        if (canShow && pendingMapExpand) {
            pendingMapExpand = false;
            mapVisible = true;
            if (scrollView != null && mapCard != null
                    && mapCard.getParent() == contentContainer) {
                scrollView.post(() -> scrollView.smoothScrollTo(0, mapScrollTarget()));
            }
        }
        syncCardTaps(canShow && mapVisible);
        if (!canShow) {
            mapCard.setVisibility(View.GONE);
            clampScrollToContent();
            updateActionRow();
            return;
        }
        MapCatalog.Scene boundScene = mapCard.getScene();
        if (boundScene == null || boundScene.sceneId != targetScene.sceneId) {
            MapPaint paint = MapPaint.get(targetScene.sceneId);
            mapCard.bind(targetScene, paint, nearby.selfX, nearby.selfY);
            mapCard.setFollowing(mapFollowing);
            if (!mapFollowing && !mapViewportRestored
                    && Double.isFinite(mapCenterX) && Double.isFinite(mapCenterY)
                    && mapSpan > 0) {
                mapViewportRestored = true;
                mapCard.restoreViewport(mapCenterX, mapCenterY, mapSpan);
            }
        } else {
            mapCard.syncPaint();
            mapCard.updateSelfPosition(nearby.selfX, nearby.selfY);
        }
        mapCard.setEntities(nearby.entities);
        applyPendingTrack();
        mapCard.setVisibility(mapVisible ? View.VISIBLE : View.GONE);
        clampScrollToContent();
        updateActionRow();
    }

    private void syncCardTaps(boolean enabled) {
        if (nearbyList == null) {
            return;
        }
        nearbyList.setOnEntityClickListener(enabled ? trackEntityListener : null);
    }

    private void onEntityCardTapped(NearbyEntity entity) {
        if (mapCard == null || entity == null) {
            return;
        }
        mapCard.trackEntity(entity);
        mapFollowing = mapCard.isFollowing();
    }

    private boolean readTrackRequest(@Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_MAP, false)) {
            return false;
        }
        intent.removeExtra(EXTRA_OPEN_MAP);
        pendingMapExpand = true;
        String key = intent.getStringExtra(EXTRA_TRACK_ENTITY);
        intent.removeExtra(EXTRA_TRACK_ENTITY);
        if (!TextUtils.isEmpty(key)) {
            pendingTrackEntityKey = key;
        }
        return true;
    }

    private void applyPendingTrack() {
        if (pendingTrackEntityKey == null || mapCard == null) {
            return;
        }
        if (nearby.entities.isEmpty()) {
            return;
        }
        NearbyEntity match = null;
        for (NearbyEntity candidate : nearby.entities) {
            if (candidate != null && pendingTrackEntityKey.equals(candidate.identityKey())) {
                match = candidate;
                break;
            }
        }
        pendingTrackEntityKey = null;
        if (match == null) {
            return;
        }
        mapCard.trackEntity(match);
        mapFollowing = mapCard.isFollowing();
    }

    private void updateNearbyList() {
        if (nearbyList == null) {
            return;
        }
        double selfX = nearby.hasPosition ? nearby.selfX : Double.NaN;
        double selfY = nearby.hasPosition ? nearby.selfY : Double.NaN;
        nearbyList.bind(nearby.entities, selfX, selfY);
    }

    private void updateEmptyCardVisibility() {
        if (emptyCard == null) {
            return;
        }
        emptyCard.setVisibility(hasLivePosition() ? View.INVISIBLE : View.VISIBLE);
    }

    private void updateActionRow() {
        if (actionRow == null) {
            return;
        }
        boolean hasPosition = hasLivePosition();
        actionRow.setVisibility(hasPosition ? View.VISIBLE : View.INVISIBLE);
        if (stopButton != null) {
            stopButton.setVisibility(hasPosition ? View.VISIBLE : View.INVISIBLE);
        }
        if (mapButton != null) {
            mapButton.setVisibility(hasPosition ? View.VISIBLE : View.INVISIBLE);
            mapButton.setEnabled(hasPosition);
        }
    }

    private boolean hasLivePosition() {
        return nearby.hasPosition && isCaptureActive();
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
            value = defaultTarget;
            targetInput.setText(value);
            targetInput.setSelection(value.length());
        }
        if (!CaptureTarget.isValid(value)) {
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
        captureTarget = CaptureTarget.parse(value);
        return true;
    }

    private boolean isRemoteTarget() {
        return captureTarget != null && captureTarget.isServer();
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

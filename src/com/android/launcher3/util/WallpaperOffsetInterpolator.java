package com.android.launcher3.util;

import static android.content.Intent.ACTION_WALLPAPER_CHANGED;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.animation.Interpolator;

import app.lawnchair.preferences.PreferenceManager;
import androidx.annotation.AnyThread;

import com.android.app.animation.Interpolators;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;

/**
 * Utility class to handle wallpaper scrolling along with workspace.
 */
public class WallpaperOffsetInterpolator {

    private static final int[] sTempInt = new int[2];
    private static final String TAG = "WPOffsetInterpolator";
    private static final int ANIMATION_DURATION = 250;

    // Don't use all the wallpaper for parallax until you have at least this many pages
    private static final int MIN_PARALLAX_PAGE_SPAN = 4;

    private final SimpleBroadcastReceiver mWallpaperChangeReceiver;
    private final Workspace<?> mWorkspace;
    private final boolean mIsRtl;
    private final Handler mHandler;

    private boolean mRegistered = false;
    private IBinder mWindowToken;
    private boolean mWallpaperIsLiveWallpaper;

    // Anchor: when Anchor's own live wallpaper (AnchorWallpaperService) is active, it drives parallax
    // cross-process via WallpaperManager.setWallpaperOffsets from WallpaperStabilizationManager. This
    // interpolator must then NOT also push offsets — two writers on the same token alternate frame to
    // frame (flicker/jank) and Launcher3's hardcoded yOffset=0.5 stomps our vertical row parallax.
    // Gated at the actual send point (setOffsetSafely) so Launcher3's own lock lifecycle, which
    // toggles mLockedToDefaultPage on layout, cannot re-enable sending. Set by the manager on setup.
    public static volatile boolean sAnchorSuppressSystemOffsets = false;

    private boolean mLockedToDefaultPage;
    private int mNumScreens;

    // Anchor: preserves the system-wallpaper horizontal position across a row switch. Rows have
    // independent page positions, so following scrollX directly would jump the wallpaper to the new
    // row's parked-page offset. When frozen, the reported offset = the preserved value plus the
    // DELTA of the live row-relative offset since the freeze — so the position is held across the
    // switch but still tracks the user's horizontal scroll in the new row (a delta model, matching
    // the custom-image world-camera). Frozen while mFrozen is true.
    private boolean mFrozen = false;
    private float mFrozenBaseOffset = 0f;   // preserved offset at freeze time
    private float mFrozenLiveAtFreeze = Float.NaN;  // live row-relative offset at freeze time

    private PreferenceManager prefs;

    public WallpaperOffsetInterpolator(Workspace<?> workspace) {
        mWorkspace = workspace;
        mWallpaperChangeReceiver = new SimpleBroadcastReceiver(
                workspace.getContext(), UI_HELPER_EXECUTOR, i -> onWallpaperChanged());
        mIsRtl = Utilities.isRtl(workspace.getResources());
        mHandler = new OffsetHandler(workspace.getContext());
        prefs = PreferenceManager.getInstance(workspace.getContext());
    }

    /**
     * Locks the wallpaper offset to the offset in the default state of Launcher.
     */
    public void setLockToDefaultPage(boolean lockToDefaultPage) {
        mLockedToDefaultPage = lockToDefaultPage;
    }

    /**
     * Anchor: preserve the system-wallpaper horizontal offset across a row switch. Captures the
     * current offset; subsequent scrolls in the new row move it by their delta. Called by the row
     * manager at the start of a row transition. The freeze persists (it is not auto-released) so the
     * preserved position survives indefinitely until the next row switch re-freezes it.
     */
    public void freezeHorizontalOffset() {
        // Capture the offset the user CURRENTLY sees. If we're already frozen (e.g. up → scroll →
        // down), that's the accumulated effective offset (base + scroll delta), NOT the raw
        // scroll-derived value — otherwise the second row switch would snap to the new row's actual
        // scroll position instead of preserving what's on screen.
        if (mFrozen && !Float.isNaN(mFrozenLiveAtFreeze)) {
            float live = rawRowRelativeOffset(mWorkspace.getScrollX());
            mFrozenBaseOffset = Utilities.boundToRange(
                    mFrozenBaseOffset + (live - mFrozenLiveAtFreeze), 0f, 1f);
        } else {
            mFrozenBaseOffset = rawRowRelativeOffset(mWorkspace.getScrollX());
        }
        mFrozenLiveAtFreeze = Float.NaN;  // re-baselined on the first sync after the switch settles
        mFrozen = true;
    }

    public void unfreezeHorizontalOffset() {
        mFrozen = false;
        mFrozenLiveAtFreeze = Float.NaN;
    }

    /** The live row-relative (or full-stack) offset for a scroll, ignoring the freeze. */
    private float rawRowRelativeOffset(int scroll) {
        wallpaperOffsetForScroll(scroll, getNumScrollableScreensExcludingEmpty(), sTempInt);
        return sTempInt[1] == 0 ? 0f : ((float) sTempInt[0]) / sTempInt[1];
    }

    public boolean isLockedToDefaultPage() {
        return mLockedToDefaultPage;
    }

    /**
     * Computes the wallpaper offset as an int ratio (out[0] / out[1])
     *
     * TODO: do different behavior if it's  a live wallpaper?
     */
    private void wallpaperOffsetForScroll(int scroll, int numScrollableScreens, final int[] out) {
        out[1] = 1;

        // To match the default wallpaper behavior in the system, we default to either the left
        // or right edge on initialization
        if (!prefs.getWallpaperScrolling().get() || mLockedToDefaultPage || numScrollableScreens <= 1) {
            out[0] = mIsRtl ? 1 : 0;
            return;
        }

        // Distribute the wallpaper parallax over a minimum of MIN_PARALLAX_PAGE_SPAN workspace
        // screens, not including the custom screen, and empty screens (if > MIN_PARALLAX_PAGE_SPAN)
        int numScreensForWallpaperParallax = mWallpaperIsLiveWallpaper ? numScrollableScreens :
                        Math.max(MIN_PARALLAX_PAGE_SPAN, numScrollableScreens);

        // Offset by the custom screen

        // Don't confuse screens & pages in this function. In a phone UI, we often use screens &
        // pages interchangeably. However, in a n-panels UI, where n > 1, the screen in this class
        // means the scrollable screen. Each screen can consist of at most n panels.
        // Each panel has at most 1 page. Take 5 pages in 2 panels UI as an example, the Workspace
        // looks as follow:
        //
        // S: scrollable screen, P: page, <E>: empty
        //   S0        S1         S2
        // _______   _______   ________
        // |P0|P1|   |P2|P3|   |P4|<E>|
        // ¯¯¯¯¯¯¯   ¯¯¯¯¯¯¯   ¯¯¯¯¯¯¯¯
        // Anchor: in multi-row mode, scope the parallax range to the active row's pages so that
        // navigating UP/DOWN a row (which moves scrollX to a different flat-stack position) does not
        // snap the system wallpaper. Within a row it still gives normal left/right parallax.
        final boolean anchorRowActive = mWorkspace.isAnchorRowRangeActive();
        int firstIndex = 0;
        int lastIndex = getNumPagesExcludingEmpty() - 1;
        if (anchorRowActive) {
            firstIndex = mWorkspace.getAllowedPageStart();
            lastIndex = mWorkspace.getAllowedPageEnd();
        }
        final int leftPageIndex = mIsRtl ? lastIndex : firstIndex;
        final int rightPageIndex = mIsRtl ? firstIndex : lastIndex;

        // Calculate the scroll range
        int leftPageScrollX = mWorkspace.getScrollForPage(leftPageIndex);
        int rightPageScrollX = mWorkspace.getScrollForPage(rightPageIndex);
        int scrollRange = rightPageScrollX - leftPageScrollX;
        if (scrollRange <= 0) {
            out[0] = 0;
            return;
        }

        // Sometimes the left parameter of the pages is animated during a layout transition;
        // this parameter offsets it to keep the wallpaper from animating as well
        int adjustedScroll = scroll - leftPageScrollX -
                mWorkspace.getLayoutTransitionOffsetForPage(0);
        adjustedScroll = Utilities.boundToRange(adjustedScroll, 0, scrollRange);

        // Anchor: when scoped to a row, map the offset directly to [0,1] over that row's scroll
        // range (out[0]/out[1] = adjustedScroll/scrollRange). This keeps every row's parallax in the
        // same 0..1 band, so switching rows doesn't move the wallpaper, and avoids the flat-stack
        // screen-count maths below (which assumes the whole workspace is one scrollable strip).
        if (anchorRowActive) {
            out[0] = mIsRtl ? (scrollRange - adjustedScroll) : adjustedScroll;
            out[1] = scrollRange;
            return;
        }

        out[1] = (numScreensForWallpaperParallax - 1) * scrollRange;

        // The offset is now distributed 0..1 between the left and right pages that we care about,
        // so we just map that between the pages that we are using for parallax
        int rtlOffset = 0;
        if (mIsRtl) {
            // In RTL, the pages are right aligned, so adjust the offset from the end
            rtlOffset = out[1] - (numScrollableScreens - 1) * scrollRange;
        }
        out[0] = rtlOffset + adjustedScroll * (numScrollableScreens - 1);
    }

    public float wallpaperOffsetForScroll(int scroll) {
        float live = rawRowRelativeOffset(scroll);
        if (!mFrozen) {
            return live;
        }
        // Frozen (post row-switch): hold the preserved offset, plus the user's scroll delta in the
        // new row since the freeze. Re-baseline mFrozenLiveAtFreeze on the first call so the row's
        // parked-page position counts as "no delta" — the wallpaper stays put on the switch itself.
        if (Float.isNaN(mFrozenLiveAtFreeze)) {
            mFrozenLiveAtFreeze = live;
        }
        float result = mFrozenBaseOffset + (live - mFrozenLiveAtFreeze);
        return Utilities.boundToRange(result, 0f, 1f);
    }

    /**
     * Returns the number of screens that can be scrolled.
     *
     * <p>In an usual phone UI, the number of scrollable screens is equal to the number of
     * CellLayouts because each screen has exactly 1 CellLayout.
     *
     * <p>In a n-panels UI, a screen shows n panels. Each panel has at most 1 CellLayout. Take
     * 2-panels UI as an example: let's say there are 5 CellLayouts in the Workspace. the number of
     * scrollable screens will be 3 = ⌈5 / 2⌉.
     */
    private int getNumScrollableScreensExcludingEmpty() {
        float numOfPages = getNumPagesExcludingEmpty();
        return (int) Math.ceil(numOfPages / mWorkspace.getPanelCount());
    }

    /**
     * Returns the number of non-empty pages in the Workspace.
     *
     * <p>If a user starts dragging on the rightmost (or leftmost in RTL), an empty CellLayout is
     * added to the Workspace. This empty CellLayout add as a hover-over target for adding a new
     * page. To avoid janky motion effect, we ignore this empty CellLayout.
     */
    private int getNumPagesExcludingEmpty() {
        int numOfPages = mWorkspace.getChildCount();
        if (numOfPages >= MIN_PARALLAX_PAGE_SPAN && mWorkspace.hasExtraEmptyScreens()) {
            return numOfPages - mWorkspace.getPanelCount();
        } else {
            return numOfPages;
        }
    }

    public void syncWithScroll() {
        int numScreens = getNumScrollableScreensExcludingEmpty();
        wallpaperOffsetForScroll(mWorkspace.getScrollX(), numScreens, sTempInt);
        // Anchor: while preserving horizontal position across a row switch, override the computed
        // offset with the frozen value (base + scroll delta in the new row). Re-express it as the
        // arg1/arg2 ratio the offset handler expects, reusing the live range as the denominator.
        if (mFrozen && sTempInt[1] > 0) {
            float live = (float) sTempInt[0] / sTempInt[1];
            if (Float.isNaN(mFrozenLiveAtFreeze)) {
                mFrozenLiveAtFreeze = live;
            }
            float frozen = Utilities.boundToRange(
                    mFrozenBaseOffset + (live - mFrozenLiveAtFreeze), 0f, 1f);
            sTempInt[0] = Math.round(frozen * sTempInt[1]);
        }
        Message msg = Message.obtain(mHandler, MSG_UPDATE_OFFSET, sTempInt[0], sTempInt[1],
                mWindowToken);
        if (numScreens != mNumScreens) {
            if (mNumScreens > 0) {
                // Don't animate if we're going from 0 screens
                msg.what = MSG_START_ANIMATION;
            }
            mNumScreens = numScreens;
            updateOffset();
        }
        msg.sendToTarget();
    }

    /** Returns the number of pages used for the wallpaper parallax. */
    public int getNumPagesForWallpaperParallax() {
        if (mWallpaperIsLiveWallpaper) {
            return mNumScreens;
        } else {
            return Math.max(MIN_PARALLAX_PAGE_SPAN, mNumScreens);
        }
    }

    @AnyThread
    private void updateOffset() {
        Message.obtain(mHandler, MSG_SET_NUM_PARALLAX, getNumPagesForWallpaperParallax(), 0,
                mWindowToken).sendToTarget();
    }

    public void jumpToFinal() {
        Message.obtain(mHandler, MSG_JUMP_TO_FINAL, mWindowToken).sendToTarget();
    }

    public void setWindowToken(IBinder token) {
        mWindowToken = token;
        if (mWindowToken == null && mRegistered) {
            mWallpaperChangeReceiver.unregisterReceiverSafely();
            mRegistered = false;
        } else if (mWindowToken != null && !mRegistered) {
            mWallpaperChangeReceiver.register(ACTION_WALLPAPER_CHANGED);
            onWallpaperChanged();
            mRegistered = true;
        }
    }

    private void onWallpaperChanged() {
        UI_HELPER_EXECUTOR.execute(() -> {
            // Updating the boolean on a background thread is fine as the assignments are atomic
            mWallpaperIsLiveWallpaper = WallpaperManager.getInstance(mWorkspace.getContext())
                    .getWallpaperInfo() != null;
            updateOffset();
        });
    }

    private static final int MSG_START_ANIMATION = 1;
    private static final int MSG_UPDATE_OFFSET = 2;
    private static final int MSG_APPLY_OFFSET = 3;
    private static final int MSG_SET_NUM_PARALLAX = 4;
    private static final int MSG_JUMP_TO_FINAL = 5;

    private static class OffsetHandler extends Handler {

        private final Interpolator mInterpolator;
        private final WallpaperManager mWM;

        private float mCurrentOffset = 0.5f; // to force an initial update
        private boolean mAnimating;
        private long mAnimationStartTime;
        private float mAnimationStartOffset;

        private float mFinalOffset;
        private float mOffsetX;

        public OffsetHandler(Context context) {
            super(UI_HELPER_EXECUTOR.getLooper());
            mInterpolator = Interpolators.DECELERATE_1_5;
            mWM = WallpaperManager.getInstance(context);
        }

        @Override
        public void handleMessage(Message msg) {
            final IBinder token = (IBinder) msg.obj;
            if (token == null) {
                return;
            }

            switch (msg.what) {
                case MSG_START_ANIMATION: {
                    mAnimating = true;
                    mAnimationStartOffset = mCurrentOffset;
                    mAnimationStartTime = msg.getWhen();
                    // Follow through
                }
                case MSG_UPDATE_OFFSET:
                    mFinalOffset = ((float) msg.arg1) / msg.arg2;
                    // Follow through
                case MSG_APPLY_OFFSET: {
                    float oldOffset = mCurrentOffset;
                    if (mAnimating) {
                        long durationSinceAnimation = SystemClock.uptimeMillis()
                                - mAnimationStartTime;
                        float t0 = durationSinceAnimation / (float) ANIMATION_DURATION;
                        float t1 = mInterpolator.getInterpolation(t0);
                        mCurrentOffset = mAnimationStartOffset +
                                (mFinalOffset - mAnimationStartOffset) * t1;
                        mAnimating = durationSinceAnimation < ANIMATION_DURATION;
                    } else {
                        mCurrentOffset = mFinalOffset;
                    }

                    if (Float.compare(mCurrentOffset, oldOffset) != 0) {
                        setOffsetSafely(token);
                        // Force the wallpaper offset steps to be set again, because another app
                        // might have changed them
                        mWM.setWallpaperOffsetSteps(mOffsetX, 1.0f);
                    }
                    if (mAnimating) {
                        // If we are animating, keep updating the offset
                        Message.obtain(this, MSG_APPLY_OFFSET, token).sendToTarget();
                    }
                    return;
                }
                case MSG_SET_NUM_PARALLAX: {
                    // Set wallpaper offset steps (1 / (number of screens - 1))
                    mOffsetX = 1.0f / (msg.arg1 - 1);
                    mWM.setWallpaperOffsetSteps(mOffsetX, 1.0f);
                    return;
                }
                case MSG_JUMP_TO_FINAL: {
                    if (Float.compare(mCurrentOffset, mFinalOffset) != 0) {
                        mCurrentOffset = mFinalOffset;
                        setOffsetSafely(token);
                    }
                    mAnimating = false;
                    return;
                }
            }
        }

        private void setOffsetSafely(IBinder token) {
            // Anchor: our live wallpaper drives offsets itself; don't double-write the token.
            if (sAnchorSuppressSystemOffsets) {
                return;
            }
            try {
                mWM.setWallpaperOffsets(token, mCurrentOffset, 0.5f);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error updating wallpaper offset: " + e);
            }
        }
    }
}

package com.dovar.common.vsview;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.Observable;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.annotation.CallSuper;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.AbsSavedState;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;

import com.dovar.common.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by heweizong on 2018/5/7.
 * 抄写recyclerView
 */

public class DRecyclerView extends ViewGroup {
    public static final boolean DEBUG = true;
    static final String TAG = "DRecyclerView";

    private RecyclerView.Adapter mAdapter;
    private LayoutManager mLayoutManager;
    /**
     * Handles adapter updates
     */
    AdapterHelper mAdapterHelper;

    final State mState = new State();
    final Recycler mRecycler = new Recycler();
    private final RecyclerViewDataObserver mObserver = new RecyclerViewDataObserver();
    private List<OnScrollListener> mOnScrollListeners;


    boolean mFirstLayoutComplete;
    boolean mDataSetHasChangedAfterLayout = false;

    // simple array to keep min and max child position during a layout calculation
    // preserved not to create a new one in each layout pass
    private final int[] mMinMaxLayoutPositions = new int[2];

    // Counting lock to control whether we should ignore requestLayout calls from children or not.
    private int mEatRequestLayout = 0;
    boolean mLayoutRequestEaten;
    boolean mLayoutFrozen;

    boolean mIsAttached;
    private int mLayoutOrScrollCounter = 0;

    private static final int INVALID_POINTER = -1;
    /**
     * The RecyclerView is not currently scrolling.
     */
    public static final int SCROLL_STATE_IDLE = 0;
    /**
     * The RecyclerView is currently being dragged by outside input such as user touch input.
     */
    public static final int SCROLL_STATE_DRAGGING = 1;
    /**
     * The RecyclerView is currently animating to a final position while not under outside control.
     */
    public static final int SCROLL_STATE_SETTLING = 2;

    // Touch/scrolling handling

    private int mScrollState = SCROLL_STATE_IDLE;
    private int mScrollPointerId = INVALID_POINTER;
    private int mInitialTouchX;
    private int mInitialTouchY;
    private int mLastTouchX;
    private int mLastTouchY;
    private int mTouchSlop;

    private final int[] mScrollOffset = new int[2];
    private final int[] mScrollConsumed = new int[2];
    private final int[] mNestedOffsets = new int[2];

    static final boolean DISPATCH_TEMP_DETACH = false;
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static final int NO_POSITION = -1;
    public static final long NO_ID = -1;
    public static final int INVALID_TYPE = -1;

    //用于setLayoutFrozen()
    private boolean mIgnoreMotionEventTillDown;


    public DRecyclerView(Context context) {
        super(context);
    }

    public DRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * When adapter is changed, all existing views are recycled back to the pool. If the pool has
     * only one adapter, it will be cleared.
     *
     * @param mAdapter The new adapter to set, or null to set no adapter.
     */
    public void setAdapter(RecyclerView.Adapter mAdapter) {
        setLayoutFrozen(false);
        setAdapterInternal(mAdapter);
        requestLayout();
    }

    private void setAdapterInternal(RecyclerView.Adapter mAdapter) {
        if (this.mAdapter != null) {
            this.mAdapter.unregisterAdapterDataObserver(mObserver);
            this.mAdapter.onDetachedFromRecyclerView(this);
        }

        if (mLayoutManager != null) {
            mLayoutManager.removeAndRecycleAllViews(mRecycler);
            mLayoutManager.removeAndRecycleScrapInt(mRecycler);
        }
        // we should clear it here before adapters are swapped to ensure correct callbacks.
        mRecycler.clear();
        mAdapterHelper.reset();
        final RecyclerView.Adapter oldAdapter = this.mAdapter;
        this.mAdapter = mAdapter;
        if (this.mAdapter != null) {
            this.mAdapter.registerAdapterDataObserver(mObserver);
            this.mAdapter.onAttachedToRecyclerView(this);
        }
        //通知LayoutManager
        if (mLayoutManager != null) {
            mLayoutManager.onAdapterChanged(oldAdapter, this.mAdapter);
        }
        mRecycler.onAdapterChanged(oldAdapter, this.mAdapter, false);
        mState.mStructureChanged = true;
        markKnownViewsInvalid();
    }

    public void setLayoutManager(LayoutManager mLayoutManager) {
        if (mLayoutManager == this.mLayoutManager) {
            return;
        }
        if (mLayoutManager != null) {
            if (mLayoutManager.mRecyclerView != null) {
                throw new IllegalArgumentException("LayoutManager " + mLayoutManager +
                        " is already attached to a RecyclerView: " + mLayoutManager.mRecyclerView);
            }
        }
        stopScroll();
        if (this.mLayoutManager != null) {

            if (mIsAttached) {
                this.mLayoutManager.dispatchDetachedFromWindow(this, mRecycler);
            }
            this.mLayoutManager.setRecyclerView(null);
            this.mLayoutManager = null;
        } else {
            mRecycler.clear();
        }

        this.mLayoutManager = mLayoutManager;
        if (this.mLayoutManager != null) {
            this.mLayoutManager.setRecyclerView(this);
            if (mIsAttached) {
                this.mLayoutManager.dispatchAttachedToWindow(this);
            }
        }
        requestLayout();
    }

    @Override
    protected void onLayout(boolean mB, int mI, int mI1, int mI2, int mI3) {
        dispatchLayout();
        mFirstLayoutComplete = true;
    }

    @Override
    public void requestLayout() {
        //过滤部分刷新请求
        if (mEatRequestLayout == 0 || mLayoutFrozen) {
            super.requestLayout();
        } else {
            mLayoutRequestEaten = true;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

//        final int count = mItemDecorations.size();
//        for (int i = 0; i < count; i++) {
//            mItemDecorations.get(i).onDrawOver(c, this, mState);
//        }

        // If padding is not 0 and clipChildrenToPadding is false, to draw glows properly, we
        // need find children closest to edges. Not sure if it is worth the effort.
//        boolean needsInvalidate = false;
//
//        if (needsInvalidate) {
//            ViewCompat.postInvalidateOnAnimation(this);
//        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        //绘制ItemDecoration
//        final int count = mItemDecorations.size();
//        for (int i = 0; i < count; i++) {
//            mItemDecorations.get(i).onDraw(c, this, mState);
//        }
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        if (mLayoutManager == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayoutManager.generateDefaultLayoutParams();
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        if (mLayoutManager == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayoutManager.generateLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (mLayoutManager == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayoutManager.generateLayoutParams(p);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLayoutOrScrollCounter = 0;
        mIsAttached = true;
        mFirstLayoutComplete = mFirstLayoutComplete && !isLayoutRequested();
        if (mLayoutManager != null) {
            mLayoutManager.dispatchAttachedToWindow(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScroll();
        mIsAttached = false;
        if (mLayoutManager != null) {
            mLayoutManager.dispatchDetachedFromWindow(this, mRecycler);
        }
    }

    private void dispatchLayout() {
        if (mAdapter == null) {
            Log.e(TAG, "No adapter attached; skipping layout");
            // leave the state in START
            return;
        }

        if (mLayoutManager == null) {
            Log.e(TAG, "No layout manager attached; skipping layout");
            // leave the state in START
            return;
        }
        mState.mIsMeasuring = false;
        if (mState.mLayoutStep == State.STEP_START) {
            dispatchLayoutStep1();
//            mLayoutManager.setExactMeasureSpecsFrom(this);
            dispatchLayoutStep2();
        } else if (mAdapterHelper.hasUpdates() ||
                mLayoutManager.getWidth() != getWidth() || mLayoutManager.getHeight() != getHeight()) {
            // First 2 steps are done in onMeasure but looks like we have to run again due to
            // changed size.
//            mLayoutManager.setExactMeasureSpecsFrom(this);
            dispatchLayoutStep2();
        } else {
            // always make sure we sync them (to ensure mode is exact)
//            mLayoutManager.setExactMeasureSpecsFrom(this);
        }
        dispatchLayoutStep3();
    }

    /**
     * The first step of a layout where we;
     * - process adapter updates
     * - decide which animation should run
     * - save information about current views
     * - If necessary, run predictive layout and save its information
     */
    private void dispatchLayoutStep1() {
        mState.assertLayoutStep(State.STEP_START);
        mState.mIsMeasuring = false;
        eatRequestLayout();
//        mViewInfoStore.clear();
        onEnterLayoutOrScroll();
//        saveFocusInfo();
        processAdapterUpdatesAndSetAnimationFlags();
//        mState.mTrackOldChangeHolders=mState.mRunSimpleAnimations && mItemsChanged;
//        mItemsAddedOrRemoved = mItemsChanged = false;
//        mState.mInPreLayout = mState.mRunPredictiveAnimations;
        mState.mItemCount = mAdapter.getItemCount();
        findMinMaxChildLayoutPositions(mMinMaxLayoutPositions);
        // TODO: 2018/5/8

        onExitLayoutOrScroll();
        resumeRequestLayout(false);
        mState.mLayoutStep = State.STEP_LAYOUT;
    }

    private void dispatchLayoutStep2() {
        eatRequestLayout();
        onEnterLayoutOrScroll();
        mState.assertLayoutStep(State.STEP_LAYOUT | State.STEP_ANIMATIONS);
        mAdapterHelper.consumeUpdatesInOnePass();
        mState.mItemCount = mAdapter.getItemCount();
//        mState.mDeletedInvisibleItemCountSincePreviousLayout = 0;

        // Step 2: Run layout
        mState.mInPreLayout = false;
        mLayoutManager.onLayoutChildren(mRecycler, mState);

        mState.mStructureChanged = false;
//        mPendingSavedState = null;

        // onLayoutChildren may have caused client code to disable item animations; re-check
//        mState.mRunSimpleAnimations = mState.mRunSimpleAnimations && mItemAnimator != null;
        mState.mLayoutStep = State.STEP_ANIMATIONS;
        onExitLayoutOrScroll();
        resumeRequestLayout(false);
    }

    //Animation
    private void dispatchLayoutStep3() {
        mState.assertLayoutStep(State.STEP_ANIMATIONS);
        eatRequestLayout();
        onEnterLayoutOrScroll();
        mState.mLayoutStep = State.STEP_START;

        // TODO: 2018/5/8

//        mLayoutManager.removeAndRecycleScrapInt(mRecycler);
//        mState.mPreviousLayoutItemCount = mState.mItemCount;
        mDataSetHasChangedAfterLayout = false;
//        mState.mRunSimpleAnimations = false;
//        mState.mRunPredictiveAnimations = false;
//        mLayoutManager.mRequestedSimpleAnimations = false;
//        if (mRecycler.mChangedScrap != null) {
//            mRecycler.mChangedScrap.clear();
//        }
        //标记layout完成
        mLayoutManager.onLayoutCompleted(mState);
        onExitLayoutOrScroll();
        resumeRequestLayout(false);
//        mViewInfoStore.clear();
        int minPositionPreLayout = mMinMaxLayoutPositions[0];
        int maxPositionPreLayout = mMinMaxLayoutPositions[1];
        findMinMaxChildLayoutPositions(mMinMaxLayoutPositions);
        if (mMinMaxLayoutPositions[0] != minPositionPreLayout || mMinMaxLayoutPositions[1] != maxPositionPreLayout) {
            dispatchOnScrolled(0, 0);
        }
//        recoverFocusFromState();
//        resetFocusInfo();
    }


    public void stopScroll() {
        setScrollState(SCROLL_STATE_IDLE);
        stopScrollersInternal();
    }

    void setScrollState(int state) {
        if (state == mScrollState) {
            return;
        }
        mScrollState = state;
        if (state != SCROLL_STATE_SETTLING) {
            stopScrollersInternal();
        }
        dispatchOnScrollStateChanged(state);
    }

    private void stopScrollersInternal() {
//        mViewFlinger.stop();
        if (mLayoutManager != null) {
            mLayoutManager.stopSmoothScroller();
        }
    }

    void dispatchOnScrollStateChanged(int state) {
        // Let the LayoutManager go first; this allows it to bring any properties into
        // a consistent state before the RecyclerView subclass responds.
        if (mLayoutManager != null) {
            mLayoutManager.onScrollStateChanged(state);
        }

        if (mOnScrollListeners != null) {
            for (int i = mOnScrollListeners.size() - 1; i >= 0; i--) {
                mOnScrollListeners.get(i).onScrollStateChanged(this, state);
            }
        }
    }

    void dispatchOnScrolled(int hresult, int vresult) {
        // Pass the current scrollX/scrollY values; no actual change in these properties occurred
        // but some general-purpose code may choose to respond to changes this way.
        final int scrollX = getScrollX();
        final int scrollY = getScrollY();
        onScrollChanged(scrollX, scrollY, scrollX, scrollY);

        if (mOnScrollListeners != null) {
            for (int i = mOnScrollListeners.size() - 1; i >= 0; i--) {
                mOnScrollListeners.get(i).onScrolled(this, hresult, vresult);
            }
        }
    }


    void eatRequestLayout() {

    }

    public void setRecycledViewPool(RecycledViewPool pool) {
        mRecycler.setRecycledViewPool(pool);
    }

    //响应数据更新并选定相应的动画
    private void processAdapterUpdatesAndSetAnimationFlags() {
        if (mDataSetHasChangedAfterLayout) {
            // Processing these items have no value since data set changed unexpectedly.
            // Instead, we just reset it.
            mAdapterHelper.reset();
            markKnownViewsInvalid();
            mLayoutManager.onItemsChanged(this);
        }

        //animation
    }

    private void markKnownViewsInvalid() {
//        int childCnt=
    }

    private void findMinMaxChildLayoutPositions(int[] mMinMaxLayoutPositions) {

    }

    void onEnterLayoutOrScroll() {
        mLayoutOrScrollCounter++;
    }

    void onExitLayoutOrScroll() {
        mLayoutOrScrollCounter--;
        if (mLayoutOrScrollCounter < 1) {
            mLayoutOrScrollCounter = 0;
//            dispatchContentChangedIfNecessary();
        }
    }

    void resumeRequestLayout(boolean performLayoutChildren) {

    }

    public void setLayoutFrozen(boolean frozen) {
        if (frozen == mLayoutFrozen) return;
        assertNotInLayoutOrScroll("Do not setLayoutFrozen in layout or scroll");
        if (frozen) {
            final long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            onTouchEvent(cancelEvent);
            mLayoutFrozen = true;
            mIgnoreMotionEventTillDown = true;
            stopScroll();
        } else {
            mLayoutFrozen = false;
            if (mLayoutRequestEaten && mLayoutManager != null && mAdapter != null) {
                requestLayout();
            }
            mLayoutRequestEaten = false;
        }
    }

    void assertNotInLayoutOrScroll(String message) {
        if (isComputingLayout()) {
            if (message == null) {
                throw new IllegalStateException("Cannot call this method while RecyclerView is computing a layout or scrolling");
            }
            throw new IllegalStateException(message);
        }
    }

    public boolean isComputingLayout() {
        return mLayoutOrScrollCounter > 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (mLayoutFrozen) {
            // When layout is frozen,  RV does not intercept the motion event.
            // A child view e.g. a button may still get the click.
            return false;
        }
//        if (dispatchOnItemTouchIntercept(e)) {
//            cancelTouch();
//            return true;
//        }

        if (mLayoutManager == null) {
            return false;
        }

        final boolean canScrollHorizontally = mLayoutManager.canScrollHorizontally();
        final boolean canScrollVertically = mLayoutManager.canScrollVertically();

        final int action = MotionEventCompat.getActionMasked(e);
        final int actionIndex = MotionEventCompat.getActionIndex(e);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (mIgnoreMotionEventTillDown) {
                    mIgnoreMotionEventTillDown = false;
                }
                mScrollPointerId = e.getPointerId(0);
                mInitialTouchX = mLastTouchX = (int) (e.getX() + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (e.getY() + 0.5f);

                if (mScrollState == SCROLL_STATE_SETTLING) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                }

                // Clear the nested offsets
                mNestedOffsets[0] = mNestedOffsets[1] = 0;

                int nestedScrollAxis = ViewCompat.SCROLL_AXIS_NONE;
                if (canScrollHorizontally) {
                    nestedScrollAxis |= ViewCompat.SCROLL_AXIS_HORIZONTAL;
                }
                if (canScrollVertically) {
                    nestedScrollAxis |= ViewCompat.SCROLL_AXIS_VERTICAL;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startNestedScroll(nestedScrollAxis);
                }
                break;

            case MotionEventCompat.ACTION_POINTER_DOWN:
                mScrollPointerId = e.getPointerId(actionIndex);
                mInitialTouchX = mLastTouchX = (int) (e.getX(actionIndex) + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (e.getY(actionIndex) + 0.5f);
                break;

            case MotionEvent.ACTION_MOVE: {
                final int index = e.findPointerIndex(mScrollPointerId);
                if (index < 0) {
                    Log.e(TAG, "Error processing scroll; pointer index for id " +
                            mScrollPointerId + " not found. Did any MotionEvents get skipped?");
                    return false;
                }

                final int x = (int) (e.getX(index) + 0.5f);
                final int y = (int) (e.getY(index) + 0.5f);
                if (mScrollState != SCROLL_STATE_DRAGGING) {
                    final int dx = x - mInitialTouchX;
                    final int dy = y - mInitialTouchY;
                    boolean startScroll = false;
                    if (canScrollHorizontally && Math.abs(dx) > mTouchSlop) {
                        mLastTouchX = mInitialTouchX + mTouchSlop * (dx < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (canScrollVertically && Math.abs(dy) > mTouchSlop) {
                        mLastTouchY = mInitialTouchY + mTouchSlop * (dy < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (startScroll) {
                        setScrollState(SCROLL_STATE_DRAGGING);
                    }
                }
            }
            break;

            case MotionEventCompat.ACTION_POINTER_UP: {
                onPointerUp(e);
            }
            break;

            case MotionEvent.ACTION_UP: {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    stopNestedScroll();
                }
            }
            break;

            case MotionEvent.ACTION_CANCEL: {
                cancelTouch();
            }
        }
        return mScrollState == SCROLL_STATE_DRAGGING;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mLayoutFrozen || mIgnoreMotionEventTillDown) {
            return false;
        }

//        if (dispatchOnItemTouch(e)) {
//            cancelTouch();
//            return true;
//        }

        if (mLayoutManager == null) {
            return false;
        }

        final boolean canScrollHorizontally = mLayoutManager.canScrollHorizontally();
        final boolean canScrollVertically = mLayoutManager.canScrollVertically();

//        if (mVelocityTracker == null) {
//            mVelocityTracker = VelocityTracker.obtain();
//        }
        boolean eventAddedToVelocityTracker = false;

        MotionEvent mEvent = MotionEvent.obtain(ev);
        int action = MotionEventCompat.getActionMasked(ev);
        int actionIndex = MotionEventCompat.getActionIndex(ev);
        if (action == MotionEvent.ACTION_DOWN) {
            mNestedOffsets[0] = mNestedOffsets[1] = 0;
        }
        mEvent.offsetLocation(mNestedOffsets[0], mNestedOffsets[1]);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mScrollPointerId = ev.getPointerId(0);
                mInitialTouchX = mLastTouchX = (int) (ev.getX() + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (ev.getY() + 0.5f);

                int nestedScrollAxis = ViewCompat.SCROLL_AXIS_NONE;
                if (canScrollHorizontally) {
                    nestedScrollAxis |= ViewCompat.SCROLL_AXIS_HORIZONTAL;
                }
                if (canScrollVertically) {
                    nestedScrollAxis |= ViewCompat.SCROLL_AXIS_VERTICAL;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startNestedScroll(nestedScrollAxis);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mScrollPointerId = ev.getPointerId(actionIndex);
                mInitialTouchX = mLastTouchX = (int) (ev.getX(actionIndex) + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (ev.getY(actionIndex) + 0.5f);
                break;
            case MotionEvent.ACTION_MOVE:
                final int index = ev.findPointerIndex(mScrollPointerId);
                if (index < 0) {
                    Log.e(TAG, "Error processing scroll; pointer index for id " +
                            mScrollPointerId + " not found. Did any MotionEvents get skipped?");
                    return false;
                }

                final int x = (int) (ev.getX(index) + 0.5f);
                final int y = (int) (ev.getY(index) + 0.5f);
                int dx = mLastTouchX - x;
                int dy = mLastTouchY - y;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (dispatchNestedPreScroll(dx, dy, mScrollConsumed, mScrollOffset)) {
                        dx -= mScrollConsumed[0];
                        dy -= mScrollConsumed[1];
                        mEvent.offsetLocation(mScrollOffset[0], mScrollOffset[1]);
                        // Updated the nested offsets
                        mNestedOffsets[0] += mScrollOffset[0];
                        mNestedOffsets[1] += mScrollOffset[1];
                    }
                }

                if (mScrollState != SCROLL_STATE_DRAGGING) {
                    boolean startScroll = false;
                    if (canScrollHorizontally && Math.abs(dx) > mTouchSlop) {
                        if (dx > 0) {
                            dx -= mTouchSlop;
                        } else {
                            dx += mTouchSlop;
                        }
                        startScroll = true;
                    }
                    if (canScrollVertically && Math.abs(dy) > mTouchSlop) {
                        if (dy > 0) {
                            dy -= mTouchSlop;
                        } else {
                            dy += mTouchSlop;
                        }
                        startScroll = true;
                    }
                    if (startScroll) {
                        setScrollState(SCROLL_STATE_DRAGGING);
                    }
                }

                if (mScrollState == SCROLL_STATE_DRAGGING) {
                    mLastTouchX = x - mScrollOffset[0];
                    mLastTouchY = y - mScrollOffset[1];

                    if (scrollByInternal(
                            canScrollHorizontally ? dx : 0,
                            canScrollVertically ? dy : 0,
                            mEvent)) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
                eventAddedToVelocityTracker = true;
                resetTouch();
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                break;
        }
        return true;
    }

    private void resetTouch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            stopNestedScroll();
        }
//        releaseGlows();
    }

    private void cancelTouch() {
        resetTouch();
        setScrollState(SCROLL_STATE_IDLE);
    }

    private void onPointerUp(MotionEvent e) {
        final int actionIndex = MotionEventCompat.getActionIndex(e);
        if (e.getPointerId(actionIndex) == mScrollPointerId) {
            // Pick a new pointer to pick up the slack.
            final int newIndex = actionIndex == 0 ? 1 : 0;
            mScrollPointerId = e.getPointerId(newIndex);
            mInitialTouchX = mLastTouchX = (int) (e.getX(newIndex) + 0.5f);
            mInitialTouchY = mLastTouchY = (int) (e.getY(newIndex) + 0.5f);
        }
    }

    boolean scrollByInternal(int x, int y, MotionEvent ev) {
        int unconsumedX = 0, unconsumedY = 0;
        int consumedX = 0, consumedY = 0;

        consumePendingUpdateOperations();
        if (mAdapter != null) {
            eatRequestLayout();
            onEnterLayoutOrScroll();
            if (x != 0) {
                consumedX = mLayout.scrollHorizontallyBy(x, mRecycler, mState);
                unconsumedX = x - consumedX;
            }
            if (y != 0) {
                consumedY = mLayout.scrollVerticallyBy(y, mRecycler, mState);
                unconsumedY = y - consumedY;
            }
            repositionShadowingViews();
            onExitLayoutOrScroll();
            resumeRequestLayout(false);
        }
        if (!mItemDecorations.isEmpty()) {
            invalidate();
        }

        if (dispatchNestedScroll(consumedX, consumedY, unconsumedX, unconsumedY, mScrollOffset)) {
            // Update the last touch co-ords, taking any scroll offset into account
            mLastTouchX -= mScrollOffset[0];
            mLastTouchY -= mScrollOffset[1];
            if (ev != null) {
                ev.offsetLocation(mScrollOffset[0], mScrollOffset[1]);
            }
            mNestedOffsets[0] += mScrollOffset[0];
            mNestedOffsets[1] += mScrollOffset[1];
        } else if (getOverScrollMode() != View.OVER_SCROLL_NEVER) {
            if (ev != null) {
                pullGlows(ev.getX(), unconsumedX, ev.getY(), unconsumedY);
            }
            considerReleasingGlowsOnScroll(x, y);
        }
        if (consumedX != 0 || consumedY != 0) {
            dispatchOnScrolled(consumedX, consumedY);
        }
        if (!awakenScrollBars()) {
            invalidate();
        }
        return consumedX != 0 || consumedY != 0;
    }

    int getAdapterPositionFor(ViewHolder viewHolder) {
        if (viewHolder.hasAnyOfTheFlags(ViewHolder.FLAG_INVALID |
                ViewHolder.FLAG_REMOVED | ViewHolder.FLAG_ADAPTER_POSITION_UNKNOWN)
                || !viewHolder.isBound()) {
            return RecyclerView.NO_POSITION;
        }
        return mAdapterHelper.applyPendingUpdatesToPosition(viewHolder.mPosition);
    }

    static ViewHolder getChildViewHolderInt(View child) {
        if (child == null) {
            return null;
        }
        return ((LayoutParams) child.getLayoutParams()).mViewHolder;
    }

    public static class State {
        static final int STEP_START = 1;
        static final int STEP_LAYOUT = 1 << 1;
        static final int STEP_ANIMATIONS = 1 << 2;

        void assertLayoutStep(int accepted) {
            if ((accepted & mLayoutStep) == 0) {
                throw new IllegalStateException("Layout state should be one of "
                        + Integer.toBinaryString(accepted) + " but it is "
                        + Integer.toBinaryString(mLayoutStep));
            }
        }

        @IntDef(flag = true, value = {
                STEP_START, STEP_LAYOUT, STEP_ANIMATIONS
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface LayoutState {
        }

        private int mTargetPosition = RecyclerView.NO_POSITION;

        @LayoutState
        int mLayoutStep = STEP_START;

        private SparseArray<Object> mData;

        /**
         * Number of items adapter has.
         */
        int mItemCount = 0;

        /**
         * Number of items adapter had in the previous layout.
         */
        int mPreviousLayoutItemCount = 0;

        /**
         * Number of items that were NOT laid out but has been deleted from the adapter after the
         * previous layout.
         */
        int mDeletedInvisibleItemCountSincePreviousLayout = 0;

        boolean mStructureChanged = false;

        boolean mInPreLayout = false;

        boolean mRunSimpleAnimations = false;

        boolean mRunPredictiveAnimations = false;

        boolean mTrackOldChangeHolders = false;

        boolean mIsMeasuring = false;

        /**
         * This data is saved before a layout calculation happens. After the layout is finished,
         * if the previously focused view has been replaced with another view for the same item, we
         * move the focus to the new item automatically.
         */
        int mFocusedItemPosition;
        long mFocusedItemId;
        // when a sub child has focus, record its id and see if we can directly request focus on
        // that one instead
        int mFocusedSubChildId;

        State reset() {
            mTargetPosition = RecyclerView.NO_POSITION;
            if (mData != null) {
                mData.clear();
            }
            mItemCount = 0;
            mStructureChanged = false;
            mIsMeasuring = false;
            return this;
        }

        /**
         * Returns true if the RecyclerView is currently measuring the layout. This value is
         * {@code true} only if the LayoutManager opted into the auto measure API and RecyclerView
         * has non-exact measurement specs.
         * <p>
         * Note that if the LayoutManager supports predictive animations and it is calculating the
         * pre-layout step, this value will be {@code false} even if the RecyclerView is in
         * {@code onMeasure} call. This is because pre-layout means the previous state of the
         * RecyclerView and measurements made for that state cannot change the RecyclerView's size.
         * LayoutManager is always guaranteed to receive another call to
         * {@link LayoutManager#onLayoutChildren(Recycler, State)} when this happens.
         *
         * @return True if the RecyclerView is currently calculating its bounds, false otherwise.
         */
        public boolean isMeasuring() {
            return mIsMeasuring;
        }

        /**
         * Returns true if
         *
         * @return
         */
        public boolean isPreLayout() {
            return mInPreLayout;
        }

        /**
         * Returns whether RecyclerView will run predictive animations in this layout pass
         * or not.
         *
         * @return true if RecyclerView is calculating predictive animations to be run at the end
         * of the layout pass.
         */
        public boolean willRunPredictiveAnimations() {
            return mRunPredictiveAnimations;
        }

        /**
         * Returns whether RecyclerView will run simple animations in this layout pass
         * or not.
         *
         * @return true if RecyclerView is calculating simple animations to be run at the end of
         * the layout pass.
         */
        public boolean willRunSimpleAnimations() {
            return mRunSimpleAnimations;
        }

        /**
         * Removes the mapping from the specified id, if there was any.
         *
         * @param resourceId Id of the resource you want to remove. It is suggested to use R.id.* to
         *                   preserve cross functionality and avoid conflicts.
         */
        public void remove(int resourceId) {
            if (mData == null) {
                return;
            }
            mData.remove(resourceId);
        }

        /**
         * Gets the Object mapped from the specified id, or <code>null</code>
         * if no such data exists.
         *
         * @param resourceId Id of the resource you want to remove. It is suggested to use R.id.*
         *                   to
         *                   preserve cross functionality and avoid conflicts.
         */
        public <T> T get(int resourceId) {
            if (mData == null) {
                return null;
            }
            return (T) mData.get(resourceId);
        }

        /**
         * Adds a mapping from the specified id to the specified value, replacing the previous
         * mapping from the specified key if there was one.
         *
         * @param resourceId Id of the resource you want to add. It is suggested to use R.id.* to
         *                   preserve cross functionality and avoid conflicts.
         * @param data       The data you want to associate with the resourceId.
         */
        public void put(int resourceId, Object data) {
            if (mData == null) {
                mData = new SparseArray<Object>();
            }
            mData.put(resourceId, data);
        }

        /**
         * If scroll is triggered to make a certain item visible, this value will return the
         * adapter index of that item.
         *
         * @return Adapter index of the target item or
         * {@link RecyclerView#NO_POSITION} if there is no target
         * position.
         */
        public int getTargetScrollPosition() {
            return mTargetPosition;
        }

        /**
         * Returns if current scroll has a target position.
         *
         * @return true if scroll is being triggered to make a certain position visible
         * @see #getTargetScrollPosition()
         */
        public boolean hasTargetScrollPosition() {
            return mTargetPosition != RecyclerView.NO_POSITION;
        }

        /**
         * @return true if the structure of the data set has changed since the last call to
         * onLayoutChildren, false otherwise
         */
        public boolean didStructureChange() {
            return mStructureChanged;
        }

        /**
         * Returns the total number of items that can be laid out. Note that this number is not
         * necessarily equal to the number of items in the adapter, so you should always use this
         * number for your position calculations and never access the adapter directly.
         * <p>
         * RecyclerView listens for Adapter's notify events and calculates the effects of adapter
         * data changes on existing Views. These calculations are used to decide which animations
         * should be run.
         * <p>
         * To support predictive animations, RecyclerView may rewrite or reorder Adapter changes to
         * present the correct state to LayoutManager in pre-layout pass.
         * <p>
         * For example, a newly added item is not included in pre-layout item count because
         * pre-layout reflects the contents of the adapter before the item is added. Behind the
         * scenes, RecyclerView offsets {@link Recycler#getViewForPosition(int)} calls such that
         * LayoutManager does not know about the new item's existence in pre-layout. The item will
         * be available in second layout pass and will be included in the item count. Similar
         * adjustments are made for moved and removed items as well.
         * <p>
         * You can get the adapter's item count via {@link LayoutManager#getItemCount()} method.
         *
         * @return The number of items currently available
         * @see LayoutManager#getItemCount()
         */
        public int getItemCount() {
            return mInPreLayout ?
                    (mPreviousLayoutItemCount - mDeletedInvisibleItemCountSincePreviousLayout) :
                    mItemCount;
        }
    }

    private class RecyclerViewDataObserver extends RecyclerView.AdapterDataObserver {
        RecyclerViewDataObserver() {
        }

        @Override
        public void onChanged() {
            assertNotInLayoutOrScroll(null);
            if (mAdapter.hasStableIds()) {
                // TODO Determine what actually changed.
                // This is more important to implement now since this callback will disable all
                // animations because we cannot rely on positions.
                mState.mStructureChanged = true;
                setDataSetChangedAfterLayout();
            } else {
                mState.mStructureChanged = true;
                setDataSetChangedAfterLayout();
            }
            if (!mAdapterHelper.hasPendingUpdates()) {
                requestLayout();
            }
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeChanged(positionStart, itemCount, payload)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeInserted(positionStart, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeRemoved(positionStart, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeMoved(fromPosition, toPosition, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        void triggerUpdateProcessor() {
            if (mPostUpdatesOnAnimation && mHasFixedSize && mIsAttached) {
                ViewCompat.postOnAnimation(RecyclerView.this, mUpdateChildViewsRunnable);
            } else {
                mAdapterUpdateDuringMeasure = true;
                requestLayout();
            }
        }
    }

    public abstract static class OnScrollListener {
        /**
         * Callback method to be invoked when RecyclerView's scroll state changes.
         *
         * @param newState The updated scroll state. One of {@link #SCROLL_STATE_IDLE},
         *                 {@link #SCROLL_STATE_DRAGGING} or {@link #SCROLL_STATE_SETTLING}.
         */
        public void onScrollStateChanged(DRecyclerView recyclerView, int newState) {
        }

        /**
         * Callback method to be invoked when the RecyclerView has been scrolled. This will be
         * called after the scroll has completed.
         * <p>
         * This callback will also be called if visible item range changes after a layout
         * calculation. In that case, dx and dy will be 0.
         *
         * @param dx The amount of horizontal scroll.
         * @param dy The amount of vertical scroll.
         */
        public void onScrolled(DRecyclerView recyclerView, int dx, int dy) {
        }
    }

    public static abstract class ViewHolder {
        public final View itemView;
        int mPosition = NO_POSITION;
        int mOldPosition = NO_POSITION;
        long mItemId = NO_ID;
        int mItemViewType = INVALID_TYPE;
        int mPreLayoutPosition = NO_POSITION;

        // The item that this holder is shadowing during an item change event/animation
        ViewHolder mShadowedHolder = null;
        // The item that is shadowing this holder during an item change event/animation
        ViewHolder mShadowingHolder = null;

        /**
         * This ViewHolder has been bound to a position; mPosition, mItemId and mItemViewType
         * are all valid.
         */
        static final int FLAG_BOUND = 1 << 0;

        /**
         * The data this ViewHolder's view reflects is stale and needs to be rebound
         * by the adapter. mPosition and mItemId are consistent.
         */
        static final int FLAG_UPDATE = 1 << 1;

        /**
         * This ViewHolder's data is invalid. The identity implied by mPosition and mItemId
         * are not to be trusted and may no longer match the item view type.
         * This ViewHolder must be fully rebound to different data.
         */
        static final int FLAG_INVALID = 1 << 2;

        /**
         * This ViewHolder points at data that represents an item previously removed from the
         * data set. Its view may still be used for things like outgoing animations.
         */
        static final int FLAG_REMOVED = 1 << 3;

        /**
         * This ViewHolder should not be recycled. This flag is set via setIsRecyclable()
         * and is intended to keep views around during animations.
         */
        static final int FLAG_NOT_RECYCLABLE = 1 << 4;

        /**
         * This ViewHolder is returned from scrap which means we are expecting an addView call
         * for this itemView. When returned from scrap, ViewHolder stays in the scrap list until
         * the end of the layout pass and then recycled by RecyclerView if it is not added back to
         * the RecyclerView.
         */
        static final int FLAG_RETURNED_FROM_SCRAP = 1 << 5;

        /**
         * This ViewHolder is fully managed by the LayoutManager. We do not scrap, recycle or remove
         * it unless LayoutManager is replaced.
         * It is still fully visible to the LayoutManager.
         */
        static final int FLAG_IGNORE = 1 << 7;

        /**
         * When the View is detached form the parent, we set this flag so that we can take correct
         * action when we need to remove it or add it back.
         */
        static final int FLAG_TMP_DETACHED = 1 << 8;

        /**
         * Set when we can no longer determine the adapter position of this ViewHolder until it is
         * rebound to a new position. It is different than FLAG_INVALID because FLAG_INVALID is
         * set even when the type does not match. Also, FLAG_ADAPTER_POSITION_UNKNOWN is set as soon
         * as adapter notification arrives vs FLAG_INVALID is set lazily before layout is
         * re-calculated.
         */
        static final int FLAG_ADAPTER_POSITION_UNKNOWN = 1 << 9;

        /**
         * Set when a addChangePayload(null) is called
         */
        static final int FLAG_ADAPTER_FULLUPDATE = 1 << 10;

        /**
         * Used by ItemAnimator when a ViewHolder's position changes
         */
        static final int FLAG_MOVED = 1 << 11;

        /**
         * Used by ItemAnimator when a ViewHolder appears in pre-layout
         */
        static final int FLAG_APPEARED_IN_PRE_LAYOUT = 1 << 12;

        /**
         * Used when a ViewHolder starts the layout pass as a hidden ViewHolder but is re-used from
         * hidden list (as if it was scrap) without being recycled in between.
         * <p>
         * When a ViewHolder is hidden, there are 2 paths it can be re-used:
         * a) Animation ends, view is recycled and used from the recycle pool.
         * b) LayoutManager asks for the View for that position while the ViewHolder is hidden.
         * <p>
         * This flag is used to represent "case b" where the ViewHolder is reused without being
         * recycled (thus "bounced" from the hidden list). This state requires special handling
         * because the ViewHolder must be added to pre layout maps for animations as if it was
         * already there.
         */
        static final int FLAG_BOUNCED_FROM_HIDDEN_LIST = 1 << 13;

        private int mFlags;

        private static final List<Object> FULLUPDATE_PAYLOADS = Collections.EMPTY_LIST;

        List<Object> mPayloads = null;
        List<Object> mUnmodifiedPayloads = null;

        private int mIsRecyclableCount = 0;

        // If non-null, view is currently considered scrap and may be reused for other data by the
        // scrap container.
        private Recycler mScrapContainer = null;
        // Keeps whether this ViewHolder lives in Change scrap or Attached scrap
        private boolean mInChangeScrap = false;

        // Saves isImportantForAccessibility value for the view item while it's in hidden state and
        // marked as unimportant for accessibility.
        private int mWasImportantForAccessibilityBeforeHidden =
                ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;

        /**
         * Is set when VH is bound from the adapter and cleaned right before it is sent to
         * {@link RecycledViewPool}.
         */
        DRecyclerView mOwnerRecyclerView;

        public ViewHolder(View itemView) {
            if (itemView == null) {
                throw new IllegalArgumentException("itemView may not be null");
            }
            this.itemView = itemView;
        }

        void flagRemovedAndOffsetPosition(int mNewPosition, int offset, boolean applyToPreLayout) {
            addFlags(ViewHolder.FLAG_REMOVED);
            offsetPosition(offset, applyToPreLayout);
            mPosition = mNewPosition;
        }

        void offsetPosition(int offset, boolean applyToPreLayout) {
            if (mOldPosition == NO_POSITION) {
                mOldPosition = mPosition;
            }
            if (mPreLayoutPosition == NO_POSITION) {
                mPreLayoutPosition = mPosition;
            }
            if (applyToPreLayout) {
                mPreLayoutPosition += offset;
            }
            mPosition += offset;
            if (itemView.getLayoutParams() != null) {
                ((LayoutParams) itemView.getLayoutParams()).mInsetsDirty = true;
            }
        }

        void clearOldPosition() {
            mOldPosition = NO_POSITION;
            mPreLayoutPosition = NO_POSITION;
        }

        void saveOldPosition() {
            if (mOldPosition == NO_POSITION) {
                mOldPosition = mPosition;
            }
        }

        boolean shouldIgnore() {
            return (mFlags & FLAG_IGNORE) != 0;
        }

        /**
         * @see #getLayoutPosition()
         * @see #getAdapterPosition()
         * @deprecated This method is deprecated because its meaning is ambiguous due to the async
         * handling of adapter updates. Please use {@link #getLayoutPosition()} or
         * {@link #getAdapterPosition()} depending on your use case.
         */
        @Deprecated
        public final int getPosition() {
            return mPreLayoutPosition == NO_POSITION ? mPosition : mPreLayoutPosition;
        }

        /**
         * Returns the position of the ViewHolder in terms of the latest layout pass.
         * <p>
         * This position is mostly used by RecyclerView components to be consistent while
         * RecyclerView lazily processes adapter updates.
         * <p>
         * For performance and animation reasons, RecyclerView batches all adapter updates until the
         * next layout pass. This may cause mismatches between the Adapter position of the item and
         * the position it had in the latest layout calculations.
         * <p>
         * LayoutManagers should always call this method while doing calculations based on item
         * positions. All methods in {@link RecyclerView.LayoutManager}, {@link RecyclerView.State},
         * {@link RecyclerView.Recycler} that receive a position expect it to be the layout position
         * of the item.
         * <p>
         * If LayoutManager needs to call an external method that requires the adapter position of
         * the item, it can use {@link #getAdapterPosition()} or
         * {@link RecyclerView.Recycler#convertPreLayoutPositionToPostLayout(int)}.
         *
         * @return Returns the adapter position of the ViewHolder in the latest layout pass.
         * @see #getAdapterPosition()
         */
        public final int getLayoutPosition() {
            return mPreLayoutPosition == NO_POSITION ? mPosition : mPreLayoutPosition;
        }

        /**
         * Returns the Adapter position of the item represented by this ViewHolder.
         * <p>
         * Note that this might be different than the {@link #getLayoutPosition()} if there are
         * pending adapter updates but a new layout pass has not happened yet.
         * <p>
         * RecyclerView does not handle any adapter updates until the next layout traversal. This
         * may create temporary inconsistencies between what user sees on the screen and what
         * adapter contents have. This inconsistency is not important since it will be less than
         * 16ms but it might be a problem if you want to use ViewHolder position to access the
         * adapter. Sometimes, you may need to get the exact adapter position to do
         * some actions in response to user events. In that case, you should use this method which
         * will calculate the Adapter position of the ViewHolder.
         * <p>
         * Note that if you've called {@link RecyclerView.Adapter#notifyDataSetChanged()}, until the
         * next layout pass, the return value of this method will be {@link #NO_POSITION}.
         *
         * @return The adapter position of the item if it still exists in the adapter.
         * {@link RecyclerView#NO_POSITION} if item has been removed from the adapter,
         * {@link RecyclerView.Adapter#notifyDataSetChanged()} has been called after the last
         * layout pass or the ViewHolder has already been recycled.
         */
        public final int getAdapterPosition() {
            if (mOwnerRecyclerView == null) {
                return NO_POSITION;
            }
            return mOwnerRecyclerView.getAdapterPositionFor(this);
        }

        /**
         * When LayoutManager supports animations, RecyclerView tracks 3 positions for ViewHolders
         * to perform animations.
         * <p>
         * If a ViewHolder was laid out in the previous onLayout call, old position will keep its
         * adapter index in the previous layout.
         *
         * @return The previous adapter index of the Item represented by this ViewHolder or
         * {@link #NO_POSITION} if old position does not exists or cleared (pre-layout is
         * complete).
         */
        public final int getOldPosition() {
            return mOldPosition;
        }

        /**
         * Returns The itemId represented by this ViewHolder.
         *
         * @return The item's id if adapter has stable ids, {@link RecyclerView#NO_ID}
         * otherwise
         */
        public final long getItemId() {
            return mItemId;
        }

        /**
         * @return The view type of this ViewHolder.
         */
        public final int getItemViewType() {
            return mItemViewType;
        }

        boolean isScrap() {
            return mScrapContainer != null;
        }

        void unScrap() {
            mScrapContainer.unscrapView(this);
        }

        boolean wasReturnedFromScrap() {
            return (mFlags & FLAG_RETURNED_FROM_SCRAP) != 0;
        }

        void clearReturnedFromScrapFlag() {
            mFlags = mFlags & ~FLAG_RETURNED_FROM_SCRAP;
        }

        void clearTmpDetachFlag() {
            mFlags = mFlags & ~FLAG_TMP_DETACHED;
        }

        void stopIgnoring() {
            mFlags = mFlags & ~FLAG_IGNORE;
        }

        void setScrapContainer(Recycler recycler, boolean isChangeScrap) {
            mScrapContainer = recycler;
            mInChangeScrap = isChangeScrap;
        }

        boolean isInvalid() {
            return (mFlags & FLAG_INVALID) != 0;
        }

        boolean needsUpdate() {
            return (mFlags & FLAG_UPDATE) != 0;
        }

        boolean isBound() {
            return (mFlags & FLAG_BOUND) != 0;
        }

        boolean isRemoved() {
            return (mFlags & FLAG_REMOVED) != 0;
        }

        boolean hasAnyOfTheFlags(int flags) {
            return (mFlags & flags) != 0;
        }

        boolean isTmpDetached() {
            return (mFlags & FLAG_TMP_DETACHED) != 0;
        }

        boolean isAdapterPositionUnknown() {
            return (mFlags & FLAG_ADAPTER_POSITION_UNKNOWN) != 0 || isInvalid();
        }

        void setFlags(int flags, int mask) {
            mFlags = (mFlags & ~mask) | (flags & mask);
        }

        void addFlags(int flags) {
            mFlags |= flags;
        }

        void addChangePayload(Object payload) {
            if (payload == null) {
                addFlags(FLAG_ADAPTER_FULLUPDATE);
            } else if ((mFlags & FLAG_ADAPTER_FULLUPDATE) == 0) {
                createPayloadsIfNeeded();
                mPayloads.add(payload);
            }
        }

        private void createPayloadsIfNeeded() {
            if (mPayloads == null) {
                mPayloads = new ArrayList<Object>();
                mUnmodifiedPayloads = Collections.unmodifiableList(mPayloads);
            }
        }

        void clearPayload() {
            if (mPayloads != null) {
                mPayloads.clear();
            }
            mFlags = mFlags & ~FLAG_ADAPTER_FULLUPDATE;
        }

        List<Object> getUnmodifiedPayloads() {
            if ((mFlags & FLAG_ADAPTER_FULLUPDATE) == 0) {
                if (mPayloads == null || mPayloads.size() == 0) {
                    // Initial state,  no update being called.
                    return FULLUPDATE_PAYLOADS;
                }
                // there are none-null payloads
                return mUnmodifiedPayloads;
            } else {
                // a full update has been called.
                return FULLUPDATE_PAYLOADS;
            }
        }

        void resetInternal() {
            mFlags = 0;
            mPosition = NO_POSITION;
            mOldPosition = NO_POSITION;
            mItemId = NO_ID;
            mPreLayoutPosition = NO_POSITION;
            mIsRecyclableCount = 0;
            mShadowedHolder = null;
            mShadowingHolder = null;
            clearPayload();
            mWasImportantForAccessibilityBeforeHidden = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        }

        /**
         * Called when the child view enters the hidden state
         */
        private void onEnteredHiddenState() {
            // While the view item is in hidden state, make it invisible for the accessibility.
            mWasImportantForAccessibilityBeforeHidden =
                    ViewCompat.getImportantForAccessibility(itemView);
            ViewCompat.setImportantForAccessibility(itemView,
                    ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        /**
         * Called when the child view leaves the hidden state
         */
        private void onLeftHiddenState() {
            ViewCompat.setImportantForAccessibility(
                    itemView, mWasImportantForAccessibilityBeforeHidden);
            mWasImportantForAccessibilityBeforeHidden = ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        }

        /**
         * Informs the recycler whether this item can be recycled. Views which are not
         * recyclable will not be reused for other items until setIsRecyclable() is
         * later set to true. Calls to setIsRecyclable() should always be paired (one
         * call to setIsRecyclabe(false) should always be matched with a later call to
         * setIsRecyclable(true)). Pairs of calls may be nested, as the state is internally
         * reference-counted.
         *
         * @param recyclable Whether this item is available to be recycled. Default value
         *                   is true.
         */
        public final void setIsRecyclable(boolean recyclable) {
            mIsRecyclableCount = recyclable ? mIsRecyclableCount - 1 : mIsRecyclableCount + 1;
            if (mIsRecyclableCount < 0) {
                mIsRecyclableCount = 0;
                if (DEBUG) {
                    throw new RuntimeException("isRecyclable decremented below 0: " +
                            "unmatched pair of setIsRecyable() calls for " + this);
                }
                Log.e(VIEW_LOG_TAG, "isRecyclable decremented below 0: " +
                        "unmatched pair of setIsRecyable() calls for " + this);
            } else if (!recyclable && mIsRecyclableCount == 1) {
                mFlags |= FLAG_NOT_RECYCLABLE;
            } else if (recyclable && mIsRecyclableCount == 0) {
                mFlags &= ~FLAG_NOT_RECYCLABLE;
            }
            if (DEBUG) {
                Log.d(TAG, "setIsRecyclable val:" + recyclable + ":" + this);
            }
        }

        /**
         * @return true if this item is available to be recycled, false otherwise.
         * @see {@link #setIsRecyclable(boolean)}
         */
        public final boolean isRecyclable() {
            return (mFlags & FLAG_NOT_RECYCLABLE) == 0 &&
                    !ViewCompat.hasTransientState(itemView);
        }

        /**
         * Returns whether we have animations referring to this view holder or not.
         * This is similar to isRecyclable flag but does not check transient state.
         */
        private boolean shouldBeKeptAsChild() {
            return (mFlags & FLAG_NOT_RECYCLABLE) != 0;
        }

        /**
         * @return True if ViewHolder is not referenced by RecyclerView animations but has
         * transient state which will prevent it from being recycled.
         */
        private boolean doesTransientStatePreventRecycling() {
            return (mFlags & FLAG_NOT_RECYCLABLE) == 0 && ViewCompat.hasTransientState(itemView);
        }

        boolean isUpdated() {
            return (mFlags & FLAG_UPDATE) != 0;
        }
    }

    public final class Recycler {
        final ArrayList<ViewHolder> mAttachedScrap = new ArrayList<>();
        ArrayList<ViewHolder> mChangedScrap = null;

        final ArrayList<ViewHolder> mCachedViews = new ArrayList<ViewHolder>();

        private final List<ViewHolder>
                mUnmodifiableAttachedScrap = Collections.unmodifiableList(mAttachedScrap);

        private int mViewCacheMax = DEFAULT_CACHE_SIZE;

        private RecycledViewPool mRecyclerPool;

        private ViewCacheExtension mViewCacheExtension;

        private static final int DEFAULT_CACHE_SIZE = 2;

        /**
         * Clear scrap views out of this recycler. Detached views contained within a
         * recycled view pool will remain.
         */
        public void clear() {
            mAttachedScrap.clear();
            recycleAndClearCachedViews();
        }

        /**
         * Set the maximum number of detached, valid views we should retain for later use.
         *
         * @param viewCount Number of views to keep before sending views to the shared pool
         */
        public void setViewCacheSize(int viewCount) {
            mViewCacheMax = viewCount;
            // first, try the views that can be recycled
            for (int i = mCachedViews.size() - 1; i >= 0 && mCachedViews.size() > viewCount; i--) {
                recycleCachedViewAt(i);
            }
        }

        /**
         * Returns an unmodifiable list of ViewHolders that are currently in the scrap list.
         *
         * @return List of ViewHolders in the scrap list.
         */
        public List<ViewHolder> getScrapList() {
            return mUnmodifiableAttachedScrap;
        }

        /**
         * Helper method for getViewForPosition.
         * <p>
         * Checks whether a given view holder can be used for the provided position.
         *
         * @param holder ViewHolder
         * @return true if ViewHolder matches the provided position, false otherwise
         */
        boolean validateViewHolderForOffsetPosition(ViewHolder holder) {
            // if it is a removed holder, nothing to verify since we cannot ask adapter anymore
            // if it is not removed, verify the type and id.
            if (holder.isRemoved()) {
                if (DEBUG && !mState.isPreLayout()) {
                    throw new IllegalStateException("should not receive a removed view unless it"
                            + " is pre layout");
                }
                return mState.isPreLayout();
            }
            if (holder.mPosition < 0 || holder.mPosition >= mAdapter.getItemCount()) {
                throw new IndexOutOfBoundsException("Inconsistency detected. Invalid view holder "
                        + "adapter position" + holder);
            }
            if (!mState.isPreLayout()) {
                // don't check type if it is pre-layout.
                final int type = mAdapter.getItemViewType(holder.mPosition);
                if (type != holder.getItemViewType()) {
                    return false;
                }
            }
            if (mAdapter.hasStableIds()) {
                return holder.getItemId() == mAdapter.getItemId(holder.mPosition);
            }
            return true;
        }

        /**
         * Binds the given View to the position. The View can be a View previously retrieved via
         * {@link #getViewForPosition(int)} or created by
         * {@link RecyclerView.Adapter#onCreateViewHolder(ViewGroup, int)}.
         * <p>
         * Generally, a LayoutManager should acquire its views via {@link #getViewForPosition(int)}
         * and let the RecyclerView handle caching. This is a helper method for LayoutManager who
         * wants to handle its own recycling logic.
         * <p>
         * Note that, {@link #getViewForPosition(int)} already binds the View to the position so
         * you don't need to call this method unless you want to bind this View to another position.
         *
         * @param view     The view to update.
         * @param position The position of the item to bind to this View.
         */
        public void bindViewToPosition(View view, int position) {
            ViewHolder holder = getChildViewHolderInt(view);
            if (holder == null) {
                throw new IllegalArgumentException("The view does not have a ViewHolder. You cannot"
                        + " pass arbitrary views to this method, they should be created by the "
                        + "Adapter");
            }
            final int offsetPosition = mAdapterHelper.findPositionOffset(position);
            if (offsetPosition < 0 || offsetPosition >= mAdapter.getItemCount()) {
                throw new IndexOutOfBoundsException("Inconsistency detected. Invalid item "
                        + "position " + position + "(offset:" + offsetPosition + ")."
                        + "state:" + mState.getItemCount());
            }
            holder.mOwnerRecyclerView = DRecyclerView.this;
            mAdapter.bindViewHolder(holder, offsetPosition);
            attachAccessibilityDelegate(view);
            if (mState.isPreLayout()) {
                holder.mPreLayoutPosition = position;
            }

            final ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            final LayoutParams rvLayoutParams;
            if (lp == null) {
                rvLayoutParams = (LayoutParams) generateDefaultLayoutParams();
                holder.itemView.setLayoutParams(rvLayoutParams);
            } else if (!checkLayoutParams(lp)) {
                rvLayoutParams = (LayoutParams) generateLayoutParams(lp);
                holder.itemView.setLayoutParams(rvLayoutParams);
            } else {
                rvLayoutParams = (LayoutParams) lp;
            }

            rvLayoutParams.mInsetsDirty = true;
            rvLayoutParams.mViewHolder = holder;
            rvLayoutParams.mPendingInvalidate = holder.itemView.getParent() == null;
        }

        /**
         * RecyclerView provides artificial position range (item count) in pre-layout state and
         * automatically maps these positions to {@link RecyclerView.Adapter} positions when
         * {@link #getViewForPosition(int)} or {@link #bindViewToPosition(View, int)} is called.
         * <p>
         * Usually, LayoutManager does not need to worry about this. However, in some cases, your
         * LayoutManager may need to call some custom component with item positions in which
         * case you need the actual adapter position instead of the pre layout position. You
         * can use this method to convert a pre-layout position to adapter (post layout) position.
         * <p>
         * Note that if the provided position belongs to a deleted ViewHolder, this method will
         * return -1.
         * <p>
         * Calling this method in post-layout state returns the same value back.
         *
         * @param position The pre-layout position to convert. Must be greater or equal to 0 and
         *                 less than {@link State#getItemCount()}.
         */
        public int convertPreLayoutPositionToPostLayout(int position) {
            if (position < 0 || position >= mState.getItemCount()) {
                throw new IndexOutOfBoundsException("invalid position " + position + ". State "
                        + "item count is " + mState.getItemCount());
            }
            if (!mState.isPreLayout()) {
                return position;
            }
            return mAdapterHelper.findPositionOffset(position);
        }

        public View getViewForPosition(int position) {
            return getViewForPosition(position, false);
        }

        View getViewForPosition(int position, boolean dryRun) {
            if (position < 0 || position >= mState.getItemCount()) {
                throw new IndexOutOfBoundsException("Invalid item position " + position
                        + "(" + position + "). Item count:" + mState.getItemCount());
            }
            boolean fromScrap = false;
            ViewHolder holder = null;
            // 0) If there is a changed scrap, try to find from there
            if (mState.isPreLayout()) {
                holder = getChangedScrapViewForPosition(position);
                fromScrap = holder != null;
            }
            // 1) Find from scrap by position
            if (holder == null) {
                holder = getScrapViewForPosition(position, INVALID_TYPE, dryRun);
                if (holder != null) {
                    if (!validateViewHolderForOffsetPosition(holder)) {
                        // recycle this scrap
                        if (!dryRun) {
                            // we would like to recycle this but need to make sure it is not used by
                            // animation logic etc.
                            holder.addFlags(ViewHolder.FLAG_INVALID);
                            if (holder.isScrap()) {
                                removeDetachedView(holder.itemView, false);
                                holder.unScrap();
                            } else if (holder.wasReturnedFromScrap()) {
                                holder.clearReturnedFromScrapFlag();
                            }
                            recycleViewHolderInternal(holder);
                        }
                        holder = null;
                    } else {
                        fromScrap = true;
                    }
                }
            }
            if (holder == null) {
                final int offsetPosition = mAdapterHelper.findPositionOffset(position);
                if (offsetPosition < 0 || offsetPosition >= mAdapter.getItemCount()) {
                    throw new IndexOutOfBoundsException("Inconsistency detected. Invalid item "
                            + "position " + position + "(offset:" + offsetPosition + ")."
                            + "state:" + mState.getItemCount());
                }

                final int type = mAdapter.getItemViewType(offsetPosition);
                // 2) Find from scrap via stable ids, if exists
                if (mAdapter.hasStableIds()) {
                    holder = getScrapViewForId(mAdapter.getItemId(offsetPosition), type, dryRun);
                    if (holder != null) {
                        // update position
                        holder.mPosition = offsetPosition;
                        fromScrap = true;
                    }
                }
                if (holder == null && mViewCacheExtension != null) {
                    // We are NOT sending the offsetPosition because LayoutManager does not
                    // know it.
                    final View view = mViewCacheExtension
                            .getViewForPositionAndType(this, position, type);
                    if (view != null) {
                        holder = getChildViewHolder(view);
                        if (holder == null) {
                            throw new IllegalArgumentException("getViewForPositionAndType returned"
                                    + " a view which does not have a ViewHolder");
                        } else if (holder.shouldIgnore()) {
                            throw new IllegalArgumentException("getViewForPositionAndType returned"
                                    + " a view that is ignored. You must call stopIgnoring before"
                                    + " returning this view.");
                        }
                    }
                }
                if (holder == null) { // fallback to recycler
                    // try recycler.
                    // Head to the shared pool.
                    if (DEBUG) {
                        Log.d(TAG, "getViewForPosition(" + position + ") fetching from shared "
                                + "pool");
                    }
                    holder = getRecycledViewPool().getRecycledView(type);
                    if (holder != null) {
                        holder.resetInternal();
                        if (FORCE_INVALIDATE_DISPLAY_LIST) {
                            invalidateDisplayListInt(holder);
                        }
                    }
                }
                if (holder == null) {
                    holder = mAdapter.createViewHolder(DRecyclerView.this, type);
                    if (DEBUG) {
                        Log.d(TAG, "getViewForPosition created new ViewHolder");
                    }
                }
            }

            // This is very ugly but the only place we can grab this information
            // before the View is rebound and returned to the LayoutManager for post layout ops.
            // We don't need this in pre-layout since the VH is not updated by the LM.
            if (fromScrap && !mState.isPreLayout() && holder
                    .hasAnyOfTheFlags(ViewHolder.FLAG_BOUNCED_FROM_HIDDEN_LIST)) {
                holder.setFlags(0, ViewHolder.FLAG_BOUNCED_FROM_HIDDEN_LIST);
                if (mState.mRunSimpleAnimations) {
                    int changeFlags = ItemAnimator
                            .buildAdapterChangeFlagsForAnimations(holder);
                    changeFlags |= ItemAnimator.FLAG_APPEARED_IN_PRE_LAYOUT;
                    final ItemHolderInfo info = mItemAnimator.recordPreLayoutInformation(mState,
                            holder, changeFlags, holder.getUnmodifiedPayloads());
                    recordAnimationInfoIfBouncedHiddenView(holder, info);
                }
            }

            boolean bound = false;
            if (mState.isPreLayout() && holder.isBound()) {
                // do not update unless we absolutely have to.
                holder.mPreLayoutPosition = position;
            } else if (!holder.isBound() || holder.needsUpdate() || holder.isInvalid()) {
                if (DEBUG && holder.isRemoved()) {
                    throw new IllegalStateException("Removed holder should be bound and it should"
                            + " come here only in pre-layout. Holder: " + holder);
                }
                final int offsetPosition = mAdapterHelper.findPositionOffset(position);
                holder.mOwnerRecyclerView = DRecyclerView.this;
                mAdapter.bindViewHolder(holder, offsetPosition);
                attachAccessibilityDelegate(holder.itemView);
                bound = true;
                if (mState.isPreLayout()) {
                    holder.mPreLayoutPosition = position;
                }
            }

            final ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            final LayoutParams rvLayoutParams;
            if (lp == null) {
                rvLayoutParams = (LayoutParams) generateDefaultLayoutParams();
                holder.itemView.setLayoutParams(rvLayoutParams);
            } else if (!checkLayoutParams(lp)) {
                rvLayoutParams = (LayoutParams) generateLayoutParams(lp);
                holder.itemView.setLayoutParams(rvLayoutParams);
            } else {
                rvLayoutParams = (LayoutParams) lp;
            }
            rvLayoutParams.mViewHolder = holder;
            rvLayoutParams.mPendingInvalidate = fromScrap && bound;
            return holder.itemView;
        }

        private void attachAccessibilityDelegate(View itemView) {
            if (isAccessibilityEnabled()) {
                if (ViewCompat.getImportantForAccessibility(itemView) ==
                        ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                    ViewCompat.setImportantForAccessibility(itemView,
                            ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
                }
                if (!ViewCompat.hasAccessibilityDelegate(itemView)) {
                    ViewCompat.setAccessibilityDelegate(itemView,
                            mAccessibilityDelegate.getItemDelegate());
                }
            }
        }

        private void invalidateDisplayListInt(ViewHolder holder) {
            if (holder.itemView instanceof ViewGroup) {
                invalidateDisplayListInt((ViewGroup) holder.itemView, false);
            }
        }

        private void invalidateDisplayListInt(ViewGroup viewGroup, boolean invalidateThis) {
            for (int i = viewGroup.getChildCount() - 1; i >= 0; i--) {
                final View view = viewGroup.getChildAt(i);
                if (view instanceof ViewGroup) {
                    invalidateDisplayListInt((ViewGroup) view, true);
                }
            }
            if (!invalidateThis) {
                return;
            }
            // we need to force it to become invisible
            if (viewGroup.getVisibility() == View.INVISIBLE) {
                viewGroup.setVisibility(View.VISIBLE);
                viewGroup.setVisibility(View.INVISIBLE);
            } else {
                final int visibility = viewGroup.getVisibility();
                viewGroup.setVisibility(View.INVISIBLE);
                viewGroup.setVisibility(visibility);
            }
        }

        /**
         * Recycle a detached view. The specified view will be added to a pool of views
         * for later rebinding and reuse.
         * <p>
         * <p>A view must be fully detached (removed from parent) before it may be recycled. If the
         * View is scrapped, it will be removed from scrap list.</p>
         *
         * @param view Removed view for recycling
         * @see LayoutManager#removeAndRecycleView(View, Recycler)
         */
        public void recycleView(View view) {
            // This public recycle method tries to make view recycle-able since layout manager
            // intended to recycle this view (e.g. even if it is in scrap or change cache)
            ViewHolder holder = getChildViewHolderInt(view);
            if (holder.isTmpDetached()) {
                removeDetachedView(view, false);
            }
            if (holder.isScrap()) {
                holder.unScrap();
            } else if (holder.wasReturnedFromScrap()) {
                holder.clearReturnedFromScrapFlag();
            }
            recycleViewHolderInternal(holder);
        }

        /**
         * Internally, use this method instead of {@link #recycleView(android.view.View)} to
         * catch potential bugs.
         *
         * @param view
         */
        void recycleViewInternal(View view) {
            recycleViewHolderInternal(getChildViewHolderInt(view));
        }

        void recycleAndClearCachedViews() {
            final int count = mCachedViews.size();
            for (int i = count - 1; i >= 0; i--) {
                recycleCachedViewAt(i);
            }
            mCachedViews.clear();
        }

        /**
         * Recycles a cached view and removes the view from the list. Views are added to cache
         * if and only if they are recyclable, so this method does not check it again.
         * <p>
         * A small exception to this rule is when the view does not have an animator reference
         * but transient state is true (due to animations created outside ItemAnimator). In that
         * case, adapter may choose to recycle it. From RecyclerView's perspective, the view is
         * still recyclable since Adapter wants to do so.
         *
         * @param cachedViewIndex The index of the view in cached views list
         */
        void recycleCachedViewAt(int cachedViewIndex) {
            if (DEBUG) {
                Log.d(TAG, "Recycling cached view at index " + cachedViewIndex);
            }
            ViewHolder viewHolder = mCachedViews.get(cachedViewIndex);
            if (DEBUG) {
                Log.d(TAG, "CachedViewHolder to be recycled: " + viewHolder);
            }
            addViewHolderToRecycledViewPool(viewHolder);
            mCachedViews.remove(cachedViewIndex);
        }

        /**
         * internal implementation checks if view is scrapped or attached and throws an exception
         * if so.
         * Public version un-scraps before calling recycle.
         */
        void recycleViewHolderInternal(ViewHolder holder) {
            if (holder.isScrap() || holder.itemView.getParent() != null) {
                throw new IllegalArgumentException(
                        "Scrapped or attached views may not be recycled. isScrap:"
                                + holder.isScrap() + " isAttached:"
                                + (holder.itemView.getParent() != null));
            }

            if (holder.isTmpDetached()) {
                throw new IllegalArgumentException("Tmp detached view should be removed "
                        + "from RecyclerView before it can be recycled: " + holder);
            }

            if (holder.shouldIgnore()) {
                throw new IllegalArgumentException("Trying to recycle an ignored view holder. You"
                        + " should first call stopIgnoringView(view) before calling recycle.");
            }
            //noinspection unchecked
            final boolean transientStatePreventsRecycling = holder
                    .doesTransientStatePreventRecycling();
            final boolean forceRecycle = mAdapter != null
                    && transientStatePreventsRecycling
                    && mAdapter.onFailedToRecycleView(holder);
            boolean cached = false;
            boolean recycled = false;
            if (DEBUG && mCachedViews.contains(holder)) {
                throw new IllegalArgumentException("cached view received recycle internal? " +
                        holder);
            }
            if (forceRecycle || holder.isRecyclable()) {
                if (!holder.hasAnyOfTheFlags(ViewHolder.FLAG_INVALID | ViewHolder.FLAG_REMOVED
                        | ViewHolder.FLAG_UPDATE)) {
                    // Retire oldest cached view
                    int cachedViewSize = mCachedViews.size();
                    if (cachedViewSize >= mViewCacheMax && cachedViewSize > 0) {
                        recycleCachedViewAt(0);
                        cachedViewSize--;
                    }
                    if (cachedViewSize < mViewCacheMax) {
                        mCachedViews.add(holder);
                        cached = true;
                    }
                }
                if (!cached) {
                    addViewHolderToRecycledViewPool(holder);
                    recycled = true;
                }
            } else if (DEBUG) {
                Log.d(TAG, "trying to recycle a non-recycleable holder. Hopefully, it will "
                        + "re-visit here. We are still removing it from animation lists");
            }
            // even if the holder is not removed, we still call this method so that it is removed
            // from view holder lists.
            mViewInfoStore.removeViewHolder(holder);
            if (!cached && !recycled && transientStatePreventsRecycling) {
                holder.mOwnerRecyclerView = null;
            }
        }

        void addViewHolderToRecycledViewPool(ViewHolder holder) {
            ViewCompat.setAccessibilityDelegate(holder.itemView, null);
            dispatchViewRecycled(holder);
            holder.mOwnerRecyclerView = null;
            getRecycledViewPool().putRecycledView(holder);
        }

        /**
         * Used as a fast path for unscrapping and recycling a view during a bulk operation.
         * The caller must call {@link #clearScrap()} when it's done to update the recycler's
         * internal bookkeeping.
         */
        void quickRecycleScrapView(View view) {
            final ViewHolder holder = getChildViewHolderInt(view);
            holder.mScrapContainer = null;
            holder.mInChangeScrap = false;
            holder.clearReturnedFromScrapFlag();
            recycleViewHolderInternal(holder);
        }

        /**
         * Mark an attached view as scrap.
         * <p>
         * <p>"Scrap" views are still attached to their parent RecyclerView but are eligible
         * for rebinding and reuse. Requests for a view for a given position may return a
         * reused or rebound scrap view instance.</p>
         *
         * @param view View to scrap
         */
        void scrapView(View view) {
            final ViewHolder holder = getChildViewHolderInt(view);
            if (holder.hasAnyOfTheFlags(ViewHolder.FLAG_REMOVED | ViewHolder.FLAG_INVALID)
                    || !holder.isUpdated() || canReuseUpdatedViewHolder(holder)) {
                if (holder.isInvalid() && !holder.isRemoved() && !mAdapter.hasStableIds()) {
                    throw new IllegalArgumentException("Called scrap view with an invalid view."
                            + " Invalid views cannot be reused from scrap, they should rebound from"
                            + " recycler pool.");
                }
                holder.setScrapContainer(this, false);
                mAttachedScrap.add(holder);
            } else {
                if (mChangedScrap == null) {
                    mChangedScrap = new ArrayList<ViewHolder>();
                }
                holder.setScrapContainer(this, true);
                mChangedScrap.add(holder);
            }
        }

        /**
         * Remove a previously scrapped view from the pool of eligible scrap.
         * <p>
         * <p>This view will no longer be eligible for reuse until re-scrapped or
         * until it is explicitly removed and recycled.</p>
         */
        void unscrapView(ViewHolder holder) {
            if (holder.mInChangeScrap) {
                mChangedScrap.remove(holder);
            } else {
                mAttachedScrap.remove(holder);
            }
            holder.mScrapContainer = null;
            holder.mInChangeScrap = false;
            holder.clearReturnedFromScrapFlag();
        }

        int getScrapCount() {
            return mAttachedScrap.size();
        }

        View getScrapViewAt(int index) {
            return mAttachedScrap.get(index).itemView;
        }

        void clearScrap() {
            mAttachedScrap.clear();
            if (mChangedScrap != null) {
                mChangedScrap.clear();
            }
        }

        ViewHolder getChangedScrapViewForPosition(int position) {
            // If pre-layout, check the changed scrap for an exact match.
            final int changedScrapSize;
            if (mChangedScrap == null || (changedScrapSize = mChangedScrap.size()) == 0) {
                return null;
            }
            // find by position
            for (int i = 0; i < changedScrapSize; i++) {
                final ViewHolder holder = mChangedScrap.get(i);
                if (!holder.wasReturnedFromScrap() && holder.getLayoutPosition() == position) {
                    holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                    return holder;
                }
            }
            // find by id
            if (mAdapter.hasStableIds()) {
                final int offsetPosition = mAdapterHelper.findPositionOffset(position);
                if (offsetPosition > 0 && offsetPosition < mAdapter.getItemCount()) {
                    final long id = mAdapter.getItemId(offsetPosition);
                    for (int i = 0; i < changedScrapSize; i++) {
                        final ViewHolder holder = mChangedScrap.get(i);
                        if (!holder.wasReturnedFromScrap() && holder.getItemId() == id) {
                            holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                            return holder;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Returns a scrap view for the position. If type is not INVALID_TYPE, it also checks if
         * ViewHolder's type matches the provided type.
         *
         * @param position Item position
         * @param type     View type
         * @param dryRun   Does a dry run, finds the ViewHolder but does not remove
         * @return a ViewHolder that can be re-used for this position.
         */
        ViewHolder getScrapViewForPosition(int position, int type, boolean dryRun) {
            final int scrapCount = mAttachedScrap.size();

            // Try first for an exact, non-invalid match from scrap.
            for (int i = 0; i < scrapCount; i++) {
                final ViewHolder holder = mAttachedScrap.get(i);
                if (!holder.wasReturnedFromScrap() && holder.getLayoutPosition() == position
                        && !holder.isInvalid() && (mState.mInPreLayout || !holder.isRemoved())) {
                    if (type != INVALID_TYPE && holder.getItemViewType() != type) {
                        Log.e(TAG, "Scrap view for position " + position + " isn't dirty but has" +
                                " wrong view type! (found " + holder.getItemViewType() +
                                " but expected " + type + ")");
                        break;
                    }
                    holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                    return holder;
                }
            }

            if (!dryRun) {
                View view = mChildHelper.findHiddenNonRemovedView(position, type);
                if (view != null) {
                    // This View is good to be used. We just need to unhide, detach and move to the
                    // scrap list.
                    final ViewHolder vh = getChildViewHolderInt(view);
                    mChildHelper.unhide(view);
                    int layoutIndex = mChildHelper.indexOfChild(view);
                    if (layoutIndex == RecyclerView.NO_POSITION) {
                        throw new IllegalStateException("layout index should not be -1 after "
                                + "unhiding a view:" + vh);
                    }
                    mChildHelper.detachViewFromParent(layoutIndex);
                    scrapView(view);
                    vh.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP
                            | ViewHolder.FLAG_BOUNCED_FROM_HIDDEN_LIST);
                    return vh;
                }
            }

            // Search in our first-level recycled view cache.
            final int cacheSize = mCachedViews.size();
            for (int i = 0; i < cacheSize; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                // invalid view holders may be in cache if adapter has stable ids as they can be
                // retrieved via getScrapViewForId
                if (!holder.isInvalid() && holder.getLayoutPosition() == position) {
                    if (!dryRun) {
                        mCachedViews.remove(i);
                    }
                    if (DEBUG) {
                        Log.d(TAG, "getScrapViewForPosition(" + position + ", " + type +
                                ") found match in cache: " + holder);
                    }
                    return holder;
                }
            }
            return null;
        }

        ViewHolder getScrapViewForId(long id, int type, boolean dryRun) {
            // Look in our attached views first
            final int count = mAttachedScrap.size();
            for (int i = count - 1; i >= 0; i--) {
                final ViewHolder holder = mAttachedScrap.get(i);
                if (holder.getItemId() == id && !holder.wasReturnedFromScrap()) {
                    if (type == holder.getItemViewType()) {
                        holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                        if (holder.isRemoved()) {
                            // this might be valid in two cases:
                            // > item is removed but we are in pre-layout pass
                            // >> do nothing. return as is. make sure we don't rebind
                            // > item is removed then added to another position and we are in
                            // post layout.
                            // >> remove removed and invalid flags, add update flag to rebind
                            // because item was invisible to us and we don't know what happened in
                            // between.
                            if (!mState.isPreLayout()) {
                                holder.setFlags(ViewHolder.FLAG_UPDATE, ViewHolder.FLAG_UPDATE |
                                        ViewHolder.FLAG_INVALID | ViewHolder.FLAG_REMOVED);
                            }
                        }
                        return holder;
                    } else if (!dryRun) {
                        // if we are running animations, it is actually better to keep it in scrap
                        // but this would force layout manager to lay it out which would be bad.
                        // Recycle this scrap. Type mismatch.
                        mAttachedScrap.remove(i);
                        removeDetachedView(holder.itemView, false);
                        quickRecycleScrapView(holder.itemView);
                    }
                }
            }

            // Search the first-level cache
            final int cacheSize = mCachedViews.size();
            for (int i = cacheSize - 1; i >= 0; i--) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder.getItemId() == id) {
                    if (type == holder.getItemViewType()) {
                        if (!dryRun) {
                            mCachedViews.remove(i);
                        }
                        return holder;
                    } else if (!dryRun) {
                        recycleCachedViewAt(i);
                    }
                }
            }
            return null;
        }

        void dispatchViewRecycled(ViewHolder holder) {
            if (mRecyclerListener != null) {
                mRecyclerListener.onViewRecycled(holder);
            }
            if (mAdapter != null) {
                mAdapter.onViewRecycled(holder);
            }
            if (mState != null) {
                mViewInfoStore.removeViewHolder(holder);
            }
            if (DEBUG) Log.d(TAG, "dispatchViewRecycled: " + holder);
        }

        void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter,
                              boolean compatibleWithPrevious) {
            clear();
            getRecycledViewPool().onAdapterChanged(oldAdapter, newAdapter, compatibleWithPrevious);
        }

        void offsetPositionRecordsForMove(int from, int to) {
            final int start, end, inBetweenOffset;
            if (from < to) {
                start = from;
                end = to;
                inBetweenOffset = -1;
            } else {
                start = to;
                end = from;
                inBetweenOffset = 1;
            }
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder == null || holder.mPosition < start || holder.mPosition > end) {
                    continue;
                }
                if (holder.mPosition == from) {
                    holder.offsetPosition(to - from, false);
                } else {
                    holder.offsetPosition(inBetweenOffset, false);
                }
                if (DEBUG) {
                    Log.d(TAG, "offsetPositionRecordsForMove cached child " + i + " holder " +
                            holder);
                }
            }
        }

        void offsetPositionRecordsForInsert(int insertedAt, int count) {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder != null && holder.mPosition >= insertedAt) {
                    if (DEBUG) {
                        Log.d(TAG, "offsetPositionRecordsForInsert cached " + i + " holder " +
                                holder + " now at position " + (holder.mPosition + count));
                    }
                    holder.offsetPosition(count, true);
                }
            }
        }

        /**
         * @param removedFrom      Remove start index
         * @param count            Remove count
         * @param applyToPreLayout If true, changes will affect ViewHolder's pre-layout position, if
         *                         false, they'll be applied before the second layout pass
         */
        void offsetPositionRecordsForRemove(int removedFrom, int count, boolean applyToPreLayout) {
            final int removedEnd = removedFrom + count;
            final int cachedCount = mCachedViews.size();
            for (int i = cachedCount - 1; i >= 0; i--) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder != null) {
                    if (holder.mPosition >= removedEnd) {
                        if (DEBUG) {
                            Log.d(TAG, "offsetPositionRecordsForRemove cached " + i +
                                    " holder " + holder + " now at position " +
                                    (holder.mPosition - count));
                        }
                        holder.offsetPosition(-count, applyToPreLayout);
                    } else if (holder.mPosition >= removedFrom) {
                        // Item for this view was removed. Dump it from the cache.
                        holder.addFlags(ViewHolder.FLAG_REMOVED);
                        recycleCachedViewAt(i);
                    }
                }
            }
        }

        void setViewCacheExtension(ViewCacheExtension extension) {
            mViewCacheExtension = extension;
        }

        void setRecycledViewPool(RecycledViewPool pool) {
            if (mRecyclerPool != null) {
                mRecyclerPool.detach();
            }
            mRecyclerPool = pool;
            if (pool != null) {
                mRecyclerPool.attach(mAdapter);
            }
        }

        RecycledViewPool getRecycledViewPool() {
            if (mRecyclerPool == null) {
                mRecyclerPool = new RecycledViewPool();
            }
            return mRecyclerPool;
        }

        void viewRangeUpdate(int positionStart, int itemCount) {
            final int positionEnd = positionStart + itemCount;
            final int cachedCount = mCachedViews.size();
            for (int i = cachedCount - 1; i >= 0; i--) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder == null) {
                    continue;
                }

                final int pos = holder.getLayoutPosition();
                if (pos >= positionStart && pos < positionEnd) {
                    holder.addFlags(ViewHolder.FLAG_UPDATE);
                    recycleCachedViewAt(i);
                    // cached views should not be flagged as changed because this will cause them
                    // to animate when they are returned from cache.
                }
            }
        }

        void setAdapterPositionsAsUnknown() {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder != null) {
                    holder.addFlags(ViewHolder.FLAG_ADAPTER_POSITION_UNKNOWN);
                }
            }
        }

        void markKnownViewsInvalid() {
            if (mAdapter != null && mAdapter.hasStableIds()) {
                final int cachedCount = mCachedViews.size();
                for (int i = 0; i < cachedCount; i++) {
                    final ViewHolder holder = mCachedViews.get(i);
                    if (holder != null) {
                        holder.addFlags(ViewHolder.FLAG_UPDATE | ViewHolder.FLAG_INVALID);
                        holder.addChangePayload(null);
                    }
                }
            } else {
                // we cannot re-use cached views in this case. Recycle them all
                recycleAndClearCachedViews();
            }
        }

        void clearOldPositions() {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                holder.clearOldPosition();
            }
            final int scrapCount = mAttachedScrap.size();
            for (int i = 0; i < scrapCount; i++) {
                mAttachedScrap.get(i).clearOldPosition();
            }
            if (mChangedScrap != null) {
                final int changedScrapCount = mChangedScrap.size();
                for (int i = 0; i < changedScrapCount; i++) {
                    mChangedScrap.get(i).clearOldPosition();
                }
            }
        }

        void markItemDecorInsetsDirty() {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                LayoutParams layoutParams = (LayoutParams) holder.itemView.getLayoutParams();
                if (layoutParams != null) {
                    layoutParams.mInsetsDirty = true;
                }
            }
        }
    }

    public abstract static class ViewCacheExtension {

        abstract public View getViewForPositionAndType(Recycler recycler, int position, int type);
    }

    /**
     * RecycledViewPool lets you share Views between multiple RecyclerViews.
     * <p>
     * If you want to recycle views across RecyclerViews, create an instance of RecycledViewPool
     * and use {@link DRecyclerView#setRecycledViewPool(RecycledViewPool)}.
     * <p>
     * RecyclerView automatically creates a pool for itself if you don't provide one.
     */
    public static class RecycledViewPool {
        private SparseArray<ArrayList<ViewHolder>> mScrap = new SparseArray<ArrayList<ViewHolder>>();
        private SparseIntArray mMaxScrap = new SparseIntArray();
        private int mAttachCount = 0;

        private static final int DEFAULT_MAX_SCRAP = 5;

        public void clear() {
            mScrap.clear();
        }

        public void setMaxRecycledViews(int viewType, int max) {
            mMaxScrap.put(viewType, max);
            final ArrayList<ViewHolder> scrapHeap = mScrap.get(viewType);
            if (scrapHeap != null) {
                while (scrapHeap.size() > max) {
                    scrapHeap.remove(scrapHeap.size() - 1);
                }
            }
        }

        public ViewHolder getRecycledView(int viewType) {
            final ArrayList<ViewHolder> scrapHeap = mScrap.get(viewType);
            if (scrapHeap != null && !scrapHeap.isEmpty()) {
                final int index = scrapHeap.size() - 1;
                final ViewHolder scrap = scrapHeap.get(index);
                scrapHeap.remove(index);
                return scrap;
            }
            return null;
        }

        int size() {
            int count = 0;
            for (int i = 0; i < mScrap.size(); i++) {
                ArrayList<ViewHolder> viewHolders = mScrap.valueAt(i);
                if (viewHolders != null) {
                    count += viewHolders.size();
                }
            }
            return count;
        }

        public void putRecycledView(ViewHolder scrap) {
            final int viewType = scrap.getItemViewType();
            final ArrayList scrapHeap = getScrapHeapForType(viewType);
            if (mMaxScrap.get(viewType) <= scrapHeap.size()) {
                return;
            }
            if (DEBUG && scrapHeap.contains(scrap)) {
                throw new IllegalArgumentException("this scrap item already exists");
            }
            scrap.resetInternal();
            scrapHeap.add(scrap);
        }

        void attach(RecyclerView.Adapter adapter) {
            mAttachCount++;
        }

        void detach() {
            mAttachCount--;
        }


        /**
         * Detaches the old adapter and attaches the new one.
         * <p>
         * RecycledViewPool will clear its cache if it has only one adapter attached and the new
         * adapter uses a different ViewHolder than the oldAdapter.
         *
         * @param oldAdapter             The previous adapter instance. Will be detached.
         * @param newAdapter             The new adapter instance. Will be attached.
         * @param compatibleWithPrevious True if both oldAdapter and newAdapter are using the same
         *                               ViewHolder and view types.
         */
        void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter,
                              boolean compatibleWithPrevious) {
            if (oldAdapter != null) {
                detach();
            }
            if (!compatibleWithPrevious && mAttachCount == 0) {
                clear();
            }
            if (newAdapter != null) {
                attach(newAdapter);
            }
        }

        private ArrayList<ViewHolder> getScrapHeapForType(int viewType) {
            ArrayList<ViewHolder> scrap = mScrap.get(viewType);
            if (scrap == null) {
                scrap = new ArrayList<>();
                mScrap.put(viewType, scrap);
                if (mMaxScrap.indexOfKey(viewType) < 0) {
                    mMaxScrap.put(viewType, DEFAULT_MAX_SCRAP);
                }
            }
            return scrap;
        }
    }

    public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {
        ViewHolder mViewHolder;
        final Rect mDecorInsets = new Rect();
        boolean mInsetsDirty = true;
        // Flag is set to true if the view is bound while it is detached from RV.
        // In this case, we need to manually call invalidate after view is added to guarantee that
        // invalidation is populated through the View hierarchy
        boolean mPendingInvalidate = false;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            super((ViewGroup.LayoutParams) source);
        }

        /**
         * Returns true if the view this LayoutParams is attached to needs to have its content
         * updated from the corresponding adapter.
         *
         * @return true if the view should have its content updated
         */
        public boolean viewNeedsUpdate() {
            return mViewHolder.needsUpdate();
        }

        /**
         * Returns true if the view this LayoutParams is attached to is now representing
         * potentially invalid data. A LayoutManager should scrap/recycle it.
         *
         * @return true if the view is invalid
         */
        public boolean isViewInvalid() {
            return mViewHolder.isInvalid();
        }

        /**
         * Returns true if the adapter data item corresponding to the view this LayoutParams
         * is attached to has been removed from the data set. A LayoutManager may choose to
         * treat it differently in order to animate its outgoing or disappearing state.
         *
         * @return true if the item the view corresponds to was removed from the data set
         */
        public boolean isItemRemoved() {
            return mViewHolder.isRemoved();
        }

        /**
         * Returns true if the adapter data item corresponding to the view this LayoutParams
         * is attached to has been changed in the data set. A LayoutManager may choose to
         * treat it differently in order to animate its changing state.
         *
         * @return true if the item the view corresponds to was changed in the data set
         */
        public boolean isItemChanged() {
            return mViewHolder.isUpdated();
        }

        /**
         * @deprecated use {@link #getViewLayoutPosition()} or {@link #getViewAdapterPosition()}
         */
        @Deprecated
        public int getViewPosition() {
            return mViewHolder.getPosition();
        }

        /**
         * Returns the adapter position that the view this LayoutParams is attached to corresponds
         * to as of latest layout calculation.
         *
         * @return the adapter position this view as of latest layout pass
         */
        public int getViewLayoutPosition() {
            return mViewHolder.getLayoutPosition();
        }

        /**
         * Returns the up-to-date adapter position that the view this LayoutParams is attached to
         * corresponds to.
         *
         * @return the up-to-date adapter position this view. It may return
         * {@link RecyclerView#NO_POSITION} if item represented by this View has been removed or
         * its up-to-date position cannot be calculated.
         */
        public int getViewAdapterPosition() {
            return mViewHolder.getAdapterPosition();
        }
    }

    static class AdapterDataObservable extends Observable<RecyclerView.AdapterDataObserver> {
        public boolean hasObservers() {
            return !mObservers.isEmpty();
        }

        public void notifyChanged() {
            // since onChanged() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onChanged();
            }
        }

        public void notifyItemRangeChanged(int positionStart, int itemCount) {
            notifyItemRangeChanged(positionStart, itemCount, null);
        }

        public void notifyItemRangeChanged(int positionStart, int itemCount, Object payload) {
            // since onItemRangeChanged() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeChanged(positionStart, itemCount, payload);
            }
        }

        public void notifyItemRangeInserted(int positionStart, int itemCount) {
            // since onItemRangeInserted() is implemented by the app, it could do anything,
            // including removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeInserted(positionStart, itemCount);
            }
        }

        public void notifyItemRangeRemoved(int positionStart, int itemCount) {
            // since onItemRangeRemoved() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeRemoved(positionStart, itemCount);
            }
        }

        public void notifyItemMoved(int fromPosition, int toPosition) {
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeMoved(fromPosition, toPosition, 1);
            }
        }
    }

    public static class SavedState extends AbsSavedState {

        Parcelable mLayoutState;

        /**
         * called by CREATOR
         */
        SavedState(Parcel in, ClassLoader loader) {
            super(in, loader);
            mLayoutState = in.readParcelable(
                    loader != null ? loader : LayoutManager.class.getClassLoader());
        }

        /**
         * Called by onSaveInstanceState
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(mLayoutState, 0);
        }

        void copyFrom(SavedState other) {
            mLayoutState = other.mLayoutState;
        }

        public static final Creator<SavedState> CREATOR = ParcelableCompat.newCreator(
                new ParcelableCompatCreatorCallbacks<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                        return new SavedState(in, loader);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                });
    }

    static final boolean ALLOW_SIZE_IN_UNSPECIFIED_SPEC = Build.VERSION.SDK_INT >= 23;

    public static abstract class LayoutManager {
        ChildHelper mChildHelper;
        DRecyclerView mRecyclerView;

        @Nullable
        RecyclerView.SmoothScroller mSmoothScroller;

        boolean mRequestedSimpleAnimations = false;

        boolean mIsAttachedToWindow = false;

        boolean mAutoMeasure = false;

        /**
         * LayoutManager has its own more strict measurement cache to avoid re-measuring a child
         * if the space that will be given to it is already larger than what it has measured before.
         */
        private boolean mMeasurementCacheEnabled = true;


        /**
         * These measure specs might be the measure specs that were passed into RecyclerView's
         * onMeasure method OR fake measure specs created by the RecyclerView.
         * For example, when a layout is run, RecyclerView always sets these specs to be
         * EXACTLY because a LayoutManager cannot resize RecyclerView during a layout pass.
         * <p>
         * Also, to be able to use the hint in unspecified measure specs, RecyclerView checks the
         * API level and sets the size to 0 pre-M to avoid any issue that might be caused by
         * corrupt values. Older platforms have no responsibility to provide a size if they set
         * mode to unspecified.
         */
        private int mWidthMode, mHeightMode;
        private int mWidth, mHeight;

        void setRecyclerView(DRecyclerView recyclerView) {
            if (recyclerView == null) {
                mRecyclerView = null;
                mChildHelper = null;
                mWidth = 0;
                mHeight = 0;
            } else {
                mRecyclerView = recyclerView;
                mChildHelper = recyclerView.mChildHelper;
                mWidth = recyclerView.getWidth();
                mHeight = recyclerView.getHeight();
            }
            mWidthMode = MeasureSpec.EXACTLY;
            mHeightMode = MeasureSpec.EXACTLY;
        }

        void setMeasureSpecs(int wSpec, int hSpec) {
            mWidth = MeasureSpec.getSize(wSpec);
            mWidthMode = MeasureSpec.getMode(wSpec);
            if (mWidthMode == MeasureSpec.UNSPECIFIED && !ALLOW_SIZE_IN_UNSPECIFIED_SPEC) {
                mWidth = 0;
            }

            mHeight = MeasureSpec.getSize(hSpec);
            mHeightMode = MeasureSpec.getMode(hSpec);
            if (mHeightMode == MeasureSpec.UNSPECIFIED && !ALLOW_SIZE_IN_UNSPECIFIED_SPEC) {
                mHeight = 0;
            }
        }

        /**
         * Called after a layout is calculated during a measure pass when using auto-measure.
         * <p>
         * It simply traverses all children to calculate a bounding box then calls
         * {@link #setMeasuredDimension(Rect, int, int)}. LayoutManagers can override that method
         * if they need to handle the bounding box differently.
         * <p>
         * For example, GridLayoutManager override that method to ensure that even if a column is
         * empty, the GridLayoutManager still measures wide enough to include it.
         *
         * @param widthSpec  The widthSpec that was passing into RecyclerView's onMeasure
         * @param heightSpec The heightSpec that was passing into RecyclerView's onMeasure
         */
        void setMeasuredDimensionFromChildren(int widthSpec, int heightSpec) {
            final int count = getChildCount();
            if (count == 0) {
                mRecyclerView.defaultOnMeasure(widthSpec, heightSpec);
                return;
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;

            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                final Rect bounds = mRecyclerView.mTempRect;
                getDecoratedBoundsWithMargins(child, bounds);
                if (bounds.left < minX) {
                    minX = bounds.left;
                }
                if (bounds.right > maxX) {
                    maxX = bounds.right;
                }
                if (bounds.top < minY) {
                    minY = bounds.top;
                }
                if (bounds.bottom > maxY) {
                    maxY = bounds.bottom;
                }
            }
            mRecyclerView.mTempRect.set(minX, minY, maxX, maxY);
            setMeasuredDimension(mRecyclerView.mTempRect, widthSpec, heightSpec);
        }

        /**
         * Sets the measured dimensions from the given bounding box of the children and the
         * measurement specs that were passed into {@link RecyclerView#onMeasure(int, int)}. It is
         * called after the RecyclerView calls
         * {@link LayoutManager#onLayoutChildren(Recycler, State)} during a measurement pass.
         * <p>
         * This method should call {@link #setMeasuredDimension(int, int)}.
         * <p>
         * The default implementation adds the RecyclerView's padding to the given bounding box
         * then caps the value to be within the given measurement specs.
         * <p>
         * This method is only called if the LayoutManager opted into the auto measurement API.
         *
         * @param childrenBounds The bounding box of all children
         * @param wSpec          The widthMeasureSpec that was passed into the RecyclerView.
         * @param hSpec          The heightMeasureSpec that was passed into the RecyclerView.
         * @see #setAutoMeasureEnabled(boolean)
         */
        public void setMeasuredDimension(Rect childrenBounds, int wSpec, int hSpec) {
            int usedWidth = childrenBounds.width() + getPaddingLeft() + getPaddingRight();
            int usedHeight = childrenBounds.height() + getPaddingTop() + getPaddingBottom();
            int width = chooseSize(wSpec, usedWidth, getMinimumWidth());
            int height = chooseSize(hSpec, usedHeight, getMinimumHeight());
            setMeasuredDimension(width, height);
        }

        /**
         * Calls {@code RecyclerView#requestLayout} on the underlying RecyclerView
         */
        public void requestLayout() {
            if (mRecyclerView != null) {
                mRecyclerView.requestLayout();
            }
        }

        /**
         * Checks if RecyclerView is in the middle of a layout or scroll and throws an
         * {@link IllegalStateException} if it <b>is not</b>.
         *
         * @param message The message for the exception. Can be null.
         * @see #assertNotInLayoutOrScroll(String)
         */
        public void assertInLayoutOrScroll(String message) {
            if (mRecyclerView != null) {
                mRecyclerView.assertInLayoutOrScroll(message);
            }
        }

        /**
         * Chooses a size from the given specs and parameters that is closest to the desired size
         * and also complies with the spec.
         *
         * @param spec    The measureSpec
         * @param desired The preferred measurement
         * @param min     The minimum value
         * @return A size that fits to the given specs
         */
        public static int chooseSize(int spec, int desired, int min) {
            final int mode = View.MeasureSpec.getMode(spec);
            final int size = View.MeasureSpec.getSize(spec);
            switch (mode) {
                case View.MeasureSpec.EXACTLY:
                    return size;
                case View.MeasureSpec.AT_MOST:
                    return Math.min(size, Math.max(desired, min));
                case View.MeasureSpec.UNSPECIFIED:
                default:
                    return Math.max(desired, min);
            }
        }

        /**
         * Checks if RecyclerView is in the middle of a layout or scroll and throws an
         * {@link IllegalStateException} if it <b>is</b>.
         *
         * @param message The message for the exception. Can be null.
         * @see #assertInLayoutOrScroll(String)
         */
        public void assertNotInLayoutOrScroll(String message) {
            if (mRecyclerView != null) {
                mRecyclerView.assertNotInLayoutOrScroll(message);
            }
        }

        /**
         * Defines whether the layout should be measured by the RecyclerView or the LayoutManager
         * wants to handle the layout measurements itself.
         * <p>
         * This method is usually called by the LayoutManager with value {@code true} if it wants
         * to support WRAP_CONTENT. If you are using a public LayoutManager but want to customize
         * the measurement logic, you can call this method with {@code false} and override
         * {@link LayoutManager#onMeasure(int, int)} to implement your custom measurement logic.
         * <p>
         * AutoMeasure is a convenience mechanism for LayoutManagers to easily wrap their content or
         * handle various specs provided by the RecyclerView's parent.
         * It works by calling {@link LayoutManager#onLayoutChildren(Recycler, State)} during an
         * {@link RecyclerView#onMeasure(int, int)} call, then calculating desired dimensions based
         * on children's positions. It does this while supporting all existing animation
         * capabilities of the RecyclerView.
         * <p>
         * AutoMeasure works as follows:
         * <ol>
         * <li>LayoutManager should call {@code setAutoMeasureEnabled(true)} to enable it. All of
         * the framework LayoutManagers use {@code auto-measure}.</li>
         * <li>When {@link RecyclerView#onMeasure(int, int)} is called, if the provided specs are
         * exact, RecyclerView will only call LayoutManager's {@code onMeasure} and return without
         * doing any layout calculation.</li>
         * <li>If one of the layout specs is not {@code EXACT}, the RecyclerView will start the
         * layout process in {@code onMeasure} call. It will process all pending Adapter updates and
         * decide whether to run a predictive layout or not. If it decides to do so, it will first
         * call {@link #onLayoutChildren(Recycler, State)} with {@link State#isPreLayout()} set to
         * {@code true}. At this stage, {@link #getWidth()} and {@link #getHeight()} will still
         * return the width and height of the RecyclerView as of the last layout calculation.
         * <p>
         * After handling the predictive case, RecyclerView will call
         * {@link #onLayoutChildren(Recycler, State)} with {@link State#isMeasuring()} set to
         * {@code true} and {@link State#isPreLayout()} set to {@code false}. The LayoutManager can
         * access the measurement specs via {@link #getHeight()}, {@link #getHeightMode()},
         * {@link #getWidth()} and {@link #getWidthMode()}.</li>
         * <li>After the layout calculation, RecyclerView sets the measured width & height by
         * calculating the bounding box for the children (+ RecyclerView's padding). The
         * LayoutManagers can override {@link #setMeasuredDimension(Rect, int, int)} to choose
         * different values. For instance, GridLayoutManager overrides this value to handle the case
         * where if it is vertical and has 3 columns but only 2 items, it should still measure its
         * width to fit 3 items, not 2.</li>
         * <li>Any following on measure call to the RecyclerView will run
         * {@link #onLayoutChildren(Recycler, State)} with {@link State#isMeasuring()} set to
         * {@code true} and {@link State#isPreLayout()} set to {@code false}. RecyclerView will
         * take care of which views are actually added / removed / moved / changed for animations so
         * that the LayoutManager should not worry about them and handle each
         * {@link #onLayoutChildren(Recycler, State)} call as if it is the last one.
         * </li>
         * <li>When measure is complete and RecyclerView's
         * {@link #onLayout(boolean, int, int, int, int)} method is called, RecyclerView checks
         * whether it already did layout calculations during the measure pass and if so, it re-uses
         * that information. It may still decide to call {@link #onLayoutChildren(Recycler, State)}
         * if the last measure spec was different from the final dimensions or adapter contents
         * have changed between the measure call and the layout call.</li>
         * <li>Finally, animations are calculated and run as usual.</li>
         * </ol>
         *
         * @param enabled <code>True</code> if the Layout should be measured by the
         *                RecyclerView, <code>false</code> if the LayoutManager wants
         *                to measure itself.
         * @see #setMeasuredDimension(Rect, int, int)
         * @see #isAutoMeasureEnabled()
         */
        public void setAutoMeasureEnabled(boolean enabled) {
            mAutoMeasure = enabled;
        }

        /**
         * Returns whether the LayoutManager uses the automatic measurement API or not.
         *
         * @return <code>True</code> if the LayoutManager is measured by the RecyclerView or
         * <code>false</code> if it measures itself.
         * @see #setAutoMeasureEnabled(boolean)
         */
        public boolean isAutoMeasureEnabled() {
            return mAutoMeasure;
        }

        public boolean supportsPredictiveItemAnimations() {
            return false;
        }

        void dispatchAttachedToWindow(RecyclerView view) {
            mIsAttachedToWindow = true;
            onAttachedToWindow(view);
        }

        void dispatchDetachedFromWindow(RecyclerView view, Recycler recycler) {
            mIsAttachedToWindow = false;
            onDetachedFromWindow(view, recycler);
        }

        /**
         * Returns whether LayoutManager is currently attached to a RecyclerView which is attached
         * to a window.
         *
         * @return True if this LayoutManager is controlling a RecyclerView and the RecyclerView
         * is attached to window.
         */
        public boolean isAttachedToWindow() {
            return mIsAttachedToWindow;
        }

        /**
         * Causes the Runnable to execute on the next animation time step.
         * The runnable will be run on the user interface thread.
         * <p>
         * Calling this method when LayoutManager is not attached to a RecyclerView has no effect.
         *
         * @param action The Runnable that will be executed.
         * @see #removeCallbacks
         */
        public void postOnAnimation(Runnable action) {
            if (mRecyclerView != null) {
                ViewCompat.postOnAnimation(mRecyclerView, action);
            }
        }

        /**
         * Removes the specified Runnable from the message queue.
         * <p>
         * Calling this method when LayoutManager is not attached to a RecyclerView has no effect.
         *
         * @param action The Runnable to remove from the message handling queue
         * @return true if RecyclerView could ask the Handler to remove the Runnable,
         * false otherwise. When the returned value is true, the Runnable
         * may or may not have been actually removed from the message queue
         * (for instance, if the Runnable was not in the queue already.)
         * @see #postOnAnimation
         */
        public boolean removeCallbacks(Runnable action) {
            if (mRecyclerView != null) {
                return mRecyclerView.removeCallbacks(action);
            }
            return false;
        }

        /**
         * Called when this LayoutManager is both attached to a RecyclerView and that RecyclerView
         * is attached to a window.
         * <p>
         * If the RecyclerView is re-attached with the same LayoutManager and Adapter, it may not
         * call {@link #onLayoutChildren(Recycler, State)} if nothing has changed and a layout was
         * not requested on the RecyclerView while it was detached.
         * <p>
         * Subclass implementations should always call through to the superclass implementation.
         *
         * @param view The RecyclerView this LayoutManager is bound to
         * @see #onDetachedFromWindow(DRecyclerView, Recycler)
         */
        @CallSuper
        public void onAttachedToWindow(RecyclerView view) {
        }

        /**
         * @deprecated override {@link #onDetachedFromWindow(DRecyclerView, Recycler)}
         */
        @Deprecated
        public void onDetachedFromWindow(DRecyclerView view) {

        }

        @CallSuper
        public void onDetachedFromWindow(DRecyclerView view, Recycler recycler) {
            onDetachedFromWindow(view);
        }

        /**
         * Check if the RecyclerView is configured to clip child views to its padding.
         *
         * @return true if this RecyclerView clips children to its padding, false otherwise
         */
        public boolean getClipToPadding() {
            return mRecyclerView != null && mRecyclerView.mClipToPadding;
        }

        public void onLayoutChildren(Recycler recycler, State state) {
            Log.e(TAG, "You must override onLayoutChildren(Recycler recycler, State state) ");
        }

        /**
         * Called after a full layout calculation is finished. The layout calculation may include
         * multiple {@link #onLayoutChildren(Recycler, State)} calls due to animations or
         * layout measurement but it will include only one {@link #onLayoutCompleted(State)} call.
         * This method will be called at the end of {@link View#layout(int, int, int, int)} call.
         * <p>
         * This is a good place for the LayoutManager to do some cleanup like pending scroll
         * position, saved state etc.
         *
         * @param state Transient state of RecyclerView
         */
        public void onLayoutCompleted(State state) {
        }

        /**
         * Create a default <code>LayoutParams</code> object for a child of the RecyclerView.
         * <p>
         * <p>LayoutManagers will often want to use a custom <code>LayoutParams</code> type
         * to store extra information specific to the layout. Client code should subclass
         * {@link RecyclerView.LayoutParams} for this purpose.</p>
         * <p>
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @return A new LayoutParams for a child view
         */
        public abstract LayoutParams generateDefaultLayoutParams();

        /**
         * Determines the validity of the supplied LayoutParams object.
         * <p>
         * <p>This should check to make sure that the object is of the correct type
         * and all values are within acceptable ranges. The default implementation
         * returns <code>true</code> for non-null params.</p>
         *
         * @param lp LayoutParams object to check
         * @return true if this LayoutParams object is valid, false otherwise
         */
        public boolean checkLayoutParams(LayoutParams lp) {
            return lp != null;
        }

        /**
         * Create a LayoutParams object suitable for this LayoutManager, copying relevant
         * values from the supplied LayoutParams object if possible.
         * <p>
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @param lp Source LayoutParams object to copy values from
         * @return a new LayoutParams object
         */
        public LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
            if (lp instanceof LayoutParams) {
                return new LayoutParams((LayoutParams) lp);
            } else if (lp instanceof MarginLayoutParams) {
                return new LayoutParams((MarginLayoutParams) lp);
            } else {
                return new LayoutParams(lp);
            }
        }

        /**
         * Create a LayoutParams object suitable for this LayoutManager from
         * an inflated layout resource.
         * <p>
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @param c     Context for obtaining styled attributes
         * @param attrs AttributeSet describing the supplied arguments
         * @return a new LayoutParams object
         */
        public LayoutParams generateLayoutParams(Context c, AttributeSet attrs) {
            return new LayoutParams(c, attrs);
        }

        /**
         * Scroll horizontally by dx pixels in screen coordinates and return the distance traveled.
         * The default implementation does nothing and returns 0.
         *
         * @param dx       distance to scroll by in pixels. X increases as scroll position
         *                 approaches the right.
         * @param recycler Recycler to use for fetching potentially cached views for a
         *                 position
         * @param state    Transient state of RecyclerView
         * @return The actual distance scrolled. The return value will be negative if dx was
         * negative and scrolling proceeeded in that direction.
         * <code>Math.abs(result)</code> may be less than dx if a boundary was reached.
         */
        public int scrollHorizontallyBy(int dx, Recycler recycler, State state) {
            return 0;
        }

        /**
         * Scroll vertically by dy pixels in screen coordinates and return the distance traveled.
         * The default implementation does nothing and returns 0.
         *
         * @param dy       distance to scroll in pixels. Y increases as scroll position
         *                 approaches the bottom.
         * @param recycler Recycler to use for fetching potentially cached views for a
         *                 position
         * @param state    Transient state of RecyclerView
         * @return The actual distance scrolled. The return value will be negative if dy was
         * negative and scrolling proceeeded in that direction.
         * <code>Math.abs(result)</code> may be less than dy if a boundary was reached.
         */
        public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
            return 0;
        }

        /**
         * Query if horizontal scrolling is currently supported. The default implementation
         * returns false.
         *
         * @return True if this LayoutManager can scroll the current contents horizontally
         */
        public boolean canScrollHorizontally() {
            return false;
        }

        /**
         * Query if vertical scrolling is currently supported. The default implementation
         * returns false.
         *
         * @return True if this LayoutManager can scroll the current contents vertically
         */
        public boolean canScrollVertically() {
            return false;
        }

        /**
         * Scroll to the specified adapter position.
         * <p>
         * Actual position of the item on the screen depends on the LayoutManager implementation.
         *
         * @param position Scroll to this adapter position.
         */
        public void scrollToPosition(int position) {
            if (DEBUG) {
                Log.e(TAG, "You MUST implement scrollToPosition. It will soon become abstract");
            }
        }

        public void smoothScrollToPosition(RecyclerView recyclerView, State state,
                                           int position) {
            Log.e(TAG, "You must override smoothScrollToPosition to support smooth scrolling");
        }

        /**
         * <p>Starts a smooth scroll using the provided SmoothScroller.</p>
         * <p>Calling this method will cancel any previous smooth scroll request.</p>
         *
         * @param smoothScroller Instance which defines how smooth scroll should be animated
         */
        public void startSmoothScroll(RecyclerView.SmoothScroller smoothScroller) {
            if (mSmoothScroller != null && smoothScroller != mSmoothScroller
                    && mSmoothScroller.isRunning()) {
                mSmoothScroller.stop();
            }
            mSmoothScroller = smoothScroller;
            mSmoothScroller.start(mRecyclerView, this);
        }

        /**
         * @return true if RecycylerView is currently in the state of smooth scrolling.
         */
        public boolean isSmoothScrolling() {
            return mSmoothScroller != null && mSmoothScroller.isRunning();
        }


        /**
         * Returns the resolved layout direction for this RecyclerView.
         *
         * @return {@link android.support.v4.view.ViewCompat#LAYOUT_DIRECTION_RTL} if the layout
         * direction is RTL or returns
         * {@link android.support.v4.view.ViewCompat#LAYOUT_DIRECTION_LTR} if the layout direction
         * is not RTL.
         */
        public int getLayoutDirection() {
            return ViewCompat.getLayoutDirection(mRecyclerView);
        }

        public void endAnimation(View view) {

        }

        /**
         * To be called only during {@link #onLayoutChildren(Recycler, State)} to add a view
         * to the layout that is known to be going away, either because it has been
         * {@link RecyclerView.Adapter#notifyItemRemoved(int) removed} or because it is actually not in the
         * visible portion of the container but is being laid out in order to inform RecyclerView
         * in how to animate the item out of view.
         * <p>
         * Views added via this method are going to be invisible to LayoutManager after the
         * dispatchLayout pass is complete. They cannot be retrieved via {@link #getChildAt(int)}
         * or won't be included in {@link #getChildCount()} method.
         *
         * @param child View to add and then remove with animation.
         */
        public void addDisappearingView(View child) {
            addDisappearingView(child, -1);
        }

        /**
         * To be called only during {@link #onLayoutChildren(Recycler, State)} to add a view
         * to the layout that is known to be going away, either because it has been
         * {@link RecyclerView.Adapter#notifyItemRemoved(int) removed} or because it is actually not in the
         * visible portion of the container but is being laid out in order to inform RecyclerView
         * in how to animate the item out of view.
         * <p>
         * Views added via this method are going to be invisible to LayoutManager after the
         * dispatchLayout pass is complete. They cannot be retrieved via {@link #getChildAt(int)}
         * or won't be included in {@link #getChildCount()} method.
         *
         * @param child View to add and then remove with animation.
         * @param index Index of the view.
         */
        public void addDisappearingView(View child, int index) {
            addViewInt(child, index, true);
        }

        /**
         * Add a view to the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to add views obtained from a {@link Recycler} using
         * {@link Recycler#getViewForPosition(int)}.
         *
         * @param child View to add
         */
        public void addView(View child) {
            addView(child, -1);
        }

        /**
         * Add a view to the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to add views obtained from a {@link Recycler} using
         * {@link Recycler#getViewForPosition(int)}.
         *
         * @param child View to add
         * @param index Index to add child at
         */
        public void addView(View child, int index) {
            addViewInt(child, index, false);
        }

        private void addViewInt(View child, int index, boolean disappearing) {
            final ViewHolder holder = getChildViewHolderInt(child);
            if (disappearing || holder.isRemoved()) {
                // these views will be hidden at the end of the layout pass.
                mRecyclerView.mViewInfoStore.addToDisappearedInLayout(holder);
            } else {
                // This may look like unnecessary but may happen if layout manager supports
                // predictive layouts and adapter removed then re-added the same item.
                // In this case, added version will be visible in the post layout (because add is
                // deferred) but RV will still bind it to the same View.
                // So if a View re-appears in post layout pass, remove it from disappearing list.
                mRecyclerView.mViewInfoStore.removeFromDisappearedInLayout(holder);
            }
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (holder.wasReturnedFromScrap() || holder.isScrap()) {
                if (holder.isScrap()) {
                    holder.unScrap();
                } else {
                    holder.clearReturnedFromScrapFlag();
                }
                mChildHelper.attachViewToParent(child, index, child.getLayoutParams(), false);
                if (DISPATCH_TEMP_DETACH) {
                    ViewCompat.dispatchFinishTemporaryDetach(child);
                }
            } else if (child.getParent() == mRecyclerView) { // it was not a scrap but a valid child
                // ensure in correct position
                int currentIndex = mChildHelper.indexOfChild(child);
                if (index == -1) {
                    index = mChildHelper.getChildCount();
                }
                if (currentIndex == -1) {
                    throw new IllegalStateException("Added View has RecyclerView as parent but"
                            + " view is not a real child. Unfiltered index:"
                            + mRecyclerView.indexOfChild(child));
                }
                if (currentIndex != index) {
                    mRecyclerView.mLayoutManager.moveView(currentIndex, index);
                }
            } else {
                mChildHelper.addView(child, index, false);
                lp.mInsetsDirty = true;
                if (mSmoothScroller != null && mSmoothScroller.isRunning()) {
                    mSmoothScroller.onChildAttachedToWindow(child);
                }
            }
            if (lp.mPendingInvalidate) {
                if (DEBUG) {
                    Log.d(TAG, "consuming pending invalidate on child " + lp.mViewHolder);
                }
                holder.itemView.invalidate();
                lp.mPendingInvalidate = false;
            }
        }

        /**
         * Remove a view from the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to completely remove a child view that is no longer needed.
         * LayoutManagers should strongly consider recycling removed views using
         * {@link Recycler#recycleView(android.view.View)}.
         *
         * @param child View to remove
         */
        public void removeView(View child) {
            mChildHelper.removeView(child);
        }

        /**
         * Remove a view from the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to completely remove a child view that is no longer needed.
         * LayoutManagers should strongly consider recycling removed views using
         * {@link Recycler#recycleView(android.view.View)}.
         *
         * @param index Index of the child view to remove
         */
        public void removeViewAt(int index) {
            final View child = getChildAt(index);
            if (child != null) {
                mChildHelper.removeViewAt(index);
            }
        }

        /**
         * Remove all views from the currently attached RecyclerView. This will not recycle
         * any of the affected views; the LayoutManager is responsible for doing so if desired.
         */
        public void removeAllViews() {
            // Only remove non-animating views
            final int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                mChildHelper.removeViewAt(i);
            }
        }

        /**
         * Returns offset of the RecyclerView's text baseline from the its top boundary.
         *
         * @return The offset of the RecyclerView's text baseline from the its top boundary; -1 if
         * there is no baseline.
         */
        public int getBaseline() {
            return -1;
        }

        /**
         * Returns the adapter position of the item represented by the given View. This does not
         * contain any adapter changes that might have happened after the last layout.
         *
         * @param view The view to query
         * @return The adapter position of the item which is rendered by this View.
         */
        public int getPosition(View view) {
            return ((RecyclerView.LayoutParams) view.getLayoutParams()).getViewLayoutPosition();
        }

        /**
         * Returns the View type defined by the adapter.
         *
         * @param view The view to query
         * @return The type of the view assigned by the adapter.
         */
        public int getItemViewType(View view) {
            return getChildViewHolderInt(view).getItemViewType();
        }

        /**
         * Traverses the ancestors of the given view and returns the item view that contains it
         * and also a direct child of the LayoutManager.
         * <p>
         * Note that this method may return null if the view is a child of the RecyclerView but
         * not a child of the LayoutManager (e.g. running a disappear animation).
         *
         * @param view The view that is a descendant of the LayoutManager.
         * @return The direct child of the LayoutManager which contains the given view or null if
         * the provided view is not a descendant of this LayoutManager.
         * @see RecyclerView#getChildViewHolder(View)
         * @see RecyclerView#findContainingViewHolder(View)
         */
        @Nullable
        public View findContainingItemView(View view) {
            if (mRecyclerView == null) {
                return null;
            }
            View found = mRecyclerView.findContainingItemView(view);
            if (found == null) {
                return null;
            }
            if (mChildHelper.isHidden(found)) {
                return null;
            }
            return found;
        }

        /**
         * Finds the view which represents the given adapter position.
         * <p>
         * This method traverses each child since it has no information about child order.
         * Override this method to improve performance if your LayoutManager keeps data about
         * child views.
         * <p>
         * If a view is ignored via {@link #ignoreView(View)}, it is also ignored by this method.
         *
         * @param position Position of the item in adapter
         * @return The child view that represents the given position or null if the position is not
         * laid out
         */
        public View findViewByPosition(int position) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                ViewHolder vh = getChildViewHolderInt(child);
                if (vh == null) {
                    continue;
                }
                if (vh.getLayoutPosition() == position && !vh.shouldIgnore() &&
                        (mRecyclerView.mState.isPreLayout() || !vh.isRemoved())) {
                    return child;
                }
            }
            return null;
        }

        public void detachView(View child) {
            final int ind = mChildHelper.indexOfChild(child);
            if (ind >= 0) {
                detachViewInternal(ind, child);
            }
        }

        public void detachViewAt(int index) {
            detachViewInternal(index, getChildAt(index));
        }

        private void detachViewInternal(int index, View view) {
            if (DISPATCH_TEMP_DETACH) {
                ViewCompat.dispatchStartTemporaryDetach(view);
            }
            mChildHelper.detachViewFromParent(index);
        }

        public void attachView(View child, int index, LayoutParams lp) {
            ViewHolder vh = getChildViewHolderInt(child);
            if (vh.isRemoved()) {
                mRecyclerView.mViewInfoStore.addToDisappearedInLayout(vh);
            } else {
                mRecyclerView.mViewInfoStore.removeFromDisappearedInLayout(vh);
            }
            mChildHelper.attachViewToParent(child, index, lp, vh.isRemoved());
            if (DISPATCH_TEMP_DETACH) {
                ViewCompat.dispatchFinishTemporaryDetach(child);
            }
        }

        public void attachView(View child, int index) {
            attachView(child, index, (LayoutParams) child.getLayoutParams());
        }

        public void attachView(View child) {
            attachView(child, -1);
        }

        /**
         * Finish removing a view that was previously temporarily
         * {@link #detachView(android.view.View) detached}.
         *
         * @param child Detached child to remove
         */
        public void removeDetachedView(View child) {
            mRecyclerView.removeDetachedView(child, false);
        }

        /**
         * Moves a View from one position to another.
         *
         * @param fromIndex The View's initial index
         * @param toIndex   The View's target index
         */
        public void moveView(int fromIndex, int toIndex) {
            View view = getChildAt(fromIndex);
            if (view == null) {
                throw new IllegalArgumentException("Cannot move a child from non-existing index:"
                        + fromIndex);
            }
            detachViewAt(fromIndex);
            attachView(view, toIndex);
        }

        /**
         * Detach a child view and add it to a {@link Recycler Recycler's} scrap heap.
         * <p>
         * <p>Scrapping a view allows it to be rebound and reused to show updated or
         * different data.</p>
         *
         * @param child    Child to detach and scrap
         * @param recycler Recycler to deposit the new scrap view into
         */
        public void detachAndScrapView(View child, Recycler recycler) {
            int index = mChildHelper.indexOfChild(child);
            scrapOrRecycleView(recycler, index, child);
        }

        /**
         * Detach a child view and add it to a {@link Recycler Recycler's} scrap heap.
         * <p>
         * <p>Scrapping a view allows it to be rebound and reused to show updated or
         * different data.</p>
         *
         * @param index    Index of child to detach and scrap
         * @param recycler Recycler to deposit the new scrap view into
         */
        public void detachAndScrapViewAt(int index, Recycler recycler) {
            final View child = getChildAt(index);
            scrapOrRecycleView(recycler, index, child);
        }

        /**
         * Remove a child view and recycle it using the given Recycler.
         *
         * @param child    Child to remove and recycle
         * @param recycler Recycler to use to recycle child
         */
        public void removeAndRecycleView(View child, Recycler recycler) {
            removeView(child);
            recycler.recycleView(child);
        }

        /**
         * Remove a child view and recycle it using the given Recycler.
         *
         * @param index    Index of child to remove and recycle
         * @param recycler Recycler to use to recycle child
         */
        public void removeAndRecycleViewAt(int index, Recycler recycler) {
            final View view = getChildAt(index);
            removeViewAt(index);
            recycler.recycleView(view);
        }

        /**
         * Return the current number of child views attached to the parent RecyclerView.
         * This does not include child views that were temporarily detached and/or scrapped.
         *
         * @return Number of attached children
         */
        public int getChildCount() {
            return mChildHelper != null ? mChildHelper.getChildCount() : 0;
        }

        /**
         * Return the child view at the given index
         *
         * @param index Index of child to return
         * @return Child view at index
         */
        public View getChildAt(int index) {
            return mChildHelper != null ? mChildHelper.getChildAt(index) : null;
        }

        /**
         * Return the width measurement spec mode of the RecyclerView.
         * <p>
         * This value is set only if the LayoutManager opts into the auto measure api via
         * {@link #setAutoMeasureEnabled(boolean)}.
         * <p>
         * When RecyclerView is running a layout, this value is always set to
         * {@link View.MeasureSpec#EXACTLY} even if it was measured with a different spec mode.
         *
         * @return Width measure spec mode.
         * @see View.MeasureSpec#getMode(int)
         * @see View#onMeasure(int, int)
         */
        public int getWidthMode() {
            return mWidthMode;
        }

        /**
         * Return the height measurement spec mode of the RecyclerView.
         * <p>
         * This value is set only if the LayoutManager opts into the auto measure api via
         * {@link #setAutoMeasureEnabled(boolean)}.
         * <p>
         * When RecyclerView is running a layout, this value is always set to
         * {@link View.MeasureSpec#EXACTLY} even if it was measured with a different spec mode.
         *
         * @return Height measure spec mode.
         * @see View.MeasureSpec#getMode(int)
         * @see View#onMeasure(int, int)
         */
        public int getHeightMode() {
            return mHeightMode;
        }

        /**
         * Return the width of the parent RecyclerView
         *
         * @return Width in pixels
         */
        public int getWidth() {
            return mWidth;
        }

        /**
         * Return the height of the parent RecyclerView
         *
         * @return Height in pixels
         */
        public int getHeight() {
            return mHeight;
        }

        /**
         * Return the left padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingLeft() {
            return mRecyclerView != null ? mRecyclerView.getPaddingLeft() : 0;
        }

        /**
         * Return the top padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingTop() {
            return mRecyclerView != null ? mRecyclerView.getPaddingTop() : 0;
        }

        /**
         * Return the right padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingRight() {
            return mRecyclerView != null ? mRecyclerView.getPaddingRight() : 0;
        }

        /**
         * Return the bottom padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingBottom() {
            return mRecyclerView != null ? mRecyclerView.getPaddingBottom() : 0;
        }

        /**
         * Return the start padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingStart() {
            return mRecyclerView != null ? ViewCompat.getPaddingStart(mRecyclerView) : 0;
        }

        /**
         * Return the end padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingEnd() {
            return mRecyclerView != null ? ViewCompat.getPaddingEnd(mRecyclerView) : 0;
        }

        /**
         * Returns true if the RecyclerView this LayoutManager is bound to has focus.
         *
         * @return True if the RecyclerView has focus, false otherwise.
         * @see View#isFocused()
         */
        public boolean isFocused() {
            return mRecyclerView != null && mRecyclerView.isFocused();
        }

        /**
         * Returns true if the RecyclerView this LayoutManager is bound to has or contains focus.
         *
         * @return true if the RecyclerView has or contains focus
         * @see View#hasFocus()
         */
        public boolean hasFocus() {
            return mRecyclerView != null && mRecyclerView.hasFocus();
        }

        /**
         * Returns the item View which has or contains focus.
         *
         * @return A direct child of RecyclerView which has focus or contains the focused child.
         */
        public View getFocusedChild() {
            if (mRecyclerView == null) {
                return null;
            }
            final View focused = mRecyclerView.getFocusedChild();
            if (focused == null || mChildHelper.isHidden(focused)) {
                return null;
            }
            return focused;
        }

        /**
         * Returns the number of items in the adapter bound to the parent RecyclerView.
         * <p>
         * Note that this number is not necessarily equal to {@link State#getItemCount()}. In
         * methods where State is available, you should use {@link State#getItemCount()} instead.
         * For more details, check the documentation for {@link State#getItemCount()}.
         *
         * @return The number of items in the bound adapter
         * @see State#getItemCount()
         */
        public int getItemCount() {
            final RecyclerView.Adapter a = mRecyclerView != null ? mRecyclerView.getAdapter() : null;
            return a != null ? a.getItemCount() : 0;
        }

        /**
         * Offset all child views attached to the parent RecyclerView by dx pixels along
         * the horizontal axis.
         *
         * @param dx Pixels to offset by
         */
        public void offsetChildrenHorizontal(int dx) {
            if (mRecyclerView != null) {
                mRecyclerView.offsetChildrenHorizontal(dx);
            }
        }

        /**
         * Offset all child views attached to the parent RecyclerView by dy pixels along
         * the vertical axis.
         *
         * @param dy Pixels to offset by
         */
        public void offsetChildrenVertical(int dy) {
            if (mRecyclerView != null) {
                mRecyclerView.offsetChildrenVertical(dy);
            }
        }

        /**
         * Flags a view so that it will not be scrapped or recycled.
         * <p>
         * Scope of ignoring a child is strictly restricted to position tracking, scrapping and
         * recyling. Methods like {@link #removeAndRecycleAllViews(Recycler)} will ignore the child
         * whereas {@link #removeAllViews()} or {@link #offsetChildrenHorizontal(int)} will not
         * ignore the child.
         * <p>
         * Before this child can be recycled again, you have to call
         * {@link #stopIgnoringView(View)}.
         * <p>
         * You can call this method only if your LayoutManger is in onLayout or onScroll callback.
         *
         * @param view View to ignore.
         * @see #stopIgnoringView(View)
         */
        public void ignoreView(View view) {
            if (view.getParent() != mRecyclerView || mRecyclerView.indexOfChild(view) == -1) {
                // checking this because calling this method on a recycled or detached view may
                // cause loss of state.
                throw new IllegalArgumentException("View should be fully attached to be ignored");
            }
            final ViewHolder vh = getChildViewHolderInt(view);
            vh.addFlags(ViewHolder.FLAG_IGNORE);
            mRecyclerView.mViewInfoStore.removeViewHolder(vh);
        }

        /**
         * View can be scrapped and recycled again.
         * <p>
         * Note that calling this method removes all information in the view holder.
         * <p>
         * You can call this method only if your LayoutManger is in onLayout or onScroll callback.
         *
         * @param view View to ignore.
         */
        public void stopIgnoringView(View view) {
            final ViewHolder vh = getChildViewHolderInt(view);
            vh.stopIgnoring();
            vh.resetInternal();
            vh.addFlags(ViewHolder.FLAG_INVALID);
        }

        /**
         * Temporarily detach and scrap all currently attached child views. Views will be scrapped
         * into the given Recycler. The Recycler may prefer to reuse scrap views before
         * other views that were previously recycled.
         *
         * @param recycler Recycler to scrap views into
         */
        public void detachAndScrapAttachedViews(Recycler recycler) {
            final int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                final View v = getChildAt(i);
                scrapOrRecycleView(recycler, i, v);
            }
        }

        private void scrapOrRecycleView(Recycler recycler, int index, View view) {
            final ViewHolder viewHolder = getChildViewHolderInt(view);
            if (viewHolder.shouldIgnore()) {
                if (DEBUG) {
                    Log.d(TAG, "ignoring view " + viewHolder);
                }
                return;
            }
            if (viewHolder.isInvalid() && !viewHolder.isRemoved() &&
                    !mRecyclerView.mAdapter.hasStableIds()) {
                removeViewAt(index);
                recycler.recycleViewHolderInternal(viewHolder);
            } else {
                detachViewAt(index);
                recycler.scrapView(view);
                mRecyclerView.mViewInfoStore.onViewDetached(viewHolder);
            }
        }

        /**
         * Recycles the scrapped views.
         * <p>
         * When a view is detached and removed, it does not trigger a ViewGroup invalidate. This is
         * the expected behavior if scrapped views are used for animations. Otherwise, we need to
         * call remove and invalidate RecyclerView to ensure UI update.
         *
         * @param recycler Recycler
         */
        void removeAndRecycleScrapInt(Recycler recycler) {
            final int scrapCount = recycler.getScrapCount();
            // Loop backward, recycler might be changed by removeDetachedView()
            for (int i = scrapCount - 1; i >= 0; i--) {
                final View scrap = recycler.getScrapViewAt(i);
                final ViewHolder vh = getChildViewHolderInt(scrap);
                if (vh.shouldIgnore()) {
                    continue;
                }
                // If the scrap view is animating, we need to cancel them first. If we cancel it
                // here, ItemAnimator callback may recycle it which will cause double recycling.
                // To avoid this, we mark it as not recycleable before calling the item animator.
                // Since removeDetachedView calls a user API, a common mistake (ending animations on
                // the view) may recycle it too, so we guard it before we call user APIs.
                vh.setIsRecyclable(false);
                if (vh.isTmpDetached()) {
                    mRecyclerView.removeDetachedView(scrap, false);
                }
                vh.setIsRecyclable(true);
                recycler.quickRecycleScrapView(scrap);
            }
            recycler.clearScrap();
            if (scrapCount > 0) {
                mRecyclerView.invalidate();
            }
        }


        /**
         * Measure a child view using standard measurement policy, taking the padding
         * of the parent RecyclerView and any added item decorations into account.
         * <p>
         * <p>If the RecyclerView can be scrolled in either dimension the caller may
         * pass 0 as the widthUsed or heightUsed parameters as they will be irrelevant.</p>
         *
         * @param child      Child view to measure
         * @param widthUsed  Width in pixels currently consumed by other views, if relevant
         * @param heightUsed Height in pixels currently consumed by other views, if relevant
         */
        public void measureChild(View child, int widthUsed, int heightUsed) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final Rect insets = mRecyclerView.getItemDecorInsetsForChild(child);
            widthUsed += insets.left + insets.right;
            heightUsed += insets.top + insets.bottom;
            final int widthSpec = getChildMeasureSpec(getWidth(), getWidthMode(),
                    getPaddingLeft() + getPaddingRight() + widthUsed, lp.width,
                    canScrollHorizontally());
            final int heightSpec = getChildMeasureSpec(getHeight(), getHeightMode(),
                    getPaddingTop() + getPaddingBottom() + heightUsed, lp.height,
                    canScrollVertically());
            if (shouldMeasureChild(child, widthSpec, heightSpec, lp)) {
                child.measure(widthSpec, heightSpec);
            }
        }

        /**
         * RecyclerView internally does its own View measurement caching which should help with
         * WRAP_CONTENT.
         * <p>
         * Use this method if the View is already measured once in this layout pass.
         */
        boolean shouldReMeasureChild(View child, int widthSpec, int heightSpec, LayoutParams lp) {
            return !mMeasurementCacheEnabled
                    || !isMeasurementUpToDate(child.getMeasuredWidth(), widthSpec, lp.width)
                    || !isMeasurementUpToDate(child.getMeasuredHeight(), heightSpec, lp.height);
        }

        // we may consider making this public

        /**
         * RecyclerView internally does its own View measurement caching which should help with
         * WRAP_CONTENT.
         * <p>
         * Use this method if the View is not yet measured and you need to decide whether to
         * measure this View or not.
         */
        boolean shouldMeasureChild(View child, int widthSpec, int heightSpec, LayoutParams lp) {
            return child.isLayoutRequested()
                    || !mMeasurementCacheEnabled
                    || !isMeasurementUpToDate(child.getWidth(), widthSpec, lp.width)
                    || !isMeasurementUpToDate(child.getHeight(), heightSpec, lp.height);
        }

        /**
         * In addition to the View Framework's measurement cache, RecyclerView uses its own
         * additional measurement cache for its children to avoid re-measuring them when not
         * necessary. It is on by default but it can be turned off via
         * {@link #setMeasurementCacheEnabled(boolean)}.
         *
         * @return True if measurement cache is enabled, false otherwise.
         * @see #setMeasurementCacheEnabled(boolean)
         */
        public boolean isMeasurementCacheEnabled() {
            return mMeasurementCacheEnabled;
        }

        /**
         * Sets whether RecyclerView should use its own measurement cache for the children. This is
         * a more aggressive cache than the framework uses.
         *
         * @param measurementCacheEnabled True to enable the measurement cache, false otherwise.
         * @see #isMeasurementCacheEnabled()
         */
        public void setMeasurementCacheEnabled(boolean measurementCacheEnabled) {
            mMeasurementCacheEnabled = measurementCacheEnabled;
        }

        private static boolean isMeasurementUpToDate(int childSize, int spec, int dimension) {
            final int specMode = MeasureSpec.getMode(spec);
            final int specSize = MeasureSpec.getSize(spec);
            if (dimension > 0 && childSize != dimension) {
                return false;
            }
            switch (specMode) {
                case MeasureSpec.UNSPECIFIED:
                    return true;
                case MeasureSpec.AT_MOST:
                    return specSize >= childSize;
                case MeasureSpec.EXACTLY:
                    return specSize == childSize;
            }
            return false;
        }

        /**
         * Measure a child view using standard measurement policy, taking the padding
         * of the parent RecyclerView, any added item decorations and the child margins
         * into account.
         * <p>
         * <p>If the RecyclerView can be scrolled in either dimension the caller may
         * pass 0 as the widthUsed or heightUsed parameters as they will be irrelevant.</p>
         *
         * @param child      Child view to measure
         * @param widthUsed  Width in pixels currently consumed by other views, if relevant
         * @param heightUsed Height in pixels currently consumed by other views, if relevant
         */
        public void measureChildWithMargins(View child, int widthUsed, int heightUsed) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final Rect insets = mRecyclerView.getItemDecorInsetsForChild(child);
            widthUsed += insets.left + insets.right;
            heightUsed += insets.top + insets.bottom;

            final int widthSpec = getChildMeasureSpec(getWidth(), getWidthMode(),
                    getPaddingLeft() + getPaddingRight() +
                            lp.leftMargin + lp.rightMargin + widthUsed, lp.width,
                    canScrollHorizontally());
            final int heightSpec = getChildMeasureSpec(getHeight(), getHeightMode(),
                    getPaddingTop() + getPaddingBottom() +
                            lp.topMargin + lp.bottomMargin + heightUsed, lp.height,
                    canScrollVertically());
            if (shouldMeasureChild(child, widthSpec, heightSpec, lp)) {
                child.measure(widthSpec, heightSpec);
            }
        }

        /**
         * Calculate a MeasureSpec value for measuring a child view in one dimension.
         *
         * @param parentSize     Size of the parent view where the child will be placed
         * @param padding        Total space currently consumed by other elements of the parent
         * @param childDimension Desired size of the child view, or MATCH_PARENT/WRAP_CONTENT.
         *                       Generally obtained from the child view's LayoutParams
         * @param canScroll      true if the parent RecyclerView can scroll in this dimension
         * @return a MeasureSpec value for the child view
         * @deprecated use {@link #getChildMeasureSpec(int, int, int, int, boolean)}
         */
        @Deprecated
        public static int getChildMeasureSpec(int parentSize, int padding, int childDimension,
                                              boolean canScroll) {
            int size = Math.max(0, parentSize - padding);
            int resultSize = 0;
            int resultMode = 0;
            if (canScroll) {
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else {
                    // MATCH_PARENT can't be applied since we can scroll in this dimension, wrap
                    // instead using UNSPECIFIED.
                    resultSize = 0;
                    resultMode = MeasureSpec.UNSPECIFIED;
                }
            } else {
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.MATCH_PARENT) {
                    resultSize = size;
                    // TODO this should be my spec.
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.AT_MOST;
                }
            }
            return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
        }

        /**
         * Calculate a MeasureSpec value for measuring a child view in one dimension.
         *
         * @param parentSize     Size of the parent view where the child will be placed
         * @param parentMode     The measurement spec mode of the parent
         * @param padding        Total space currently consumed by other elements of parent
         * @param childDimension Desired size of the child view, or MATCH_PARENT/WRAP_CONTENT.
         *                       Generally obtained from the child view's LayoutParams
         * @param canScroll      true if the parent RecyclerView can scroll in this dimension
         * @return a MeasureSpec value for the child view
         */
        public static int getChildMeasureSpec(int parentSize, int parentMode, int padding,
                                              int childDimension, boolean canScroll) {
            int size = Math.max(0, parentSize - padding);
            int resultSize = 0;
            int resultMode = 0;
            if (childDimension >= 0) {
                resultSize = childDimension;
                resultMode = MeasureSpec.EXACTLY;
            } else if (canScroll) {
                if (childDimension == LayoutParams.MATCH_PARENT) {
                    switch (parentMode) {
                        case MeasureSpec.AT_MOST:
                        case MeasureSpec.EXACTLY:
                            resultSize = size;
                            resultMode = parentMode;
                            break;
                        case MeasureSpec.UNSPECIFIED:
                            resultSize = 0;
                            resultMode = MeasureSpec.UNSPECIFIED;
                            break;
                    }
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = 0;
                    resultMode = MeasureSpec.UNSPECIFIED;
                }
            } else {
                if (childDimension == LayoutParams.MATCH_PARENT) {
                    resultSize = size;
                    resultMode = parentMode;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    if (parentMode == MeasureSpec.AT_MOST || parentMode == MeasureSpec.EXACTLY) {
                        resultMode = MeasureSpec.AT_MOST;
                    } else {
                        resultMode = MeasureSpec.UNSPECIFIED;
                    }

                }
            }
            //noinspection WrongConstant
            return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
        }

        public int getDecoratedMeasuredWidth(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getMeasuredWidth() + insets.left + insets.right;
        }

        public int getDecoratedMeasuredHeight(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getMeasuredHeight() + insets.top + insets.bottom;
        }

        public void layoutDecorated(View child, int left, int top, int right, int bottom) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            child.layout(left + insets.left, top + insets.top, right - insets.right,
                    bottom - insets.bottom);
        }

        public void layoutDecoratedWithMargins(View child, int left, int top, int right,
                                               int bottom) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            final Rect insets = lp.mDecorInsets;
            child.layout(left + insets.left + lp.leftMargin, top + insets.top + lp.topMargin,
                    right - insets.right - lp.rightMargin,
                    bottom - insets.bottom - lp.bottomMargin);
        }

        /**
         * Calculates the bounding box of the View while taking into account its matrix changes
         * (translation, scale etc) with respect to the RecyclerView.
         * <p>
         * If {@code includeDecorInsets} is {@code true}, they are applied first before applying
         * the View's matrix so that the decor offsets also go through the same transformation.
         *
         * @param child              The ItemView whose bounding box should be calculated.
         * @param includeDecorInsets True if the decor insets should be included in the bounding box
         * @param out                The rectangle into which the output will be written.
         */
        public void getTransformedBoundingBox(View child, boolean includeDecorInsets, Rect out) {
            if (includeDecorInsets) {
                Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
                out.set(-insets.left, -insets.top,
                        child.getWidth() + insets.right, child.getHeight() + insets.bottom);
            } else {
                out.set(0, 0, child.getWidth(), child.getHeight());
            }

            if (mRecyclerView != null) {
                final Matrix childMatrix = ViewCompat.getMatrix(child);
                if (childMatrix != null && !childMatrix.isIdentity()) {
                    final RectF tempRectF = mRecyclerView.mTempRectF;
                    tempRectF.set(out);
                    childMatrix.mapRect(tempRectF);
                    out.set(
                            (int) Math.floor(tempRectF.left),
                            (int) Math.floor(tempRectF.top),
                            (int) Math.ceil(tempRectF.right),
                            (int) Math.ceil(tempRectF.bottom)
                    );
                }
            }
            out.offset(child.getLeft(), child.getTop());
        }

        /**
         * Returns the bounds of the view including its decoration and margins.
         *
         * @param view      The view element to check
         * @param outBounds A rect that will receive the bounds of the element including its
         *                  decoration and margins.
         */
        public void getDecoratedBoundsWithMargins(View view, Rect outBounds) {
            final LayoutParams lp = (LayoutParams) view.getLayoutParams();
            final Rect insets = lp.mDecorInsets;
            outBounds.set(view.getLeft() - insets.left - lp.leftMargin,
                    view.getTop() - insets.top - lp.topMargin,
                    view.getRight() + insets.right + lp.rightMargin,
                    view.getBottom() + insets.bottom + lp.bottomMargin);
        }

        public int getDecoratedLeft(View child) {
            return child.getLeft() - getLeftDecorationWidth(child);
        }

        public int getDecoratedTop(View child) {
            return child.getTop() - getTopDecorationHeight(child);
        }

        public int getDecoratedRight(View child) {
            return child.getRight() + getRightDecorationWidth(child);
        }

        public int getDecoratedBottom(View child) {
            return child.getBottom() + getBottomDecorationHeight(child);
        }

        /**
         * Calculates the item decor insets applied to the given child and updates the provided
         * Rect instance with the inset values.
         * <ul>
         * <li>The Rect's left is set to the total width of left decorations.</li>
         * <li>The Rect's top is set to the total height of top decorations.</li>
         * <li>The Rect's right is set to the total width of right decorations.</li>
         * <li>The Rect's bottom is set to total height of bottom decorations.</li>
         * </ul>
         * <p>
         * Note that item decorations are automatically calculated when one of the LayoutManager's
         * measure child methods is called. If you need to measure the child with custom specs via
         * {@link View#measure(int, int)}, you can use this method to get decorations.
         *
         * @param child   The child view whose decorations should be calculated
         * @param outRect The Rect to hold result values
         */
        public void calculateItemDecorationsForChild(View child, Rect outRect) {
            if (mRecyclerView == null) {
                outRect.set(0, 0, 0, 0);
                return;
            }
            Rect insets = mRecyclerView.getItemDecorInsetsForChild(child);
            outRect.set(insets);
        }

        /**
         * Returns the total height of item decorations applied to child's top.
         * <p>
         * Note that this value is not updated until the View is measured or
         * {@link #calculateItemDecorationsForChild(View, Rect)} is called.
         *
         * @param child Child to query
         * @return The total height of item decorations applied to the child's top.
         * @see #getDecoratedTop(View)
         * @see #calculateItemDecorationsForChild(View, Rect)
         */
        public int getTopDecorationHeight(View child) {
            return ((LayoutParams) child.getLayoutParams()).mDecorInsets.top;
        }

        /**
         * Returns the total height of item decorations applied to child's bottom.
         * <p>
         * Note that this value is not updated until the View is measured or
         * {@link #calculateItemDecorationsForChild(View, Rect)} is called.
         *
         * @param child Child to query
         * @return The total height of item decorations applied to the child's bottom.
         * @see #getDecoratedBottom(View)
         * @see #calculateItemDecorationsForChild(View, Rect)
         */
        public int getBottomDecorationHeight(View child) {
            return ((LayoutParams) child.getLayoutParams()).mDecorInsets.bottom;
        }

        /**
         * Returns the total width of item decorations applied to child's left.
         * <p>
         * Note that this value is not updated until the View is measured or
         * {@link #calculateItemDecorationsForChild(View, Rect)} is called.
         *
         * @param child Child to query
         * @return The total width of item decorations applied to the child's left.
         * @see #getDecoratedLeft(View)
         * @see #calculateItemDecorationsForChild(View, Rect)
         */
        public int getLeftDecorationWidth(View child) {
            return ((LayoutParams) child.getLayoutParams()).mDecorInsets.left;
        }

        /**
         * Returns the total width of item decorations applied to child's right.
         * <p>
         * Note that this value is not updated until the View is measured or
         * {@link #calculateItemDecorationsForChild(View, Rect)} is called.
         *
         * @param child Child to query
         * @return The total width of item decorations applied to the child's right.
         * @see #getDecoratedRight(View)
         * @see #calculateItemDecorationsForChild(View, Rect)
         */
        public int getRightDecorationWidth(View child) {
            return ((LayoutParams) child.getLayoutParams()).mDecorInsets.right;
        }

        /**
         * Called when searching for a focusable view in the given direction has failed
         * for the current content of the RecyclerView.
         * <p>
         * <p>This is the LayoutManager's opportunity to populate views in the given direction
         * to fulfill the request if it can. The LayoutManager should attach and return
         * the view to be focused. The default implementation returns null.</p>
         *
         * @param focused   The currently focused view
         * @param direction One of {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
         *                  {@link View#FOCUS_LEFT}, {@link View#FOCUS_RIGHT},
         *                  {@link View#FOCUS_BACKWARD}, {@link View#FOCUS_FORWARD}
         *                  or 0 for not applicable
         * @param recycler  The recycler to use for obtaining views for currently offscreen items
         * @param state     Transient state of RecyclerView
         * @return The chosen view to be focused
         */
        @Nullable
        public View onFocusSearchFailed(View focused, int direction, Recycler recycler,
                                        State state) {
            return null;
        }

        public View onInterceptFocusSearch(View focused, int direction) {
            return null;
        }

        /**
         * Called when a child of the RecyclerView wants a particular rectangle to be positioned
         * onto the screen.
         * <p>
         * <p>The base implementation will attempt to perform a standard programmatic scroll
         * to bring the given rect into view, within the padded area of the RecyclerView.</p>
         *
         * @param child     The direct child making the request.
         * @param rect      The rectangle in the child's coordinates the child
         *                  wishes to be on the screen.
         * @param immediate True to forbid animated or delayed scrolling,
         *                  false otherwise
         * @return Whether the group scrolled to handle the operation
         */
        public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect,
                                                     boolean immediate) {
            final int parentLeft = getPaddingLeft();
            final int parentTop = getPaddingTop();
            final int parentRight = getWidth() - getPaddingRight();
            final int parentBottom = getHeight() - getPaddingBottom();
            final int childLeft = child.getLeft() + rect.left - child.getScrollX();
            final int childTop = child.getTop() + rect.top - child.getScrollY();
            final int childRight = childLeft + rect.width();
            final int childBottom = childTop + rect.height();

            final int offScreenLeft = Math.min(0, childLeft - parentLeft);
            final int offScreenTop = Math.min(0, childTop - parentTop);
            final int offScreenRight = Math.max(0, childRight - parentRight);
            final int offScreenBottom = Math.max(0, childBottom - parentBottom);

            // Favor the "start" layout direction over the end when bringing one side or the other
            // of a large rect into view. If we decide to bring in end because start is already
            // visible, limit the scroll such that start won't go out of bounds.
            final int dx;
            if (getLayoutDirection() == ViewCompat.LAYOUT_DIRECTION_RTL) {
                dx = offScreenRight != 0 ? offScreenRight
                        : Math.max(offScreenLeft, childRight - parentRight);
            } else {
                dx = offScreenLeft != 0 ? offScreenLeft
                        : Math.min(childLeft - parentLeft, offScreenRight);
            }

            // Favor bringing the top into view over the bottom. If top is already visible and
            // we should scroll to make bottom visible, make sure top does not go out of bounds.
            final int dy = offScreenTop != 0 ? offScreenTop
                    : Math.min(childTop - parentTop, offScreenBottom);

            if (dx != 0 || dy != 0) {
                if (immediate) {
                    parent.scrollBy(dx, dy);
                } else {
                    parent.smoothScrollBy(dx, dy);
                }
                return true;
            }
            return false;
        }

        /**
         * @deprecated Use {@link #onRequestChildFocus(RecyclerView, State, View, View)}
         */
        @Deprecated
        public boolean onRequestChildFocus(RecyclerView parent, View child, View focused) {
            // eat the request if we are in the middle of a scroll or layout
            return isSmoothScrolling() || parent.isComputingLayout();
        }

        /**
         * Called when a descendant view of the RecyclerView requests focus.
         * <p>
         * <p>A LayoutManager wishing to keep focused views aligned in a specific
         * portion of the view may implement that behavior in an override of this method.</p>
         * <p>
         * <p>If the LayoutManager executes different behavior that should override the default
         * behavior of scrolling the focused child on screen instead of running alongside it,
         * this method should return true.</p>
         *
         * @param parent  The RecyclerView hosting this LayoutManager
         * @param state   Current state of RecyclerView
         * @param child   Direct child of the RecyclerView containing the newly focused view
         * @param focused The newly focused view. This may be the same view as child or it may be
         *                null
         * @return true if the default scroll behavior should be suppressed
         */
        public boolean onRequestChildFocus(RecyclerView parent, State state, View child,
                                           View focused) {
            return onRequestChildFocus(parent, child, focused);
        }

        /**
         * Called if the RecyclerView this LayoutManager is bound to has a different adapter set.
         * The LayoutManager may use this opportunity to clear caches and configure state such
         * that it can relayout appropriately with the new data and potentially new view types.
         * <p>
         * <p>The default implementation removes all currently attached views.</p>
         *
         * @param oldAdapter The previous adapter instance. Will be null if there was previously no
         *                   adapter.
         * @param newAdapter The new adapter instance. Might be null if
         *                   {@link #setAdapter(RecyclerView.Adapter)} is called with {@code null}.
         */
        public void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter) {
        }

        /**
         * Called to populate focusable views within the RecyclerView.
         * <p>
         * <p>The LayoutManager implementation should return <code>true</code> if the default
         * behavior of {@link ViewGroup#addFocusables(java.util.ArrayList, int)} should be
         * suppressed.</p>
         * <p>
         * <p>The default implementation returns <code>false</code> to trigger RecyclerView
         * to fall back to the default ViewGroup behavior.</p>
         *
         * @param recyclerView  The RecyclerView hosting this LayoutManager
         * @param views         List of output views. This method should add valid focusable views
         *                      to this list.
         * @param direction     One of {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
         *                      {@link View#FOCUS_LEFT}, {@link View#FOCUS_RIGHT},
         *                      {@link View#FOCUS_BACKWARD}, {@link View#FOCUS_FORWARD}
         * @param focusableMode The type of focusables to be added.
         * @return true to suppress the default behavior, false to add default focusables after
         * this method returns.
         * @see #FOCUSABLES_ALL
         * @see #FOCUSABLES_TOUCH_MODE
         */
        public boolean onAddFocusables(RecyclerView recyclerView, ArrayList<View> views,
                                       int direction, int focusableMode) {
            return false;
        }

        /**
         * Called when {@link RecyclerView.Adapter#notifyDataSetChanged()} is triggered instead of giving
         * detailed information on what has actually changed.
         *
         * @param recyclerView
         */
        public void onItemsChanged(DRecyclerView recyclerView) {
        }

        /**
         * Called when items have been added to the adapter. The LayoutManager may choose to
         * requestLayout if the inserted items would require refreshing the currently visible set
         * of child views. (e.g. currently empty space would be filled by appended items, etc.)
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when items have been removed from the adapter.
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when items have been changed in the adapter.
         * To receive payload,  override {@link #onItemsUpdated(RecyclerView, int, int, Object)}
         * instead, then this callback will not be invoked.
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when items have been changed in the adapter and with optional payload.
         * Default implementation calls {@link #onItemsUpdated(RecyclerView, int, int)}.
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         * @param payload
         */
        public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount,
                                   Object payload) {
            onItemsUpdated(recyclerView, positionStart, itemCount);
        }

        /**
         * Called when an item is moved withing the adapter.
         * <p>
         * Note that, an item may also change position in response to another ADD/REMOVE/MOVE
         * operation. This callback is only called if and only if {@link RecyclerView.Adapter#notifyItemMoved}
         * is called.
         *
         * @param recyclerView
         * @param from
         * @param to
         * @param itemCount
         */
        public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {

        }


        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeHorizontalScrollExtent()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current state of RecyclerView
         * @return The horizontal extent of the scrollbar's thumb
         * @see RecyclerView#computeHorizontalScrollExtent()
         */
        public int computeHorizontalScrollExtent(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeHorizontalScrollOffset()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The horizontal offset of the scrollbar's thumb
         * @see RecyclerView#computeHorizontalScrollOffset()
         */
        public int computeHorizontalScrollOffset(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeHorizontalScrollRange()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The total horizontal range represented by the vertical scrollbar
         * @see RecyclerView#computeHorizontalScrollRange()
         */
        public int computeHorizontalScrollRange(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeVerticalScrollExtent()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current state of RecyclerView
         * @return The vertical extent of the scrollbar's thumb
         * @see RecyclerView#computeVerticalScrollExtent()
         */
        public int computeVerticalScrollExtent(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeVerticalScrollOffset()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The vertical offset of the scrollbar's thumb
         * @see RecyclerView#computeVerticalScrollOffset()
         */
        public int computeVerticalScrollOffset(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         * <p>
         * <p>Read {@link RecyclerView#computeVerticalScrollRange()} for details.</p>
         * <p>
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The total vertical range represented by the vertical scrollbar
         * @see RecyclerView#computeVerticalScrollRange()
         */
        public int computeVerticalScrollRange(State state) {
            return 0;
        }

        /**
         * Measure the attached RecyclerView. Implementations must call
         * {@link #setMeasuredDimension(int, int)} before returning.
         * <p>
         * <p>The default implementation will handle EXACTLY measurements and respect
         * the minimum width and height properties of the host RecyclerView if measured
         * as UNSPECIFIED. AT_MOST measurements will be treated as EXACTLY and the RecyclerView
         * will consume all available space.</p>
         *
         * @param recycler   Recycler
         * @param state      Transient state of RecyclerView
         * @param widthSpec  Width {@link android.view.View.MeasureSpec}
         * @param heightSpec Height {@link android.view.View.MeasureSpec}
         */
        public void onMeasure(Recycler recycler, State state, int widthSpec, int heightSpec) {
            mRecyclerView.defaultOnMeasure(widthSpec, heightSpec);
        }

        /**
         * {@link View#setMeasuredDimension(int, int) Set the measured dimensions} of the
         * host RecyclerView.
         *
         * @param widthSize  Measured width
         * @param heightSize Measured height
         */
        public void setMeasuredDimension(int widthSize, int heightSize) {
            mRecyclerView.setMeasuredDimension(widthSize, heightSize);
        }

        /**
         * @return The host RecyclerView's {@link View#getMinimumWidth()}
         */
        public int getMinimumWidth() {
            return ViewCompat.getMinimumWidth(mRecyclerView);
        }

        /**
         * @return The host RecyclerView's {@link View#getMinimumHeight()}
         */
        public int getMinimumHeight() {
            return ViewCompat.getMinimumHeight(mRecyclerView);
        }

        /**
         * <p>Called when the LayoutManager should save its state. This is a good time to save your
         * scroll position, configuration and anything else that may be required to restore the same
         * layout state if the LayoutManager is recreated.</p>
         * <p>RecyclerView does NOT verify if the LayoutManager has changed between state save and
         * restore. This will let you share information between your LayoutManagers but it is also
         * your responsibility to make sure they use the same parcelable class.</p>
         *
         * @return Necessary information for LayoutManager to be able to restore its state
         */
        public Parcelable onSaveInstanceState() {
            return null;
        }


        public void onRestoreInstanceState(Parcelable state) {

        }

        void stopSmoothScroller() {
            if (mSmoothScroller != null) {
                mSmoothScroller.stop();
            }
        }

        private void onSmoothScrollerStopped(SmoothScroller smoothScroller) {
            if (mSmoothScroller == smoothScroller) {
                mSmoothScroller = null;
            }
        }

        /**
         * RecyclerView calls this method to notify LayoutManager that scroll state has changed.
         *
         * @param state The new scroll state for RecyclerView
         */
        public void onScrollStateChanged(int state) {
        }

        /**
         * Removes all views and recycles them using the given recycler.
         * <p>
         * If you want to clean cached views as well, you should call {@link Recycler#clear()} too.
         * <p>
         * If a View is marked as "ignored", it is not removed nor recycled.
         *
         * @param recycler Recycler to use to recycle children
         * @see #removeAndRecycleView(View, Recycler)
         * @see #removeAndRecycleViewAt(int, Recycler)
         * @see #ignoreView(View)
         */
        public void removeAndRecycleAllViews(Recycler recycler) {
            for (int i = getChildCount() - 1; i >= 0; i--) {
                final View view = getChildAt(i);
                if (!getChildViewHolderInt(view).shouldIgnore()) {
                    removeAndRecycleViewAt(i, recycler);
                }
            }
        }

        // called by accessibility delegate
        void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfoCompat info) {
            onInitializeAccessibilityNodeInfo(mRecyclerView.mRecycler, mRecyclerView.mState, info);
        }

        public void onInitializeAccessibilityNodeInfo(Recycler recycler, State state,
                                                      AccessibilityNodeInfoCompat info) {
            if (ViewCompat.canScrollVertically(mRecyclerView, -1) ||
                    ViewCompat.canScrollHorizontally(mRecyclerView, -1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
                info.setScrollable(true);
            }
            if (ViewCompat.canScrollVertically(mRecyclerView, 1) ||
                    ViewCompat.canScrollHorizontally(mRecyclerView, 1)) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
                info.setScrollable(true);
            }
            final AccessibilityNodeInfoCompat.CollectionInfoCompat collectionInfo
                    = AccessibilityNodeInfoCompat.CollectionInfoCompat
                    .obtain(getRowCountForAccessibility(recycler, state),
                            getColumnCountForAccessibility(recycler, state),
                            isLayoutHierarchical(recycler, state),
                            getSelectionModeForAccessibility(recycler, state));
            info.setCollectionInfo(collectionInfo);
        }

        // called by accessibility delegate
        public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
            onInitializeAccessibilityEvent(mRecyclerView.mRecycler, mRecyclerView.mState, event);
        }

        /**
         * Called by the accessibility delegate to initialize an accessibility event.
         * <p>
         * Default implementation adds item count and scroll information to the event.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @param event    The event instance to initialize
         * @see View#onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent)
         */
        public void onInitializeAccessibilityEvent(Recycler recycler, State state,
                                                   AccessibilityEvent event) {
            final AccessibilityRecordCompat record = AccessibilityEventCompat
                    .asRecord(event);
            if (mRecyclerView == null || record == null) {
                return;
            }
            record.setScrollable(ViewCompat.canScrollVertically(mRecyclerView, 1)
                    || ViewCompat.canScrollVertically(mRecyclerView, -1)
                    || ViewCompat.canScrollHorizontally(mRecyclerView, -1)
                    || ViewCompat.canScrollHorizontally(mRecyclerView, 1));

            if (mRecyclerView.mAdapter != null) {
                record.setItemCount(mRecyclerView.mAdapter.getItemCount());
            }
        }

        // called by accessibility delegate
        void onInitializeAccessibilityNodeInfoForItem(View host, AccessibilityNodeInfoCompat info) {
            final ViewHolder vh = getChildViewHolderInt(host);
            // avoid trying to create accessibility node info for removed children
            if (vh != null && !vh.isRemoved() && !mChildHelper.isHidden(vh.itemView)) {
                onInitializeAccessibilityNodeInfoForItem(mRecyclerView.mRecycler,
                        mRecyclerView.mState, host, info);
            }
        }

        /**
         * Called by the AccessibilityDelegate when the accessibility information for a specific
         * item should be populated.
         * <p>
         * Default implementation adds basic positioning information about the item.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @param host     The child for which accessibility node info should be populated
         * @param info     The info to fill out about the item
         * @see android.widget.AbsListView#onInitializeAccessibilityNodeInfoForItem(View, int,
         * android.view.accessibility.AccessibilityNodeInfo)
         */
        public void onInitializeAccessibilityNodeInfoForItem(Recycler recycler, State state,
                                                             View host, AccessibilityNodeInfoCompat info) {
            int rowIndexGuess = canScrollVertically() ? getPosition(host) : 0;
            int columnIndexGuess = canScrollHorizontally() ? getPosition(host) : 0;
            final AccessibilityNodeInfoCompat.CollectionItemInfoCompat itemInfo
                    = AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(rowIndexGuess, 1,
                    columnIndexGuess, 1, false, false);
            info.setCollectionItemInfo(itemInfo);
        }

        public void requestSimpleAnimationsInNextLayout() {
            mRequestedSimpleAnimations = true;
        }

        /**
         * Returns the selection mode for accessibility. Should be
         * {@link AccessibilityNodeInfoCompat.CollectionInfoCompat#SELECTION_MODE_NONE},
         * {@link AccessibilityNodeInfoCompat.CollectionInfoCompat#SELECTION_MODE_SINGLE} or
         * {@link AccessibilityNodeInfoCompat.CollectionInfoCompat#SELECTION_MODE_MULTIPLE}.
         * <p>
         * Default implementation returns
         * {@link AccessibilityNodeInfoCompat.CollectionInfoCompat#SELECTION_MODE_NONE}.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @return Selection mode for accessibility. Default implementation returns
         * {@link AccessibilityNodeInfoCompat.CollectionInfoCompat#SELECTION_MODE_NONE}.
         */
        public int getSelectionModeForAccessibility(Recycler recycler, State state) {
            return AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_NONE;
        }

        /**
         * Returns the number of rows for accessibility.
         * <p>
         * Default implementation returns the number of items in the adapter if LayoutManager
         * supports vertical scrolling or 1 if LayoutManager does not support vertical
         * scrolling.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @return The number of rows in LayoutManager for accessibility.
         */
        public int getRowCountForAccessibility(Recycler recycler, State state) {
            if (mRecyclerView == null || mRecyclerView.mAdapter == null) {
                return 1;
            }
            return canScrollVertically() ? mRecyclerView.mAdapter.getItemCount() : 1;
        }

        /**
         * Returns the number of columns for accessibility.
         * <p>
         * Default implementation returns the number of items in the adapter if LayoutManager
         * supports horizontal scrolling or 1 if LayoutManager does not support horizontal
         * scrolling.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @return The number of rows in LayoutManager for accessibility.
         */
        public int getColumnCountForAccessibility(Recycler recycler, State state) {
            if (mRecyclerView == null || mRecyclerView.mAdapter == null) {
                return 1;
            }
            return canScrollHorizontally() ? mRecyclerView.mAdapter.getItemCount() : 1;
        }

        /**
         * Returns whether layout is hierarchical or not to be used for accessibility.
         * <p>
         * Default implementation returns false.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @return True if layout is hierarchical.
         */
        public boolean isLayoutHierarchical(Recycler recycler, State state) {
            return false;
        }

        // called by accessibility delegate
        boolean performAccessibilityAction(int action, Bundle args) {
            return performAccessibilityAction(mRecyclerView.mRecycler, mRecyclerView.mState,
                    action, args);
        }

        /**
         * Called by AccessibilityDelegate when an action is requested from the RecyclerView.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @param action   The action to perform
         * @param args     Optional action arguments
         * @see View#performAccessibilityAction(int, android.os.Bundle)
         */
        public boolean performAccessibilityAction(Recycler recycler, State state, int action,
                                                  Bundle args) {
            if (mRecyclerView == null) {
                return false;
            }
            int vScroll = 0, hScroll = 0;
            switch (action) {
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD:
                    if (ViewCompat.canScrollVertically(mRecyclerView, -1)) {
                        vScroll = -(getHeight() - getPaddingTop() - getPaddingBottom());
                    }
                    if (ViewCompat.canScrollHorizontally(mRecyclerView, -1)) {
                        hScroll = -(getWidth() - getPaddingLeft() - getPaddingRight());
                    }
                    break;
                case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD:
                    if (ViewCompat.canScrollVertically(mRecyclerView, 1)) {
                        vScroll = getHeight() - getPaddingTop() - getPaddingBottom();
                    }
                    if (ViewCompat.canScrollHorizontally(mRecyclerView, 1)) {
                        hScroll = getWidth() - getPaddingLeft() - getPaddingRight();
                    }
                    break;
            }
            if (vScroll == 0 && hScroll == 0) {
                return false;
            }
            mRecyclerView.scrollBy(hScroll, vScroll);
            return true;
        }

        // called by accessibility delegate
        boolean performAccessibilityActionForItem(View view, int action, Bundle args) {
            return performAccessibilityActionForItem(mRecyclerView.mRecycler, mRecyclerView.mState,
                    view, action, args);
        }

        /**
         * Called by AccessibilityDelegate when an accessibility action is requested on one of the
         * children of LayoutManager.
         * <p>
         * Default implementation does not do anything.
         *
         * @param recycler The Recycler that can be used to convert view positions into adapter
         *                 positions
         * @param state    The current state of RecyclerView
         * @param view     The child view on which the action is performed
         * @param action   The action to perform
         * @param args     Optional action arguments
         * @return true if action is handled
         * @see View#performAccessibilityAction(int, android.os.Bundle)
         */
        public boolean performAccessibilityActionForItem(Recycler recycler, State state, View view,
                                                         int action, Bundle args) {
            return false;
        }

        /**
         * Parse the xml attributes to get the most common properties used by layout managers.
         *
         * @return an object containing the properties as specified in the attrs.
         * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_android_orientation
         * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_spanCount
         * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_reverseLayout
         * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_stackFromEnd
         */
        public static Properties getProperties(Context context, AttributeSet attrs,
                                               int defStyleAttr, int defStyleRes) {
            Properties properties = new Properties();
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RecyclerView,
                    defStyleAttr, defStyleRes);
            properties.orientation = a.getInt(R.styleable.RecyclerView_android_orientation, VERTICAL);
            properties.spanCount = a.getInt(R.styleable.RecyclerView_spanCount, 1);
            properties.reverseLayout = a.getBoolean(R.styleable.RecyclerView_reverseLayout, false);
            properties.stackFromEnd = a.getBoolean(R.styleable.RecyclerView_stackFromEnd, false);
            a.recycle();
            return properties;
        }

        void setExactMeasureSpecsFrom(RecyclerView recyclerView) {
            setMeasureSpecs(
                    MeasureSpec.makeMeasureSpec(recyclerView.getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(recyclerView.getHeight(), MeasureSpec.EXACTLY)
            );
        }

        /**
         * Internal API to allow LayoutManagers to be measured twice.
         * <p>
         * This is not public because LayoutManagers should be able to handle their layouts in one
         * pass but it is very convenient to make existing LayoutManagers support wrapping content
         * when both orientations are undefined.
         * <p>
         * This API will be removed after default LayoutManagers properly implement wrap content in
         * non-scroll orientation.
         */
        boolean shouldMeasureTwice() {
            return false;
        }

        boolean hasFlexibleChildInBothOrientations() {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp.width < 0 && lp.height < 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Some general properties that a LayoutManager may want to use.
         */
        public static class Properties {
            /**
             * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_android_orientation
             */
            public int orientation;
            /**
             * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_spanCount
             */
            public int spanCount;
            /**
             * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_reverseLayout
             */
            public boolean reverseLayout;
            /**
             * @attr ref android.support.v7.recyclerview.R.styleable#RecyclerView_stackFromEnd
             */
            public boolean stackFromEnd;
        }
    }

    public static abstract class ItemAnimator {

        /**
         * The Item represented by this ViewHolder is updated.
         * <p>
         *
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         */
        public static final int FLAG_CHANGED = ViewHolder.FLAG_UPDATE;

        /**
         * The Item represented by this ViewHolder is removed from the adapter.
         * <p>
         *
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         */
        public static final int FLAG_REMOVED = ViewHolder.FLAG_REMOVED;

        /**
         * Adapter {@link RecyclerView.Adapter#notifyDataSetChanged()} has been called and the content
         * represented by this ViewHolder is invalid.
         * <p>
         *
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         */
        public static final int FLAG_INVALIDATED = ViewHolder.FLAG_INVALID;

        /**
         * The position of the Item represented by this ViewHolder has been changed. This flag is
         * not bound to {@link RecyclerView.Adapter#notifyItemMoved(int, int)}. It might be set in response to
         * any adapter change that may have a side effect on this item. (e.g. The item before this
         * one has been removed from the Adapter).
         * <p>
         *
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         */
        public static final int FLAG_MOVED = ViewHolder.FLAG_MOVED;

        /**
         * This ViewHolder was not laid out but has been added to the layout in pre-layout state
         * by the {@link LayoutManager}. This means that the item was already in the Adapter but
         * invisible and it may become visible in the post layout phase. LayoutManagers may prefer
         * to add new items in pre-layout to specify their virtual location when they are invisible
         * (e.g. to specify the item should <i>animate in</i> from below the visible area).
         * <p>
         *
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         */
        public static final int FLAG_APPEARED_IN_PRE_LAYOUT
                = ViewHolder.FLAG_APPEARED_IN_PRE_LAYOUT;

        /**
         * The set of flags that might be passed to
         * {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         */
        @IntDef(flag = true, value = {
                FLAG_CHANGED, FLAG_REMOVED, FLAG_MOVED, FLAG_INVALIDATED,
                FLAG_APPEARED_IN_PRE_LAYOUT
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface AdapterChanges {
        }

        private ItemAnimatorListener mListener = null;
        private ArrayList<ItemAnimatorFinishedListener> mFinishedListeners =
                new ArrayList<ItemAnimatorFinishedListener>();

        private long mAddDuration = 120;
        private long mRemoveDuration = 120;
        private long mMoveDuration = 250;
        private long mChangeDuration = 250;

        /**
         * Gets the current duration for which all move animations will run.
         *
         * @return The current move duration
         */
        public long getMoveDuration() {
            return mMoveDuration;
        }

        /**
         * Sets the duration for which all move animations will run.
         *
         * @param moveDuration The move duration
         */
        public void setMoveDuration(long moveDuration) {
            mMoveDuration = moveDuration;
        }

        /**
         * Gets the current duration for which all add animations will run.
         *
         * @return The current add duration
         */
        public long getAddDuration() {
            return mAddDuration;
        }

        /**
         * Sets the duration for which all add animations will run.
         *
         * @param addDuration The add duration
         */
        public void setAddDuration(long addDuration) {
            mAddDuration = addDuration;
        }

        /**
         * Gets the current duration for which all remove animations will run.
         *
         * @return The current remove duration
         */
        public long getRemoveDuration() {
            return mRemoveDuration;
        }

        /**
         * Sets the duration for which all remove animations will run.
         *
         * @param removeDuration The remove duration
         */
        public void setRemoveDuration(long removeDuration) {
            mRemoveDuration = removeDuration;
        }

        /**
         * Gets the current duration for which all change animations will run.
         *
         * @return The current change duration
         */
        public long getChangeDuration() {
            return mChangeDuration;
        }

        /**
         * Sets the duration for which all change animations will run.
         *
         * @param changeDuration The change duration
         */
        public void setChangeDuration(long changeDuration) {
            mChangeDuration = changeDuration;
        }

        /**
         * Internal only:
         * Sets the listener that must be called when the animator is finished
         * animating the item (or immediately if no animation happens). This is set
         * internally and is not intended to be set by external code.
         *
         * @param listener The listener that must be called.
         */
        void setListener(ItemAnimatorListener listener) {
            mListener = listener;
        }

        /**
         * Called by the RecyclerView before the layout begins. Item animator should record
         * necessary information about the View before it is potentially rebound, moved or removed.
         * <p>
         * The data returned from this method will be passed to the related <code>animate**</code>
         * methods.
         * <p>
         * Note that this method may be called after pre-layout phase if LayoutManager adds new
         * Views to the layout in pre-layout pass.
         * <p>
         * The default implementation returns an {@link ItemHolderInfo} which holds the bounds of
         * the View and the adapter change flags.
         *
         * @param state       The current State of RecyclerView which includes some useful data
         *                    about the layout that will be calculated.
         * @param viewHolder  The ViewHolder whose information should be recorded.
         * @param changeFlags Additional information about what changes happened in the Adapter
         *                    about the Item represented by this ViewHolder. For instance, if
         *                    item is deleted from the adapter, {@link #FLAG_REMOVED} will be set.
         * @param payloads    The payload list that was previously passed to
         *                    {@link RecyclerView.Adapter#notifyItemChanged(int, Object)} or
         *                    {@link RecyclerView.Adapter#notifyItemRangeChanged(int, int, Object)}.
         * @return An ItemHolderInfo instance that preserves necessary information about the
         * ViewHolder. This object will be passed back to related <code>animate**</code> methods
         * after layout is complete.
         * @see #recordPostLayoutInformation(State, ViewHolder)
         * @see #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animatePersistence(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         */
        public
        @NonNull
        ItemHolderInfo recordPreLayoutInformation(@NonNull State state,
                                                  @NonNull ViewHolder viewHolder, @AdapterChanges int changeFlags,
                                                  @NonNull List<Object> payloads) {
            return obtainHolderInfo().setFrom(viewHolder);
        }

        /**
         * Called by the RecyclerView after the layout is complete. Item animator should record
         * necessary information about the View's final state.
         * <p>
         * The data returned from this method will be passed to the related <code>animate**</code>
         * methods.
         * <p>
         * The default implementation returns an {@link ItemHolderInfo} which holds the bounds of
         * the View.
         *
         * @param state      The current State of RecyclerView which includes some useful data about
         *                   the layout that will be calculated.
         * @param viewHolder The ViewHolder whose information should be recorded.
         * @return An ItemHolderInfo that preserves necessary information about the ViewHolder.
         * This object will be passed back to related <code>animate**</code> methods when
         * RecyclerView decides how items should be animated.
         * @see #recordPreLayoutInformation(State, ViewHolder, int, List)
         * @see #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * @see #animatePersistence(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         */
        public
        @NonNull
        ItemHolderInfo recordPostLayoutInformation(@NonNull State state, @NonNull ViewHolder viewHolder) {
            return obtainHolderInfo().setFrom(viewHolder);
        }

        /**
         * Called by the RecyclerView when a ViewHolder has disappeared from the layout.
         * <p>
         * This means that the View was a child of the LayoutManager when layout started but has
         * been removed by the LayoutManager. It might have been removed from the adapter or simply
         * become invisible due to other factors. You can distinguish these two cases by checking
         * the change flags that were passed to
         * {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * <p>
         * Note that when a ViewHolder both changes and disappears in the same layout pass, the
         * animation callback method which will be called by the RecyclerView depends on the
         * ItemAnimator's decision whether to re-use the same ViewHolder or not, and also the
         * LayoutManager's decision whether to layout the changed version of a disappearing
         * ViewHolder or not. RecyclerView will call
         * {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateChange} instead of {@code animateDisappearance} if and only if the ItemAnimator
         * returns {@code false} from
         * {@link #canReuseUpdatedViewHolder(ViewHolder) canReuseUpdatedViewHolder} and the
         * LayoutManager lays out a new disappearing view that holds the updated information.
         * Built-in LayoutManagers try to avoid laying out updated versions of disappearing views.
         * <p>
         * If LayoutManager supports predictive animations, it might provide a target disappear
         * location for the View by laying it out in that location. When that happens,
         * RecyclerView will call {@link #recordPostLayoutInformation(State, ViewHolder)} and the
         * response of that call will be passed to this method as the <code>postLayoutInfo</code>.
         * <p>
         * ItemAnimator must call {@link #dispatchAnimationFinished(ViewHolder)} when the animation
         * is complete (or instantly call {@link #dispatchAnimationFinished(ViewHolder)} if it
         * decides not to animate the view).
         *
         * @param viewHolder     The ViewHolder which should be animated
         * @param preLayoutInfo  The information that was returned from
         *                       {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @param postLayoutInfo The information that was returned from
         *                       {@link #recordPostLayoutInformation(State, ViewHolder)}. Might be
         *                       null if the LayoutManager did not layout the item.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        public abstract boolean animateDisappearance(@NonNull ViewHolder viewHolder,
                                                     @NonNull ItemHolderInfo preLayoutInfo, @Nullable ItemHolderInfo postLayoutInfo);

        /**
         * Called by the RecyclerView when a ViewHolder is added to the layout.
         * <p>
         * In detail, this means that the ViewHolder was <b>not</b> a child when the layout started
         * but has  been added by the LayoutManager. It might be newly added to the adapter or
         * simply become visible due to other factors.
         * <p>
         * ItemAnimator must call {@link #dispatchAnimationFinished(ViewHolder)} when the animation
         * is complete (or instantly call {@link #dispatchAnimationFinished(ViewHolder)} if it
         * decides not to animate the view).
         *
         * @param viewHolder     The ViewHolder which should be animated
         * @param preLayoutInfo  The information that was returned from
         *                       {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         *                       Might be null if Item was just added to the adapter or
         *                       LayoutManager does not support predictive animations or it could
         *                       not predict that this ViewHolder will become visible.
         * @param postLayoutInfo The information that was returned from {@link
         *                       #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        public abstract boolean animateAppearance(@NonNull ViewHolder viewHolder,
                                                  @Nullable ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo);

        /**
         * Called by the RecyclerView when a ViewHolder is present in both before and after the
         * layout and RecyclerView has not received a {@link RecyclerView.Adapter#notifyItemChanged(int)} call
         * for it or a {@link RecyclerView.Adapter#notifyDataSetChanged()} call.
         * <p>
         * This ViewHolder still represents the same data that it was representing when the layout
         * started but its position / size may be changed by the LayoutManager.
         * <p>
         * If the Item's layout position didn't change, RecyclerView still calls this method because
         * it does not track this information (or does not necessarily know that an animation is
         * not required). Your ItemAnimator should handle this case and if there is nothing to
         * animate, it should call {@link #dispatchAnimationFinished(ViewHolder)} and return
         * <code>false</code>.
         * <p>
         * ItemAnimator must call {@link #dispatchAnimationFinished(ViewHolder)} when the animation
         * is complete (or instantly call {@link #dispatchAnimationFinished(ViewHolder)} if it
         * decides not to animate the view).
         *
         * @param viewHolder     The ViewHolder which should be animated
         * @param preLayoutInfo  The information that was returned from
         *                       {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @param postLayoutInfo The information that was returned from {@link
         *                       #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        public abstract boolean animatePersistence(@NonNull ViewHolder viewHolder,
                                                   @NonNull ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo);

        /**
         * Called by the RecyclerView when an adapter item is present both before and after the
         * layout and RecyclerView has received a {@link RecyclerView.Adapter#notifyItemChanged(int)} call
         * for it. This method may also be called when
         * {@link RecyclerView.Adapter#notifyDataSetChanged()} is called and adapter has stable ids so that
         * RecyclerView could still rebind views to the same ViewHolders. If viewType changes when
         * {@link RecyclerView.Adapter#notifyDataSetChanged()} is called, this method <b>will not</b> be called,
         * instead, {@link #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)} will be
         * called for the new ViewHolder and the old one will be recycled.
         * <p>
         * If this method is called due to a {@link RecyclerView.Adapter#notifyDataSetChanged()} call, there is
         * a good possibility that item contents didn't really change but it is rebound from the
         * adapter. {@link DefaultItemAnimator} will skip animating the View if its location on the
         * screen didn't change and your animator should handle this case as well and avoid creating
         * unnecessary animations.
         * <p>
         * When an item is updated, ItemAnimator has a chance to ask RecyclerView to keep the
         * previous presentation of the item as-is and supply a new ViewHolder for the updated
         * presentation (see: {@link #canReuseUpdatedViewHolder(ViewHolder, List)}.
         * This is useful if you don't know the contents of the Item and would like
         * to cross-fade the old and the new one ({@link DefaultItemAnimator} uses this technique).
         * <p>
         * When you are writing a custom item animator for your layout, it might be more performant
         * and elegant to re-use the same ViewHolder and animate the content changes manually.
         * <p>
         * When {@link RecyclerView.Adapter#notifyItemChanged(int)} is called, the Item's view type may change.
         * If the Item's view type has changed or ItemAnimator returned <code>false</code> for
         * this ViewHolder when {@link #canReuseUpdatedViewHolder(ViewHolder, List)} was called, the
         * <code>oldHolder</code> and <code>newHolder</code> will be different ViewHolder instances
         * which represent the same Item. In that case, only the new ViewHolder is visible
         * to the LayoutManager but RecyclerView keeps old ViewHolder attached for animations.
         * <p>
         * ItemAnimator must call {@link #dispatchAnimationFinished(ViewHolder)} for each distinct
         * ViewHolder when their animation is complete
         * (or instantly call {@link #dispatchAnimationFinished(ViewHolder)} if it decides not to
         * animate the view).
         * <p>
         * If oldHolder and newHolder are the same instance, you should call
         * {@link #dispatchAnimationFinished(ViewHolder)} <b>only once</b>.
         * <p>
         * Note that when a ViewHolder both changes and disappears in the same layout pass, the
         * animation callback method which will be called by the RecyclerView depends on the
         * ItemAnimator's decision whether to re-use the same ViewHolder or not, and also the
         * LayoutManager's decision whether to layout the changed version of a disappearing
         * ViewHolder or not. RecyclerView will call
         * {@code animateChange} instead of
         * {@link #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateDisappearance} if and only if the ItemAnimator returns {@code false} from
         * {@link #canReuseUpdatedViewHolder(ViewHolder) canReuseUpdatedViewHolder} and the
         * LayoutManager lays out a new disappearing view that holds the updated information.
         * Built-in LayoutManagers try to avoid laying out updated versions of disappearing views.
         *
         * @param oldHolder      The ViewHolder before the layout is started, might be the same
         *                       instance with newHolder.
         * @param newHolder      The ViewHolder after the layout is finished, might be the same
         *                       instance with oldHolder.
         * @param preLayoutInfo  The information that was returned from
         *                       {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @param postLayoutInfo The information that was returned from {@link
         *                       #recordPreLayoutInformation(State, ViewHolder, int, List)}.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        public abstract boolean animateChange(@NonNull ViewHolder oldHolder,
                                              @NonNull ViewHolder newHolder,
                                              @NonNull ItemHolderInfo preLayoutInfo, @NonNull ItemHolderInfo postLayoutInfo);

        @AdapterChanges
        static int buildAdapterChangeFlagsForAnimations(ViewHolder viewHolder) {
            int flags = viewHolder.mFlags & (FLAG_INVALIDATED | FLAG_REMOVED | FLAG_CHANGED);
            if (viewHolder.isInvalid()) {
                return FLAG_INVALIDATED;
            }
            if ((flags & FLAG_INVALIDATED) == 0) {
                final int oldPos = viewHolder.getOldPosition();
                final int pos = viewHolder.getAdapterPosition();
                if (oldPos != NO_POSITION && pos != NO_POSITION && oldPos != pos) {
                    flags |= FLAG_MOVED;
                }
            }
            return flags;
        }

        /**
         * Called when there are pending animations waiting to be started. This state
         * is governed by the return values from
         * {@link #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateAppearance()},
         * {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateChange()}
         * {@link #animatePersistence(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animatePersistence()}, and
         * {@link #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateDisappearance()}, which inform the RecyclerView that the ItemAnimator wants to be
         * called later to start the associated animations. runPendingAnimations() will be scheduled
         * to be run on the next frame.
         */
        abstract public void runPendingAnimations();

        /**
         * Method called when an animation on a view should be ended immediately.
         * This could happen when other events, like scrolling, occur, so that
         * animating views can be quickly put into their proper end locations.
         * Implementations should ensure that any animations running on the item
         * are canceled and affected properties are set to their end values.
         * Also, {@link #dispatchAnimationFinished(ViewHolder)} should be called for each finished
         * animation since the animations are effectively done when this method is called.
         *
         * @param item The item for which an animation should be stopped.
         */
        abstract public void endAnimation(ViewHolder item);

        /**
         * Method called when all item animations should be ended immediately.
         * This could happen when other events, like scrolling, occur, so that
         * animating views can be quickly put into their proper end locations.
         * Implementations should ensure that any animations running on any items
         * are canceled and affected properties are set to their end values.
         * Also, {@link #dispatchAnimationFinished(ViewHolder)} should be called for each finished
         * animation since the animations are effectively done when this method is called.
         */
        abstract public void endAnimations();

        /**
         * Method which returns whether there are any item animations currently running.
         * This method can be used to determine whether to delay other actions until
         * animations end.
         *
         * @return true if there are any item animations currently running, false otherwise.
         */
        abstract public boolean isRunning();

        /**
         * Method to be called by subclasses when an animation is finished.
         * <p>
         * For each call RecyclerView makes to
         * {@link #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateAppearance()},
         * {@link #animatePersistence(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animatePersistence()}, or
         * {@link #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateDisappearance()}, there
         * should
         * be a matching {@link #dispatchAnimationFinished(ViewHolder)} call by the subclass.
         * <p>
         * For {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateChange()}, subclass should call this method for both the <code>oldHolder</code>
         * and <code>newHolder</code>  (if they are not the same instance).
         *
         * @param viewHolder The ViewHolder whose animation is finished.
         * @see #onAnimationFinished(ViewHolder)
         */
        public final void dispatchAnimationFinished(ViewHolder viewHolder) {
            onAnimationFinished(viewHolder);
            if (mListener != null) {
                mListener.onAnimationFinished(viewHolder);
            }
        }

        /**
         * Called after {@link #dispatchAnimationFinished(ViewHolder)} is called by the
         * ItemAnimator.
         *
         * @param viewHolder The ViewHolder whose animation is finished. There might still be other
         *                   animations running on this ViewHolder.
         * @see #dispatchAnimationFinished(ViewHolder)
         */
        public void onAnimationFinished(ViewHolder viewHolder) {
        }

        /**
         * Method to be called by subclasses when an animation is started.
         * <p>
         * For each call RecyclerView makes to
         * {@link #animateAppearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateAppearance()},
         * {@link #animatePersistence(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animatePersistence()}, or
         * {@link #animateDisappearance(ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateDisappearance()}, there should be a matching
         * {@link #dispatchAnimationStarted(ViewHolder)} call by the subclass.
         * <p>
         * For {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)
         * animateChange()}, subclass should call this method for both the <code>oldHolder</code>
         * and <code>newHolder</code> (if they are not the same instance).
         * <p>
         * If your ItemAnimator decides not to animate a ViewHolder, it should call
         * {@link #dispatchAnimationFinished(ViewHolder)} <b>without</b> calling
         * {@link #dispatchAnimationStarted(ViewHolder)}.
         *
         * @param viewHolder The ViewHolder whose animation is starting.
         * @see #onAnimationStarted(ViewHolder)
         */
        public final void dispatchAnimationStarted(ViewHolder viewHolder) {
            onAnimationStarted(viewHolder);
        }

        /**
         * Called when a new animation is started on the given ViewHolder.
         *
         * @param viewHolder The ViewHolder which started animating. Note that the ViewHolder
         *                   might already be animating and this might be another animation.
         * @see #dispatchAnimationStarted(ViewHolder)
         */
        public void onAnimationStarted(ViewHolder viewHolder) {

        }

        /**
         * Like {@link #isRunning()}, this method returns whether there are any item
         * animations currently running. Additionally, the listener passed in will be called
         * when there are no item animations running, either immediately (before the method
         * returns) if no animations are currently running, or when the currently running
         * animations are {@link #dispatchAnimationsFinished() finished}.
         * <p>
         * <p>Note that the listener is transient - it is either called immediately and not
         * stored at all, or stored only until it is called when running animations
         * are finished sometime later.</p>
         *
         * @param listener A listener to be called immediately if no animations are running
         *                 or later when currently-running animations have finished. A null listener is
         *                 equivalent to calling {@link #isRunning()}.
         * @return true if there are any item animations currently running, false otherwise.
         */
        public final boolean isRunning(ItemAnimatorFinishedListener listener) {
            boolean running = isRunning();
            if (listener != null) {
                if (!running) {
                    listener.onAnimationsFinished();
                } else {
                    mFinishedListeners.add(listener);
                }
            }
            return running;
        }

        /**
         * When an item is changed, ItemAnimator can decide whether it wants to re-use
         * the same ViewHolder for animations or RecyclerView should create a copy of the
         * item and ItemAnimator will use both to run the animation (e.g. cross-fade).
         * <p>
         * Note that this method will only be called if the {@link ViewHolder} still has the same
         * type ({@link RecyclerView.Adapter#getItemViewType(int)}). Otherwise, ItemAnimator will always receive
         * both {@link ViewHolder}s in the
         * {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)} method.
         * <p>
         * If your application is using change payloads, you can override
         * {@link #canReuseUpdatedViewHolder(ViewHolder, List)} to decide based on payloads.
         *
         * @param viewHolder The ViewHolder which represents the changed item's old content.
         * @return True if RecyclerView should just rebind to the same ViewHolder or false if
         * RecyclerView should create a new ViewHolder and pass this ViewHolder to the
         * ItemAnimator to animate. Default implementation returns <code>true</code>.
         * @see #canReuseUpdatedViewHolder(ViewHolder, List)
         */
        public boolean canReuseUpdatedViewHolder(@NonNull ViewHolder viewHolder) {
            return true;
        }

        /**
         * When an item is changed, ItemAnimator can decide whether it wants to re-use
         * the same ViewHolder for animations or RecyclerView should create a copy of the
         * item and ItemAnimator will use both to run the animation (e.g. cross-fade).
         * <p>
         * Note that this method will only be called if the {@link ViewHolder} still has the same
         * type ({@link RecyclerView.Adapter#getItemViewType(int)}). Otherwise, ItemAnimator will always receive
         * both {@link ViewHolder}s in the
         * {@link #animateChange(ViewHolder, ViewHolder, ItemHolderInfo, ItemHolderInfo)} method.
         *
         * @param viewHolder The ViewHolder which represents the changed item's old content.
         * @param payloads   A non-null list of merged payloads that were sent with change
         *                   notifications. Can be empty if the adapter is invalidated via
         *                   {@link RecyclerView.Adapter#notifyDataSetChanged()}. The same list of
         *                   payloads will be passed into
         *                   {@link RecyclerView.Adapter#onBindViewHolder(ViewHolder, int, List)}
         *                   method <b>if</b> this method returns <code>true</code>.
         * @return True if RecyclerView should just rebind to the same ViewHolder or false if
         * RecyclerView should create a new ViewHolder and pass this ViewHolder to the
         * ItemAnimator to animate. Default implementation calls
         * {@link #canReuseUpdatedViewHolder(ViewHolder)}.
         * @see #canReuseUpdatedViewHolder(ViewHolder)
         */
        public boolean canReuseUpdatedViewHolder(@NonNull ViewHolder viewHolder, @NonNull List<Object> payloads) {
            return canReuseUpdatedViewHolder(viewHolder);
        }

        /**
         * This method should be called by ItemAnimator implementations to notify
         * any listeners that all pending and active item animations are finished.
         */
        public final void dispatchAnimationsFinished() {
            final int count = mFinishedListeners.size();
            for (int i = 0; i < count; ++i) {
                mFinishedListeners.get(i).onAnimationsFinished();
            }
            mFinishedListeners.clear();
        }

        /**
         * Returns a new {@link ItemHolderInfo} which will be used to store information about the
         * ViewHolder. This information will later be passed into <code>animate**</code> methods.
         * <p>
         * You can override this method if you want to extend {@link ItemHolderInfo} and provide
         * your own instances.
         *
         * @return A new {@link ItemHolderInfo}.
         */
        public ItemHolderInfo obtainHolderInfo() {
            return new ItemHolderInfo();
        }

        /**
         * The interface to be implemented by listeners to animation events from this
         * ItemAnimator. This is used internally and is not intended for developers to
         * create directly.
         */
        interface ItemAnimatorListener {
            void onAnimationFinished(ViewHolder item);
        }

        /**
         * This interface is used to inform listeners when all pending or running animations
         * in an ItemAnimator are finished. This can be used, for example, to delay an action
         * in a data set until currently-running animations are complete.
         *
         * @see #isRunning(ItemAnimatorFinishedListener)
         */
        public interface ItemAnimatorFinishedListener {
            void onAnimationsFinished();
        }

        public static class ItemHolderInfo {

            /**
             * The left edge of the View (excluding decorations)
             */
            public int left;

            /**
             * The top edge of the View (excluding decorations)
             */
            public int top;

            /**
             * The right edge of the View (excluding decorations)
             */
            public int right;

            /**
             * The bottom edge of the View (excluding decorations)
             */
            public int bottom;

            /**
             * The change flags that were passed to
             * {@link #recordPreLayoutInformation(State, ViewHolder, int, List)}.
             */
            @AdapterChanges
            public int changeFlags;

            public ItemHolderInfo() {
            }

            /**
             * Sets the {@link #left}, {@link #top}, {@link #right} and {@link #bottom} values from
             * the given ViewHolder. Clears all {@link #changeFlags}.
             *
             * @param holder The ViewHolder whose bounds should be copied.
             * @return This {@link ItemHolderInfo}
             */
            public ItemHolderInfo setFrom(ViewHolder holder) {
                return setFrom(holder, 0);
            }

            /**
             * Sets the {@link #left}, {@link #top}, {@link #right} and {@link #bottom} values from
             * the given ViewHolder and sets the {@link #changeFlags} to the given flags parameter.
             *
             * @param holder The ViewHolder whose bounds should be copied.
             * @param flags  The adapter change flags that were passed into
             *               {@link #recordPreLayoutInformation(State, ViewHolder, int,
             *               List)}.
             * @return This {@link ItemHolderInfo}
             */
            public ItemHolderInfo setFrom(ViewHolder holder,
                                          @AdapterChanges int flags) {
                final View view = holder.itemView;
                this.left = view.getLeft();
                this.top = view.getTop();
                this.right = view.getRight();
                this.bottom = view.getBottom();
                return this;
            }
        }
    }

    public static abstract class SmoothScroller {

        private int mTargetPosition = DRecyclerView.NO_POSITION;

        private DRecyclerView mRecyclerView;

        private DRecyclerView.LayoutManager mLayoutManager;

        private boolean mPendingInitialRun;

        private boolean mRunning;

        private View mTargetView;

        private final Action mRecyclingAction;

        public SmoothScroller() {
            mRecyclingAction = new Action(0, 0);
        }

        /**
         * Starts a smooth scroll for the given target position.
         * <p>In each animation step, {@link RecyclerView} will check
         * for the target view and call either
         * {@link #onTargetFound(android.view.View, DRecyclerView.State, SmoothScroller.Action)} or
         * {@link #onSeekTargetStep(int, int, DRecyclerView.State, SmoothScroller.Action)} until
         * SmoothScroller is stopped.</p>
         * <p>
         * <p>Note that if RecyclerView finds the target view, it will automatically stop the
         * SmoothScroller. This <b>does not</b> mean that scroll will stop, it only means it will
         * stop calling SmoothScroller in each animation step.</p>
         */
        void start(DRecyclerView recyclerView, DRecyclerView.LayoutManager layoutManager) {
            mRecyclerView = recyclerView;
            mLayoutManager = layoutManager;
            if (mTargetPosition == RecyclerView.NO_POSITION) {
                throw new IllegalArgumentException("Invalid target position");
            }
            mRecyclerView.mState.mTargetPosition = mTargetPosition;
            mRunning = true;
            mPendingInitialRun = true;
            mTargetView = findViewByPosition(getTargetPosition());
            onStart();
            mRecyclerView.mViewFlinger.postOnAnimation();
        }

        public void setTargetPosition(int targetPosition) {
            mTargetPosition = targetPosition;
        }

        /**
         * @return The LayoutManager to which this SmoothScroller is attached. Will return
         * <code>null</code> after the SmoothScroller is stopped.
         */
        @Nullable
        public DRecyclerView.LayoutManager getLayoutManager() {
            return mLayoutManager;
        }

        /**
         * Stops running the SmoothScroller in each animation callback. Note that this does not
         * cancel any existing {@link Action} updated by
         * {@link #onTargetFound(android.view.View, DRecyclerView.State, SmoothScroller.Action)} or
         * {@link #onSeekTargetStep(int, int, DRecyclerView.State, SmoothScroller.Action)}.
         */
        final protected void stop() {
            if (!mRunning) {
                return;
            }
            onStop();
            mRecyclerView.mState.mTargetPosition = RecyclerView.NO_POSITION;
            mTargetView = null;
            mTargetPosition = RecyclerView.NO_POSITION;
            mPendingInitialRun = false;
            mRunning = false;
            // trigger a cleanup
            mLayoutManager.onSmoothScrollerStopped(this);
            // clear references to avoid any potential leak by a custom smooth scroller
            mLayoutManager = null;
            mRecyclerView = null;
        }

        /**
         * Returns true if SmoothScroller has been started but has not received the first
         * animation
         * callback yet.
         *
         * @return True if this SmoothScroller is waiting to start
         */
        public boolean isPendingInitialRun() {
            return mPendingInitialRun;
        }


        /**
         * @return True if SmoothScroller is currently active
         */
        public boolean isRunning() {
            return mRunning;
        }

        /**
         * Returns the adapter position of the target item
         *
         * @return Adapter position of the target item or
         * {@link RecyclerView#NO_POSITION} if no target view is set.
         */
        public int getTargetPosition() {
            return mTargetPosition;
        }

        private void onAnimation(int dx, int dy) {
            final DRecyclerView recyclerView = mRecyclerView;
            if (!mRunning || mTargetPosition == RecyclerView.NO_POSITION || recyclerView == null) {
                stop();
            }
            mPendingInitialRun = false;
            if (mTargetView != null) {
                // verify target position
                if (getChildPosition(mTargetView) == mTargetPosition) {
                    onTargetFound(mTargetView, recyclerView.mState, mRecyclingAction);
                    mRecyclingAction.runIfNecessary(recyclerView);
                    stop();
                } else {
                    Log.e(TAG, "Passed over target position while smooth scrolling.");
                    mTargetView = null;
                }
            }
            if (mRunning) {
                onSeekTargetStep(dx, dy, recyclerView.mState, mRecyclingAction);
                boolean hadJumpTarget = mRecyclingAction.hasJumpTarget();
                mRecyclingAction.runIfNecessary(recyclerView);
                if (hadJumpTarget) {
                    // It is not stopped so needs to be restarted
                    if (mRunning) {
                        mPendingInitialRun = true;
                        recyclerView.mViewFlinger.postOnAnimation();
                    } else {
                        stop(); // done
                    }
                }
            }
        }

        /**
         * @see RecyclerView#getChildLayoutPosition(android.view.View)
         */
        public int getChildPosition(View view) {
            return mRecyclerView.getChildLayoutPosition(view);
        }

        /**
         * @see RecyclerView.LayoutManager#getChildCount()
         */
        public int getChildCount() {
            return mRecyclerView.mLayoutManager.getChildCount();
        }

        /**
         * @see RecyclerView.LayoutManager#findViewByPosition(int)
         */
        public View findViewByPosition(int position) {
            return mRecyclerView.mLayoutManager.findViewByPosition(position);
        }

        /**
         * @see RecyclerView#scrollToPosition(int)
         * @deprecated Use {@link Action#jumpTo(int)}.
         */
        @Deprecated
        public void instantScrollToPosition(int position) {
            mRecyclerView.scrollToPosition(position);
        }

        protected void onChildAttachedToWindow(View child) {
            if (getChildPosition(child) == getTargetPosition()) {
                mTargetView = child;
                if (DEBUG) {
                    Log.d(TAG, "smooth scroll target view has been attached");
                }
            }
        }

        /**
         * Normalizes the vector.
         *
         * @param scrollVector The vector that points to the target scroll position
         */
        protected void normalize(PointF scrollVector) {
            final double magnitude = Math.sqrt(scrollVector.x * scrollVector.x + scrollVector.y
                    * scrollVector.y);
            scrollVector.x /= magnitude;
            scrollVector.y /= magnitude;
        }

        /**
         * Called when smooth scroll is started. This might be a good time to do setup.
         */
        abstract protected void onStart();

        /**
         * Called when smooth scroller is stopped. This is a good place to cleanup your state etc.
         *
         * @see #stop()
         */
        abstract protected void onStop();

        /**
         * <p>RecyclerView will call this method each time it scrolls until it can find the target
         * position in the layout.</p>
         * <p>SmoothScroller should check dx, dy and if scroll should be changed, update the
         * provided {@link Action} to define the next scroll.</p>
         *
         * @param dx     Last scroll amount horizontally
         * @param dy     Last scroll amount vertically
         * @param state  Transient state of RecyclerView
         * @param action If you want to trigger a new smooth scroll and cancel the previous one,
         *               update this object.
         */
        abstract protected void onSeekTargetStep(int dx, int dy, DRecyclerView.State state, Action action);

        /**
         * Called when the target position is laid out. This is the last callback SmoothScroller
         * will receive and it should update the provided {@link Action} to define the scroll
         * details towards the target view.
         *
         * @param targetView The view element which render the target position.
         * @param state      Transient state of RecyclerView
         * @param action     Action instance that you should update to define final scroll action
         *                   towards the targetView
         */
        abstract protected void onTargetFound(View targetView, DRecyclerView.State state, Action action);

        /**
         * Holds information about a smooth scroll request by a {@link SmoothScroller}.
         */
        public static class Action {

            public static final int UNDEFINED_DURATION = Integer.MIN_VALUE;

            private int mDx;

            private int mDy;

            private int mDuration;

            private int mJumpToPosition = NO_POSITION;

            private Interpolator mInterpolator;

            private boolean changed = false;

            // we track this variable to inform custom implementer if they are updating the action
            // in every animation callback
            private int consecutiveUpdates = 0;

            /**
             * @param dx Pixels to scroll horizontally
             * @param dy Pixels to scroll vertically
             */
            public Action(int dx, int dy) {
                this(dx, dy, UNDEFINED_DURATION, null);
            }

            /**
             * @param dx       Pixels to scroll horizontally
             * @param dy       Pixels to scroll vertically
             * @param duration Duration of the animation in milliseconds
             */
            public Action(int dx, int dy, int duration) {
                this(dx, dy, duration, null);
            }

            /**
             * @param dx           Pixels to scroll horizontally
             * @param dy           Pixels to scroll vertically
             * @param duration     Duration of the animation in milliseconds
             * @param interpolator Interpolator to be used when calculating scroll position in each
             *                     animation step
             */
            public Action(int dx, int dy, int duration, Interpolator interpolator) {
                mDx = dx;
                mDy = dy;
                mDuration = duration;
                mInterpolator = interpolator;
            }

            /**
             * Instead of specifying pixels to scroll, use the target position to jump using
             * {@link RecyclerView#scrollToPosition(int)}.
             * <p>
             * You may prefer using this method if scroll target is really far away and you prefer
             * to jump to a location and smooth scroll afterwards.
             * <p>
             * Note that calling this method takes priority over other update methods such as
             * {@link #update(int, int, int, Interpolator)}, {@link #setX(float)},
             * {@link #setY(float)} and #{@link #setInterpolator(Interpolator)}. If you call
             * {@link #jumpTo(int)}, the other changes will not be considered for this animation
             * frame.
             *
             * @param targetPosition The target item position to scroll to using instant scrolling.
             */
            public void jumpTo(int targetPosition) {
                mJumpToPosition = targetPosition;
            }

            boolean hasJumpTarget() {
                return mJumpToPosition >= 0;
            }

            void runIfNecessary(DRecyclerView recyclerView) {
                if (mJumpToPosition >= 0) {
                    final int position = mJumpToPosition;
                    mJumpToPosition = NO_POSITION;
                    recyclerView.jumpToPositionForSmoothScroller(position);
                    changed = false;
                    return;
                }
                if (changed) {
                    validate();
                    if (mInterpolator == null) {
                        if (mDuration == UNDEFINED_DURATION) {
                            recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy);
                        } else {
                            recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy, mDuration);
                        }
                    } else {
                        recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy, mDuration, mInterpolator);
                    }
                    consecutiveUpdates++;
                    if (consecutiveUpdates > 10) {
                        // A new action is being set in every animation step. This looks like a bad
                        // implementation. Inform developer.
                        Log.e(TAG, "Smooth Scroll action is being updated too frequently. Make sure"
                                + " you are not changing it unless necessary");
                    }
                    changed = false;
                } else {
                    consecutiveUpdates = 0;
                }
            }

            private void validate() {
                if (mInterpolator != null && mDuration < 1) {
                    throw new IllegalStateException("If you provide an interpolator, you must"
                            + " set a positive duration");
                } else if (mDuration < 1) {
                    throw new IllegalStateException("Scroll duration must be a positive number");
                }
            }

            public int getDx() {
                return mDx;
            }

            public void setDx(int dx) {
                changed = true;
                mDx = dx;
            }

            public int getDy() {
                return mDy;
            }

            public void setDy(int dy) {
                changed = true;
                mDy = dy;
            }

            public int getDuration() {
                return mDuration;
            }

            public void setDuration(int duration) {
                changed = true;
                mDuration = duration;
            }

            public Interpolator getInterpolator() {
                return mInterpolator;
            }

            /**
             * Sets the interpolator to calculate scroll steps
             *
             * @param interpolator The interpolator to use. If you specify an interpolator, you must
             *                     also set the duration.
             * @see #setDuration(int)
             */
            public void setInterpolator(Interpolator interpolator) {
                changed = true;
                mInterpolator = interpolator;
            }

            /**
             * Updates the action with given parameters.
             *
             * @param dx           Pixels to scroll horizontally
             * @param dy           Pixels to scroll vertically
             * @param duration     Duration of the animation in milliseconds
             * @param interpolator Interpolator to be used when calculating scroll position in each
             *                     animation step
             */
            public void update(int dx, int dy, int duration, Interpolator interpolator) {
                mDx = dx;
                mDy = dy;
                mDuration = duration;
                mInterpolator = interpolator;
                changed = true;
            }
        }

        /**
         * An interface which is optionally implemented by custom {@link RecyclerView.LayoutManager}
         * to provide a hint to a {@link SmoothScroller} about the location of the target position.
         */
        public interface ScrollVectorProvider {
            /**
             * Should calculate the vector that points to the direction where the target position
             * can be found.
             * <p>
             * This method is used by the {@link LinearSmoothScroller} to initiate a scroll towards
             * the target position.
             * <p>
             * The magnitude of the vector is not important. It is always normalized before being
             * used by the {@link LinearSmoothScroller}.
             * <p>
             * LayoutManager should not check whether the position exists in the adapter or not.
             *
             * @param targetPosition the target position to which the returned vector should point
             * @return the scroll vector for a given position.
             */
            PointF computeScrollVectorForPosition(int targetPosition);
        }
    }
}
/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fred.feedex.view;

import android.R.attr;
import android.R.integer;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Transformation;


/**
 * The SwipeRefreshLayout should be used whenever the user can refresh the
 * contents of a view via a vertical swipe gesture. The activity that
 * instantiates this view should add an OnRefreshListener to be notified
 * whenever the swipe to refresh gesture is completed. The SwipeRefreshLayout
 * will notify the listener each and every time the gesture is completed again;
 * the listener is responsible for correctly determining when to actually
 * initiate a refresh of its content. If the listener determines there should
 * not be a refresh, it must call setRefreshing(false) to cancel any visual
 * indication of a refresh. If an activity wishes to show just the progress
 * animation, it should call setRefreshing(true). To disable the gesture and progress
 * animation, call setEnabled(false) on the view.
 * <p/>
 * <p> This layout should be made the parent of the view that will be refreshed as a
 * result of the gesture and can only support one direct child. This view will
 * also be made the target of the gesture and will be forced to match both the
 * width and the height supplied in this layout. The SwipeRefreshLayout does not
 * provide accessibility events; instead, a menu item must be provided to allow
 * refresh of the content wherever this gesture is used.</p>
 */
public class SwipeRefreshLayout extends ViewGroup {
    private static final long RETURN_TO_ORIGINAL_POSITION_TIMEOUT = 300;
    private static final float ACCELERATE_INTERPOLATION_FACTOR = 1.5f;
    private static final float DECELERATE_INTERPOLATION_FACTOR = 2f;
    private static final float PROGRESS_BAR_HEIGHT = 4;
    private static final float MAX_SWIPE_DISTANCE_FACTOR = .6f;
    private static final int REFRESH_TRIGGER_DISTANCE = 120;
    private static final int[] LAYOUT_ATTRS = {
            attr.enabled
    };
    private final DecelerateInterpolator mDecelerateInterpolator;
    private final AccelerateInterpolator mAccelerateInterpolator;
    private final SwipeProgressBar mProgressBar; //the thing that shows progress is going
    private final int mTouchSlop;
    private final int mMediumAnimationDuration;
    private View mTarget; //the content that gets pulled down
    private int mOriginalOffsetTop;
    private OnRefreshListener mListener;
    private MotionEvent mDownEvent;
    private int mFrom;
    private boolean mRefreshing;
    private float mDistanceToTriggerSync = -1;
    private float mPrevY;
    private float mFromPercentage;
    private final Animation mShrinkTrigger = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            float percent = mFromPercentage + ((0 - mFromPercentage) * interpolatedTime);
            mProgressBar.setTriggerPercentage(percent);
        }
    };
    private float mCurrPercentage;
    private final AnimationListener mShrinkAnimationListener = new SwipeRefreshLayout.BaseAnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            mCurrPercentage = 0;
        }
    };
    private int mProgressBarHeight;
    private int mCurrentTargetOffsetTop;
    private final Animation mAnimateToStartPosition = new Animation() {
        @Override
        public void applyTransformation(float interpolatedTime, Transformation t) {
            int targetTop = 0;
            if (mFrom != mOriginalOffsetTop) {
                targetTop = (mFrom + (int) ((mOriginalOffsetTop - mFrom) * interpolatedTime));
            }
            int offset = targetTop - mTarget.getTop();
            final int currentTop = mTarget.getTop();
            if (offset + currentTop < 0) {
                offset = 0 - currentTop;
            }
            setTargetOffsetTopAndBottom(offset);
        }
    };
    private final Animation.AnimationListener mReturnToStartPositionListener = new SwipeRefreshLayout.BaseAnimationListener() {
        @Override
        public void onAnimationEnd(Animation animation) {
            // Once the target content has returned to its start position, reset
            // the target offset to 0
            SwipeRefreshLayout.this.mCurrentTargetOffsetTop = 0;
        }
    };
    // Target is returning to its start offset because it was cancelled or a
    // refresh was triggered.
    private boolean mReturningToStart;
    private final Runnable mReturnToStartPosition = new Runnable() {

        @Override
        public void run() {
            SwipeRefreshLayout.this.mReturningToStart = true;
            SwipeRefreshLayout.this.animateOffsetToStartPosition(SwipeRefreshLayout.this.mCurrentTargetOffsetTop + SwipeRefreshLayout.this.getPaddingTop(),
                    SwipeRefreshLayout.this.mReturnToStartPositionListener);
        }

    };

    // Cancel the refresh gesture and animate everything back to its original state.
    private final Runnable mCancel = new Runnable() {

        @Override
        public void run() {
            SwipeRefreshLayout.this.mReturningToStart = true;
            // Timeout fired since the user last moved their finger; animate the
            // trigger to 0 and put the target back at its original position
            if (SwipeRefreshLayout.this.mProgressBar != null) {
                SwipeRefreshLayout.this.mFromPercentage = SwipeRefreshLayout.this.mCurrPercentage;
                SwipeRefreshLayout.this.mShrinkTrigger.setDuration(SwipeRefreshLayout.this.mMediumAnimationDuration);
                SwipeRefreshLayout.this.mShrinkTrigger.setAnimationListener(SwipeRefreshLayout.this.mShrinkAnimationListener);
                SwipeRefreshLayout.this.mShrinkTrigger.reset();
                SwipeRefreshLayout.this.mShrinkTrigger.setInterpolator(SwipeRefreshLayout.this.mDecelerateInterpolator);
                SwipeRefreshLayout.this.startAnimation(SwipeRefreshLayout.this.mShrinkTrigger);
            }
            SwipeRefreshLayout.this.animateOffsetToStartPosition(SwipeRefreshLayout.this.mCurrentTargetOffsetTop + SwipeRefreshLayout.this.getPaddingTop(),
                    SwipeRefreshLayout.this.mReturnToStartPositionListener);
        }

    };

    /**
     * Simple constructor to use when creating a SwipeRefreshLayout from code.
     *
     * @param context
     */
    public SwipeRefreshLayout(Context context) {
        this(context, null);
    }

    /**
     * Constructor that is called when inflating SwipeRefreshLayout from XML.
     *
     * @param context
     * @param attrs
     */
    public SwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        this.mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        this.mMediumAnimationDuration = this.getResources().getInteger(
                integer.config_mediumAnimTime);

        this.setWillNotDraw(false);
        this.mProgressBar = new SwipeProgressBar(this);
        DisplayMetrics metrics = this.getResources().getDisplayMetrics();
        this.mProgressBarHeight = (int) (metrics.density * SwipeRefreshLayout.PROGRESS_BAR_HEIGHT);
        this.mDecelerateInterpolator = new DecelerateInterpolator(SwipeRefreshLayout.DECELERATE_INTERPOLATION_FACTOR);
        this.mAccelerateInterpolator = new AccelerateInterpolator(SwipeRefreshLayout.ACCELERATE_INTERPOLATION_FACTOR);

        TypedArray a = context.obtainStyledAttributes(attrs, SwipeRefreshLayout.LAYOUT_ATTRS);
        this.setEnabled(a.getBoolean(0, true));
        a.recycle();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.removeCallbacks(this.mCancel);
        this.removeCallbacks(this.mReturnToStartPosition);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.removeCallbacks(this.mReturnToStartPosition);
        this.removeCallbacks(this.mCancel);
    }

    private void animateOffsetToStartPosition(int from, AnimationListener listener) {
        this.mFrom = from;
        this.mAnimateToStartPosition.reset();
        this.mAnimateToStartPosition.setDuration(this.mMediumAnimationDuration);
        this.mAnimateToStartPosition.setAnimationListener(listener);
        this.mAnimateToStartPosition.setInterpolator(this.mDecelerateInterpolator);
        this.mTarget.startAnimation(this.mAnimateToStartPosition);
    }

    /**
     * Set the listener to be notified when a refresh is triggered via the swipe
     * gesture.
     */
    public void setOnRefreshListener(OnRefreshListener listener) {
        this.mListener = listener;
    }

    private void setTriggerPercentage(float percent) {
        if (percent == 0f) {
            // No-op. A null trigger means it's uninitialized, and setting it to zero-percent
            // means we're trying to reset state, so there's nothing to reset in this case.
            this.mCurrPercentage = 0;
            return;
        }
        this.mCurrPercentage = percent;
        this.mProgressBar.setTriggerPercentage(percent);
    }

    /**
     * Set the four colors used in the progress animation. The first color will
     * also be the color of the bar that grows in response to a user swipe
     * gesture.
     *
     * @param colorRes1 Color resource.
     * @param colorRes2 Color resource.
     * @param colorRes3 Color resource.
     * @param colorRes4 Color resource.
     */
    public void setColorScheme(int colorRes1, int colorRes2, int colorRes3, int colorRes4) {
        this.ensureTarget();
        Resources res = this.getResources();
        int color1 = res.getColor(colorRes1);
        int color2 = res.getColor(colorRes2);
        int color3 = res.getColor(colorRes3);
        int color4 = res.getColor(colorRes4);
        this.mProgressBar.setColorScheme(color1, color2, color3, color4);
    }

    /**
     * @return Whether the SwipeRefreshWidget is actively showing refresh
     * progress.
     */
    public boolean isRefreshing() {
        return this.mRefreshing;
    }

    /**
     * Notify the widget that refresh state has changed. Do not call this when
     * refresh is triggered by a swipe gesture.
     *
     * @param refreshing Whether or not the view should show refresh progress.
     */
    public void setRefreshing(boolean refreshing) {
        if (this.mRefreshing != refreshing) {
            this.ensureTarget();
            this.mCurrPercentage = 0;
            this.mRefreshing = refreshing;
            if (this.mRefreshing) {
                this.mProgressBar.start();
            } else {
                this.mProgressBar.stop();
            }
        }
    }

    private void ensureTarget() {
        // Don't bother getting the parent height if the parent hasn't been laid out yet.
        if (this.mTarget == null) {
            if (this.getChildCount() > 1 && !this.isInEditMode()) {
                throw new IllegalStateException(
                        "SwipeRefreshLayout can host only one direct child");
            }
            this.mTarget = this.getChildAt(0);
            this.mOriginalOffsetTop = this.mTarget.getTop() + this.getPaddingTop();
        }
        if (this.mDistanceToTriggerSync == -1) {
            if (this.getParent() != null && ((View) this.getParent()).getHeight() > 0) {
                DisplayMetrics metrics = this.getResources().getDisplayMetrics();
                this.mDistanceToTriggerSync = (int) Math.min(
                        ((View) this.getParent()).getHeight() * SwipeRefreshLayout.MAX_SWIPE_DISTANCE_FACTOR,
                        SwipeRefreshLayout.REFRESH_TRIGGER_DISTANCE * metrics.density);
            }
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        this.mProgressBar.draw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = this.getMeasuredWidth();
        int height = this.getMeasuredHeight();
        this.mProgressBar.setBounds(0, 0, width, this.mProgressBarHeight);
        if (this.getChildCount() == 0) {
            return;
        }
        View child = this.getChildAt(0);
        int childLeft = this.getPaddingLeft();
        int childTop = this.mCurrentTargetOffsetTop + this.getPaddingTop();
        int childWidth = width - this.getPaddingLeft() - this.getPaddingRight();
        int childHeight = height - this.getPaddingTop() - this.getPaddingBottom();
        child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (this.getChildCount() > 1 && !this.isInEditMode()) {
            throw new IllegalStateException("SwipeRefreshLayout can host only one direct child");
        }
        if (this.getChildCount() > 0) {
            this.getChildAt(0).measure(
                    View.MeasureSpec.makeMeasureSpec(
                            getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(
                            getMeasuredHeight() - getPaddingTop() - getPaddingBottom(),
                            View.MeasureSpec.EXACTLY));
        }
    }

    /**
     * @return Whether it is possible for the child view of this layout to
     * scroll up. Override this if the child view is a custom view.
     */
    public boolean canChildScrollUp() {

        return ViewCompat.canScrollVertically(this.mTarget, -1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        this.ensureTarget();
        boolean handled = false;
        if (this.mReturningToStart && ev.getAction() == MotionEvent.ACTION_DOWN) {
            this.mReturningToStart = false;
        }
        if (this.isEnabled() && !this.mReturningToStart && !this.canChildScrollUp()) {
            handled = this.onTouchEvent(ev);
        }
        return handled || super.onInterceptTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean b) {
        // Nope.
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                this.mCurrPercentage = 0;
                this.mDownEvent = MotionEvent.obtain(event);
                this.mPrevY = this.mDownEvent.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (this.mDownEvent != null && !this.mReturningToStart) {
                    float eventY = event.getY();
                    float yDiff = eventY - this.mDownEvent.getY();
                    if (yDiff > this.mTouchSlop) {
                        // User velocity passed min velocity; trigger a refresh
                        if (yDiff > this.mDistanceToTriggerSync) {
                            // User movement passed distance; trigger a refresh
                            this.startRefresh();
                            handled = true;
                            break;
                        } else {
                            // Just track the user's movement
                            this.setTriggerPercentage(
                                    this.mAccelerateInterpolator.getInterpolation(
                                            yDiff / this.mDistanceToTriggerSync));
                            float offsetTop = yDiff;
                            if (this.mPrevY > eventY) {
                                offsetTop = yDiff - this.mTouchSlop;
                            }
                            this.updateContentOffsetTop((int) (offsetTop));
                            if (this.mPrevY > eventY && (this.mTarget.getTop() < this.mTouchSlop)) {
                                // If the user puts the view back at the top, we
                                // don't need to. This shouldn't be considered
                                // cancelling the gesture as the user can restart from the top.
                                this.removeCallbacks(this.mCancel);
                            } else {
                                this.updatePositionTimeout();
                            }
                            this.mPrevY = event.getY();
                            handled = true;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (this.mDownEvent != null) {
                    this.mDownEvent.recycle();
                    this.mDownEvent = null;
                }
                break;
        }
        return handled;
    }

    private void startRefresh() {
        this.removeCallbacks(this.mCancel);
        this.mReturnToStartPosition.run();
        this.setRefreshing(true);
        this.mListener.onRefresh();
    }

    private void updateContentOffsetTop(int targetTop) {
        int currentTop = this.mTarget.getTop();
        if (targetTop > this.mDistanceToTriggerSync) {
            targetTop = (int) this.mDistanceToTriggerSync;
        } else if (targetTop < 0) {
            targetTop = 0;
        }
        this.setTargetOffsetTopAndBottom(targetTop - currentTop);
    }

    private void setTargetOffsetTopAndBottom(int offset) {
        this.mTarget.offsetTopAndBottom(offset);
        this.mCurrentTargetOffsetTop = this.mTarget.getTop();
    }

    private void updatePositionTimeout() {
        this.removeCallbacks(this.mCancel);
        this.postDelayed(this.mCancel, SwipeRefreshLayout.RETURN_TO_ORIGINAL_POSITION_TIMEOUT);
    }

    /**
     * Classes that wish to be notified when the swipe gesture correctly
     * triggers a refresh should implement this interface.
     */
    public interface OnRefreshListener {
        void onRefresh();
    }

    /**
     * Simple AnimationListener to avoid having to implement unneeded methods in
     * AnimationListeners.
     */
    private class BaseAnimationListener implements AnimationListener {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    }
}